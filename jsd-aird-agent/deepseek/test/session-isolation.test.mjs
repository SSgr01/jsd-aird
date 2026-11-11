import test from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { tmpdir } from 'node:os';
import { apply } from '../../enterprise-plugin/src/index.mjs';

test('enterprise gates read each logged native preset, never a Host mode, header or caller field', async t => {
  t.mock.method(globalThis,'fetch',async (url,options)=>{
    const body = JSON.parse(options.body), action = url.split('/').at(-1);
    calls.push({action,body});
    return {ok:true,json:async()=>action==='catalog'?{tools:[{name:'get_project_snapshot',parameters:{type:'object'}}],instructions:'test',max_output_tokens:1000}
      :action==='register'?{id:body.session_id,tools:[{name:'get_project_snapshot'}],dsh_turn:body.turn,instructions:'test'}:{allowed:true}};
  });
  const previous = {...process.env}, calls = [], events = new Map(), routes = new Map();
  process.env.JSD_DSH_IDENTITY=JSON.stringify({ticket:'test'}); process.env.JSD_DSH_CONTROL_URL='http://control.invalid';
  process.env.JSD_DSH_RUNTIME_SECRET='test'; process.env.JSD_DSH_PROFILE='fast_qa';
  process.env.JSD_DSH_EXECUTION_ROOT=tmpdir();
  t.after(()=>{for(const key of Object.keys(process.env)) if(!Object.hasOwn(previous,key)) delete process.env[key]; Object.assign(process.env,previous);});
  const presets = new Map([['a','enterprise'],['q','enterprise']]);
  let guard;
  const ctx = {
    tools:{register:()=>()=>{},guard:fn=>{guard=fn;return ()=>{};}},
    plugin:()=>{},web:{registerSearchProvider:()=>{}},fs:{hostPath:path=>path},
    agents:{get:()=>null},sessions:{},systemPrompt:{section:()=>()=>{},context:()=>()=>{}},llm:{},
    agentPresets:{presetForSession:session=>presets.get(session.id)},
    workingDirectory:{get:()=>tmpdir(),ensure:async()=>tmpdir()},
    workspaceRegistry:{create:()=>{throw new Error('Modes must not create Workspaces');}},
    sessionController:{projections:async({sessionId})=>({values:{agentPreset:presets.get(sessionId)}})},
    webServer:{register:route=>{routes.set(route.path,route.handler);return ()=>{};}},
    connection:{requestRejection:()=>undefined},logger:{warn:()=>{}},
    effect:fn=>fn(),on:(name,fn)=>events.set(name,fn),
  };
  await apply(ctx);
  const agent = id=>({id,session:{id,header:{agentPreset:'enterprise-fast-qa',cwd:'/mode-is-not-a-directory'}},options:{}});
  for (const id of ['a','q']) await events.get('agent/pre-step')({agent:agent(id),turn:1,step:1,messages:[],signal:new AbortController().signal},async()=>({kind:'continue',messages:[]}));
  assert.deepEqual(calls.filter(call=>call.action==='register').map(call=>call.body.profile),['agent','agent']);
  assert.equal(guard({agent:agent('a'),name:'get_project_snapshot'}),undefined);
  assert.equal(guard({agent:agent('q'),name:'get_project_snapshot'}),undefined);
  assert.equal(guard({agent:agent('q'),name:'todo_write'}),'ENTERPRISE_TOOL_DENIED');
  for (const action of ['mode','state','queue','report','attach']) {
    const request=Readable.from([Buffer.from('{}')]);Object.assign(request,{method:'POST',url:'/business/'+action,headers:{}});
    let status;
    await routes.get('/business')(request,{writeHead:value=>{status=value;},end:()=>{}});
    assert.equal(status,404);
  }
  const pending=[{id:'queued-a',source:{kind:'user'},content:[{type:'text',text:'first'}]},
    {id:'queued-b',source:{kind:'user'},content:[{type:'text',text:'second'}]}];
  let resumed=0;
  ctx.sessionController.resolveAgent=async()=>{resumed++;return {agent:{inbox:{get nextTurn(){return pending;},
    splice:(_target,start,count,inserted)=>pending.splice(start,count,...inserted)}}};};
  async function move(sessionId) {
    const request=Readable.from([Buffer.from(JSON.stringify({session_id:sessionId,kind:'move',item_id:'queued-b',
      direction:-1,expected_ids:['queued-a','queued-b']}))]);
    Object.assign(request,{method:'POST',url:'/business/inbox',headers:{}});
    let status;
    await routes.get('/business')(request,{writeHead:value=>{status=value;},end:()=>{}});
    return status;
  }
  assert.equal(await move('not-in-this-host'),409);
  assert.equal(resumed,0);
  assert.deepEqual(pending.map(item=>item.id),['queued-a','queued-b']);
  assert.equal(await move('a'),200);
  assert.equal(resumed,1);
  assert.deepEqual(calls.at(-1),{action:'ownership',body:{session_id:'a',profile:'agent'}});
  assert.deepEqual(pending.map(item=>item.id),['queued-b','queued-a']);
});
