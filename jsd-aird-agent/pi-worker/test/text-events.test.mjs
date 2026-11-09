import assert from 'node:assert/strict';
import test from 'node:test';
import { textEvents } from '../src/text-events.mjs';

const delta = (text, id = 'message') => ({ type: 'message.delta', payload: { id, delta: text } });

test('shows first text immediately and flushes adjacent text before completion or tools', () => {
  const received = [];
  const buffer = textEvents(event => received.push(event));
  buffer.push({ type: 'message.started', payload: { id: 'message' } });
  buffer.push(delta('首'));
  assert.equal(received.at(-1).payload.delta, '首');
  for (const text of ['段', '文字', '。']) buffer.push(delta(text));
  buffer.push({ type: 'tool.started', payload: {} });
  buffer.push({ type: 'message.completed', payload: { id: 'message', text: '首段文字。' } });
  assert.deepEqual(received.map(event => event.type), ['message.started', 'message.delta', 'message.delta', 'tool.started', 'message.completed']);
  assert.equal(received.filter(event => event.type === 'message.delta').map(event => event.payload.delta).join(''), '首段文字。');
});

test('a pause in the model stream does not strand buffered text', async () => {
  const received = [];
  let delivered;
  const waiting = new Promise(resolve => { delivered = resolve; });
  const buffer = textEvents(event => { received.push(event); if (received.length === 2) delivered(); }, { intervalMs: 5 });
  buffer.push(delta('已'));
  buffer.push(delta('找到依据'));
  await waiting;
  assert.equal(received[1].payload.delta, '找到依据');
});

test('bounds buffered text and never mixes different message identities', () => {
  const received = [];
  const buffer = textEvents(event => received.push(event), { maxChars: 3 });
  buffer.push(delta('a'));
  buffer.push(delta('bc'));
  buffer.push(delta('d'));
  buffer.push(delta('e', 'next'));
  buffer.flush();
  assert.deepEqual(received.map(event => [event.payload.id, event.payload.delta]), [
    ['message', 'a'], ['message', 'bcd'], ['next', 'e'],
  ]);
});
