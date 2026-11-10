import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer, request as httpRequest } from 'node:http';
import { gateway } from '../src/server.mjs';
import { Hosts } from '../src/hosts.mjs';
import { BackgroundHosts } from '../src/background-hosts.mjs';

async function listen(t, server) {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  return `http://127.0.0.1:${server.address().port}`;
}

function request(origin, path, headers = {}, method = 'GET') {
  return new Promise((resolve, reject) => {
    const req = httpRequest(new URL(path, origin), { method, headers }, res => {
      let body = '';
      res.setEncoding('utf8');
      res.on('data', chunk => { body += chunk; });
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body }));
    });
    req.on('error', reject); req.end();
  });
}

test('workbench entry redirects expired browser authentication without redirecting RPCs or service errors', async t => {
  t.mock.method(BackgroundHosts.prototype, 'start', () => {});
  const logs = [];
  t.mock.method(process.stderr, 'write', message => { logs.push(message); return true; });
  const javaCalls = [];
  let identityStatus = 401;
  const javaUrl = await listen(t, createServer((req, res) => {
    javaCalls.push({ path: req.url, cookie: req.headers.cookie });
    res.writeHead(identityStatus, { 'content-type': 'application/json' });
    res.end('{}');
  }));
  const origin = 'http://workbench.invalid';
  const address = await listen(t, gateway({ origin, javaUrl, secret: 'test-runtime-secret'.repeat(3) }));

  const entry = await request(address, '/agent/?session=stopped', { accept: 'text/html' });
  assert.equal(entry.status, 303);
  assert.equal(entry.headers.location, '/login?returnTo=%2Fagent%2F%3Fsession%3Dstopped');
  assert.equal(entry.headers['cache-control'], 'no-store');
  assert.equal(entry.body, '');
  assert.equal(javaCalls.length, 0);

  const expired = await request(address, '/agent/', { 'sec-fetch-dest': 'document', cookie: 'SESSION=expired-test' });
  assert.equal(expired.status, 303);
  assert.equal(javaCalls[0].cookie, 'SESSION=expired-test');

  const head = await request(address, '/agent/', { accept: 'text/html' }, 'HEAD');
  assert.equal(head.status, 303);
  assert.equal(head.body, '');

  for (const [path, accept] of [['/agent/', 'application/json'], ['/agent/api/session/list', 'text/html'], ['/agent/assets/app.js', 'text/html']]) {
    const result = await request(address, path, { accept });
    assert.equal(result.status, 401);
    assert.equal(result.headers.location, undefined);
    assert.deepEqual(JSON.parse(result.body), { error: 'ENTERPRISE_AUTH_REQUIRED' });
  }

  const crossSite = await request(address, '/agent/', { accept: 'text/html', 'sec-fetch-site': 'cross-site' });
  assert.equal(crossSite.status, 403);
  assert.equal(crossSite.headers.location, undefined);

  identityStatus = 403;
  const denied = await request(address, '/agent/', { accept: 'text/html', cookie: 'SESSION=denied-test' });
  assert.equal(denied.status, 403);
  assert.equal(denied.headers.location, undefined);
  assert.deepEqual(JSON.parse(denied.body), { error: 'ENTERPRISE_ACCESS_DENIED' });

  for (const status of [404, 500, 503]) {
    identityStatus = status;
    const unavailable = await request(address, '/agent/', { accept: 'text/html', cookie: 'SESSION=valid-test' });
    assert.equal(unavailable.status, 503);
    assert.equal(unavailable.headers.location, undefined);
    assert.deepEqual(JSON.parse(unavailable.body), { error: 'DEEPSEEK_UNAVAILABLE' });
  }
  assert.ok(logs.some(line => JSON.parse(line).reason === 'ORIGIN_DENIED'));
  assert.ok(logs.some(line => JSON.parse(line).reason === 'ENTERPRISE_ACCESS_DENIED'));
  assert.ok(logs.every(line => !/SESSION=|stopped|test-runtime-secret/.test(line)));
});

test('authenticated entry continues to the native host with its own cookie', async t => {
  t.mock.method(BackgroundHosts.prototype, 'start', () => {});
  t.mock.method(BackgroundHosts.prototype, 'install', async () => {});
  const nativeCalls = [];
  const nativeUrl = await listen(t, createServer((req, res) => {
    nativeCalls.push({ path: req.url, cookie: req.headers.cookie });
    res.writeHead(200, { 'content-type': 'text/html', 'set-cookie': 'native-private=hidden' });
    res.end('<!doctype html><title>Test workbench</title>');
  }));
  const user = { organization_id: 'organization', actor_id: 'user', auth_version: 1 };
  const javaUrl = await listen(t, createServer((_req, res) => {
    res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify(user));
  }));
  let admitted;
  t.mock.method(Hosts.prototype, 'ensure', async identity => {
    admitted = identity;
    return { path: '/agent/', port: Number(new URL(nativeUrl).port), cookie: 'NATIVE=private', identity };
  });
  const address = await listen(t, gateway({ origin: 'http://workbench.invalid', javaUrl, secret: 'test-runtime-secret'.repeat(3) }));
  const result = await request(address, '/agent/?session=existing', { accept: 'text/html', cookie: 'SESSION=valid-test' });
  assert.equal(result.status, 200);
  assert.deepEqual(admitted, user);
  assert.equal(nativeCalls[0].path, '/?session=existing');
  assert.equal(nativeCalls[0].cookie, 'NATIVE=private');
  assert.equal(result.headers.location, undefined);
  assert.equal(result.headers['set-cookie'], undefined);
});
