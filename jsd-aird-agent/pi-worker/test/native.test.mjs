import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import test from 'node:test';
import { Runtime } from '../src/session.mjs';

async function fixture(t, modelReply, { reviewReady = () => true, reviewResult = () => ({}), steering = () => [], modelId = 'synthetic', gateState = () => ({}),
  toolResult = body => body.name === 'discover' ? { activate_tools: ['platform_echo'] } : { echoed: body.arguments.text } } = {}) {
  const root = await mkdtemp(join(tmpdir(), 'jsd-pi-native-'));
  const calls = [], requests = [];
  const server = createServer(async (request, response) => {
    let raw = '';
    for await (const chunk of request) raw += chunk;
    const body = JSON.parse(raw || '{}');
    if (request.url.startsWith('/v1/')) {
      requests.push(body);
      const reply = modelReply(body, requests.length);
      response.writeHead(200, { 'Content-Type': 'text/event-stream' });
      const send = (delta, finish = null) => response.write('data: ' + JSON.stringify({
        id: 'synthetic-' + requests.length, object: 'chat.completion.chunk', created: 1, model: 'synthetic',
        choices: [{ index: 0, delta, finish_reason: finish }],
      }) + '\n\n');
      send({ role: 'assistant' });
      if (reply.tool) {
        send({ tool_calls: [{ index: 0, id: 'call-' + requests.length, type: 'function',
          function: { name: reply.tool, arguments: JSON.stringify(reply.arguments || {}) } }] });
        send({}, 'tool_calls');
      } else {
        for (const content of reply.chunks || [reply.text || 'done']) send({ content });
        send({}, 'stop');
      }
      response.end('data: [DONE]\n\n');
      return;
    }
    const action = request.url.split('/').at(-1);
    calls.push({ action, ...body });
    let result = {};
    if (action === 'gate') result = { stop: false, messages: steering(), notes: [], ...gateState() };
    if (action === 'tool') result = toolResult(body);
    if (action === 'review') result = { ready: reviewReady(calls), messages: steering(), ...reviewResult(body) };
    response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(result));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}`;
  const runtime = new Runtime({ root, controlUrl: url, secret: 'r'.repeat(32),
    model: { id: modelId, baseUrl: url + '/v1', apiKey: 'synthetic-key' } });
  t.after(async () => {
    await Promise.all([...runtime.runs.keys()].map(id => runtime.abort(id)));
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
    // mkdtemp is the exact directory created by this fixture, never a user workspace.
    await rm(root, { recursive: true, force: true });
  });
  return { runtime, calls, requests };
}

function command(overrides = {}) {
  return { id: randomUUID(), epoch: 1, contextWindow: 128000, outputTokens: 2048,
    request: 'Use the authorized test tools, then answer.', instructions: 'Synthetic integration test.',
    tools: [
      { name: 'discover', description: 'Discover the echo tool', parameters: { type: 'object', properties: {} } },
      { name: 'platform_echo', description: 'Echo the given text', parameters: {
        type: 'object', properties: { text: { type: 'string' } }, required: ['text'],
      } },
    ], initialTools: ['discover'], ...overrides };
}

test('source-built pi runs native discovery and tools with a gate before every model call', { timeout: 30000 }, async t => {
  const { runtime, calls, requests } = await fixture(t, (_, turn) => turn === 1
    ? { tool: 'discover' } : turn === 2 ? { tool: 'platform_echo', arguments: { text: 'authorized echo' } }
      : { text: 'PI_NATIVE_OK' });
  const input = command();
  await runtime.start(input);
  await runtime.runs.get(input.id).done;
  assert.equal(requests.length, 3);
  assert.equal(calls.filter(call => call.action === 'gate').length, 3);
  assert.deepEqual(calls.filter(call => call.action === 'tool').map(call => call.name), ['discover', 'platform_echo']);
  assert.deepEqual(requests[0].tools.map(tool => tool.function.name), ['discover']);
  assert.ok(requests[1].tools.some(tool => tool.function.name === 'platform_echo'));
  assert.ok(requests[2].messages.some(message => message.role === 'tool' && message.content.includes('authorized echo')));
  assert.equal(calls.find(call => call.action === 'settled')?.text, 'PI_NATIVE_OK');
  assert.ok(calls.some(call => call.action === 'checkpoint' && call.content.includes('authorized echo')));
  assert.equal(runtime.runs.size, 0);
});

test('delivery review repairs a missing citation without repeating saved operations', { timeout: 30000 }, async t => {
  const { runtime, calls, requests } = await fixture(t,
    (_, turn) => ({ text: turn === 1 ? 'Report saved.' : 'Report saved. Source [E:e1].' }),
    { reviewResult: body => body.text ? {
      ready: body.text.includes('[E:e1]'), repair_output: !body.text.includes('[E:e1]'),
      hint: 'Correct only the delivery summary with source [E:e1]; do not save the report again.',
    } : {} });
  const input = command({ tools: [{ name: 'finish_task', description: 'Record task completion',
    parameters: { type: 'object', properties: {} } }], initialTools: ['finish_task'] });
  await runtime.start(input);
  await runtime.runs.get(input.id).done;
  assert.equal(requests.length, 2);
  assert.ok(requests[1].messages.some(message => JSON.stringify(message.content).includes('Correct only the delivery summary')));
  assert.equal(calls.filter(call => call.action === 'tool').length, 0);
  assert.equal(calls.find(call => call.action === 'settled')?.text, 'Report saved. Source [E:e1].');
});

test('an unsuccessful citation repair stays bounded and never invokes saved operations', { timeout: 30000 }, async t => {
  const { runtime, calls, requests } = await fixture(t, () => ({ text: 'Report saved.' }),
    { reviewResult: body => body.text ? { ready: false, repair_output: true, hint: 'Use a real source citation.' } : {} });
  const input = command({ tools: [{ name: 'finish_task', description: 'Record completion',
    parameters: { type: 'object', properties: {} } }], initialTools: ['finish_task'] });
  await runtime.start(input);
  await runtime.runs.get(input.id).done;
  assert.equal(requests.length, 2);
  assert.equal(calls.filter(call => call.action === 'tool').length, 0);
  assert.equal(calls.find(call => call.action === 'settled')?.text, 'Report saved.');
});

test('completion on the last reminder still gets a bounded final citation repair', { timeout: 30000 }, async t => {
  let recorded = 0;
  const { runtime, calls, requests } = await fixture(t,
    (_, turn) => [2, 4].includes(turn) ? { tool: 'finish_task' }
      : { text: turn === 6 ? 'Report saved. Source [E:e1].' : 'Report saved.' },
    { toolResult: () => ({ saved: ++recorded >= 2 }), reviewResult: body => body.text ? {
      ready: recorded >= 2 && body.text.includes('[E:e1]'),
      repair_output: recorded >= 2 && !body.text.includes('[E:e1]'),
      hint: 'Correct only the summary with [E:e1].',
    } : {} });
  const input = command({ tools: [{ name: 'finish_task', description: 'Record completion',
    parameters: { type: 'object', properties: {} } }], initialTools: ['finish_task'] });
  await runtime.start(input);
  await runtime.runs.get(input.id).done;
  assert.equal(requests.length, 6);
  assert.equal(recorded, 2);
  assert.deepEqual(calls.filter(call => call.action === 'tool').map(call => call.name), ['finish_task', 'finish_task']);
  assert.equal(calls.find(call => call.action === 'settled')?.text, 'Report saved. Source [E:e1].');
});

test('source-built pi restores the persisted session and fixed QA cannot load tools', { timeout: 30000 }, async t => {
  const { runtime, calls, requests } = await fixture(t, (_, turn) => ({ text: turn === 1 ? 'FIRST_TURN' : 'RESTORED_OK' }));
  const input = command({ profile: 'fast_qa', tools: [], initialTools: [], request: 'Remember FIRST_TURN.' });
  await runtime.start(input);
  await runtime.runs.get(input.id).done;
  const checkpoint = calls.filter(call => call.action === 'checkpoint').at(-1).content;
  await runtime.start({ ...input, epoch: 2, profile: 'agent', checkpoint, request: 'Continue from the saved turn.' });
  await runtime.runs.get(input.id).done;
  assert.ok(requests[1].messages.some(message => message.role === 'assistant' && message.content.includes('FIRST_TURN')));
  assert.equal(calls.filter(call => call.action === 'settled').at(-1).text, 'RESTORED_OK');
  await assert.rejects(runtime.start(command({ profile: 'fast_qa' })), /FAST_QA_CANNOT_ACTIVATE_TOOLS/);
});

test('research sends intact source text and handles while keeping reader geometry in durable details', { timeout: 30000 }, async t => {
  const source = { kind: 'KB_PUBLICATION', document_id: 'doc', publication_id: 'pub', version_id: 'v1',
    source_node_key: 'node', content_sha256: 'a'.repeat(64), anchor: { page: 3, polygon: [0, 0, 1, 1],
      original: { page: 3, polygon: [0, 0, 1, 1], start: 12, end: 29 } } };
  const result = { candidates: [{ evidence_id: 'e1', text: 'Exact original source.', source, node_offset: 2 }],
    next_offset: 3, trace: { total_nodes: 8, cache_hit: false } };
  const { runtime, calls, requests } = await fixture(t, (_, turn) => turn === 1
    ? { tool: 'document_read' } : { text: 'READ_OK' }, { toolResult: () => result });
  const input = command({ tools: [{ name: 'document_read', description: 'Read authorized evidence',
    parameters: { type: 'object', properties: {} } }], initialTools: ['document_read'] });
  await runtime.start(input);
  await runtime.runs.get(input.id).done;
  const text = requests[1].messages.find(message => message.role === 'tool').content;
  assert.ok(text.includes('Exact original source.') && text.includes('content_sha256'));
  assert.ok(text.includes('"version_id":"v1"') && text.includes('"next_offset":3'));
  assert.ok(text.includes('"start":12') && !text.includes('polygon'));
  const persisted = calls.filter(call => call.action === 'checkpoint').at(-1).content;
  const entries = persisted.split('\n').filter(Boolean).map(line => JSON.parse(line));
  const stored = entries.find(entry => entry.message?.role === 'toolResult').message;
  assert.deepEqual(stored.details, result);
  assert.deepEqual(result.candidates[0].source.anchor.polygon, [0, 0, 1, 1]);
  assert.equal(calls.find(call => call.action === 'settled')?.text, 'READ_OK');
});

test('durable recovery data never replaces the latest task or tool result with a user request', { timeout: 30000 }, async t => {
  const { runtime, calls, requests } = await fixture(t, (_, turn) => turn === 1
    ? { tool: 'discover' } : { text: 'CONTINUED_FROM_LATEST_RESULT' },
  { gateState: () => ({ task_state: { plan: { goal: 'RECOVERY_PLAN' } }, notes: ['UNTRUSTED_NOTE'] }) });
  const input = command();
  await runtime.start(input);
  await runtime.runs.get(input.id).done;
  for (const request of requests.slice(0, 1)) {
    const recovery = request.messages.findIndex(message => message.role === 'user' && JSON.stringify(message.content).includes('RECOVERY_PLAN'));
    const task = request.messages.findIndex(message => message.role === 'user' && JSON.stringify(message.content).includes(input.request));
    assert.ok(recovery >= 0 && recovery <= task);
    if (recovery === task) {
      // The completions adapter may merge adjacent user messages.
      const text = JSON.stringify(request.messages[task].content);
      assert.ok(text.indexOf('RECOVERY_PLAN') < text.indexOf(input.request));
    }
    assert.ok(JSON.stringify(request.messages[recovery].content).includes('UNTRUSTED_NOTE'));
  }
  assert.equal(requests[1].messages.at(-1).role, 'tool');
  assert.ok(requests[1].messages.at(-1).content.includes('activate_tools'));
  assert.ok(requests[1].messages.at(-1).content.includes('RECOVERY_PLAN'));
  assert.ok(requests[1].messages.at(-1).content.includes('UNTRUSTED_NOTE'));
  assert.ok(requests[1].messages.at(-1).content.indexOf('RECOVERY_PLAN')
    < requests[1].messages.at(-1).content.indexOf('activate_tools'));
  const persisted = calls.filter(call => call.action === 'checkpoint').at(-1).content;
  assert.ok(!persisted.includes('RECOVERY_PLAN'));
  assert.equal(calls.find(call => call.action === 'settled')?.text, 'CONTINUED_FROM_LATEST_RESULT');
});

test('Qwen QA and research explicitly honor the runtime thinking-off setting', { timeout: 30000 }, async t => {
  const { runtime, calls, requests } = await fixture(t, () => ({ text: 'SHORT_ANSWER' }),
    { modelId: 'qwen3.7-flash-2026-07-15' });
  const fixed = command({ profile: 'fast_qa', tools: [], initialTools: [] });
  await runtime.start(fixed);
  await runtime.runs.get(fixed.id).done;
  assert.equal(requests[0].enable_thinking, false);
  assert.equal(requests[0].messages[0].role, 'system');
  assert.ok(!requests[0].messages.some(message => message.role === 'developer'));
  assert.equal(requests[0].tools, undefined);
  assert.equal(calls.find(call => call.action === 'settled')?.text, 'SHORT_ANSWER');
  const research = command();
  await runtime.start(research);
  await runtime.runs.get(research.id).done;
  assert.equal(requests[1].enable_thinking, false);
  assert.equal(requests[1].messages[0].role, 'system');
  assert.deepEqual(requests[1].tools.map(tool => tool.function.name), ['discover']);
});

test('configured real model can complete the source-built pi loop with synthetic input', {
  timeout: 180000, skip: process.env.HARNESS_LIVE_MODEL !== '1' && 'Set HARNESS_LIVE_MODEL=1 explicitly',
}, async t => {
  const { runtime, calls } = await fixture(t, () => ({ text: 'unused' }));
  runtime.modelConfig = { id: process.env.JSD_AIRD_MODEL_NAME, baseUrl: process.env.JSD_AIRD_MODEL_BASE_URL,
    apiKey: process.env.JSD_AIRD_MODEL_API_KEY };
  const input = command({ profile: 'fast_qa', tools: [], initialTools: [],
    instructions: 'Return only PI_LIVE_OK. This is a synthetic connectivity check.', request: 'Please run the connectivity check.' });
  await runtime.start(input);
  await runtime.runs.get(input.id).done;
  assert.match(calls.find(call => call.action === 'settled')?.text || '', /PI_LIVE_OK/);
});

test('native text keeps one identity and coalesces deltas without losing text before checkpoints', { timeout: 30000 }, async t => {
  const chunks = Array.from({ length: 40 }, (_, index) => `${index}，`);
  const { runtime, calls } = await fixture(t, () => ({ chunks }));
  const input = command({ profile: 'fast_qa', tools: [], initialTools: [] });
  await runtime.start(input);
  await runtime.runs.get(input.id).done;
  const events = calls.filter(call => call.action === 'event');
  const started = events.find(event => event.type === 'message.started');
  const completed = events.find(event => event.type === 'message.completed');
  const deltas = events.filter(event => event.type === 'message.delta');
  assert.ok(deltas.length < chunks.length);
  assert.equal(deltas.map(event => event.payload.delta).join(''), chunks.join(''));
  assert.equal(completed.payload.text, chunks.join(''));
  assert.ok(deltas.every(event => event.payload.id === started.payload.id));
  assert.equal(completed.payload.id, started.payload.id);
  assert.equal(calls.find(call => call.action === 'settled').text, chunks.join(''));
});

test('a silent review completion preserves the answer already delivered in this run', { timeout: 30000 }, async t => {
  const answer = '请先保留连续的写作时间，邮件集中处理。';
  const { runtime, calls, requests } = await fixture(t, (_, turn) => turn === 1
    ? { text: answer } : turn === 2 ? { tool: 'finish_task', arguments: { text: 'covered' } }
      : { chunks: [] }, { reviewReady: calls => calls.some(call => call.action === 'tool' && call.name === 'finish_task') });
  const input = command({ tools: [{ name: 'finish_task', description: 'Record delivery review',
    parameters: { type: 'object', properties: { text: { type: 'string' } } } }], initialTools: ['finish_task'] });
  await runtime.start(input);
  await runtime.runs.get(input.id).done;
  assert.equal(requests.length, 3);
  assert.equal(calls.filter(call => call.action === 'gate').length, 3);
  assert.ok(calls.filter(call => call.action === 'review').length >= 2);
  assert.equal(calls.filter(call => call.action === 'tool').length, 1);
  assert.equal(calls.find(call => call.action === 'settled')?.text, answer);
  assert.deepEqual(calls.filter(call => call.type === 'message.completed').map(call => call.payload.text), [answer, '', '']);
});

test('steering accepted during the final answer is consumed automatically in native order', { timeout: 30000 }, async t => {
  let pending = [];
  const ids = [randomUUID(), randomUUID()];
  const { runtime, calls, requests } = await fixture(t, (_, turn) => {
    if (turn === 1) pending = ids.map((id, index) => ({ id, seq: index + 1, text: `supplement-${index + 1}` }));
    return { text: `answer-${turn}` };
  }, { steering: () => pending });
  const input = command({ tools: [], initialTools: [] });
  await runtime.start(input);
  await runtime.runs.get(input.id).done;
  const applied = calls.filter(call => call.type === 'message.applied');
  assert.deepEqual(applied.map(call => call.payload.message_id), ids);
  assert.ok(requests[1].messages.some(message => message.role === 'user' && JSON.stringify(message.content).includes('supplement-1')));
  assert.ok(requests.at(-1).messages.some(message => message.role === 'user' && JSON.stringify(message.content).includes('supplement-2')));
  assert.deepEqual(calls.filter(call => call.type === 'message.completed').map(call => call.payload.after_message_seq), [0, 1, 2]);
  assert.equal(calls.filter(call => call.action === 'settled').length, 1);
});
