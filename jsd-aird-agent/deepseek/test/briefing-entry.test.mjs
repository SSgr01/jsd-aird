import test from 'node:test';
import assert from 'node:assert/strict';
import {readBriefingEntry} from '../../enterprise-plugin/src/briefing-entry.mjs';

test('briefing links carry only a known kind and existing result identity',()=>{
  const run='550e8400-e29b-41d4-a716-446655440000';
  assert.deepEqual(readBriefingEntry('/agent/?briefing=project-morning&run='+run),{kind:'project-morning',run_id:run});
  for(const path of ['/agent/','/agent/?briefing=other&run='+run,
    '/agent/?briefing=research-weekly&run=../other', '/agent/?briefing=experiment-review&session_id=other']) {
    assert.equal(readBriefingEntry(path),null);
  }
});
