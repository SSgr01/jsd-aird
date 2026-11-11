import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import vm from 'node:vm';
import { chemistryList } from '../src/components/markdown/chemistry-input.ts';

const require = createRequire(new URL('../package.json', import.meta.url));
const rdkit = await require('@rdkit/rdkit')();
const workerSource = await readFile(new URL('../public/chemistry/worker.js', import.meta.url), 'utf8');
async function draw(items, reaction = false) {
  const responses = [];
  const context = vm.createContext({ TextEncoder, importScripts() {}, initRDKitModule: async () => rdkit, postMessage: value => { responses.push(value); } });
  vm.runInContext(workerSource, context);
  await context.onmessage({ data: { type: 'init' } });
  assert.equal(responses.pop().type, 'ready');
  for (let id = 0; id < items.length; id++) await context.onmessage({ data: { type: 'draw', id, item: items[id], reaction, maxSvgBytes: 8388608 } });
  return { items: responses.map(response => response.item) };
}
test('real RDKit detects equivalent structures and invalid valence without losing labels', async () => {
  const result = await draw([{label:'S2',source:'c1ccccc1'}, {label:'S3',source:'C1=CC=CC=C1'}, {label:'S4',source:'C(C)(C)(C)(C)C'}]);
  assert.equal(result.items[0].canonical,result.items[1].canonical);
  assert.equal(result.items[0].valid,true);
  assert.match(result.items[0].svg,/<svg/);
  assert.equal(result.items[2].valid,false);
  assert.equal(result.items[2].label,'S4');
  assert.equal(result.items[2].source,'C(C)(C)(C)(C)C');
});
test('reaction SMILES draws without claiming molecular canonical or reaction verification', async () => {
  const result=await draw([{label:'反应',source:'CCO>>CC=O'}],true);
  assert.equal(result.items[0].valid,true);
  assert.equal(result.items[0].canonical,null);
  assert.match(result.items[0].svg,/<svg/);
});

test('labels use one grammar for symbols, punctuation and long names', () => {
  assert.deepEqual(chemistryList('A: CCO\nBDB: c1ccccc1\nESBO: CC1CO1\n对照组: "OCC"'), [
    {label:'A',source:'CCO'},{label:'BDB',source:'c1ccccc1'},{label:'ESBO',source:'CC1CO1'},{label:'对照组',source:'OCC'},
  ]);
  assert.equal(chemistryList('name: Alice\nmode: auto'), null);
  for (const [input, label] of [['A:CCO','A'],['B:CCO','B'],['样品：CCO','样品'],['[BDB]: CCO','[BDB]'],['长'.repeat(200)+':CCO','长'.repeat(200)]]) {
    assert.deepEqual(chemistryList(input), [{label,source:'CCO'}]);
  }
  assert.deepEqual(chemistryList('A:invalid\nBDB: CCO', false), [{label:'A',source:'invalid'},{label:'BDB',source:'CCO'}]);
  assert.equal(chemistryList('样品: ' + 'C'.repeat(5000))[0].source.length,5000);
});

test('RDKit chooses canvas dimensions from the molecule instead of cropping long chains', async () => {
  const result = await draw([{label:'长链',source:'C'.repeat(120)}, {label:'乙醇',source:'CCO'}]);
  assert.equal(result.items[0].valid,true);
  assert.ok(result.items[0].width > 900);
  assert.ok(result.items[0].width > result.items[1].width);
  assert.ok(result.items[0].height > 0);
});
