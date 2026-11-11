import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import { JSDOM } from 'jsdom';
import { chemistryList } from '../src/components/markdown/chemistry-input.ts';

const html = await readFile(new URL('../public/chemistry/viewer.html', import.meta.url), 'utf8');
const script = await readFile(new URL('../public/chemistry/viewer.js', import.meta.url), 'utf8');
const defaults = JSON.parse(await readFile(new URL('../public/chemistry/limits.json', import.meta.url), 'utf8'));

async function viewer(t, source, overrides = {}, parameters = {}) {
  const dom = new JSDOM(html, { runScripts: 'outside-only', url: 'http://localhost/chemistry/viewer.html#' + new URLSearchParams({ source, ...parameters }) });
  const { window } = dom; const workers = [], timers = new Map(); let nextTimer = 0, now = 0, nextUrl = 0;
  window.ResizeObserver = class { observe() {} };
  window.chemistryList = chemistryList;
  window.postMessage = () => {};
  window.fetch = async () => ({ ok: true, json: async () => ({ ...defaults, ...overrides }) });
  window.URL.createObjectURL = () => 'blob:structure-' + ++nextUrl;
  window.URL.revokeObjectURL = () => {};
  window.setTimeout = (callback, delay) => { timers.set(++nextTimer, { callback, delay }); return nextTimer; };
  window.clearTimeout = id => timers.delete(id);
  Object.defineProperty(window.performance, 'now', { value: () => now });
  window.Worker = class {
    messages = []; terminated = false;
    constructor() { workers.push(this); }
    postMessage(message) { this.messages.push(message); }
    terminate() { this.terminated = true; }
  };
  vm.runInContext(script, dom.getInternalVMContext());
  await new Promise(resolve => setImmediate(resolve));
  t.after(() => { window.dispatchEvent(new window.Event('pagehide')); window.close(); });
  return { document: window.document, workers, timers, elapse: value => { now = value; } };
}
function ready(worker) { worker.onmessage({ data: { type: 'ready' } }); }
function complete(worker) {
  const request = worker.messages.at(-1);
  worker.onmessage({ data: { type: 'result', id: request.id, item: {
    ...request.item, valid: true, canonical: 'CCO', width: 100, height: 50,
    svg: '<svg xmlns="http://www.w3.org/2000/svg" width="100" height="50"></svg>',
  } } });
}

test('one structure timeout preserves completed images, continues the list and retries only that card', async t => {
  const { document, workers, timers } = await viewer(t, 'A: CCO\nBDB: OCC\nESBO: CC');
  ready(workers[0]); complete(workers[0]);
  const original = document.querySelector('.drawing img').src;
  const timeout = [...timers.values()].find(timer => timer.delay === defaults.itemTimeoutMs);
  assert.ok(timeout); timers.clear(); timeout.callback();
  assert.equal(workers[0].terminated, true);
  assert.equal(workers.length, 2);
  assert.match(document.querySelectorAll('.card')[1].textContent, /超时/);
  ready(workers[1]); complete(workers[1]);
  assert.equal(document.querySelectorAll('.drawing img').length, 2);
  const retry = document.querySelectorAll('.card')[1].querySelector('.drawing button');
  retry.click(); retry.click(); // A stale double click must not enqueue a second draw.
  complete(workers[1]);
  assert.equal(workers.length, 2);
  assert.equal(document.querySelectorAll('.drawing img').length, 3);
  assert.equal(document.querySelector('.drawing img').src, original);
  assert.equal(document.getElementById('status').hidden, true);
});

test('resource limits are loaded from configuration and long SMILES reach the renderer', async t => {
  const accepted = await viewer(t, 'C'.repeat(5000));
  assert.equal(accepted.workers.length, 1);
  ready(accepted.workers[0]);
  assert.equal(accepted.workers[0].messages.at(-1).item.source.length, 5000);
  const rejected = await viewer(t, 'C'.repeat(5000), { maxSourceChars: 4096 });
  assert.equal(rejected.workers.length, 0);
  assert.match(rejected.document.getElementById('status').textContent, /4096/);
});

test('the group time budget stops new work and leaves individual retry controls', async t => {
  const instance = await viewer(t, 'A: CCO\nBDB: CC', { groupTimeoutMs: 100 });
  ready(instance.workers[0]); instance.elapse(101); complete(instance.workers[0]);
  assert.equal(instance.document.querySelectorAll('.drawing img').length, 1);
  assert.match(instance.document.querySelectorAll('.card')[1].textContent, /时间预算/);
  assert.ok(instance.document.querySelector('.drawing button'));
});

test('raw SMILES preserve aromatic and mapped colons, while labels retain brackets and full names', async t => {
  for (const source of ['c1ccccc1:c2ccccc2', '[CH3:1][OH:2]']) {
    const raw = await viewer(t, source, {}, { format: 'raw' }); ready(raw.workers[0]);
    assert.equal(raw.workers[0].messages.at(-1).item.source, source);
  }
  const label = '[BDB]'.repeat(40);
  const named = await viewer(t, label + '：CCO', {}, { format: 'labels' }); ready(named.workers[0]);
  assert.equal(named.document.querySelector('h2').textContent, label);
  assert.equal(named.workers[0].messages.at(-1).item.source, 'CCO');
  const single = await viewer(t, 'CCO', {}, { format: 'raw', name: label });
  assert.equal(single.document.querySelector('h2').textContent, label);
});
