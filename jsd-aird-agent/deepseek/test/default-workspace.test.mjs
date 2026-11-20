import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm,symlink,realpath} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {ensureDefaultWorkspace} from '../../enterprise-plugin/src/default-workspace.mjs';

test('default directory is available on first entry and legacy automatic names become 默认工作区',async()=>{
  const root=await mkdtemp(join(tmpdir(),'default-workspace-'));
  try {
    for(const title of ['default','/workspaces/default','研发资料']) {
      const row={id:'existing',title,async setTitle(value){this.title=value;}};
      const registry={async create(path,name){assert.equal(path,join(await realpath(root),'default'));assert.equal(name,'默认工作区');return row;}};
      assert.equal(await ensureDefaultWorkspace(registry,root),row);
      assert.equal(row.title,title==='研发资料'?title:'默认工作区');
    }
  } finally {await rm(root,{recursive:true,force:true});}
});

test('a linked default directory cannot bypass directory admission',async()=>{
  const root=await mkdtemp(join(tmpdir(),'default-workspace-'));
  const outside=await mkdtemp(join(tmpdir(),'outside-workspace-'));
  try {
    await symlink(outside,join(root,'default'),process.platform==='win32'?'junction':'dir');
    let created=false;
    await assert.rejects(ensureDefaultWorkspace({async create(){created=true;}},root),/EXECUTION_DIRECTORY_DENIED/);
    assert.equal(created,false);
  } finally {await rm(root,{recursive:true,force:true});await rm(outside,{recursive:true,force:true});}
});
