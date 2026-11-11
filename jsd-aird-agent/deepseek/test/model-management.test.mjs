import test from 'node:test';
import assert from 'node:assert/strict';
import { checkRpc } from '../src/rpc-policy.mjs';
import { configuration, enterprisePatch } from '../src/config.mjs';
import { prepareProfile } from '../src/profile.mjs';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { loadProfileDirectory, composeEntries } from '../../../vendor/deepseek-harness/packages/boot/app-boot/lib/index.js';

const envelope = (method,args) => ({type:'client-request',method,payload:{args}});
const admin = {identity:{role:'SYSTEM_ADMIN'}};
const reader = {identity:{role:'RESEARCHER'}};

test('native provider edits and credential writes require the server-verified administrator role', async () => {
  const cases = [
    ['settings/mutate',{ns:'llm-pi-ai',ops:[{op:'set',path:['providers','openai'],value:{apiKeyEnv:'OPENAI_API_KEY'}}]}],
    ['settings/update',{ns:'llm-deepseek',patch:{baseURL:'https://example.test/anthropic'}}],
    ['credentials/set',{ref:'OPENAI_API_KEY',value:'synthetic'}],
    ['credentials/unset',{ref:'OPENAI_API_KEY'}],
    ['llm/discoverModels',{settingsNs:'llm-pi-ai',request:{provider:'openai'}}],
  ];
  for (const [method,args] of cases) {
    await checkRpc(method,envelope(method,args),admin);
    await assert.rejects(checkRpc(method,envelope(method,args),reader),/RPC_DENIED/);
    await assert.rejects(checkRpc(method,envelope(method,args),{}),/RPC_DENIED/);
  }
  await assert.rejects(checkRpc('settings/mutate',envelope('settings/mutate',{ns:'enterprise',ops:[]}),admin),/RPC_DENIED/);
});

test('native model catalogs and session selection remain usable without configuration privileges', async () => {
  for (const method of ['session/modelCatalog','session/selectModel','llm/listProviders','llm/listConfigurableProviders'])
    await checkRpc(method,envelope(method,{request:{sessionId:'test',provider:'anthropic',model:'configured-model'}}),reader);
});

test('title requests follow the selected native model and both native adapters remain available', () => {
  const config=configuration({JSD_AIRD_HARNESS_RUNTIME_SECRET:'x'.repeat(32),JSD_AIRD_DSH_PROVIDER:'openai',JSD_AIRD_DSH_MODEL:'configured-model'});
  const patch=enterprisePatch(config);
  assert.equal(patch.find(row=>row.id==='agent-default-model').config.provider,'openai');
  assert.equal(patch.find(row=>row.id==='session-title-llm').config.provider,undefined);
  for (const id of ['llm-deepseek','llm-pi-ai','ui-model-selection','ui-settings-models'])
    assert.equal(patch.find(row=>row.id===id).disabled,false);
});

test('verified custom routes declare image input without borrowing official protocol extensions',()=>{
  const config=configuration({JSD_AIRD_HARNESS_RUNTIME_SECRET:'x'.repeat(32),
    JSD_AIRD_DSH_MODEL:'verified-gateway-model',JSD_AIRD_DSH_INPUT_MODALITIES:'text,image'});
  const model=enterprisePatch(config).find(row=>row.id==='llm-deepseek').config.models[0];
  assert.deepEqual(model.inputModalities,['text','image']);
  assert.equal(model.systemPromptUpdate,undefined);
  assert.equal(model.toolUpdate,undefined);
  assert.throws(()=>configuration({JSD_AIRD_HARNESS_RUNTIME_SECRET:'x'.repeat(32),JSD_AIRD_DSH_INPUT_MODALITIES:'text,audio'}),/modalities/);
});

test('native model and display preferences survive deployment regeneration and override only defaults', async t => {
  const home=await mkdtemp(join(tmpdir(),'jsd-native-settings-'));
  t.after(()=>rm(home,{recursive:true,force:true}));
  const config=configuration({JSD_AIRD_HARNESS_RUNTIME_SECRET:'x'.repeat(32)});
  const policy=await prepareProfile(config,home,home);
  const userPatch=join(home,'profiles/web/cordis.patch.yml');
  const overrides=[{id:'llm-pi-ai',config:{providers:{openai:{apiKeyEnv:'SYNTHETIC_KEY'}}}},
    {id:'agent-default-model',config:{provider:'openai',model:'configured-model'}},
    {id:'ui-chat',config:{transcriptView:'detailed',collapseTiming:'next-input',foldedReplies:'all'}}];
  await writeFile(userPatch,JSON.stringify(overrides));
  await prepareProfile(config,home,home);
  assert.deepEqual(JSON.parse(await readFile(userPatch,'utf8')),overrides);
  const profile=loadProfileDirectory('dsh',join(home,'profiles/web'),resolve(config.upstream,'apps/cli/package.json'));
  assert.deepEqual(profile.skippedBundles,[]);
  const entries=composeEntries([...profile.layers.map(layer=>layer.patches),profile.patches,JSON.parse(await readFile(policy,'utf8'))]);
  assert.equal(entries.find(row=>row.id==='agent-default-model').config.provider,'openai');
  assert.equal(entries.find(row=>row.id==='llm-pi-ai').config.providers.openai.apiKeyEnv,'SYNTHETIC_KEY');
  assert.equal(entries.find(row=>row.id==='ui-chat').config.foldedReplies,'all');
  assert.equal(entries.find(row=>row.id==='bash-sandbox').disabled,true);
});

test('native forms save safe display settings through mutate while execution settings remain closed', async () => {
  for(const [ns,path,value] of [['ui-chat',['foldedReplies'],'final'],['ui-chat',['collapseTiming'],'completion'],
    ['ui-settings-general',['welcomeNoticeVersion'],'2026-09-28.1'],['ui-settings',['enabled'],false]])
    await checkRpc('settings/mutate',envelope('settings/mutate',{ns,ops:[{op:'set',path,value}]}),reader);
  for(const [ns,path,value] of [['ui-chat',['foldedReplies','nested'],'all'],['ui-chat',['__proto__'],'all'],
    ['ui-settings',['shell'],'host'],['bash-sandbox',['policy'],'danger-full-access']])
    await assert.rejects(checkRpc('settings/mutate',envelope('settings/mutate',{ns,ops:[{op:'set',path,value}]}),reader),/RPC_DENIED/);
});
