import test from 'node:test';
import assert from 'node:assert/strict';
import {StageTiming} from '../../enterprise-plugin/src/stage-timing.mjs';

test('enabled stage diagnostics survive a silent native INFO exporter and include failures',async()=>{
 const lines=[];
 const timer=new StageTiming({info:()=>assert.fail('native exporter may drop INFO')},true,line=>lines.push(line));
 await assert.rejects(timer.time('bind.queue_wait',{session_id:'s'},()=>{throw new Error('failed');}),/failed/);
 const record=JSON.parse(lines[0].slice('HARNESS_STAGE '.length));
 assert.equal(record.stage,'bind.queue_wait');assert.equal(record.session_id,'s');assert.ok(record.elapsed_ms>=0);
 assert.equal(record.error,undefined);
 const off=new StageTiming({},false,()=>assert.fail('disabled timing emitted a log'));
 assert.equal(await off.time('off',{},()=>7),7);
});
