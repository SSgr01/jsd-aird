import test from 'node:test';
import assert from 'node:assert/strict';
import { mergeBusinessContext } from '../../enterprise-plugin/src/business-context.mjs';

test('unchanged business state preserves the exact prompt bytes for caching', () => {
  const previous = '{"authorized_context": {"request": "read"}, "task_state": {"registered_outputs": []}, "evidence": []}';
  assert.equal(mergeBusinessContext(previous, JSON.stringify({task_state:{registered_outputs:[]},evidence:[]})),previous);
});

test('host object-key reordering does not append another context snapshot', () => {
  const previous = '{"task_state": {"output": {"id": "r1", "revision": 2}}, "evidence": []}';
  assert.equal(mergeBusinessContext(previous, JSON.stringify({
    evidence: [], task_state: {output: {revision: 2, id: 'r1'}},
  })), previous);
  assert.notEqual(mergeBusinessContext(previous, JSON.stringify({task_state:{output:{id:'r1',revision:3}}})), previous);
  assert.notEqual(mergeBusinessContext('{"evidence":["a","b"]}', '{"evidence":["b","a"]}'), '{"evidence":["a","b"]}');
});

test('confirmed writes refresh business state while retaining the admitted input', () => {
  const previous = JSON.stringify({authorized_context:{request:'create draft'},task_state:{registered_outputs:[]},evidence:[]});
  const state = {registered_outputs:[{kind:'experiment_draft',id:'existing-draft'}],
    business_context_updates:{current_records:[{evidence_id:'new-source',text:'experimentCount: 1'}]}};
  const merged = mergeBusinessContext(previous, JSON.stringify({task_state:state,evidence:[]}));
  assert.deepEqual(JSON.parse(merged),{authorized_context:{request:'create draft'},task_state:state,evidence:[]});
  assert.equal(mergeBusinessContext(merged,JSON.stringify({task_state:state,evidence:[]})),merged);
});
