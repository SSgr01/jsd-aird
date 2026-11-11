import test from 'node:test';
import assert from 'node:assert/strict';
import { ReactLoopInbox, inboxProjectionDefinition } from '../../../vendor/deepseek-harness/packages/core/agent-loop/src/inbox.ts';
import { createUserMessage } from '../../../vendor/deepseek-harness/packages/llm/llm/lib/index.js';
import { updateNativeInbox } from '../../enterprise-plugin/src/inbox-actions.mjs';

function fixture() {
  const events = [];
  let state = inboxProjectionDefinition.init();
  const session = {append(type,data) {
    const event = {seq:events.length+1,type,data};events.push(event);
    state = inboxProjectionDefinition.apply(state,event);return event;
  }};
  const inbox = new ReactLoopInbox(()=>state,session,{emit(){}});
  const rows = ['A','B','C'].map(text=>createUserMessage({source:{kind:'user'},content:[{type:'text',text}]}));
  for (const row of rows) inbox.append('next-turn',row);
  return {agent:{inbox},rows,events};
}

test('sorting uses one native splice, preserving identities and replayed queue order', () => {
  const {agent,rows,events} = fixture();
  assert.deepEqual(updateNativeInbox(agent,{kind:'move',item_id:rows[1].id,direction:-1,
    expected_ids:rows.map(row=>row.id)}),{accepted:true});
  assert.deepEqual(agent.inbox.nextTurn.map(row=>row.id),[rows[1].id,rows[0].id,rows[2].id]);
  assert.equal(events.length,4);
  const restored = events.reduce((state,event)=>inboxProjectionDefinition.apply(state,event),inboxProjectionDefinition.init());
  assert.deepEqual(restored['next-turn'],agent.inbox.nextTurn);
  const count = events.length;
  assert.throws(()=>updateNativeInbox(agent,{kind:'take',item_id:rows[0].id,expected_ids:rows.map(row=>row.id)}),/INBOX_CHANGED/);
  assert.equal(events.length,count);
});

test('returning text removes only the still-pending native occurrence and rejects attachments', () => {
  const {agent,rows} = fixture();
  assert.throws(()=>updateNativeInbox(agent,{kind:'take',item_id:rows[1].id,expected_text:'stale B',
    expected_ids:rows.map(row=>row.id)}),/INBOX_CHANGED/);
  assert.deepEqual(agent.inbox.nextTurn,rows);
  assert.deepEqual(updateNativeInbox(agent,{kind:'take',item_id:rows[1].id,expected_text:'B',expected_ids:rows.map(row=>row.id)}),
    {accepted:true,text:'B'});
  assert.deepEqual(agent.inbox.nextTurn.map(row=>row.id),[rows[0].id,rows[2].id]);
  const attachment = createUserMessage({source:{kind:'user'},content:[{type:'file',attachment:{name:'sample.pdf'}}]});
  agent.inbox.append('next-turn',attachment);
  const pending = agent.inbox.nextTurn.map(row=>row.id);
  assert.throws(()=>updateNativeInbox(agent,{kind:'take',item_id:attachment.id,expected_ids:pending}),/INBOX_TEXT_ONLY/);
  assert.deepEqual(agent.inbox.nextTurn.map(row=>row.id),pending);
});
