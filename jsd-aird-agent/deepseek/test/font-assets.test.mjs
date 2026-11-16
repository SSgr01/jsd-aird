import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { serveFontAsset } from '../../enterprise-plugin/src/font-assets.mjs';

test('packaged workbench font serves the same CSS and subsets as the business UI', async t => {
  const server = createServer(serveFontAsset);
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = `http://127.0.0.1:${server.address().port}/font-assets/`;
  const source = new URL('../../../jsd-aird-web/node_modules/@fontsource-variable/noto-sans-sc/', import.meta.url);
  const response = await fetch(base + 'index.css');
  assert.equal(response.status, 200);
  assert.match(response.headers.get('content-type'), /^text\/css/);
  const css = await response.text();
  assert.equal(css, await readFile(new URL('index.css', source), 'utf8'));
  const subsets = [...css.matchAll(/url\(\.\/(files\/[a-z0-9-]+\.woff2)\)/g)];
  assert.ok(subsets.length > 1, 'Unicode subsets must remain available');
  for (const [, file] of subsets) {
    const font = await fetch(base + file);
    assert.equal(font.status, 200, file);
    assert.equal(font.headers.get('content-type'), 'font/woff2');
    assert.deepEqual(Buffer.from(await font.arrayBuffer()), await readFile(new URL(file, source)), file);
  }
  const license = await fetch(base + 'LICENSE');
  assert.equal(await license.text(), await readFile(new URL('LICENSE', source), 'utf8'));
  const head = await fetch(base + 'index.css', {method:'HEAD'});
  assert.equal(head.status, 200);
  assert.equal(Number(head.headers.get('content-length')), Buffer.byteLength(css));
  assert.equal(await head.text(), '');
  for (const file of ['../package.json', '%2e%2e%2fpackage.json', 'files/unknown.woff2', 'index.css/extra', 'files/noto-sans-sc-latin-wght-normal.woff2%00']) {
    assert.equal((await fetch(base + file)).status, 404, file);
  }
  const post = await fetch(base + 'index.css', {method:'POST'});
  assert.equal(post.status, 405);
  assert.equal(post.headers.get('allow'), 'GET, HEAD');
});
