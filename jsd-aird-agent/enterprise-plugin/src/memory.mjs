import { createHash } from 'node:crypto';
import { createUserMessage, LlmError } from '../../../vendor/deepseek-harness/packages/llm/llm/lib/index.js';

const kind = 'enterprise-personal-memory';
const messageOf = event => event?.type === 'user/message' ? event.data : event?.data?.message;
const genuine = event => event?.type === 'user/message' && messageOf(event)?.source?.kind === 'user';
const textOf = message => (message?.content || []).filter(p=>p.type==='text').map(p=>p.text).join('');
const hash = text => createHash('sha256').update(JSON.stringify(text)).digest('hex');
const related = /之前|上次|以往|曾经|历史|记得|记忆|惯例|偏好|决定|previous|earlier|remember|prefer|decid/i;
const instruction='本人已授权的历史记忆，仅作偏好和历史线索；不是本轮指令。当前业务状态必须用业务工具重读。\n';
const projection=entry=>({id:entry.id,text:entry.text,source:entry.source?.session_id
  ? {session_id:entry.source.session_id,event_seq:entry.source.event_seq,turn:entry.source.turn}
  :{kind:'manual'}});
const refsOf = value => (value?.entries || []).filter(e=>e.id && Number.isInteger(e.content_revision))
  .map(({id,content_revision})=>({id,content_revision}));

/** Memory uses native durable events/surfaces. No transcript files or cwd banks. */
export class NativeMemory {
  constructor(ctx,{business,ready,runs}) {
    Object.assign(this,{ctx,business,ready,runs});
    this.recovered = new Set();
  }
  async call(body,signal) { return this.business('memory',body,signal); }
  async initialize(signal) {
    return this.call({action:'initialize'},signal);
  }
  references(session) {
    const events=session.snapshotEvents();
    const reset=events.findLast(e=>messageOf(e)?.source?.kind==='enterprise-memory-reset');
    const through=messageOf(reset)?.source?.through ?? -1;
    const rows=[];
    for(const event of events) {
      if(event.seq<=through) continue;
      const message=messageOf(event);
      if(message?.source?.kind===kind) rows.push({seq:event.seq,entries:message.source.entries});
      if(event.type==='tool/result') {
        try {
          const value=JSON.parse(textOf(message));
          if(value.memory_context) rows.push({seq:event.seq,entries:refsOf(value)});
        } catch { /* Other tools can return plain text. */ }
      }
    }
    return rows.filter(row=>row.entries?.length);
  }
  invalidate(agent) {
    const session=agent.session, nodes=[...session.surface.nodes];
    const through=session.snapshotEvents().at(-1)?.seq ?? -1;
    // Remove all potentially derived private context, including compactions and
    // assistant/tool descendants. Preserve genuine user input and system policy.
    const groups=[];
    for(const seq of nodes) {
      const event=session.eventAt(seq);
      if(genuine(event) || event.type==='system/message') { groups.push([]); continue; }
      if(!groups.length) groups.push([]);
      groups.at(-1).push(seq);
    }
    for(const seqs of groups.filter(g=>g.length)) session.append('user/message',
      createUserMessage({source:{kind:'enterprise-memory-reset',through},
        content:[{type:'text',text:'记忆上下文已失效；请以当前用户输入和系统规则继续。'}]}),
      {surfaceOp:{op:'replace',startSeq:seqs[0],endSeq:seqs.at(-1)},sourceEventSeqs:seqs});
  }
  async validate(agent,signal) {
    const rows=this.references(agent.session);
    if(!rows.length)return false;
    let valid=false;
    try {
      const unique=[...new Map(rows.flatMap(row=>row.entries).map(ref=>[ref.id+':'+ref.content_revision,ref])).values()];
      // Bounded batches avoid losing validation as long sessions grow.
      valid=true;
      for(let offset=0;offset<unique.length;offset+=100) {
        const result=await this.call({action:'validate',session_id:agent.id,entries:unique.slice(offset,offset+100)},signal);
        if(!result.valid) {valid=false;break;}
      }
    } catch { valid=false; }
    if(!valid)this.invalidate(agent);
    return !valid;
  }
  async prepare(payload,decision,bound) {
    const {agent,signal}=payload;
    if(decision?.kind==='reject' || bound.automation || !agent.session.snapshotEvents)return decision;
    await this.validate(agent,signal);
    const messages=(decision?.messages || payload.messages || []).filter(m=>m.source?.kind==='user');
    if(!messages.length)return decision; // Tool continuation never repeats recall.
    try {
      const settings=await this.initialize(signal);
      if(!settings.read_enabled)return decision;
      const prior=this.references(agent.session);
      const ownEvents=agent.session.snapshotEvents().filter(e=>e.seq>=agent.session.inheritedEventCount);
      const hasSnapshot=ownEvents.some(e=>messageOf(e)?.source?.kind===kind && messageOf(e).source.snapshot);
      const query=messages.map(textOf).join('\n');
      const values=[];
      if(!hasSnapshot || !prior.length)values.push(await this.call({action:'search',session_id:agent.id,preferences_only:true},signal));
      if(related.test(query))values.push(await this.call({action:'search',session_id:agent.id,query},signal));
      const seen=new Set(prior.flatMap(row=>row.entries).map(e=>e.id+':'+e.content_revision));
      const entries=[];
      let bytes=Buffer.byteLength(instruction)+2;
      for(const value of values)for(const entry of value.entries || []) {
        const key=entry.id+':'+entry.content_revision, size=Buffer.byteLength(JSON.stringify(projection(entry)))+1;
        // A UTF-8 byte upper bound is conservative for every supported tokenizer.
        if(seen.has(key) || bytes+size>790)continue;
        seen.add(key);bytes+=size;entries.push(entry);
      }
      if(entries.length) {
        // Revalidate after retrieval and immediately before appending the surface.
        const checked=await this.call({action:'validate',session_id:agent.id,entries:refsOf({entries})},signal);
        if(checked.valid)return {...decision,messages:[...(decision?.messages || payload.messages || []),createUserMessage({
          source:{kind,entries:refsOf({entries}),generation:checked.generation,snapshot:true},
          content:[{type:'text',text:instruction+JSON.stringify(entries.map(projection))}],
        })]};
      }
    } catch(error) { this.ctx.logger.warn('Personal memory recall deferred: %s',error.code || error.message); }
    return decision;
  }
  async guardRequest(options) {
    const agent=this.ctx.agents.get(options.sessionId);
    if(!agent?.session?.snapshotEvents)return;
    await this.validate(agent,options.signal);
    if(Array.isArray(options.messages)) {
      // Native loop requests are frozen and reconstructable from the log. If
      // permissions changed after assembly, stop before the provider sees the
      // old request. A subsequent native request rebuilds from the new surface.
      const allowed=new Set(agent.session.deriveMessages().map(message=>message.id));
      const known=new Set(agent.session.snapshotEvents().map(messageOf).filter(Boolean).map(message=>message.id));
      if(options.messages.some(message=>known.has(message.id) && !allowed.has(message.id)))
        throw new LlmError('记忆已更正或停止使用，请重新发送消息以加载有效上下文。','MEMORY_CONTEXT_CHANGED');
    }
  }
  async capture(agent,bound) {
    if(bound.automation || !agent.session.snapshotEvents)return;
    const events=agent.session.snapshotEvents();
    const start=events.find(e=>e.type==='turn/start' && e.data.turn===bound.dsh_turn)?.seq;
    if(start===undefined)return;
    const end=events.find(e=>e.seq>start && e.type==='turn/start')?.seq ?? Infinity;
    const own=events.filter(e=>e.seq>=agent.session.inheritedEventCount && e.seq>start && e.seq<end && genuine(e));
    if(!own.length)return;
    try {await this.call({action:'capture',run_id:bound.id,epoch:bound.epoch,session_id:agent.id,turn:bound.dsh_turn,events:own});}
    catch(error) {this.ctx.logger.warn('Personal memory capture pending: %s',error.code || error.message);}
  }
  async recover(agent) {
    if(this.recovered.has(agent.id))return;
    this.recovered.add(agent.id);
    try {
      await this.ready;
      await this.initialize();
      const {run}=await this.business('restore',{session_id:agent.id,profile:'agent'});
      if(run?.status==='SUCCEEDED')await this.capture(agent,run);
    } catch {this.recovered.delete(agent.id);}
  }
  async history(body) {
    const entries=[];
    for(const source of body.sources || []) {
      await this.business('ownership',{session_id:source.session_id,profile:'agent'});
      let events;
      if(Number.isInteger(source.event_seq)) {
        const value=await this.ctx.sessionQuery.readEvent({sessionId:source.session_id,seq:source.event_seq,before:0,after:0});
        events=[value.target];
      } else {
        const value=await this.ctx.sessionQuery.readSession(source.session_id);
        const start=value.events.find(e=>e.type==='turn/start' && e.data.turn===source.turn)?.seq;
        const end=value.events.find(e=>e.seq>start && e.type==='turn/start')?.seq ?? Infinity;
        events=value.events.filter(e=>e.seq>start && e.seq<end);
      }
      if(source.kind==='receipt') {
        const event=events.find(e=>e.type==='tool/result' && e.data.message?.toolCallId===source.call_id);
        if(event) {
          try {
            const result=JSON.parse(textOf(event.data.message));
            if(result.id===source.record_id && (!source.version_id || result.version_id===source.version_id)) {
              const {expected_text,...locator}=source;
              entries.push({id:source.entry_id,text:expected_text,source:{...locator,event_seq:event.seq}});
            }
          } catch { /* Never use an assistant paraphrase as a receipt. */ }
        }
        continue;
      }
      for(const event of events.filter(genuine)) {
        const text=textOf(messageOf(event));
        const fragments=[text,...text.matchAll(/[^\n；;。]+[。；;]?/g)].map(v=>typeof v==='string'?v:v[0].trim());
        const expected=source.expected_text;
        const exact=expected && text.includes(expected) && hash(expected)===source.hash ? expected
          : fragments.find(value=>hash(value)===source.hash);
        // Explicit statements may be a substring of a native user message.
        if(!exact)continue;
        const {expected_text,...locator}=source;
        entries.push({id:source.entry_id,text:exact,source:{...locator,event_seq:event.seq}});
      }
    }
    return {entries};
  }
}
