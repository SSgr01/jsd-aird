import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, realpath } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import EnterpriseFileSystem, { worldRoot } from '../../enterprise-plugin/src/filesystem.mjs';
import EnterpriseWorkingDirectory from '../../enterprise-plugin/src/working-directory.mjs';
import EnterpriseSpillStore from '../../enterprise-plugin/src/spill-store.mjs';
import EnterpriseDirectoryPicker from '../../enterprise-plugin/src/directory-picker.mjs';
import { workspacePath } from '../src/path-view.mjs';
import { enterprisePatch } from '../src/config.mjs';

const require = createRequire(new URL('../../../vendor/deepseek-harness/packages/fs/fs-local/package.json', import.meta.url));
const { Context } = await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href);

test('native directory projection and overflow text share the sandbox-visible session tree', async t => {
  const root = await mkdtemp(join(tmpdir(), 'aird-runtime-mapping-'));
  const previous = process.env.JSD_DSH_EXECUTION_ROOT;
  process.env.JSD_DSH_EXECUTION_ROOT = root;
  const ctx = new Context(), fs = new EnterpriseFileSystem(ctx);
  t.after(async () => {
    await ctx.fiber.dispose();
    if (previous === undefined) delete process.env.JSD_DSH_EXECUTION_ROOT; else process.env.JSD_DSH_EXECUTION_ROOT = previous;
    await rm(root, { recursive:true, force:true });
  });
  const session = { cwd:join(root, 'project') };
  await mkdir(session.cwd);
  const directory = { ctx:{fs}, defaultDirectory:root, workingDirectoryProjectionHandle:{stateOf:s => s.cwd},
    get:EnterpriseWorkingDirectory.prototype.get };
  assert.equal(directory.get(session), worldRoot + '/project');
  session.cwd = worldRoot + '/project';
  assert.equal(directory.get(session), session.cwd);
  const store = { ctx:{fs, agents:{get:id => id === 'session-a' ? {session} : undefined}, workingDirectory:directory} };
  const input = { owner:{sessionId:'session-a'}, suggestedName:'../../web_fetch.txt', content:'完整原文\nβ'.repeat(15000) };
  const saved = await EnterpriseSpillStore.prototype.saveText.call(store, input);
  assert.ok(saved.locator.startsWith(worldRoot + '/project/.dsh-spill/'));
  assert.equal(saved.bytes, Buffer.byteLength(input.content));
  assert.equal(await fs.readText(await fs.resolve(saved.locator)), input.content);
  const snapshot = await fs.snapshot(directory.get(session));
  assert.equal(snapshot.length, 1);
  assert.equal(Buffer.from(snapshot[0].base64, 'base64').toString(), input.content);
  const another = await EnterpriseSpillStore.prototype.saveText.call(store, input);
  assert.notEqual(another.locator, saved.locator);
  await assert.rejects(EnterpriseSpillStore.prototype.saveText.call(store, {...input,owner:{sessionId:'other'}}), /SPILL_SESSION_UNAVAILABLE/);
});

test('enterprise composition replaces native providers without replacing native policies', () => {
  const patch = enterprisePatch({ plugin:'G:/enterprise', executionRoot:'G:/execution', model:{id:'deepseek-flash'} });
  assert.ok(patch.find(row => row.id === 'working-directory').name.endsWith('working-directory.mjs'));
  assert.ok(patch.find(row => row.id === 'spill-local').name.endsWith('spill-store.mjs'));
  assert.equal(patch.find(row => row.id === 'spill-policy')?.disabled, undefined);
});

test('native directory selection and creation round-trip sandbox paths and keep failures private', async t => {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'aird-directory-mapping-')));
  const previous = process.env.JSD_DSH_EXECUTION_ROOT;
  process.env.JSD_DSH_EXECUTION_ROOT = root;
  const ctx = new Context(), picker = new EnterpriseDirectoryPicker(ctx).capability();
  t.after(async () => {
    await ctx.fiber.dispose();
    if (previous === undefined) delete process.env.JSD_DSH_EXECUTION_ROOT; else process.env.JSD_DSH_EXECUTION_ROOT = previous;
    await rm(root, { recursive:true, force:true });
  });
  assert.equal(await picker.createDirectory(workspacePath, '乳液工艺'), workspacePath + '/乳液工艺');
  const listing = await picker.list();
  assert.equal(listing.path, workspacePath);
  assert.equal(listing.home, workspacePath);
  assert.deepEqual(listing.crumbs, [{name:'/workspaces',path:workspacePath,hidden:false}]);
  assert.equal(listing.entries[0].path, workspacePath + '/乳液工艺');
  assert.equal((await picker.list(listing.entries[0].path)).path, workspacePath + '/乳液工艺');
  await assert.rejects(picker.createDirectory(workspacePath, '乳液工艺'), error => {
    assert.equal(error.code, 'directory-exists');
    assert.equal(error.path, workspacePath + '/乳液工艺');
    assert.ok(!error.message.includes(root));
    return true;
  });
  await assert.rejects(picker.list(workspacePath + '/../../'), /EXECUTION_DIRECTORY_DENIED/);
});


test('sessions share a workspace file while a different owner has an independent namespace', async t => {
  const root = await mkdtemp(join(tmpdir(),'aird-workspace-sharing-'));
  const previous = process.env.JSD_DSH_EXECUTION_ROOT;
  const contexts=[];
  t.after(async()=>{for(const ctx of contexts) await ctx.fiber.dispose();
    if(previous===undefined) delete process.env.JSD_DSH_EXECUTION_ROOT; else process.env.JSD_DSH_EXECUTION_ROOT=previous;
    await rm(root,{recursive:true,force:true});});
  const make = async owner => {
    const directory=join(root,owner); await mkdir(join(directory,'default'),{recursive:true});
    process.env.JSD_DSH_EXECUTION_ROOT=directory;
    const ctx=new Context();contexts.push(ctx);return new EnterpriseFileSystem(ctx);
  };
  const first=await make('first'), same=await make('first'), other=await make('second');
  await first.publish('/workspaces/default/配方.txt',Buffer.from('固含量 45%'));
  assert.equal(await same.readText(await same.resolve('/workspaces/default/配方.txt')),'固含量 45%');
  await assert.rejects(other.readText(await other.resolve('/workspaces/default/配方.txt')));
  await assert.rejects(first.resolve('/workspaces/../../second/default/配方.txt'));
});
