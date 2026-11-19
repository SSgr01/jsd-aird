import test from 'node:test';
import assert from 'node:assert/strict';
import {SlotCore} from '../../../vendor/deepseek-harness/packages/client/ui-slots/src/index.ts';
import {registerEnterpriseOnboarding} from '../../enterprise-plugin/src/onboarding-client.mjs';

test('enterprise onboarding suppresses only the preview notice without writing acknowledgements', () => {
  const slots=new SlotCore(),effects=[],finished={current:false};let completed=0;
  slots.register({name:'root',children:{'settings.onboarding':{kind:'list',scope:'root'}}},()=>null);
  const Preview=()=> 'preview',Models=()=> 'models';
  slots.register({name:'settings.onboarding',id:'welcome-notice',order:-100},Preview);
  slots.register({name:'settings.onboarding',id:'deepseek-official'},Models);
  let release;
  registerEnterpriseOnboarding({slots:{inject:(_name,fn)=>{release=fn();},register:(...args)=>slots.register(...args)}},
    {React:{useRef:()=>finished,useEffect:fn=>effects.push(fn)}});
  const entries=slots.entriesOfSlot('settings.onboarding');
  assert.equal(entries.length,2);assert.ok(entries.some(e=>e.component===Models));
  const notice=entries.find(e=>e.options.id==='welcome-notice');
  assert.notEqual(notice.component,Preview);
  assert.equal(notice.component({complete:()=>completed++}),null);
  effects.forEach(fn=>{fn();fn();});assert.equal(completed,1);
  release();assert.equal(slots.entriesOfSlot('settings.onboarding').find(e=>e.options.id==='welcome-notice').component,Preview);
});
