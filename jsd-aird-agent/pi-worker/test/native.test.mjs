import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import test from 'node:test';
import { Runtime } from '../src/session.mjs';

async function fixture(t, modelReply) {
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
      } else { send({ content: reply.text || 'done' }); send({}, 'stop'); }
      response.end('data: [DONE]\n\n');
      return;
    }
    const action = request.url.split('/').at(-1);
    calls.push({ action, ...body });
    let result = {};
    if (action === 'gate') result = { stop: false, messages: [], notes: [] };
    if (action === 'tool') result = body.name === 'discover'
      ? { activate_tools: ['platform_echo'] } : { echoed: body.arguments.text };
    if (action === 'review') result = { ready: true };
    response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(result));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}`;
  const runtime = new Runtime({ root, controlUrl: url, secret: 'r'.repeat(32),
    model: { id: 'synthetic', baseUrl: url + '/v1', apiKey: 'synthetic-key' } });
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
