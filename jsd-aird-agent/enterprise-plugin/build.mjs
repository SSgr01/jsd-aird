import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { themeTokens } from './src/theme.mjs';

const [client, css] = await Promise.all([
  readFile(new URL('./src/client.js', import.meta.url), 'utf8'),
  readFile(new URL('./src/theme.css', import.meta.url), 'utf8'),
]);
const marker = '/* @enterprise-theme */ null';
if (client.split(marker).length !== 2) throw new Error('Expected one enterprise theme insertion point');
await mkdir(new URL('./lib/', import.meta.url), {recursive:true});
await writeFile(new URL('./lib/client.js', import.meta.url),
  client.replace(marker, () => JSON.stringify({tokens:themeTokens, css})));
