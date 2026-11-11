import { BackgroundHosts } from './background-hosts.mjs';
import { createServer, request as httpRequest } from 'node:http';
import { configuration, engine, revision } from './config.mjs';
import { Hosts } from './hosts.mjs';
import { credentialMatches, json, readJson, requireSameOrigin, sameOwner } from './security.mjs';
import { checkRpc, checkStream, checkUpload, nativeFileSession } from './rpc-policy.mjs';
import { pluginRoute, checkPluginRequest } from './plugin-policy.mjs';
import { browserMetadata, browserJsonResponse } from './path-view.mjs';
import { staticBrandingTarget, brandStaticResponse } from './branding.mjs';
import { requestError } from './request-error.mjs';
import { browserRedirect } from './redirects.mjs';
import { WebSocket, WebSocketServer } from 'ws';

export async function identity(config, headers) {
  const result = await fetch(config.javaUrl + '/internal/v1/agent-identity', {
    method: 'POST', headers: { authorization: 'Bearer ' + config.secret, ...headers }, signal: AbortSignal.timeout(10000),
  });
  if (!result.ok) throw Object.assign(new Error(result.status === 401 ? 'ENTERPRISE_AUTH_REQUIRED'
    : result.status === 403 ? 'ENTERPRISE_ACCESS_DENIED' : 'ENTERPRISE_IDENTITY_UNAVAILABLE'),
  { status: [401, 403].includes(result.status) ? result.status : 503 });
  return result.json();
}
export function gateway(config) {
  const hosts = new Hosts(config);
  const background = new BackgroundHosts(config,hosts);
  const sockets = new WebSocketServer({noServer:true, perMessageDeflate:false, maxPayload:1024*1024});
  async function browserHost(req) {
    requireSameOrigin(req, config.origin);
    if (!req.headers.cookie) throw Object.assign(new Error('ENTERPRISE_AUTH_REQUIRED'), { status: 401 });
    const user = await identity(config, { cookie: req.headers.cookie });
    const host=await hosts.ensure(user);
    await background.install(host).catch(error => {
      // An unavailable background exchange must not lock users out of the
      // interactive workbench. Installation remains durable and is retried.
      process.stderr.write('Built-in task installation pending ('+(error.status || 'connection')+')\n');
    });
    return host;
  }
  const server = createServer(async (req, res) => {
    try {
      const url = new URL(req.url, config.origin);
      if (url.pathname === '/health') {
        if (!credentialMatches(req.headers.authorization, config.secret)) return json(res, 401, { error: 'AUTH_REQUIRED' });
        return json(res, 200, { engine, revision, available: true });
      }
      if (url.pathname.startsWith('/internal/')) {
        if (!credentialMatches(req.headers.authorization, config.secret)) return json(res, 401, { error: 'AUTH_REQUIRED' });
        const body = await readJson(req);
        const user = await identity(config, { 'x-jsd-agent-ticket': body.ticket || '' });
        const host = await hosts.ensure(user);
        const action = url.pathname.slice('/internal/'.length);
        if (!['create', 'prompt', 'cancel', 'queue', 'resume', 'wake', 'state', 'review', 'models', 'memory-history'].includes(action)) return json(res, 404, { error: 'NOT_FOUND' });
        return json(res, 200, { ...await hosts.call(host, action, { ...body, identity: undefined, ticket: user.ticket }), workspace_url: host.path });
      }
      if (!url.pathname.startsWith('/agent/')) return json(res, 404, { error: 'NOT_FOUND' });
      const host = await browserHost(req);
      if (['/agent/qa/', '/agent/research/'].includes(url.pathname)) {
        res.writeHead(302, { location: '/agent/' }); res.end(); return;
      }
      if (/^\/agent\/(qa|research)\//.test(url.pathname)) return json(res, 404, { error: 'NOT_FOUND' });
      if (!url.pathname.startsWith(host.path)) return json(res, 404, { error: 'NOT_FOUND' });
      const target = '/' + url.pathname.slice(host.path.length) + url.search;
      if (target.startsWith('/connect') || target.startsWith('/enterprise') || url.searchParams.has('token')) {
        return json(res, 403, { error: 'INTERNAL_ENDPOINT' });
      }
      // The host plugin also checks RPC dispatch; sensitive host remotes are not composed.
      const headers = { ...req.headers, cookie: host.cookie, host: new URL(config.origin).host };
      delete headers.authorization; delete headers['x-jsd-agent-ticket'];
      if (staticBrandingTarget(target)) {
        headers['accept-encoding'] = 'identity';
        delete headers['if-none-match']; delete headers['if-modified-since'];
      }
      let rpcBody;
      const targetPath = target.split('?', 1)[0];
      if (targetPath.startsWith('/api/')) headers['accept-encoding'] = 'identity';
      if (pluginRoute(targetPath)) {
        const body = req.method === 'POST' ? await readJson(req) : undefined;
        const sessionId = checkPluginRequest(targetPath, req.method, body);
        if (sessionId) await hosts.call(host, 'authorize', {session_id:sessionId});
        if (body !== undefined) {
          rpcBody = JSON.stringify(body);
          delete headers['transfer-encoding']; headers['content-length'] = Buffer.byteLength(rpcBody);
        }
      } else if (url.pathname === host.path + 'api/session/uploadFileBinary' && req.method === 'POST') {
        checkUpload(url, req.headers);
      } else if (target.startsWith('/api/') && req.method === 'POST') {
        const endpoint = decodeURIComponent(url.pathname.slice(host.path.length + 4));
        const envelope = await readJson(req);
        await checkRpc(endpoint, envelope, host);
        const sessionId = nativeFileSession(endpoint, envelope);
        if(sessionId) {
          await hosts.call(host,'authorize',{session_id:sessionId});
        }
        rpcBody = JSON.stringify(envelope);
        delete headers['transfer-encoding']; headers['content-length'] = Buffer.byteLength(rpcBody);
      }
      const upstream = httpRequest({ hostname: '127.0.0.1', port: host.port, path: target, method: req.method, headers }, reply => {
        const forwarded = { ...reply.headers, 'cache-control': 'no-store', 'referrer-policy': 'same-origin' };
        delete forwarded['set-cookie'];
        if (forwarded.location) {
          try { forwarded.location = browserRedirect(forwarded.location, {origin:config.origin,prefix:host.path,target}); }
          catch { reply.destroy(); return json(res, 502, { error: 'INVALID_UPSTREAM_REDIRECT' }); }
        }
        void (async () => {
          try {
            const branded = targetPath.startsWith('/api/')
              ? await browserJsonResponse(reply, forwarded, host.executionRoot)
              : req.method === 'GET' ? await brandStaticResponse(target, reply, forwarded) : undefined;
            res.writeHead(reply.statusCode, forwarded);
            if (branded !== undefined) res.end(branded); else reply.pipe(res);
          } catch {
            reply.destroy();
            if (!res.headersSent) json(res, 502, {error:'WORKBENCH_RESOURCE_UNAVAILABLE'}); else res.destroy();
          }
        })();
        const check = setInterval(async () => {
          try { const current = await identity(config, { cookie: req.headers.cookie }); if (!sameOwner(current, host.identity)) throw new Error(); }
          catch { reply.destroy(); res.destroy(); }
        }, 10000); check.unref(); reply.once('close', () => clearInterval(check));
      });
      upstream.on('error', () => { if (!res.headersSent) json(res, 503, { error: 'DEEPSEEK_UNAVAILABLE' }); else res.destroy(); });
      req.on('aborted', () => upstream.destroy());
      if (rpcBody !== undefined) upstream.end(rpcBody); else req.pipe(upstream);
    } catch (error) { requestError(req, res, error, config.origin); }
  });
  server.on('upgrade', async (req, socket, head) => {
    try {
      const host = await browserHost(req);
      const url = new URL(req.url, config.origin);
      if (!url.pathname.startsWith(host.path) || url.searchParams.has('token')) throw new Error();
      const target = '/' + url.pathname.slice(host.path.length) + url.search;
      if (target !== '/api/remote.mux') throw new Error();
      const upstream = new WebSocket(`ws://127.0.0.1:${host.port}${target}`, {perMessageDeflate:false, maxPayload:8*1024*1024,
        headers:{cookie:host.cookie,host:new URL(config.origin).host,origin:config.origin}});
      upstream.once('open', () => sockets.handleUpgrade(req, socket, head, connection => {
        connection.on('message', (data, binary) => {
          try { if (binary) throw new Error(); checkStream(JSON.parse(data.toString())); upstream.send(data.toString()); }
          catch { connection.close(1008,'ENTERPRISE_STREAM_REFUSED'); }
        });
        upstream.on('message', (data, binary) => {
          if (connection.readyState !== WebSocket.OPEN) return;
          if (binary) { connection.send(data,{binary}); return; }
          try { connection.send(JSON.stringify(browserMetadata(JSON.parse(data.toString()),host.executionRoot))); }
          catch { connection.close(1011,'WORKSPACE_METADATA_UNAVAILABLE'); }
        });
        upstream.on('close',()=>connection.close());
        const check = setInterval(async () => {
          try { if (!sameOwner(await identity(config, { cookie: req.headers.cookie }), host.identity)) throw new Error(); }
          catch { connection.close(1008,'ENTERPRISE_AUTH_REQUIRED'); upstream.close(); }
        }, 10000); check.unref(); socket.once('close', () => { clearInterval(check); connection.terminate(); });
        connection.once('close',()=>{clearInterval(check);upstream.close();});
      }));
      upstream.once('error', () => socket.destroy());
    } catch { socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n'); }
  });
  server.once('listening',()=>background.start());
  server.once('close', () => { background.close(); void hosts.close(); });
  return server;
}
if (import.meta.url === new URL(process.argv[1], 'file:').href || process.argv[1]?.endsWith('server.mjs')) {
  const config = configuration();
  if (!['127.0.0.1', '::1'].includes(config.host)) throw new Error('Gateway must bind a loopback address');
  const server = gateway(config);
  server.listen(config.port, config.host, () => process.stdout.write(`JSD DeepSeek gateway listening on ${config.host}:${config.port}\n`));
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close());
}
