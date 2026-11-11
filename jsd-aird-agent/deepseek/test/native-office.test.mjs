import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { apply } from '../../enterprise-plugin/src/office-runtime.mjs';

test('bundled native Office skills retain their original workflows with sandbox runtime paths',async()=>{
 let provider;
 apply({skills:{registerProvider(factory){provider=factory({});}}});
 const candidates=await provider.list();
 assert.equal(candidates.length,7);
 for(const candidate of candidates){
  assert.equal(candidate.resourceBase.path,'/opt/dsh-skills/office/'+candidate.name);
  const skill=await provider.get(candidate,{});
  const raw=await readFile(candidate.locator,'utf8');
  const original=raw.replace(/^---\r?\n[\s\S]*?\r?\n---(?:\r?\n|$)/u,'').trim();
  assert.equal(skill.content.split('\n\n## Configured execution environment\n')[0],original);
  assert.ok(skill.content.includes('/usr/local/bin/python3'));
  assert.ok(skill.content.includes('load_workspace_dependencies tool is unavailable'));
  if(['office-render','office-recalculate','office-export-pdf'].includes(skill.name)){
   assert.ok(skill.content.includes('"node": "/usr/local/bin/node"'));
   assert.ok(skill.content.includes('/opt/dsh-office/node_modules/@deepseek-ai/libreoffice-kit/lib/cli.js'));
   assert.ok(!skill.content.includes('disabled in this deployment'));
  }
 }
});
