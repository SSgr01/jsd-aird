import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, symlink, realpath } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ownerKey } from '../src/security.mjs';
import { Hosts } from '../src/hosts.mjs';
import { enterprisePatch } from '../src/config.mjs';
import { checkRpc } from '../src/rpc-policy.mjs';
import { executionDirectory } from '../src/paths.mjs';
import { presetForProfile, profileForPreset } from '../src/session-policy.mjs';

const owner = {organization_id:'00000000-0000-0000-0000-000000000001',actor_id:'00000000-0000-0000-0000-000000000099',auth_version:1,expires_at:4e9};
const rpc = (method, request) => ({type:'client-request',method,payload:{args:{request}}});

test('one owner uses one Host across modes; other users, organizations and revoked identities do not', async () => {
  const hosts = new Hosts({maxHosts:10}), stopped = [];
  let starts = 0;
  hosts.start = async identity => {starts++; return {identity,child:{exitCode:null}};};
  hosts.stop = async host => stopped.push(host);
  const [a,b] = await Promise.all([hosts.ensure({...owner,profile:'agent'}),hosts.ensure({...owner,profile:'fast_qa'})]);
  assert.equal(a,b); assert.equal(starts,1);
  assert.equal(ownerKey({...owner,profile:'fast_qa'}),ownerKey({...owner,profile:'agent'}));
  assert.notEqual(await hosts.ensure({...owner,actor_id:'00000000-0000-0000-0000-000000000100'}),a);
  assert.notEqual(await hosts.ensure({...owner,organization_id:'00000000-0000-0000-0000-000000000002'}),a);
  assert.notEqual(await hosts.ensure({...owner,auth_version:2}),a);
  assert.deepEqual(stopped,[a]); await hosts.close();
});

test('concurrent requests share one replacement after exit or identity revocation', async () => {
  for (const reason of ['exit','revocation']) {
    const hosts = new Hosts({maxHosts:1});
    let starts = 0, stops = 0, release;
    const shutdown = new Promise(resolve => { release = resolve; });
    hosts.start = async identity => { starts++; return {identity,child:{exitCode:null}}; };
    hosts.stop = async () => { stops++; await shutdown; };
    const original = await hosts.ensure(owner);
    if (reason === 'exit') original.child.exitCode = 1;
    const identity = {...owner,auth_version:reason === 'revocation' ? 2 : 1};
    const requests = Array.from({length:20},()=>hosts.ensure(identity));
    await Promise.resolve();
    assert.equal(stops,1);
    assert.equal(starts,1);
    release();
    const replacements = await Promise.all(requests);
    assert.equal(starts,2);
    assert.equal(stops,1);
    assert.equal(new Set(replacements).size,1);
    assert.notEqual(replacements[0],original);
    assert.equal(await hosts.ensure(identity),replacements[0]);
    await hosts.close();
  }
});

test('native Workspace membership survives the unified session policy, without adding cwd or business scope', async () => {
  for (const profile of ['agent']) {
    const request = {workspaceId:'native-workspace',agentPreset:presetForProfile(profile)};
    await checkRpc('session/create',rpc('session/create',request),{executionRoot:'unused'});
    assert.deepEqual(request,{workspaceId:'native-workspace',agentPreset:presetForProfile(profile)});
  }
  await assert.rejects(checkRpc('session/create',rpc('session/create',{workspaceId:'native',cwd:tmpdir()}),{executionRoot:tmpdir()}),/SESSION_LOCATION_CONFLICT/);
  await assert.rejects(checkRpc('session/create',rpc('session/create',{workspaceId:'native',agentPreset:'cordis'}),{executionRoot:tmpdir()}),/ENTERPRISE_PRESET_REQUIRED/);
});

test('directory admission refuses another owner, prefix collisions and junction escapes', async t => {
  const base = await mkdtemp(join(tmpdir(),'jsd-workspace-policy-'));
  t.after(()=>rm(base,{recursive:true,force:true}));
  const root = join(base,'owner'), other = join(base,'owner-other'), dir = join(root,'documents');
  await mkdir(dir,{recursive:true}); await mkdir(other);
  const host = {executionRoot:root};
  const create = rpc('workspace/create',{path:dir});
  await checkRpc('workspace/create',create,host);
  assert.equal(create.payload.args.request.path,await realpath(dir));
  const detached = rpc('session/create',{cwd:dir}); await checkRpc('session/create',detached,host);
  assert.equal(detached.payload.args.request.cwd,await realpath(dir));
  assert.equal(detached.payload.args.request.workspaceId,undefined);
  for (const path of [other,base]) await assert.rejects(executionDirectory(root,path),/EXECUTION_DIRECTORY_DENIED/);
  await symlink(other,join(root,'foreign'),process.platform==='win32'?'junction':'dir');
  await assert.rejects(executionDirectory(root,join(root,'foreign')),/EXECUTION_DIRECTORY_DENIED/);
  await assert.rejects(checkRpc('directoryPicker/list',{type:'client-request',method:'directoryPicker/list',payload:{args:{path:other}}},host),/EXECUTION_DIRECTORY_DENIED/);
  await assert.rejects(checkRpc('directoryPicker/pick',rpc('directoryPicker/pick',{}),host),/RPC_DENIED/);
});

test('one enterprise preset composes the native standard tools, skills and compaction', () => {
  const patch = enterprisePatch({plugin:tmpdir(),executionRoot:tmpdir(),model:{id:'test',baseURL:'http://model.invalid',contextWindow:10000,maxTokens:1000}});
  const rows = patch.find(row=>row.insert).insert;
  const agent = rows.find(row=>row.config?.id==='enterprise'), qa = rows.find(row=>row.config?.id==='enterprise-fast-qa');
  assert.ok(agent.config.plugins.some(row=>row.id==='tool-todo'));
  assert.equal(qa,undefined);
  for (const id of ['tool-fs-search','skill-filesystem','tool-ask-user','planning','compaction','tool-web','present','tool-schedule'])
    assert.ok(agent.config.plugins.some(row=>row.id===id));
  assert.equal(patch.find(row=>row.id==='ui-schedule').disabled,false);
  assert.equal(profileForPreset(agent.config.id),'agent');
  assert.equal(patch.find(row=>row.id==='workspace-controller').config.documentsDirectory,tmpdir());
});
