import test from 'node:test';
import assert from 'node:assert/strict';
import { tmpdir } from 'node:os';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { Readable } from 'node:stream';
import { apply } from '../../enterprise-plugin/src/index.mjs';
import { LocalAttachmentStore } from '../../../vendor/deepseek-harness/packages/attachment/attachment-local/lib/index.js';
const require = createRequire(new URL('../../../vendor/deepseek-harness/packages/attachment/attachment-local/package.json',import.meta.url));
const {Context} = await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href);

test('native tool resolution binds the claimed batch before the first prompt and each new turn', async t => {
  const previous = {...process.env}, calls = [], events = new Map(), definitions = [], exposed = [];
  const sharedRules = (await readFile(new URL('../../control/src/aird_agent/capabilities/answer_guidelines.txt',import.meta.url),'utf8')).trim();
  const repairs = new Map();
  const repairFor = body => {
    const key=body.session_id+'-'+body.turn;
    if(!repairs.has(key))repairs.set(key,{model_calls:0,tool_calls:0,max_model_calls:body.session_id==='qa'?1:2,max_tool_calls:2});
    return repairs.get(key);
  };
  const attachmentRoot = await mkdtemp(join(tmpdir(),'dsh-native-input-'));
  t.after(()=>rm(attachmentRoot,{recursive:true,force:true}));
  const attachmentContext = new Context();
  t.after(()=>attachmentContext.fiber.dispose());
  const attachments = new LocalAttachmentStore(attachmentContext,{dshHome:attachmentRoot});
  const attachment = await attachments.saveFile({name:'sample.txt',data:Buffer.from('native document')});
  Object.assign(process.env, {JSD_DSH_IDENTITY:JSON.stringify({ticket:'test'}),
    JSD_DSH_CONTROL_URL:'http://control.invalid', JSD_DSH_RUNTIME_SECRET:'test', JSD_DSH_INTERNAL_KEY:'internal', JSD_DSH_EXECUTION_ROOT:tmpdir()});
  t.after(()=>{for(const key of Object.keys(process.env))if(!Object.hasOwn(previous,key))delete process.env[key];Object.assign(process.env,previous);});
  t.mock.method(globalThis,'fetch',async(url,options)=>{
    const action=url.split('/').at(-1), body=JSON.parse(options.body); calls.push({action,body});
    const repair=repairFor(body);
    if(action==='gate' && body.kind==='model') {
      assert.ok(repair.model_calls < repair.max_model_calls);
      repair.model_calls++;
    }
    return {ok:true,json:async()=>action==='catalog'
      ? {tools:['search_projects','search_experiments'].map(name=>({name,parameters:{type:'object'}})),instructions:'unbound fallback',max_output_tokens:1000}
      : action==='register' ? {id:body.session_id+'-'+body.turn,dsh_turn:body.turn,
        binding_protocol:2,binding_etags:{tools:'tools-v1'},citation_links:{},citation_evidence_ids:[],
        pack_ids:['files'],
        ...(body.binding_etags?.tools==='tools-v1' ? {} : {
          tools:[{name:'search_experiments'}], active_tools:['search_experiments'],
          instructions:'authorized policy '+body.profile+' turn '+body.turn+'\n'+sharedRules+'\n当前命令沙箱未启用，不要调用 bash。'}),
        context:JSON.stringify({task_state:{registered_outputs:body.request?[]:[{kind:'experiment_draft',id:'created-once'}]},
          ...(body.request?{authorized_context:{request:body.request}}:{})})}
      : ['builtin-state','builtin-sync'].includes(action) ? {tasks:{}}
      : action==='builtin-pending' ? {sessions:[]}
      : action==='complete' ? (body.session_id==='research' && body.turn===2 && repair.model_calls===2
        ? {status:'SUCCEEDED'} : {status:'FAILED',completion:{gaps:['CITATION_INVALID']}})
      : action==='output-repair' ? {repair:{...repair,
        diagnostics:{issues:[{code:'UNKNOWN_EVIDENCE_ID',citation:repair.model_calls?'[E:still-wrong]':'[E:missing]',line:repair.model_calls?1:3}],
          available_evidence:[{evidence_id:'actual',excerpt:'A measured fact'}]}}}
      : {allowed:true}};
  });
  let section, context, enterpriseRoute;
  const ctx={tools:{register:(definition,options)=>{definitions.push({definition,options});return ()=>{};},guard:()=>()=>{}},
    plugin:()=>{},web:{registerSearchProvider:()=>{}},fs:{hostPath:path=>path,exposeAttachment:async(...args)=>exposed.push(args)},agents:{get:()=>undefined},sessions:{},systemPrompt:{section:value=>{if(value.name==='enterprise-policy')section=value;return ()=>{};},
      context:value=>{context=value;return ()=>{};}},llm:{},
    agentPresets:{presetForSession:()=> 'enterprise'},
    workingDirectory:{get:()=>tmpdir(),ensure:async()=>tmpdir()},workspaceRegistry:{},sessionController:{},
    attachments,
    schedule:{catalog:async()=>[]},
    webServer:{register:route=>{if(route.path==='/enterprise')enterpriseRoute=route.handler;return ()=>{};}},connection:{},logger:{warn:()=>{}},
    effect:fn=>fn(),on:(name,fn)=>events.set(name,fn)};
  await apply(ctx);
  const background=Readable.from([Buffer.from(JSON.stringify({identity:{ticket:'background',automation_grant_id:'grant',expires_at:Date.now()/1000+3600}}))]);
  Object.assign(background,{url:'/enterprise/background-identity',headers:{authorization:'Bearer internal'}});
  let status;
  await enterpriseRoute(background,{writeHead:value=>{status=value;},end:()=>{}});
  assert.equal(status,200);
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
    assert.equal(section.text({agent}).split(sharedRules).length-1,1);
    assert.match(section.text({agent}),/涉及过程总耗时时/);
    assert.doesNotMatch(section.text({agent}),/原生文件与 bash 命令在 Linux 沙箱的同一执行空间/);
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
    assert.equal(calls.filter(c=>c.action==='register').at(-1).body.binding_etags.tools,'tools-v1');
    assert.equal(refreshed.authorized_context.request,text);
    assert.deepEqual(refreshed.task_state.registered_outputs,[{kind:'experiment_draft',id:'created-once'}]);
    assert.equal(section.text({agent}).split(sharedRules).length-1,1);
    await events.get('agent/pre-step')({agent,turn,step:2,messages:[],signal},async()=>({kind:'enter',messages:[]}));
    const stop=()=>events.get('agent/turn-stopping')({agent,turn,signal});
    {
      await stop();
      assert.equal(steered.length,1);
      assert.equal(steered[0].source.turn,turn);
      assert.match(steered[0].content[0].text,/\[E:/);
      assert.match(steered[0].content[0].text,/UNKNOWN_EVIDENCE_ID/);
      assert.match(steered[0].content[0].text,/不输出修复说明/);
      assert.match(steered[0].content[0].text,/不得在正文、引号或代码中复述无效引用句柄/);
      assert.equal(calls.filter(c=>c.action==='complete').at(-1).body.check_only,true);
    }
    // Repeated stopping before another model call does not enqueue duplicate
    // repair messages or prematurely commit a terminal failure.
    await stop();assert.equal(steered.length,1);
    assert.equal(calls.filter(c=>c.action==='complete').at(-1).body.check_only,true);
    const repair=repairFor({session_id:id,turn});
    for(let used=0;used<repair.max_model_calls;used++) {
      if(used) {
        await stop();
        assert.equal(steered.length,used+1);
        assert.match(steered.at(-1).content[0].text,/\[E:still-wrong\]/);
      }
      assert.equal(steered.at(-1).source.model_calls,used);
      assert.equal(steered.at(-1).source.run_id,id+'-'+turn);
      assert.match(steered.at(-1).content[0].text,new RegExp('剩余 '+(repair.max_model_calls-used)+' 次模型请求'));
      if(used===repair.max_model_calls-1)assert.match(steered.at(-1).content[0].text,/0 次定向补读/);
      // Exercise the native model admission hook; no real provider is called.
      for await(const _ of events.get('llm/stream')({sessionId:id,signal},async function*(){})) {}
    }
    if(id==='research' && turn===2)await stop();
    else await assert.rejects(stop(),/引用/);
    assert.equal(steered.length,repair.max_model_calls);
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
