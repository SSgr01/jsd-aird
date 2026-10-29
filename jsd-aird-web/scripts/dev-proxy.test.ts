// @vitest-environment node
import { createServer as createTcpServer, type Server as TcpServer } from 'node:net';
import { createServer as createHttpServer, Server as HttpServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, describe, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { createDevProxy } from './dev-proxy';

const servers: Array<TcpServer | ViteDevServer> = [];

afterEach(async () => {
  for (const server of servers.reverse()) {
    if ('httpServer' in server) await server.close();
    else await new Promise<void>((resolve) => {
      if (server instanceof HttpServer) server.closeAllConnections();
      server.close(() => resolve());
    });
  }
  servers.length = 0;
});

async function listen(server: TcpServer) {
  servers.push(server);
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  return `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
}

async function startProxy(target: string) {
  const server = await createServer({
    configFile: false,
    customLogger: { info() {}, warn() {}, warnOnce() {}, error() {}, clearScreen() {}, hasErrorLogged: () => false, hasWarned: false },
    server: { host: '127.0.0.1', port: 0, proxy: { '/api': createDevProxy(target) } },
  });
  servers.push(server);
  await server.listen();
  return `http://127.0.0.1:${(server.httpServer!.address() as AddressInfo).port}`;
}

describe('development proxy failure isolation', () => {
  it('survives malformed chunk framing and serves the next response', async () => {
    let count = 0;
    const upstream = createTcpServer((socket) => socket.once('data', () => {
      if (count++ === 0) {
        socket.end('HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n3\r\nabcX\n0\r\n\r\n');
      } else {
        socket.end('HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok');
      }
    }));
    const address = await startProxy(await listen(upstream));
    // If some bytes were already sent, the client sees an interrupted response.
    // Otherwise the proxy can return a complete 502 response.
    try {
      const failed = await fetch(`${address}/api/broken`);
      const text = await failed.text();
      expect(failed.status).toBe(502);
      const body: unknown = JSON.parse(text);
      expect(body).toMatchObject({ code: 'DEV_PROXY_UPSTREAM_ERROR' });
    } catch (error) {
      expect(error).toMatchObject({ name: 'TypeError' });
    }
    const next = await fetch(`${address}/api/healthy`);
    expect(next.status).toBe(200);
    expect(await next.text()).toBe('ok');
  });

  it('returns 502 when the upstream disconnects before sending headers', async () => {
    const upstream = createTcpServer((socket) => socket.once('data', () => socket.destroy()));
    const address = await startProxy(await listen(upstream));
    const response = await fetch(`${address}/api/unavailable`);
    expect(response.status).toBe(502);
    expect(await response.json()).toMatchObject({ code: 'DEV_PROXY_UPSTREAM_ERROR' });
  });

  it('preserves ordinary API status, headers and response body', async () => {
    const upstream = createHttpServer((_request, response) => {
      response.writeHead(409, { 'Content-Type': 'application/json', 'X-Request-Id': 'proxy-check' });
      response.end(JSON.stringify({ message: '版本冲突' }));
    });
    const address = await startProxy(await listen(upstream));
    const response = await fetch(`${address}/api/conflict`);
    expect(response.status).toBe(409);
    expect(response.headers.get('X-Request-Id')).toBe('proxy-check');
    expect(await response.json()).toEqual({ message: '版本冲突' });
  });
});
