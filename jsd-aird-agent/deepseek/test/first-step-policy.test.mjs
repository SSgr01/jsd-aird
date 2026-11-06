import test from 'node:test';
import assert from 'node:assert/strict';
import { tmpdir } from 'node:os';
import { mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { apply } from '../../enterprise-plugin/src/index.mjs';
import { LocalAttachmentStore } from '../../../vendor/deepseek-harness/packages/attachment/attachment-local/lib/index.js';
const require = createRequire(new URL('../../../vendor/deepseek-harness/packages/attachment/attachment-local/package.json',import.meta.url));
const {Context} = await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href);

test('native tool resolution binds the claimed batch before the first prompt and each new turn', async t => {
  const previous = {...process.env}, calls = [], events = new Map(), definitions = [], exposed = [];
  const attachmentRoot = await mkdtemp(join(tmpdir(),'dsh-native-input-'));
  t.after(()=>rm(attachmentRoot,{recursive:true,force:true}));
  const attachmentContext = new Context();
  t.after(()=>attachmentContext.fiber.dispose());
  const attachments = new LocalAttachmentStore(attachmentContext,{dshHome:attachmentRoot});
  const attachment = await attachments.saveFile({name:'sample.txt',data:Buffer.from('native document')});
  Object.assign(process.env, {JSD_DSH_IDENTITY:JSON.stringify({ticket:'test'}),
    JSD_DSH_CONTROL_URL:'http://control.invalid', JSD_DSH_RUNTIME_SECRET:'test', JSD_DSH_EXECUTION_ROOT:tmpdir()});
  t.after(()=>{for(const key of Object.keys(process.env))if(!Object.hasOwn(previous,key))delete process.env[key];Object.assign(process.env,previous);});
  t.mock.method(globalThis,'fetch',async(url,options)=>{
    const action=url.split('/').at(-1), body=JSON.parse(options.body); calls.push({action,body});
    return {ok:true,json:async()=>action==='catalog'
      ? {tools:['search_projects','search_experiments'].map(name=>({name,parameters:{type:'object'}})),instructions:'unbound fallback',max_output_tokens:1000}
      : action==='register' ? {id:body.session_id+'-'+body.turn,dsh_turn:body.turn,
        pack_ids:['files'],
        tools:[{name:'search_experiments'}], active_tools:['search_experiments'],
        instructions:'authorized policy '+body.profile+' turn '+body.turn,
        context:JSON.stringify({task_state:{registered_outputs:body.request?[]:[{kind:'experiment_draft',id:'created-once'}]},
          ...(body.request?{authorized_context:{request:body.request}}:{})})}
      : action==='complete' ? {status:'FAILED',completion:{gaps:['CITATION_INVALID']}}
      : {allowed:true}};
  });
  let section, context;
  const ctx={tools:{register:(definition,options)=>{definitions.push({definition,options});return ()=>{};},guard:()=>()=>{}},
    plugin:()=>{},web:{registerSearchProvider:()=>{}},fs:{exposeAttachment:async(...args)=>exposed.push(args)},agents:{},sessions:{},systemPrompt:{section:value=>{section=value;return ()=>{};},
      context:value=>{context=value;return ()=>{};}},llm:{},
    agentPresets:{presetForSession:()=> 'enterprise'},
    workingDirectory:{get:()=>tmpdir(),ensure:async()=>tmpdir()},workspaceRegistry:{},sessionController:{},
    attachments,
    webServer:{register:()=>()=>{}},connection:{},logger:{warn:()=>{}},
    effect:fn=>fn(),on:(name,fn)=>events.set(name,fn)};
  await apply(ctx);
  const signal=new AbortController().signal;
  for(const [id,turn,text] of [['research',1,'first request'],['research',2,'next task'],['qa',1,'fixed question'],['scheduled',1,'scheduled enterprise request']]) {
    const nativeEvents=[], steered=[];
    const agent={id,session:{id,snapshotEvents:()=>nativeEvents},options:{},steer:message=>{
      steered.push(message);nativeEvents.push({type:'user/message',data:message});
    }};
    events.get('session/event')(agent.session,{type:'turn/start',data:{turn}});
    const message={id:'input-'+turn,role:'user',source:{kind:id==='scheduled'?'schedule':'user'},content:[{type:'text',text}]};
    if(id==='research'&&turn===1)message.content.push({type:'file',attachment});
    events.get('agent/inbox/claimed')({agent,turn,message});
    // Matches dsh: claim -> await tools.resolve -> systemPrompt.assemble -> pre-step.
    const selected=[];
    for(const {definition,options} of definitions)if(await options.when({agent,signal}))selected.push(definition.name);
    assert.deepEqual(selected,['search_experiments']);
    assert.match(section.text({agent}),new RegExp('authorized policy '+'agent'+' turn '+turn));
    assert.notEqual(section.complete,true); // retain the upstream base prompt
    assert.equal(JSON.parse(context.text({agent})).authorized_context.request,text);
    assert.equal(context.required,true);
    const admitted=await events.get('agent/pre-step')({agent,turn,step:1,messages:[message],signal},async()=>({kind:'enter',messages:[message]}));
    assert.deepEqual(admitted.messages,[message]); // dsh owns snapshot persistence and deduplication
    assert.equal(calls.filter(c=>c.action==='register'&&c.body.session_id===id&&c.body.turn===turn).length,1);
    assert.equal(calls.filter(c=>c.action==='register').at(-1).body.request,text);
    assert.deepEqual(calls.filter(c=>c.action==='register').at(-1).body.attachments,
      id==='research'&&turn===1?[{attachment_id:attachment.attachmentId,name:'sample.txt',base64:Buffer.from('native document').toString('base64')}]:[]);
    assert.equal(exposed.length,1);
    assert.deepEqual(exposed[0],[attachment,Buffer.from('native document'),tmpdir(),attachments.fileHostPath(attachment)]);
    // A subsequent native step retains the admitted input but sees newly
    // confirmed outputs instead of restoring the entire stale snapshot.
    await definitions[0].options.when({agent,signal});
    const refreshed=JSON.parse(context.text({agent}));
    assert.equal(refreshed.authorized_context.request,text);
    assert.deepEqual(refreshed.task_state.registered_outputs,[{kind:'experiment_draft',id:'created-once'}]);
    await events.get('agent/pre-step')({agent,turn,step:2,messages:[],signal},async()=>({kind:'enter',messages:[]}));
    const stop=()=>events.get('agent/turn-stopping')({agent,turn,signal});
    {
      await stop();
      assert.equal(steered.length,1);
      assert.equal(steered[0].source.turn,turn);
      assert.match(steered[0].content[0].text,/\[E:/);
      assert.equal(calls.filter(c=>c.action==='complete').at(-1).body.check_only,true);
    }
    // A second failure closes with its real diagnosis; correction is bounded to one generation.
    await assert.rejects(stop(),/引用/);
    assert.equal(steered.length,1);
    assert.equal(calls.filter(c=>c.action==='complete').at(-1).body.check_only,undefined);
  }
  const agent={id:'truncated',session:{id:'truncated',snapshotEvents:()=>[]},options:{},
    steer:()=>assert.fail('truncation must keep the native continuation flow')};
  events.get('session/event')(agent.session,{type:'turn/start',data:{turn:1}});
  const message={role:'user',source:{kind:'user'},content:[{type:'text',text:'generate files'}]};
  events.get('agent/inbox/claimed')({agent,turn:1,message});
  await definitions[0].options.when({agent,signal});
  events.get('agent/assistant-stream')({agent,frame:{type:'chunk',chunk:{type:'finish',reason:{kind:'max-tokens'}}}});
  await events.get('agent/turn-stopping')({agent,turn:1,signal});
  assert.equal(calls.filter(c=>c.action==='complete').at(-1).body.stop_reason,'max-tokens');
  assert.equal(calls.filter(c=>c.action==='complete').at(-1).body.check_only,undefined);
});
