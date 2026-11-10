/* Shared chemistry rendering for both chat surfaces. */
const params = new URLSearchParams(location.hash.slice(1));
addEventListener('hashchange', () => location.reload());
const source = (params.get('source') || '').trim(), reaction = params.get('kind') === 'reaction-smiles';
const status = document.getElementById('status'), cards = document.getElementById('cards'), retry = document.getElementById('retry');
const urls = [], entries = [], pending = [];
let worker, timer, limits, active, ready = false, deadline = 0, sequence = 0;
function resize() { parent.postMessage({ type: 'jsd-chemistry-height', height: Math.ceil(document.querySelector('main').getBoundingClientRect().height) }, location.origin); }
new ResizeObserver(resize).observe(document.body);
function stopWorker() { clearTimeout(timer); worker?.terminate(); worker = undefined; ready = false; }
function progress() {
  const done = entries.filter(entry => entry.state === 'done').length;
  status.hidden = !pending.length && active === undefined;
  status.textContent = `正在绘制结构… ${done}/${entries.length}`;
  resize();
}
function fail(entry, message, canRetry = true) {
  entry.state = 'failed';
  const drawing = entry.card.querySelector('.drawing');
  drawing.classList.add('unavailable'); drawing.textContent = message;
  if (canRetry) {
    const button = document.createElement('button'); button.type = 'button'; button.textContent = '重试此结构';
    button.onclick = () => enqueue(entry); drawing.append(button);
  }
  entry.card.querySelector('.source').hidden = false;
}
function settle(item) {
  const entry = active; active = undefined; clearTimeout(timer);
  if (item.valid) {
    entry.state = 'done'; entry.result = item;
    const url = URL.createObjectURL(new Blob([item.svg], { type: 'image/svg+xml' })); urls.push(url);
    const drawing = entry.card.querySelector('.drawing'); drawing.classList.remove('unavailable');
    const img = document.createElement('img'); img.src = url; img.alt = `${item.label}，由输入 SMILES 绘制`;
    // Preserve aspect ratio; long structures can also be inspected at their original SVG size.
    img.style.setProperty('--structure-width', `${Math.max(180, item.width * 2)}px`);
    if (reaction || item.width / item.height > 4) entry.card.classList.add('wide');
    drawing.replaceChildren(img);
    const download = entry.card.querySelector('[data-action=download]');
    download.href = url; download.download = item.label.replace(/[^\w\u4e00-\u9fff-]/gu, '_') + '.svg'; download.hidden = false;
    entry.card.querySelector('[data-action=zoom]').hidden = false;
  } else fail(entry, item.error, false);
  progress(); pump();
}
function broken(message) {
  stopWorker();
  if (active !== undefined) { fail(active, message); active = undefined; }
  progress(); pump();
}
function pump() {
  if (active !== undefined || !pending.length) return;
  if (performance.now() >= deadline) {
    for (const entry of pending.splice(0)) fail(entry, '本组绘图达到时间预算，可单独重试继续');
    progress(); return;
  }
  active = pending.shift(); active.state = 'drawing';
  active.card.querySelector('.drawing').textContent = '正在绘制…';
  if (!worker) {
    try {
      const instance = new Worker('worker.js'); worker = instance;
      timer = setTimeout(() => broken('绘图组件加载超时，可单独重试'), Math.min(limits.loadTimeoutMs, deadline - performance.now()));
      instance.onerror = () => { if (instance === worker) broken('绘图组件加载失败，可单独重试'); };
      instance.onmessage = ({ data }) => {
        if (instance !== worker) return;
        if (data.type === 'error') { broken(data.error); return; }
        if (data.type === 'ready') { ready = true; clearTimeout(timer); draw(); }
        else if (data.type === 'result' && active?.id === data.id) settle(data.item);
      };
      instance.postMessage({ type: 'init' });
    } catch { broken('浏览器无法启动绘图，可单独重试'); }
  } else if (ready) draw();
  progress();
}
function draw() {
  if (!active) return;
  active.id = ++sequence;
  timer = setTimeout(() => broken('此结构绘图超时，可单独重试'), Math.min(limits.itemTimeoutMs, Math.max(0, deadline - performance.now())));
  worker.postMessage({ type: 'draw', id: active.id, item: active.item, reaction, maxSvgBytes: limits.maxSvgBytes });
}
function enqueue(entry) {
  if (entry.state === 'queued' || entry.state === 'drawing') return;
  if (!pending.length && active === undefined) deadline = performance.now() + limits.groupTimeoutMs;
  entry.state = 'queued'; pending.push(entry); pump();
}
function card(item) {
  const element = document.getElementById('card-template').content.firstElementChild.cloneNode(true);
  const entry = { item, card: element, state: 'new' }; entries.push(entry);
  element.querySelector('h2').textContent = item.label; element.querySelector('code').textContent = item.source;
  const menu = element.querySelector('details');
  element.querySelector('[data-action=source]').onclick = () => { const panel = element.querySelector('.source'); panel.hidden = !panel.hidden; menu.open = false; };
  element.querySelector('[data-action=copy]').onclick = async event => {
    try { await navigator.clipboard.writeText(item.source); event.target.textContent = '已复制'; }
    catch { element.querySelector('.source').hidden = false; event.target.textContent = '请选中原文复制'; }
  };
  element.querySelector('[data-action=zoom]').onclick = event => {
    const expanded = element.classList.toggle('expanded');
    event.target.textContent = expanded ? '适应宽度' : '放大查看'; menu.open = false; resize();
  };
  element.querySelector('[data-action=download]').onclick = () => { menu.open = false; };
  cards.append(element); return entry;
}
async function start() {
  retry.hidden = true;
  try {
    const response = await fetch('limits.json', { cache: 'no-cache', signal: AbortSignal.timeout(10000) });
    if (!response.ok) throw new Error();
    limits = await response.json();
    for (const name of ['maxSourceChars','maxEntryChars','maxEntries','maxSvgBytes','loadTimeoutMs','itemTimeoutMs','groupTimeoutMs'])
      if (!Number.isSafeInteger(limits[name]) || limits[name] < 1) throw new Error();
  } catch { status.hidden = false; status.textContent = '绘图配置加载失败，请重试'; retry.hidden = false; return; }
  if (!source || source.length + (params.get('name') || '').length > limits.maxSourceChars) {
    status.hidden = false; status.textContent = `结构内容为空或超过本组 ${limits.maxSourceChars} 字符预算，请拆分后重试`; return;
  }
  const labelled = params.get('format') === 'raw' ? null : chemistryList(source, false);
  const items = labelled || [{ label: params.get('name') || (reaction ? '化学反应结构' : '分子结构'), source }];
  if (items.length > limits.maxEntries) { status.hidden = false; status.textContent = `本组超过 ${limits.maxEntries} 个结构，请拆分后重试`; return; }
  for (const item of items) {
    const entry = card(item);
    if (item.source.length > limits.maxEntryChars) fail(entry, `此结构超过 ${limits.maxEntryChars} 字符预算，请拆分输入`, false);
    else { entry.state = 'queued'; pending.push(entry); }
  }
  deadline = performance.now() + limits.groupTimeoutMs; progress(); pump();
}
document.addEventListener('click', event => { for (const menu of document.querySelectorAll('details[open]')) if (!menu.contains(event.target)) menu.open = false; });
document.addEventListener('keydown', event => { if (event.key === 'Escape') for (const menu of document.querySelectorAll('details[open]')) { menu.open = false; menu.querySelector('summary').focus(); } });
retry.onclick = start;
start();
addEventListener('pagehide', () => { stopWorker(); urls.forEach(url => URL.revokeObjectURL(url)); });
