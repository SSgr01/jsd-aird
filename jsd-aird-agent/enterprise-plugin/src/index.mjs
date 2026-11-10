import { readFile as readChemistryAsset } from 'node:fs/promises';
import { AsyncLocalStorage } from 'node:async_hooks';
import { NativeBriefings, beginBriefingPeriod } from './briefings.mjs';
import { installNativeRuntime, nativeTools } from './native-runtime.mjs';
import { randomUUID, createHash } from 'node:crypto';
import { timingSafeEqual } from 'node:crypto';
import { presetForProfile, profileForPreset } from '../../deepseek/src/session-policy.mjs';
import { executionDirectory } from '../../deepseek/src/paths.mjs';
import { installScheduling, requestText, scheduleTools } from './scheduling.mjs';
import { businessError, deliveryError } from './business-error.mjs';
import { bindingCursor, mergeBinding } from './binding-state.mjs';
import { EventProjection, StreamProjection } from './event-projection.mjs';
import { updateNativeInbox } from './inbox-actions.mjs';
import { citationStream } from './citation-stream.mjs';
import { modelResult, renderModelResult, presentation } from './model-view.mjs';
import { repairInstruction } from './citation-repair.mjs';
import { StageTiming } from './stage-timing.mjs';
import { readExecutionPolicy } from './read-policy.mjs';
import { materializeBusinessDataset } from './business-dataset.mjs';
import { installSessionLifecycle } from './session-lifecycle.mjs';
import { installAdmission, inputSignature, previewTurnInput } from './admission.mjs';
import { createUserMessage, createAssistantMessage, BlockAssembler } from '../../../vendor/deepseek-harness/packages/llm/llm/lib/index.js';

export const name = 'jsd-enterprise';
export const inject = ['tools', 'agents', 'sessions', 'sessionProjectionCache', 'systemPrompt', 'webServer', 'sessionController', 'llm', 'agentDefaultModel', 'connection', 'workspaceRegistry', 'agentPresets', 'workingDirectory', 'attachments', 'fs', 'web', 'schedule', 'tokenMeter'];

/** Host-owned adapter. Identity and tool-call keys never come from model arguments. */
export async function apply(ctx) {
  const identity = JSON.parse(process.env.JSD_DSH_IDENTITY);
  let interactiveIdentity = identity.automation_grant_id ? undefined : identity;
  let backgroundIdentity = identity.automation_grant_id ? identity : undefined;
  const authorizationContext = new AsyncLocalStorage();
  const sessionAuthorities = new Map();
  const asBackground = operation => authorizationContext.run({background:true}, operation);
  let renewing;
  async function backgroundTicket() {
    if (backgroundIdentity?.expires_at > Date.now()/1000+60) return backgroundIdentity.ticket;
    if (!backgroundIdentity?.automation_grant_id) throw new Error('BACKGROUND_AUTHORIZATION_REQUIRED');
    renewing ||= fetch(process.env.JSD_DSH_CONTROL_URL + '/internal/v1/harness/background/ticket', {
      method:'POST',headers:{'content-type':'application/json',authorization:'Bearer '+process.env.JSD_DSH_RUNTIME_SECRET},
      body:JSON.stringify({owner_id:backgroundIdentity.automation_grant_id}),signal:AbortSignal.timeout(10000),
    }).then(async response=> {if(!response.ok)throw new Error('BACKGROUND_AUTHORIZATION_REVOKED');
      backgroundIdentity=await response.json();return backgroundIdentity.ticket;}).finally(()=>{renewing=undefined;});
    return renewing;
  }
  const control = process.env.JSD_DSH_CONTROL_URL;
  const secret = process.env.JSD_DSH_RUNTIME_SECRET;
  const internalKey = process.env.JSD_DSH_INTERNAL_KEY;
  const executionRoot = process.env.JSD_DSH_EXECUTION_ROOT;
  const profileOf = agent => profileForPreset(ctx.agentPresets.presetForSession(agent.session));
  async function sessionProfile(sessionId, signal) {
    const state = await ctx.sessionController.projections({ sessionId }, signal);
    if (!state) throw Object.assign(new Error('SESSION_NOT_FOUND'), {code:'SESSION_NOT_FOUND'});
    return profileForPreset(state.values.agentPreset);
  }
  const runs = new Map();
  const serial = new Map();
  const cancelled = new Set();
  const pendingEvents = new Map();
  const outputLimited = new Set();
  const claimedInputs = new Map();
  const currentTurns = new Map();
  const preparedBindings = new Map();
  const preparedBriefingTurns = new Map();
  const timing = new StageTiming(ctx.logger);
  let admission;
  async function business(action, body, signal) {
    const grant = admission?.grants.get(body.session_id);
    const bound = runs.get(body.session_id);
    if (body.run_id && body.epoch === undefined && (grant?.run_id === body.run_id || bound?.id === body.run_id))
      body = {...body, epoch: grant?.run_id === body.run_id ? grant.epoch : bound.epoch};
    const started=performance.now(), started_at_ms=Date.now();
    const metadata={action,session_id:body.session_id,run_id:body.run_id,turn:body.turn,kind:body.kind};
    const background = authorizationContext.getStore()?.background ?? (body.session_id ? sessionAuthorities.get(body.session_id)?.background : undefined) ?? !interactiveIdentity;
    const ticket = background ? await backgroundTicket() : interactiveIdentity?.ticket;
    if (!ticket) throw new Error('INTERACTIVE_AUTHORIZATION_REQUIRED');
    const timeoutMs=action==='native-tool' && body.name==='dsh_execute'
      ? Math.min(600000,Math.max(1,body.arguments?.timeout_ms || 120000))+15000
      : ['native-output','native-cancel'].includes(action) ? 7000 : 120000;
    const encoded=JSON.stringify(body), encodedAt=performance.now();
    let response, headersAt, responseBytes, completed=false;
    try {
    response = await fetch(control + '/internal/v1/harness/dsh/' + action, {
      method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer ' + secret,
        'x-jsd-agent-ticket': ticket }, body: encoded,
      signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(timeoutMs)]) : AbortSignal.timeout(timeoutMs),
    });
    headersAt=performance.now();
    if (!response.ok) throw await businessError(response);
    let value;
    if(timing.enabled && response.text) {
      const text=await response.text();responseBytes=Buffer.byteLength(text);value=JSON.parse(text);
    } else value=await response.json();
    completed=true;
    return value;
    } finally {
    timing.emit('control.request',{...metadata,started_at_ms,elapsed_ms:performance.now()-started,completed,status:response?.status,
      encode_ms:encodedAt-started,http_headers_ms:(headersAt ?? performance.now())-encodedAt,decode_ms:headersAt===undefined ? 0 : performance.now()-headersAt,
      request_bytes:Buffer.byteLength(encoded),response_bytes:responseBytes,
      trace_id:response?.headers?.get('x-jsd-trace-id'),server_timing:response?.headers?.get('server-timing')});
    }
  }
  function enqueue(sessionId, operation) {
    const queuedAt=performance.now(), started_at_ms=Date.now();
    const next = (serial.get(sessionId) || Promise.resolve()).catch(() => {}).then(()=>{
      timing.emit('projection.queue_wait',{session_id:sessionId,started_at_ms,elapsed_ms:performance.now()-queuedAt});
      return operation();
    });
    serial.set(sessionId, next);
    // Persistence remains in DeepSeek. A failed projection is replayed from its log on restore.
    void next.catch(error => ctx.logger.warn('Enterprise projection pending: %s', error.message));
    return next;
  }
  const projection = new EventProjection({enqueue,
    send:(session_id,event)=>business('event',{session_id,event}),
    onCoalesce:fields=>timing.emit('projection.coalesced',fields)});
  const streams = new StreamProjection({enqueue,
    send:(session_id,bound,frames)=>business('stream',{run_id:bound.id,epoch:bound.epoch,session_id,turn:bound.dsh_turn,frames})});
  async function drainProjection(sessionId) {
    projection.flush(sessionId);streams.flush(sessionId);
    let pending;
    do {pending=serial.get(sessionId);await pending?.catch(()=>{});} while(pending!==serial.get(sessionId));
    projection.check(sessionId);
  }
  async function replayEvents(sessionId,events) {
    projection.clear(sessionId);
    for(const event of events)projection.push(sessionId,event);
    await drainProjection(sessionId);
  }
  installSessionLifecycle(ctx, {business,
    drain: async sessionId => {
      // Await all already queued business receipts after native cancellation.
      await drainProjection(sessionId);
    },
    clear: sessionId => {
      projection.clear(sessionId);streams.clear(sessionId);
      for (const map of [runs,serial,pendingEvents,claimedInputs,currentTurns,
        preparedBindings,preparedBriefingTurns,sessionAuthorities]) map.delete(sessionId);
      cancelled.delete(sessionId); outputLimited.delete(sessionId);
    },
  });
  async function reconcileRunSession(sessionId) {
    const {run}=await business('restore',{session_id:sessionId,profile:'agent'});
    if(!run || run.status!=='RUNNING')return;
    if(run.automation)sessionAuthorities.set(sessionId,{background:true,turn:run.dsh_turn});
    const bound = runs.get(sessionId);
    if (!bound || bound.id !== run.id || bound.epoch !== run.epoch) runs.set(sessionId,run);
    const signal=new AbortController().signal, records=[];
    let beforeSeq;
    do {
      const page=await ctx.sessionController.events({address:{kind:'session',sessionId},beforeSeq,maxMessages:100},signal);
      records.push(...page.records);
      if(!page.hasMore || page.records.some(event=>event.seq<=(run.dsh_seq ?? -1)) || !page.records.length)break;
      beforeSeq=Math.min(...page.records.map(event=>event.seq));
    } while(true);
    await replayEvents(sessionId,records.filter(event=>event.seq>(run.dsh_seq ?? -1)).sort((a,b)=>a.seq-b.seq));
    const end=records.find(event=>event.type==='turn/end' && event.data.turn===run.dsh_turn);
    if(end?.data.reason.kind==='completed')
      await business('complete',{run_id:run.id,epoch:run.epoch,session_id:sessionId,turn:run.dsh_turn});
  }
  const briefings = new NativeBriefings(ctx,{business,executionRoot,asBackground,reconcileSession:reconcileRunSession});
  // A recovered native timer may fire while the enterprise plugin initializes.
  // Hold activation and model admission until all guards and projections exist.
  let initialized;
  const ready = new Promise(resolve => { initialized = resolve; });
  let scheduleSync;
  ctx.on('schedule/changed', () => {
    clearTimeout(scheduleSync);
    scheduleSync = setTimeout(() => {
      void ready.then(() => asBackground(() => briefings.sync()))
        .catch(error => ctx.logger.warn('Built-in task catalog pending: %s', error.message));
    }, 200);
    scheduleSync.unref();
  });
  ctx.effect(() => () => clearTimeout(scheduleSync));
  admission = installAdmission(ctx, { business, ready, prepare: async (agent, input) => {
    const automation = await briefings.delivery(agent.id, input);
    sessionAuthorities.set(agent.id, {background:Boolean(automation),turn:input.turn});
    const {attachments} = await inputFiles(input.messages, input.signal);
    return {session_id:agent.id,turn:input.turn,profile:profileOf(agent),request:requestText(input.messages),
      attachments,input_signature:input.input_signature,...(automation ? {automation} : {})};
  }});
  ctx.on('agent/pre-step', async (_payload, next) => { await ready; return next(); });
  installScheduling(ctx, {business, sessionProfile, executionRoot,
    activation:async(sessionId, operation)=> {
      await ready;
      return briefings.owns(sessionId) ? asBackground(operation) : operation();
    }});
  const catalog = await business('catalog', { profile: 'agent' });
  const native = installNativeRuntime(ctx,{business,runs,profileOf,searchAvailable:catalog.web_search_available === true});
  for (const spec of catalog.tools) {
    ctx.effect(() => ctx.tools.register({
      ...spec,
      ...readExecutionPolicy(spec.name),
      output: {
        schema: { type: 'object', additionalProperties: true },
        render: (_args, value) => renderModelResult(value,
          ['knowledge_search','document_read','document_outline','table_read','private_document_search',
            'private_document_read','private_document_outline','evidence_read','evidence_context'].includes(spec.name)
            ? catalog.max_tool_characters : undefined),
        presentationMeta: (_args, value) => presentation(spec.name, value),
      },
      async execute(args, execution) {
        const bound = runs.get(execution.agent?.id);
        if (!bound || !bound.tools.some(tool => tool.name === spec.name)) throw new Error('CAPABILITY_NOT_AUTHORIZED');
        await business('gate', { run_id: bound.id, epoch:bound.epoch, session_id: execution.agent.id, turn: bound.dsh_turn,
          kind: 'tool', call_id: execution.callId }, execution.signal);
        if(spec.name==='artifact_save')return native.register(execution,args.path,0,args.source_quotes||[]);
        let result = await business('tool', { run_id: bound.id, epoch:bound.epoch, session_id: execution.agent.id,
          turn: bound.dsh_turn, call_id: execution.callId, name: spec.name, arguments: args }, execution.signal);
        // The business ledger already records the refusal. Keep native error
        // presentation useful without exposing the host's internal diagnosis.
        if (result.error && result.access_denied) throw new Error(modelResult(result).error);
        result = await materializeBusinessDataset(ctx, execution, result);
        if (result.activate_tools) bound.active_tools = [...new Set([...(bound.active_tools || []),
          ...result.activate_tools.filter(name => bound.tools.some(tool => tool.name === name))])];
        Object.assign(bound.citation_links ||= {}, result.citation_links || {});
        return result;
      },
    }, { when: async ({ agent, signal }) => {
      const bound = await prepareBinding(agent, signal);
      return Boolean(bound?.tools.some(tool => tool.name === spec.name) && bound.active_tools?.includes(spec.name));
    } }));
  }
  ctx.effect(() => ctx.tools.guard(execution => {
    if (!execution.agent) return 'ENTERPRISE_TOOL_DENIED';
    const bound = runs.get(execution.agent.id);
    if (bound?.automation && nativeTools.has(execution.name) && !['skill','todo_write','ask_user_question','enter_plan_mode','exit_plan_mode','get_goal','create_goal','update_goal'].includes(execution.name))
      return 'BACKGROUND_READ_ONLY';
    return (nativeTools.has(execution.name) && bound && (scheduleTools.has(execution.name) || bound.pack_ids?.includes('files'))) || bound?.tools.some(tool => tool.name === execution.name)
      ? undefined : 'ENTERPRISE_TOOL_DENIED';
  }));
  ctx.effect(() => ctx.systemPrompt.section({ name: 'enterprise-policy', order: 0,
    interpolate: false, text: ({ agent }) => (runs.get(agent?.id)?.instructions || catalog.instructions) + '\n化学式和反应式使用 $ 或 $$ 内的 \\ce{...}。已知且有依据的分子结构使用 smiles 代码块，反应结构使用 reaction-smiles 代码块（反应物>试剂>产物）。不得猜测文献结构对应的 SMILES；不确定时展示原图并说明。绘图成功仅表示语法可解析，不代表原文结构核验。' + '\n原生文件与 bash 命令在 Linux 沙箱的同一执行空间中运行。使用会话相对路径，不在脚本中使用宿主 Windows 路径。' }));
  ctx.effect(() => ctx.systemPrompt.context({ name: 'enterprise-business', order: 0, required: true,
    interpolate: false, text: ({ agent }) => runs.get(agent?.id)?.context || '' }));
  ctx.effect(() => ctx.systemPrompt.section({name:'enterprise-document-references',order:1,
    interpolate:false,text:'读取结果的 document_sources 是本次返回的固定版本文献目录，片段的 document_ref 指向其中的 ref。继续读取时取目录中的 document_id、publication_id；引用仍使用片段的 evidence_id，document_ref 不能作为证据 ID。'}));
  ctx.on('agent/created', async ({ agent, source, signal }) => {
    await executionDirectory(executionRoot, ctx.fs.hostPath(ctx.workingDirectory.get(agent.session)));
    if (source === 'resume') {
      const profile = profileOf(agent);
      const restored = await business('restore', { session_id: agent.id, profile }, signal);
      if(restored.run?.automation)sessionAuthorities.set(agent.id,{background:true,turn:restored.run.dsh_turn});
      if (restored.run) runs.set(agent.id, restored.run);
      if (profile === 'agent' && restored.run?.pack_ids.includes('files')) {
        const messages = agent.session.snapshotEvents().filter(event=>event.type==='user/message').map(event=>event.data.message||event.data);
        await exposeFiles(agent, (await inputFiles(messages, signal)).files, signal);
      }
      // Reconcile committed events; no pi checkpoints and no model-state copy.
      await replayEvents(agent.id,agent.session.snapshotEvents().filter(event=>event.seq>(restored.run?.dsh_seq ?? -1)));
    }
  });
  ctx.on('agent/inbox/claimed', ({ agent, message, turn }) => {
    currentTurns.set(agent.id, turn);
    const input = claimedInputs.get(agent.id) || {turn, messages:[]};
    input.messages.push(message);
    claimedInputs.set(agent.id, input);
  });
  // dsh resolves asynchronous tool conditions before assembling the system
  // prompt, then invokes pre-step. Bind the claimed Inbox batch at that native
  // admission boundary so the first request has its actual policy and tools.
  async function prepareBinding(agent, signal, proposal) {
    if (preparedBindings.has(agent.id)) return preparedBindings.get(agent.id);
    const input = proposal || claimedInputs.get(agent.id) || {turn:currentTurns.get(agent.id),messages:[]};
    if (input.turn === undefined) return undefined;
    claimedInputs.delete(agent.id);
    const pending = timing.time('bind.total',{session_id:agent.id,turn:input.turn},()=>bindInput(agent, signal, input));
    preparedBindings.set(agent.id, pending);
    try { return await pending; }
    catch (error) { preparedBindings.delete(agent.id); throw error; }
  }
  async function bindInput(agent, signal, input) {
    await executionDirectory(executionRoot, ctx.fs.hostPath(await ctx.workingDirectory.ensure(agent, signal)));
    const profile = profileOf(agent);
    await timing.time('bind.queue_wait',{session_id:agent.id,turn:input.turn},()=>drainProjection(agent.id));
    const automation = await briefings.delivery(agent.id,input);
    sessionAuthorities.set(agent.id,{background:Boolean(automation),turn:input.turn});
    const text = requestText(input.messages);
    const {attachments, files} = await inputFiles(input.messages, signal);
    if (files.length && profile === 'agent') await exposeFiles(agent, files, signal);
    const previous = runs.get(agent.id);
    const grant = admission?.grants.get(agent.id);
    const hasInput=Boolean(input.messages.length);
    const current = await business('register', { session_id: agent.id, turn: input.turn,
      ...bindingCursor(previous,input.turn,hasInput),
      ...(grant ? {run_id:grant.run_id,epoch:grant.epoch,input_signature:grant.inputSignature} : {}),
      profile, request: text, attachments, ...(automation ? {automation} : {}) }, signal);
    const bound=mergeBinding(previous,current,hasInput);
    runs.set(agent.id, bound);
    const waiting = pendingEvents.get(agent.id) || [];
    pendingEvents.delete(agent.id);
    for (const event of waiting)projection.push(agent.id,event);
    await drainProjection(agent.id);
    cancelled.delete(agent.id);
    return bound;
  }
  async function inputFiles(messages, signal) {
    const attachments = [], files = [];
    for (const message of messages.filter(message => message.source?.kind === 'user')) {
      for (const part of message.content.filter(part => part.type === 'file')) {
        if (part.attachment.bytes > 10 * 1024 * 1024) throw new Error('DOCUMENT_PARSE_LIMIT');
        const chunks = []; let bytes = 0;
        for await (const chunk of ctx.attachments.readFileStream(part.attachment, signal)) {
          bytes += chunk.byteLength;
          if (bytes > 10 * 1024 * 1024) throw new Error('DOCUMENT_PARSE_LIMIT');
          chunks.push(chunk);
        }
        const file = Buffer.concat(chunks);
        if (file.length !== part.attachment.bytes || 'sha256:' + createHash('sha256').update(file).digest('hex') !== part.attachment.attachmentId)
          throw new Error('NATIVE_ATTACHMENT_MISMATCH');
        files.push({ref:part.attachment,bytes:file});
        if (/\.(pdf|docx|txt)$/i.test(part.attachment.name))
          attachments.push({attachment_id:part.attachment.attachmentId,name:part.attachment.name,base64:file.toString('base64')});
      }
    }
    return {attachments,files};
  }
  async function exposeFiles(agent, files, signal) {
    const cwd = await ctx.workingDirectory.ensure(agent, signal);
    for (const {ref,bytes} of files)
      await ctx.fs.exposeAttachment(ref,bytes,cwd,ctx.attachments.fileHostPath(ref));
  }
  ctx.on('agent/pre-step', async (payload, next) => {
    try {
      const bound = await prepareBinding(payload.agent, payload.signal, payload);
      if (!bound || bound.dsh_turn !== payload.turn) throw new Error('TURN_BINDING_CONFLICT');
      await business('gate', { run_id: bound.id, epoch:bound.epoch, session_id: payload.agent.id, turn: payload.turn, kind: 'step' }, payload.signal);
      if(bound.automation && preparedBriefingTurns.get(payload.agent.id)!==payload.turn) {
        beginBriefingPeriod(payload.agent,payload.turn,payload.step,payload.signal);
        preparedBriefingTurns.set(payload.agent.id,payload.turn);
      }
      return await next();
    } finally { preparedBindings.delete(payload.agent.id); }
  });
  // All native provider calls share enterprise admission and metering. Title
  // requests can precede the first Run and use their own session-bound budget.
  ctx.on('llm/stream', async function* (options, next) {
    const callId = randomUUID();
    const title = options.purpose === 'session-title';
    const bound = runs.get(options.sessionId);
    if (!title && !bound) throw new Error('UNBOUND_ENTERPRISE_SESSION');
    if (title) await sessionProfile(options.sessionId, options.signal);
    const request = { session_id: options.sessionId, call_id: callId,
      ...(!title ? { run_id: bound.id, epoch:bound.epoch, turn: bound.dsh_turn, kind: 'model' } : {}),
      purpose: options.purpose || 'conversation', provider: options.provider, model: options.model };
    await business(title ? 'auxiliary-admit' : 'gate', request, options.signal);
    const assembler = new BlockAssembler();
    let completed = false;
    try {
      const stream = ['report-review', 'evidence-score'].includes(options.purpose) || title
        ? next() : citationStream(next(), bound.citation_links || {});
      for await (const chunk of stream) { assembler.push(chunk); yield chunk; }
      completed = true;
    } finally {
      // Cancellation may abort the inference signal; persist already observed
      // usage independently and let a metering outage leave the request pending.
      try { await business(title ? 'auxiliary-result' : 'model-result', {
        ...request, usage: assembler.usage, completed,
      }); } catch (error) { ctx.logger.warn('Model usage projection pending: %s', error.message); }
    }
  });
  ctx.on('session/event', (session, event) => {
    if (event.type === 'turn/start') {
      projection.flush(session.id);streams.flush(session.id);
      outputLimited.delete(session.id);
      currentTurns.set(session.id, event.data.turn);
      if (runs.get(session.id)?.dsh_turn !== event.data.turn) {
        pendingEvents.set(session.id, []);
        runs.delete(session.id);
        preparedBindings.delete(session.id);
        claimedInputs.delete(session.id);
      }
    }
    if (pendingEvents.has(session.id)) {pendingEvents.get(session.id).push(event);return;}
    projection.push(session.id,event);
  });
  ctx.on('agent/assistant-stream', ({ agent, frame }) => {
    const bound = runs.get(agent.id);
    if (!bound) return;
    if (frame.type === 'chunk' && frame.chunk.type === 'finish' && frame.chunk.reason?.kind === 'max-tokens') outputLimited.add(agent.id);
    streams.push(agent.id,bound,frame);
  });
  ctx.on('agent/turn-stopping', async ({ agent, turn, signal }) => {
    await drainProjection(agent.id);
    const bound = runs.get(agent.id);
    if (bound) {
      const input = { run_id: bound.id, session_id: agent.id, turn,
        epoch:bound.epoch,
        ...(outputLimited.has(agent.id) ? {stop_reason:'max-tokens'} : {}) };
      // Keep the native truncation/continue flow. A cut-off response is not a
      // completed business result and must not trigger a different task.
      if (outputLimited.has(agent.id)) { await business('complete', input, signal); return; }
      const checked = await business('complete', {...input, check_only:true}, signal);
      if(checked.status==='FAILED' && checked.coverage && !checked.coverage.complete) {
        const source='enterprise-briefing-coverage';
        const corrected=agent.session.snapshotEvents().some(event=>event.type==='user/message' && event.data.source?.kind===source && event.data.source.turn===turn);
        if(!corrected) {
          agent.steer(createUserMessage({source:{kind:source,turn},content:[{type:'text',text:
            '本期业务读取尚未完整。请继续沿原任务补齐以下索引和固定记录，再整理完整正文。同一实验缺少多个分区时，优先用 business_read 的 section=record 完整读取该固定版本；仅记录过大时再按 next_offset 分页。不得补造缺失结果；仍不可读取时说明具体缺口。'+JSON.stringify(checked.coverage)}]}));
          return;
        }
      }
      const correctionSource = 'enterprise-citation-correction';
      const alreadyCorrected = agent.session.snapshotEvents().some(event => event.type === 'user/message'
        && event.data.source?.kind === correctionSource && event.data.source.turn === turn);
      if (checked.status === 'FAILED' && checked.completion?.gaps?.[0] === 'CITATION_INVALID'
          && profileOf(agent) === 'agent' && !alreadyCorrected) {
        const {repair} = await business('output-repair', input, signal);
        bound.output_repair = repair;
        agent.steer(createUserMessage({source:{kind:correctionSource,turn},content:[{type:'text',text:
          repairInstruction(repair)}]}));
        return;
      }
      const result = await business('complete', input, signal);
      if (result.status === 'FAILED') throw deliveryError(result);
    }
  });
  ctx.on('agent/error', ({ agent, error }) => {
    projection.flush(agent.id);streams.flush(agent.id);
    const bound = runs.get(agent.id);
    if (bound) enqueue(agent.id, () => business('failure', { run_id: bound.id, session_id: agent.id,
      epoch:bound.epoch,diagnostic:{code:error?.code,phase:'native-turn',message:error?.message},
      cancelled: cancelled.has(agent.id), error_code: error?.code }));
  });

  async function readBody(req) {
    let size = 0; const chunks = [];
    for await (const chunk of req) { size += chunk.length; if (size > 20 * 1024 * 1024) throw new Error('PAYLOAD_TOO_LARGE'); chunks.push(chunk); }
    return JSON.parse(Buffer.concat(chunks).toString() || '{}');
  }
  // Public static code only; source structures stay in the browser URL fragment.
  ctx.effect(() => ctx.webServer.register({kind:'prefix',path:'/chemistry-assets',async handler(req,res){
    const file = new URL(req.url,'http://local').pathname.split('/').pop();
    const types = {'viewer.html':'text/html; charset=utf-8','viewer.js':'text/javascript','worker.js':'text/javascript','input.js':'text/javascript','limits.json':'application/json','viewer.css':'text/css','RDKit_minimal.js':'text/javascript','RDKit_minimal.wasm':'application/wasm','LICENSE':'text/plain'};
    if (!Object.hasOwn(types,file)) {res.writeHead(404);res.end();return;}
    try {const data=await readChemistryAsset(new URL('../lib/chemistry/'+file,import.meta.url));
      res.writeHead(200,{'content-type':types[file],'x-content-type-options':'nosniff','cache-control':'no-cache'});res.end(data);
    } catch {res.writeHead(503);res.end('Chemistry assets unavailable');}
  }}));
  ctx.effect(() => ctx.webServer.register({ kind: 'prefix', path: '/enterprise', async handler(req, res) {
    const provided = Buffer.from(req.headers.authorization || '');
    const expected = Buffer.from('Bearer ' + internalKey);
    if (provided.length !== expected.length || !timingSafeEqual(provided, expected)) { res.writeHead(401); res.end(); return; }
    const action = new URL(req.url, 'http://local').pathname.slice('/enterprise/'.length);
    try {
      const body = await readBody(req);
      if (body.ticket) interactiveIdentity = {...(interactiveIdentity || identity), ticket:body.ticket};
      let value;
      const signal = new AbortController().signal;
      if (action === 'identity') {
        if(body.identity)interactiveIdentity=body.identity;
        value = { accepted: true };
      } else if (action === 'background-identity') {
        backgroundIdentity=body.identity;
        value=await asBackground(()=>briefings.sync());
      } else if (action === 'background-revoke') {
        backgroundIdentity=undefined;
        for(const [sessionId,auth] of sessionAuthorities) if(auth.background) {
          const agent=ctx.agents.get(sessionId);if(agent)agent.cancel({kind:'user'});
        }
        value={accepted:true};
      } else if (action === 'automations-install') {
        value=await authorizationContext.run({background:false},()=>briefings.sync({install:true}));
      } else if (action === 'automations-sync') {
        value=await asBackground(()=>briefings.sync());
      }
      else if (action === 'models') {
        const selected = ctx.agentDefaultModel.currentSelection();
        try {
          await ctx.llm.prepareCall(selected, signal);
          value = { available: true, ...selected };
        } catch {
          value = { available: false, ...selected };
        }
      }
      else if (action === 'create') {
        const profile = body.profile || 'agent';
        const location = body.workspace_id ? { workspaceId: body.workspace_id } : { cwd: executionRoot };
        const directory = body.workspace_id ? ctx.workspaceRegistry.get(body.workspace_id)?.path : executionRoot;
        await executionDirectory(executionRoot, directory);
        value = await ctx.sessionController.create({ ...location, agentPreset: presetForProfile(profile), sessionId: body.session_id });
        await business('bind', { run_id: body.run_id, session_id: value.sessionId, profile });
      } else {
        const sessionId = body.session_id;
        if (!sessionId) throw new Error('SESSION_REQUIRED');
        const profile = await sessionProfile(sessionId, signal);
        if (body.profile && body.profile !== profile) throw new Error('SESSION_PROFILE_CHANGED');
        await business('ownership', { session_id: sessionId, profile });
        if (action === 'wake') {
          const resolved = await ctx.sessionController.resolveAgent(sessionId);
          if ('error' in resolved) throw new Error(resolved.error.code || 'SESSION_RESUME_FAILED');
          const agent=resolved.agent;
          const pending=Boolean(agent.inbox.nextStep.length || agent.inbox.nextTurn.length);
          if (pending && agent.status !== 'running') {
            if (typeof agent.wakePending !== 'function') throw new Error('ADMISSION_WAKE_UNAVAILABLE');
            agent.wakePending();
          }
          value = {accepted:true,pending,running:agent.status==='running'};
        } else if (action === 'prompt' || action === 'resume') {
          value = await ctx.sessionController.prompt({ sessionId, requestId: body.request_id || randomUUID(),
            mode: body.mode === 'steer' ? 'steer' : 'queue', content: [{ type: 'text', text: body.text || '继续' }],
            clientTimeZone: 'Asia/Shanghai' }, signal);
        } else if (action === 'review') {
          const purpose = body.purpose || 'report-review';
          if (!['report-review', 'evidence-score'].includes(purpose)) throw new Error('ANALYSIS_PURPOSE_DENIED');
          const bound = runs.get(sessionId);
          if (!bound || bound.id !== body.run_id) throw new Error('RUN_BINDING_CONFLICT');
          const agent = ctx.agents.get(sessionId);
          const route = agent?.model.current();
          if (!route) throw new Error('MODEL_SELECTION_REQUIRED');
          const call = await ctx.llm.prepareCall({ ...route, maxTokens: 8192 }, signal);
          const assembler = new BlockAssembler();
          const messages = body.messages.map(message => {
            const input = { content: [{ type: 'text', text: message.content }], source: { kind: 'enterprise-report-review' } };
            if (message.role === 'user') return createUserMessage(input);
            if (message.role === 'assistant') return createAssistantMessage({ ...input,
              source: { provider: call.config.provider, model: call.config.model } });
            throw new Error('REVIEW_MESSAGE_ROLE_INVALID');
          });
          for await (const chunk of call.stream({ ...call.config, system: body.system,
            messages, sessionId, purpose, signal })) assembler.push(chunk);
          if (assembler.finish?.kind !== 'stop') throw new Error('REVIEW_INCOMPLETE');
          value = { content: assembler.blocks().filter(block => block.type === 'text').map(block => block.text).join(''),
            model: call.config.model, provider: call.config.provider };
        } else if (action === 'cancel') {
          if(body.cancel_waiting && body.input_signature) {
            const found=await ctx.sessionController.resolveAgent(sessionId);
            if(!('error' in found) && inputSignature(previewTurnInput(found.agent))===body.input_signature) {
              found.agent.inbox.splice('next-step',0,found.agent.inbox.nextStep.length,[]);
              found.agent.inbox.splice('next-turn',0,1,[]);
            }
          }
          cancelled.add(sessionId); value = await ctx.sessionController.cancel({ sessionId });
        } else if (action === 'queue') {
          const kind = {delete:'remove',promote:'steer'}[body.action?.kind] || body.action?.kind;
          if (!['edit','remove','steer'].includes(kind)) throw new Error('NATIVE_QUEUE_ACTION_REQUIRED');
          const action = kind === 'edit' ? {kind,content:[{type:'text',text:body.action.text}]} : {kind};
          value = await ctx.sessionController.updateQueue({sessionId,itemId:body.item_id,action});        } else if (action === 'authorize') value = {allowed:true};
        else if (action === 'state') {
          await reconcileRunSession(sessionId);
          value = await ctx.sessionController.state({ sessionId }, signal);
        }
        else throw new Error('UNKNOWN_COMMAND');
      }
      res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify(value));
    } catch (error) {
      if (action === 'state' && error.code === 'SESSION_NOT_FOUND') {
        res.writeHead(200, {'content-type':'application/json'});
        res.end(JSON.stringify({running:false,pending:false,missing:true}));
        return;
      }
      process.stderr.write('Enterprise command refused: ' + error.message + '\n'); res.writeHead(409); res.end(JSON.stringify({ error: 'COMMAND_REFUSED' }));
    }
  } }));

  ctx.effect(() => ctx.webServer.register({ kind: 'prefix', path: '/business', async handler(req, res) {
    const denied = ctx.connection.requestRejection({ method: req.method, headers: req.headers });
    if (denied) { res.writeHead(denied); res.end(); return; }
    const action = new URL(req.url, 'http://local').pathname.slice('/business/'.length);
    if (!['context','resource','inbox','run-state','run-diagnostics','run-cancel','run-retry'].includes(action)) {res.writeHead(404);res.end();return;}
    try {
      const body = await readBody(req);
      let value;
      if (action.startsWith('run-')) {
        if (action === 'run-cancel' || action === 'run-retry') {
          const state = await business('run-state',body);
          if (!state.run) throw new Error('RUN_NOT_FOUND');
          value = await business(action, {...body,run_id:state.run.run_id});
        } else value = await business(action,body);
      } else if (action === 'inbox') {
        const profile = await sessionProfile(body.session_id, new AbortController().signal);
        await business('ownership', {session_id:body.session_id,profile});
        // Restore the native durable Inbox without starting a model turn.
        const found = await ctx.sessionController.resolveAgent(body.session_id);
        if ('error' in found) throw found.error;
        value = updateNativeInbox(found.agent,body);
      } else value = await business(action, body);
      res.writeHead(200, {'content-type':'application/json','cache-control':'no-store'});
      res.end(JSON.stringify(value));
    } catch {res.writeHead(409);res.end(JSON.stringify({error:'BUSINESS_ACCESS_REFUSED'}));}
  }}));
  if(backgroundIdentity) await asBackground(()=>briefings.sync());
  else {
    try { briefings.plan=await business('builtin-state',{}); }
    catch(error) { if(error.code!=='ENTERPRISE_GATE_404')throw error; }
  }
  initialized();
}
