import test from 'node:test';
import assert from 'node:assert/strict';
import { standardPlugins } from '../src/config.mjs';
import { apply as nativeTimeContext } from '../../../vendor/deepseek-harness/packages/context/time-context/lib/index.js';

test('enterprise preset retains native time context with an explicit fallback and fresh request clock', () => {
  const row = standardPlugins({plugin:'G:/enterprise'}).find(row => row.id === 'time-context');
  assert.equal(row.name, '@deepseek-ai/dsh-time-context');
  assert.notEqual(row.disabled, true);
  assert.deepEqual(row.config, {timeZone:'Asia/Shanghai', refreshIntervalMs:0});
});

test('native time context selects browser zones and refreshes followups after restoration', async () => {
  let projection, state, preStep;
  const ctx = {
    fiber:{assertActive(){}},
    sessionProjections:{async register(spec) {
      projection=spec; state=spec.init(); return {stateOf:()=>state};
    }},
    on(event, listener) {assert.equal(event,'agent/pre-step'); preStep=listener;},
  };
  await nativeTimeContext(ctx, {timeZone:'Asia/Shanghai',refreshIntervalMs:0});
  const message = zone => ({role:'user',source:{kind:'user',rpcId:'request',clientTimeZone:zone},
    content:[{type:'text',text:'请安排会议'}]});
  const prepare = async (turn, messages) => {
    state=projection.apply(state,{type:'turn/start',data:{turn},time:Date.now()});
    const decision=await preStep({agent:{session:{}},turn,step:1,signal:new AbortController().signal},
      async()=>({kind:'allow',messages}));
    const clock=decision.messages.at(-1);
    assert.equal(clock.source.kind,'time-context');
    state=projection.apply(state,{type:'user/message',data:clock,time:Date.now()});
    return clock.content[0].text;
  };
  assert.match(await prepare(1,[message('Asia/Shanghai')]), /Browser time zone for this request: Asia\/Shanghai/);
  state=JSON.parse(JSON.stringify(state)); // the native projection is persisted across resume
  assert.match(await prepare(2,[message('America/New_York')]), /Browser time zone for this request: America\/New_York/);
  const mixed=await prepare(3,[message('Asia/Shanghai'),message('America/New_York')]);
  assert.match(mixed,/mixed/);
  assert.match(mixed,/Ask the user to clarify/);
  const missing=await prepare(4,[]);
  assert.match(missing,/unavailable/);
  assert.match(missing,/Asia\/Shanghai/);
});
