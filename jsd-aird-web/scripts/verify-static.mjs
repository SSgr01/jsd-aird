import { gunzipSync } from 'node:zlib';

const base = new URL(process.argv[2] || 'http://47.121.199.247:82');
const paths = process.argv.slice(3);
if (paths.length < 3) throw new Error('Usage: node scripts/verify-static.mjs BASE /assets/entry.js /assets/sheets.js /assets/shared.js');
let failed = false;
for (const path of ['/', '/index.html', '/build-info.json', '/assets/__missing_performance_check__.js', ...paths]) {
  // node:http keeps the wire compressed; fetch automatically decompresses it.
  const { request } = await import(base.protocol === 'https:' ? 'node:https' : 'node:http');
  const start = performance.now();
  const result = await new Promise((resolve, reject) => {
    const req = request(new URL(path, base), { headers: { 'Accept-Encoding': 'gzip' }, timeout: 30_000 }, res => {
      const ttfb = performance.now() - start; const parts = [];
      res.on('data', part => parts.push(part));
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(parts), ttfb, total: performance.now() - start }));
      res.on('error', reject);
    });
    req.on('timeout', () => req.destroy(new Error('Request timed out'))); req.on('error', reject); req.end();
  });
  const encoding = result.headers['content-encoding'];
  const raw = encoding === 'gzip' ? gunzipSync(result.body) : result.body;
  const cache = result.headers['cache-control'] || '';
  let pass;
  if (path.includes('__missing_')) pass = result.status === 404 && !cache.includes('immutable');
  else if (path === '/build-info.json') pass = result.status === 200 && cache === 'no-store' && JSON.parse(raw).component === 'frontend';
  else if (path === '/' || path === '/index.html') pass = result.status === 200 && cache === 'no-cache';
  else pass = result.status === 200 && encoding === 'gzip' && /accept-encoding/i.test(result.headers.vary || '') && result.body.length / raw.length <= 0.4 && cache === 'public, max-age=2592000, immutable' && /javascript/.test(result.headers['content-type'] || '');
  failed ||= !pass;
  console.log(JSON.stringify({ path, pass, status: result.status, encoding: encoding || 'identity', cache, vary: result.headers.vary, wireBytes: result.body.length, rawBytes: raw.length, ratio: +(result.body.length / raw.length).toFixed(3), ttfbMs: Math.round(result.ttfb), totalMs: Math.round(result.total) }));
}
if (failed) process.exitCode = 1;
