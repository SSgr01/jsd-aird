import { readFile, stat } from 'node:fs/promises';
import { resolve } from 'node:path';

const dist = resolve('dist');
const html = await readFile(resolve(dist, 'index.html'), 'utf8');
const entry = html.match(/src="\/assets\/([^\"]+\.js)"/);
if (!entry) throw new Error('未找到 Vite 入口脚本');

const entryPath = resolve(dist, 'assets', entry[1]);
const entrySize = (await stat(entryPath)).size;
const maxEntryBytes = 1_200_000;
if (entrySize > maxEntryBytes) {
  throw new Error(`首屏入口包超过 1.2 MB: ${entry[1]} (${Math.round(entrySize / 1024)} KB)`);
}

console.log(`Bundle gate passed: ${entry[1]} (${Math.round(entrySize / 1024)} KB) <= 1200 KB`);
