import { createServer } from 'node:http';
import { Runtime } from './session.mjs';
import { authenticated } from './policy.mjs';

const secret = process.env.JSD_AIRD_HARNESS_RUNTIME_SECRET || '';
if (secret.length < 32) throw new Error('JSD_AIRD_HARNESS_RUNTIME_SECRET must contain at least 32 characters');
const runtime = new Runtime({
  root: process.env.JSD_AIRD_PI_SESSION_ROOT || '.runtime/pi-sessions',
  controlUrl: process.env.JSD_AIRD_HARNESS_CONTROL_URL || 'http://127.0.0.1:8093', secret,
  model: { id: process.env.JSD_AIRD_MODEL_NAME, baseUrl: process.env.JSD_AIRD_MODEL_BASE_URL, apiKey: process.env.JSD_AIRD_MODEL_API_KEY },
});

const server = createServer(async (request, response) => {
  response.setHeader('Content-Type', 'application/json');
  if (!authenticated(request.headers.authorization, secret)) {
    response.writeHead(401).end(JSON.stringify({ error: 'UNAUTHORIZED' })); return;
  }
  try {
    const url = new URL(request.url, 'http://runtime.local');
    if (request.method === 'GET' && url.pathname === '/health') {
      response.end(JSON.stringify({ engine: 'pi-v1', activeRuns: runtime.runs.size,
        modelConfigured: Boolean(runtime.modelConfig.id && runtime.modelConfig.baseUrl && runtime.modelConfig.apiKey) })); return;
    }
    const match = /^\/runs\/([0-9a-f-]{36})\/(start|abort|compact)$/.exec(url.pathname);
    if (request.method !== 'POST' || !match) { response.writeHead(404).end('{}'); return; }
    let bytes = 0;
    const chunks = [];
    for await (const chunk of request) {
      bytes += chunk.length;
      if (bytes > 20 * 1024 * 1024) throw new Error('REQUEST_TOO_LARGE');
      chunks.push(chunk);
    }
    const command = JSON.parse(Buffer.concat(chunks).toString() || '{}');
    if (match[2] === 'start') {
      if (command.id !== match[1]) throw new Error('RUN_SCOPE_MISMATCH');
      await runtime.start(command);
    } else await runtime[match[2]](match[1]);
    response.writeHead(202).end(JSON.stringify({ accepted: true }));
  } catch (error) {
    response.writeHead(409).end(JSON.stringify({ error: String(error.message).slice(0, 200) }));
  }
});
server.listen(Number(process.env.JSD_AIRD_PI_PORT || 8092), process.env.JSD_AIRD_PI_HOST || '127.0.0.1');
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => {
  server.close();
  void Promise.all([...runtime.runs.keys()].map(id => runtime.abort(id))).then(() => process.exit(0));
});
