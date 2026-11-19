import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { themeTokens } from './src/theme.mjs';
import './prepare-chemistry-renderer.mjs';

const [client, css, sourceLinks, memoryUI, briefingEntry, expertsUI, spectrumUI, workbenchTasks, browserCrypto] = await Promise.all([
  readFile(new URL('./src/client.js', import.meta.url), 'utf8'),
  readFile(new URL('./src/theme.css', import.meta.url), 'utf8'),
  readFile(new URL('./src/source-links.mjs', import.meta.url), 'utf8'),
  readFile(new URL('./src/memory-client.mjs', import.meta.url), 'utf8'),
  readFile(new URL('./src/briefing-entry.mjs', import.meta.url), 'utf8'),
  readFile(new URL('./src/experts-client.mjs', import.meta.url), 'utf8'),
  readFile(new URL('./src/spectrum-client.mjs', import.meta.url), 'utf8'),
  readFile(new URL('./src/workbench-tasks.mjs', import.meta.url), 'utf8'),
  readFile(new URL('./src/browser-crypto.mjs', import.meta.url), 'utf8'),
]);
const marker = '/* @enterprise-theme */ null';
if (client.split(marker).length !== 2) throw new Error('Expected one enterprise theme insertion point');
await mkdir(new URL('./lib/', import.meta.url), {recursive:true});
await writeFile(new URL('./lib/client.js', import.meta.url),
  client.replace(marker, () => JSON.stringify({tokens:themeTokens, css}))
    .replace('/* @source-links */ null', () => '(()=>{'+sourceLinks.replaceAll('export function ', 'function ')+';return {normalizeOriginalLinks,sourceLinkLabel,shortSourceTitle,projectSourceBlocks};})()')
    .replace('/* @browser-crypto */ null', () => '(()=>{'+browserCrypto.replaceAll('export function ','function ')+';return {sha256Hex,generateBrowserUUID};})()')
    .replace('/* @memory-ui */ null', () => '(()=>{'+memoryUI.replace("import {generateBrowserUUID} from './browser-crypto.mjs';", 'const {generateBrowserUUID}=browserCrypto;').replace('export function ','function ')+';return {registerMemoryUI};})()')
    .replace('/* @briefing-entry */ null', () => '(()=>{'+briefingEntry.replace('export function ','function ')+';return {readBriefingEntry};})()')
    .replace('/* @experts-ui */ null', () => '(()=>{'+expertsUI.replace('export function ','function ')+';return {registerExpertsUI};})()')
    .replace('/* @workbench-tasks */ null', () => '(()=>{'+workbenchTasks.replace('export const ','const ').replaceAll('export function ','function ')+';return {workbenchTasks,startWorkbenchTask};})()')
    .replace('/* @spectrum-ui */ null', () => '(()=>{'+spectrumUI.replace("import {sha256Hex} from './browser-crypto.mjs';", 'const {sha256Hex}=browserCrypto;').replaceAll('export function ','function ')+';return {registerSpectrumUI,SpectrumResourceBody};})()'));

await import('../../jsd-aird-web/scripts/prepare-chemistry.mjs');
const { copyFile } = await import('node:fs/promises');
// Use the exact Fontsource package/version imported by the business UI. Preserve
// Unicode subsets and relative URLs so browsers fetch only the glyphs they need.
const fontSource = new URL('../../jsd-aird-web/node_modules/@fontsource-variable/noto-sans-sc/', import.meta.url);
const fontTarget = new URL('./lib/fonts/', import.meta.url);
const fontCss = await readFile(new URL('index.css', fontSource), 'utf8');
await mkdir(new URL('files/', fontTarget), {recursive:true});
await writeFile(new URL('index.css', fontTarget), fontCss);
await copyFile(new URL('LICENSE', fontSource), new URL('LICENSE', fontTarget));
for (const [, file] of fontCss.matchAll(/url\(\.\/(files\/[a-z0-9-]+\.woff2)\)/g)) {
  await copyFile(new URL(file, fontSource), new URL(file, fontTarget));
}
// Package self-hosted chemistry assets alongside the native chat plugin.
await mkdir(new URL('./lib/chemistry/', import.meta.url), {recursive:true});
for (const file of ['viewer.html','viewer.js','viewer.css','worker.js','input.js','limits.json','RDKit_minimal.js','RDKit_minimal.wasm','LICENSE']) {
  await copyFile(new URL('../../jsd-aird-web/public/chemistry/'+file, import.meta.url), new URL('./lib/chemistry/'+file, import.meta.url));
}
