import test from 'node:test';
import assert from 'node:assert/strict';
import {workbenchTasks,startWorkbenchTask} from '../../enterprise-plugin/src/workbench-tasks.mjs';

test('task entries dispatch to the existing experts, with a draft rather than an execution request',async()=>{
  const expected={'experiment-records':'experiment-assistant',tables:'experiment-assistant',materials:'materials-advisor','experiment-review':'materials-advisor'};
  const calls=[];
  for(const [id,expert] of Object.entries(expected)) {
    await startWorkbenchTask(id,async(identity,draft)=>{calls.push({identity,draft});});
    assert.deepEqual(calls.at(-1).identity,{id:expert});
    assert.equal(calls.at(-1).draft,workbenchTasks.find(task=>task.id===id).prompt);
  }
  assert.equal(calls.length,4);
  assert.match(calls.at(-1).draft,/不创建或修改定时任务/);
  assert.match(calls.at(-1).draft,/没有指定实验时先询问范围/);
});
test('unknown task cannot choose an arbitrary expert or initiate a session',()=>{
  let calls=0;
  assert.throws(()=>startWorkbenchTask('new-expert',()=>calls++),/未知任务/);
  assert.equal(calls,0);
});
test('failed expert creation is surfaced without a different expert fallback',async()=>{
  let calls=0;
  await assert.rejects(()=>startWorkbenchTask('experiment-records',async()=>{calls++;throw new Error('EXPERT_DISABLED');}),/EXPERT_DISABLED/);
  assert.equal(calls,1);
});
