import test from 'node:test';
import assert from 'node:assert/strict';
import {bindingCursor,mergeBinding} from '../../enterprise-plugin/src/binding-state.mjs';
import {EventProjection,StreamProjection} from '../../enterprise-plugin/src/event-projection.mjs';

test('binding deltas retain admitted context and links but reset cursors on new input or turn',()=>{
 const first={binding_protocol:2,id:'r1',dsh_turn:1,tools:[{name:'read'}],active_tools:['read'],instructions:'policy',
  context:JSON.stringify({authorized_context:{request:'original'},task_state:{}}),
  citation_links:{e1:{url:'first'}},citation_evidence_ids:['e1'],binding_etags:{context:'v1'}};
 const second=mergeBinding(first,{binding_protocol:2,id:'r1',dsh_turn:1,citation_links:{e2:{url:'second'}},
  citation_evidence_ids:['e2'],binding_etags:{context:'v2'},context:JSON.stringify({task_state:{saved:true}})});
 assert.equal(second.tools,first.tools);
 assert.equal(second.instructions,'policy');
 assert.deepEqual(second.citation_evidence_ids,['e1','e2']);
 assert.equal(second.citation_links.e1.url,'first');
 assert.equal(JSON.parse(second.context).authorized_context.request,'original');
 assert.deepEqual(JSON.parse(second.context).task_state,{saved:true});
 assert.deepEqual(bindingCursor(second,1).known_citation_evidence_ids,['e1','e2']);
 assert.deepEqual(bindingCursor(second,2),{binding_protocol:2});
 assert.deepEqual(bindingCursor(second,1,true),{binding_protocol:2});
 assert.equal(mergeBinding(first,{id:'r2',dsh_turn:2,binding_protocol:2}).citation_links,undefined);
});

test('failed critical projection cannot be skipped by a later checkpoint',async()=>{
 const sent=[],pending=[];
 const queue=new EventProjection({enqueue:(_sid,fn)=>pending.push(fn),send:async(_sid,event)=>{
  sent.push(event.seq);throw new Error('projection unavailable');
 }});
 queue.push('s',{seq:1,type:'assistant/message',data:{turn:1}});
 queue.push('s',{seq:2,type:'step/end',data:{turn:1}});queue.flush('s');
 for(const fn of pending)await assert.rejects(fn(),/projection unavailable/);
 assert.deepEqual(sent,[1]);assert.throws(()=>queue.check('s'),/projection unavailable/);
 queue.clear('s');assert.doesNotThrow(()=>queue.check('s'));
});

test('stream projection filters reasoning, combines queued text and drains the final delta across turns',async()=>{
 const sent=[],pending=[];
 const streams=new StreamProjection({enqueue:(_sid,fn)=>pending.push(fn),send:async(sid,bound,frames)=>sent.push({sid,turn:bound.dsh_turn,frames})});
 const frame=text=>({type:'chunk',chunk:{type:'text-delta',text}});
 streams.push('s',{dsh_turn:1},{type:'chunk',chunk:{type:'reasoning-delta',text:'private'}});
 assert.equal(streams.pending.size,0);
 streams.push('s',{dsh_turn:1},frame('first'));streams.flush('s');
 streams.push('s',{dsh_turn:1},frame('last'));streams.flush('s');
 assert.equal(pending.length,1);await pending.shift()();
 assert.deepEqual(sent[0].frames.map(f=>f.chunk.text),['first','last']);
 streams.push('s',{dsh_turn:2},frame('next'));streams.flush('s');await pending.shift()();
 assert.equal(sent[1].turn,2);assert.equal(streams.pending.size,0);
});

test('text arriving during an in-flight projection is drained in order before completion',async()=>{
 const pending=[],sent=[];let release;
 const blocked=new Promise(resolve=>{release=resolve;});
 const streams=new StreamProjection({enqueue:(_sid,fn)=>pending.push(fn),send:async(_sid,_bound,frames)=>{
  sent.push(frames.map(f=>f.chunk.text).join(''));if(sent.length===1)await blocked;
 }});
 const push=text=>streams.push('s',{dsh_turn:1},{type:'chunk',chunk:{type:'text_delta',text}});
 push('first');streams.flush('s');const sending=pending.shift()();
 push('last');streams.flush('s');assert.equal(pending.length,0);
 release();await sending;assert.equal(pending.length,1);await pending.shift()();
 assert.deepEqual(sent,['first','last']);assert.equal(streams.pending.size,0);
});

test('progress is coalesced without context payloads and stays before messages, receipts and turn endings',async()=>{
 const sent=[],pending=[];
 const queue=new EventProjection({delay:100000,enqueue:(_sid,operation)=>{pending.push(operation);},send:async(sid,event)=>sent.push({sid,event})});
 for(let seq=1;seq<=100;seq++)queue.push('s',{seq,type:'request/context',data:{turn:1,largePayload:'private source body'}});
 queue.push('s',{seq:101,type:'assistant/message',data:{turn:1,message:{content:[{type:'tool-call',name:'present',arguments:{files:[{path:'report.md'}]}}]}}});
 queue.push('s',{seq:102,type:'tool/result',data:{turn:1,result:{receipt:'native-only'}}});
 queue.push('s',{seq:103,type:'turn/end',data:{turn:1,reason:{kind:'error'}}});
 for(const operation of pending)await operation();
 assert.deepEqual(sent.map(item=>item.event.seq),[100,101,102,103]);
 assert.equal(sent[0].event.data.largePayload,undefined);
 assert.equal(sent[1].event.data.message.content[0].arguments.files[0].path,'report.md');
 assert.equal(sent[3].event.data.reason.kind,'error');
 queue.clear('s');
});
