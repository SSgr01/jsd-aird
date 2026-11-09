import assert from 'node:assert/strict';
import test from 'node:test';
import { authenticated, contextSettings, publicEvent } from '../src/policy.mjs';

test('runtime authentication requires its complete, sufficiently long bearer secret', () => {
  const secret = 'r'.repeat(32);
  assert.equal(authenticated(`Bearer ${secret}`, secret), true);
  for (const header of [undefined, '', secret, `Bearer ${secret}x`, `Bearer ${'c'.repeat(32)}`]) {
    assert.equal(authenticated(header, secret), false);
  }
  assert.equal(authenticated('Bearer short', 'short'), false);
  assert.equal(authenticated('Bearer ', ''), false);
});

test('model input and compaction stay within the context and output reservations', () => {
  for (const [window, output] of [[128000, 8192], [16000, 4096]]) {
    const { inputBudget, compaction } = contextSettings(window, output);
    assert.ok(inputBudget <= 64000);
    assert.ok(inputBudget + output + 4096 <= window);
    assert.equal(window - compaction.reserveTokens, Math.floor(inputBudget * 0.8));
    assert.ok(compaction.keepRecentTokens <= 16000);
    assert.ok(compaction.keepRecentTokens < inputBudget);
  }
});

test('invalid model context declarations are refused', () => {
  for (const pair of [[8192, 1], [16384, 8192], [NaN, 1024], [32000, 1.5]]) {
    assert.throws(() => contextSettings(...pair), /MODEL_CONTEXT_INVALID/);
  }
});

test('only assistant text deltas become visible UI output', () => {
  assert.deepEqual(publicEvent({ type: 'message_update', assistantMessageEvent: { type: 'text_delta', delta: '正文' } }),
    { type: 'message.delta', payload: { delta: '正文' } });
  for (const type of ['thinking_delta', 'toolcall_delta', 'error']) {
    assert.equal(publicEvent({ type: 'message_update', assistantMessageEvent: { type, delta: 'private' } }), null);
  }
  assert.equal(publicEvent({ type: 'message_end', message: { role: 'user' } }), null);
});

test('completed messages expose text and usage without reasoning or provider credentials', () => {
  const visible = publicEvent({ type: 'message_end', message: {
    role: 'assistant', stopReason: 'stop', apiKey: 'private-key',
    content: [{ type: 'thinking', thinking: 'private-reasoning' }, { type: 'text', text: '答案' }],
    usage: { input: 10, output: 2, cacheRead: 0, cacheWrite: 0, cost: 99 },
  } });
  assert.equal(visible.payload.text, '答案');
  assert.equal(visible.payload.usage.pricing, 'UNKNOWN');
  assert.doesNotMatch(JSON.stringify(visible), /private|cost/);
});

test('tool events exclude arguments and raw results', () => {
  const event = publicEvent({ type: 'tool_execution_end', toolCallId: 'call-1', toolName: 'document_read',
    isError: false, arguments: { secret: 'private' }, result: { text: 'unfiltered' } });
  assert.equal(event.type, 'tool.completed');
  assert.equal(event.payload.toolCallId, 'call-1');
  assert.doesNotMatch(JSON.stringify(event), /private|unfiltered|arguments|result/);
  assert.equal(publicEvent({ type: 'provider_request', apiKey: 'private' }), null);
});
