import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {workbenchTasks,startWorkbenchTask,tasksForExpert} from '../../enterprise-plugin/src/workbench-tasks.mjs';

test('task entries dispatch to the existing experts, with a draft rather than an execution request',async()=>{
  const expected={'experiment-records':'experiment-assistant',materials:'materials-advisor',literature:'materials-advisor',project:'project-assistant'};
  const calls=[];
  for(const [id,expert] of Object.entries(expected)) {
    await startWorkbenchTask(id,async(identity,draft)=>{calls.push({identity,draft});});
    assert.deepEqual(calls.at(-1).identity,{id:expert});
    assert.equal(calls.at(-1).draft,workbenchTasks.find(task=>task.id===id).prompt);
  }
  assert.equal(calls.length,4);
  assert.match(calls.at(-1).draft,/不修改项目状态/);
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

test('unselected experts keep default tasks; selected experts use their own catalog examples',()=>{
  assert.equal(tasksForExpert(null),workbenchTasks);
  const a={id:'a',name:'文献专家',summary:'研读文献',examples:['比较论文方法','核对文献证据']};
  const b={id:'b',name:'材料专家',examples:['分析材料失效']};
  assert.deepEqual(tasksForExpert(a).slice(0,2).map(t=>t.prompt),a.examples);
  assert.deepEqual(tasksForExpert(b).slice(0,1).map(t=>t.prompt),b.examples);
  assert.equal(tasksForExpert(a).length,4);
  assert.ok(tasksForExpert(a).every(t=>t.expertId==='a'));
  assert.equal(tasksForExpert({...a,examples:[]})[0].expertId,'a');
  assert.equal(tasksForExpert({...a,examples:['',null,...Array(6).fill('任务')]}).length,4);
});

test('all seven company experts have four distinct concise cards backed by their own prompts',()=>{
  const catalog=JSON.parse(readFileSync(new URL('../../../jsd-aird-api/src/main/resources/ai/expert-presets.json',import.meta.url),'utf8'));
  assert.equal(catalog.experts.length,7);
  for(const expert of catalog.experts) {
    const tasks=tasksForExpert(expert);
    assert.equal(tasks.length,4,expert.name);
    assert.equal(expert.starter_tasks.length,4,expert.name);
    assert.equal(new Set(tasks.map(task=>task.prompt)).size,4,expert.name);
    assert.ok(tasks.every(task=>task.title.length<=12 && task.description.length>0 && task.prompt.length>task.title.length && task.expertId===expert.id));
  }
  assert.equal(workbenchTasks.length,4);
  assert.equal(new Set(workbenchTasks.map(task=>task.title)).size,4);
});
