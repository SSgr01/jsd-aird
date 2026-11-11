import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { themeTokens } from './src/theme.mjs';
import './prepare-chemistry-renderer.mjs';

const [client, css, sourceLinks, memoryUI, briefingEntry] = await Promise.all([
  readFile(new URL('./src/client.js', import.meta.url), 'utf8'),
  readFile(new URL('./src/theme.css', import.meta.url), 'utf8'),
  readFile(new URL('./src/source-links.mjs', import.meta.url), 'utf8'),
  readFile(new URL('./src/memory-client.mjs', import.meta.url), 'utf8'),
  readFile(new URL('./src/briefing-entry.mjs', import.meta.url), 'utf8'),
]);
const marker = '/* @enterprise-theme */ null';
if (client.split(marker).length !== 2) throw new Error('Expected one enterprise theme insertion point');
await mkdir(new URL('./lib/', import.meta.url), {recursive:true});
await writeFile(new URL('./lib/client.js', import.meta.url),
  client.replace(marker, () => JSON.stringify({tokens:themeTokens, css}))
    .replace('/* @source-links */ null', () => '(()=>{'+sourceLinks.replaceAll('export function ', 'function ')+';return {normalizeOriginalLinks,sourceLinkLabel,shortSourceTitle,projectSourceBlocks};})()')
    .replace('/* @memory-ui */ null', () => '(()=>{'+memoryUI.replace('export function ','function ')+';return {registerMemoryUI};})()')
    .replace('/* @briefing-entry */ null', () => '(()=>{'+briefingEntry.replace('export function ','function ')+';return {readBriefingEntry};})()'));

await import('../../jsd-aird-web/scripts/prepare-chemistry.mjs');
// Package self-hosted chemistry assets alongside the native chat plugin.
const { copyFile } = await import('node:fs/promises');
await mkdir(new URL('./lib/chemistry/', import.meta.url), {recursive:true});
for (const file of ['viewer.html','viewer.js','viewer.css','worker.js','input.js','limits.json','RDKit_minimal.js','RDKit_minimal.wasm','LICENSE']) {
  await copyFile(new URL('../../jsd-aird-web/public/chemistry/'+file, import.meta.url), new URL('./lib/chemistry/'+file, import.meta.url));
}
