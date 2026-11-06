import test from 'node:test';
import assert from 'node:assert/strict';
import { installNativeRuntime } from '../../enterprise-plugin/src/native-runtime.mjs';
import { enterprisePatch } from '../src/config.mjs';
import { WebRuntime } from '../../../vendor/deepseek-harness/packages/web/web/lib/index.js';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

test('enterprise composition routes native search to its registered backend',async t=>{
  const require=createRequire(new URL('../../../vendor/deepseek-harness/packages/web/web/package.json',import.meta.url));
  const {Context}=await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href);
  const ctx=new Context();t.after(()=>ctx.fiber.dispose());
  const patches=enterprisePatch({model:{id:'gateway-model'},plugin:'plugin',executionRoot:'workspace'});
  const selection=patches.find(row=>row.id==='web').config;
  const web=new WebRuntime(ctx,Object.fromEntries(Object.entries(selection).map(([key,value])=>[key,{get:()=>value}])));
  web.registerSearchProvider({id:'deepseek-search-api',available:()=>true,search:()=>assert.fail('unsupported gateway search route')});
  web.registerSearchProvider({id:'enterprise-search',available:()=>true,search:async()=>({sources:[{url:'https://example.org'}],truncated:false})});
  assert.equal((await web.search({query:'search'})).sources[0].url,'https://example.org');
  for(const id of ['web-search-deepseek','web-deepseek-api'])assert.equal(patches.find(row=>row.id===id).disabled,true);
});

test('native search binds the actual tool turn, deduplicates queries and exposes provider failure',async()=>{
  const events=new Map(),calls=[];let provider,fail=false;
  const ctx={on:(name,fn)=>events.set(name,fn),plugin:()=>{},
    web:{registerSearchProvider:value=>{provider=value;}},fs:{}};
  const agent={id:'session'},runs=new Map([['session',{id:'run',dsh_turn:7,pack_ids:['files','retrieval']}]]);
  installNativeRuntime(ctx,{runs,profileOf:()=> 'agent',searchAvailable:true,
    business:async(action,body)=>{calls.push({action,body});return fail?{error:'WEB_SEARCH_PROVIDER_FAILED'}:{sources:[],truncated:false};}});
  assert.equal(await provider.ready(),true);
  await assert.rejects(provider.search({query:'outside tool'}),/REQUIRES_TOOL/);
  const exec={agent,name:'web_search',callId:'native-call',signal:new AbortController().signal};
  await events.get('tools/execute')(exec,async()=>{
    for(let index=0;index<2;index++)await provider.search({query:'UV resin',maxResults:5});
    fail=true;
    await assert.rejects(provider.search({query:'failed query'}),/WEB_SEARCH_PROVIDER_FAILED/);
  });
  const searches=calls.filter(call=>call.action==='native-tool');
  assert.equal(searches[0].body.call_id,searches[1].body.call_id);
  assert.notEqual(searches[0].body.call_id,searches[2].body.call_id);
  assert.equal(searches[0].body.run_id,'run');
  assert.equal(searches[0].body.turn,7);
  assert.equal(searches[0].body.name,'dsh_web_search');
  assert.equal(calls[0].body.kind,'tool');
});
