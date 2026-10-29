// Disposable, loopback-only fixture for production-UI cache regression.
// No upstream requests, real accounts, database, or persistent business data.
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
import { gzipSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../dist/', import.meta.url));
const port = Number(process.env.FIXTURE_PORT || 4181);
let revision = 1;
let name = '缓存测试：修改前';
let count = 1;
let readStatus = 200;
let authenticated = true;
const requests = [];
const user = {
  userId: 'cache-fixture',
  organizationId: 'isolated-fixture',
  organizationName: '隔离测试',
  username: 'fixture',
  displayName: '缓存回归测试',
  status: 'ACTIVE',
  authVersion: 1,
  permissions: ['project.view', 'project.create', 'project.update', 'project.delete'],
};
const types = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript',
  '.css': 'text/css',
  '.json': 'application/json',
  '.svg': 'image/svg+xml',
  '.woff2': 'font/woff2',
};

async function body(req) {
  let text = '';
  for await (const part of req) {
    text += part;
    if (text.length > 8192) throw new Error('fixture payload too large');
  }
  return text ? JSON.parse(text) : {};
}
const json = (res, status, value) => {
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  });
  res.end(JSON.stringify(value));
};
const row = (index = 0) => ({
  id: `fixture-${index + 1}`,
  projectCode: `CACHE-${index + 1}`,
  name: index ? `${name} ${index + 1}` : name,
  partnerName: '隔离测试数据',
  owner: '测试员',
  priority: 'MEDIUM',
  status: 'NOT_STARTED',
  teamSize: 1,
  teamMembers: ['测试员'],
  version: revision,
  allowedActions: ['update', 'delete'],
});

createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://127.0.0.1');
    if (url.pathname === '/__fixture/control' && req.method === 'POST') {
      const next = await body(req);
      if (typeof next.name === 'string') {
        name = next.name;
        revision++;
      }
      if (Number.isSafeInteger(next.count) && next.count >= 0 && next.count <= 50)
        count = next.count;
      if ([200, 401, 403, 429, 500, 503].includes(next.readStatus)) readStatus = next.readStatus;
      if (typeof next.authenticated === 'boolean') authenticated = next.authenticated;
      json(res, 200, { revision, count, readStatus, authenticated });
      return;
    }
    if (url.pathname === '/__fixture/requests') {
      json(res, 200, requests);
      return;
    }
    if (url.pathname.startsWith('/api/')) {
      requests.push({ method: req.method, path: url.pathname, at: new Date().toISOString() });
      if (requests.length > 200) requests.shift();
      if (url.pathname.endsWith('/auth/csrf')) {
        json(res, 200, { data: { token: 'disposable-fixture-token' } });
        return;
      }
      if (!authenticated) {
        json(res, 401, { code: 'AUTH_REQUIRED', message: '隔离测试：会话失效' });
        return;
      }
      if (url.pathname.endsWith('/auth/me')) {
        json(res, 200, { data: user });
        return;
      }
      if (url.pathname === '/api/v1/projects' && req.method === 'POST') {
        const input = await body(req);
        name = String(input.name || '新建缓存测试');
        revision++;
        count++;
        json(res, 200, { data: { id: 'fixture-1', version: revision } });
        return;
      }
      if (url.pathname === '/api/v1/projects' && req.method === 'GET') {
        if (readStatus !== 200) {
          json(res, readStatus, { code: 'FIXTURE_ERROR', message: `隔离测试：${readStatus}` });
          return;
        }
        const page = Math.max(1, Number(url.searchParams.get('page') || 1));
        const size = Math.max(1, Number(url.searchParams.get('size') || 10));
        const keyword = url.searchParams.get('keyword');
        const all = Array.from({ length: count }, (_, index) => row(index)).filter(
          (item) => !keyword || item.name.includes(keyword),
        );
        json(res, 200, {
          data: {
            items: all.slice((page - 1) * size, page * size),
            page,
            size,
            total: all.length,
            totalPages: Math.ceil(all.length / size),
          },
        });
        return;
      }
      if (
        req.method === 'GET' &&
        ['/api/v1/tasks', '/api/v1/experiments', '/api/v1/inventory/balances'].includes(
          url.pathname,
        )
      ) {
        json(res, 200, {
          data: {
            items: [],
            page: 1,
            size: 6,
            total: 0,
            totalPages: 0,
            ...(url.pathname.includes('/inventory/')
              ? { summary: { totalKg: 0, productCount: 0, lowStockCount: 0, outOfStockCount: 0 } }
              : {}),
          },
        });
        return;
      }
      json(res, 404, {
        code: 'FIXTURE_ROUTE_NOT_IMPLEMENTED',
        message: '隔离接口未实现此测试操作',
      });
      return;
    }
    const path = decodeURIComponent(url.pathname);
    const file = resolve(root, '.' + (path === '/' ? '/index.html' : path));
    if (!file.startsWith(resolve(root) + sep)) {
      json(res, 403, {});
      return;
    }
    let actual = file;
    let content;
    try {
      content = await readFile(file);
    } catch {
      if (extname(path) || path.startsWith('/assets/')) {
        res.writeHead(404);
        res.end('not found');
        return;
      }
      actual = resolve(root, 'index.html');
      content = await readFile(actual);
    }
    const headers = {
      'Content-Type': types[extname(actual)] || 'application/octet-stream',
      'Cache-Control': path.startsWith('/assets/')
        ? 'public, max-age=2592000, immutable'
        : path === '/build-info.json'
          ? 'no-store'
          : 'no-cache',
    };
    if (content.length > 1024 && (req.headers['accept-encoding'] || '').includes('gzip')) {
      content = gzipSync(content, { level: 5 });
      headers['Content-Encoding'] = 'gzip';
      headers.Vary = 'Accept-Encoding';
    }
    res.writeHead(200, headers);
    res.end(content);
  } catch {
    json(res, 500, { message: 'fixture request failed' });
  }
}).listen(port, '127.0.0.1', () =>
  console.log(`Isolated cache fixture: http://127.0.0.1:${port}/projects/list`),
);
