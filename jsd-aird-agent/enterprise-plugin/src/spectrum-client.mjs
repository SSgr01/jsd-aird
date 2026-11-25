import {sha256Hex} from './browser-crypto.mjs';

/** Recover selections from the live semantic draft, including after session navigation. */
export function spectrumDraftSelection(draft, attachments = []) {
  const selected = new Map(), owned = new Set();
  for (const occurrence of draft.references || draft.occurrences || []) {
    let value;
    try {value = JSON.parse(occurrence.ref);} catch {continue;}
    const ref = value?.ref;
    if (ref?.kind !== 'SPECTRUM' || typeof ref.resource_id !== 'string' || !Array.isArray(ref.pages)) continue;
    let item = selected.get(ref.resource_id);
    if (!item) {item = {id:ref.resource_id, versionId:ref.version_id, pages:[], files:{}}; selected.set(item.id, item);}
    for (const page of ref.pages) if (!item.pages.includes(page)) item.pages.push(page);
    for (const attachment of attachments) {
      const declared = Array.isArray(value.spectrumFiles) && value.spectrumFiles.find(file => file?.id === attachment.id && ref.pages.includes(file.page));
      // References created before ownership metadata used this exact generated suffix.
      const page = declared?.page ?? ref.pages.find(page => attachment.file?.name?.endsWith('-' + ref.resource_id.slice(0,8) + '-第' + page + '页.png'));
      if (page !== undefined) {item.files[page] = attachment.id; owned.add(attachment.id);}
    }
  }
  return {selection:[...selected.values()], attachmentIds:[...owned]};
}

/** Keep model images attached, but present their source once through the reference. */
export function spectrumAttachmentPresentation(draft, attachments) {
  const owned = new Set(spectrumDraftSelection(draft, attachments).attachmentIds);
  return {owned, visible:attachments.filter(item => !owned.has(item.id))};
}

/** Wrap the native attachment seat without changing uploads, drag/drop or submission. */
export function registerSpectrumAttachments(scope, {React}) {
  if (!scope.slots.entries || !scope.slots.subscribe) return;
  const id = 'enterprise-spectrum-attachments';
  scope.slots.inject('conversation.input.attachments', () => {
    let current, dispose;
    function sync() {
      const entry = scope.slots.entries('conversation.input.attachments').find(item => item.options.id !== id);
      if (entry === current) return;
      current = entry;
      dispose?.(); dispose = undefined;
      if (!entry) return;
      function Attachments(props) {
        const binding = scope.sessions.binding(props.spectrumSessionId);
        const shell = binding && scope.conversation.input.shellFor(binding);
        const snapshot = React.useSyncExternalStore(
          listener => shell ? shell.state.subscribe(listener) : () => {},
          () => shell ? shell.state.getSnapshot() : null);
        const {owned, visible} = spectrumAttachmentPresentation(shell?.draftSnapshot || {}, props.attachments);
        const previous = React.useRef(new Set());
        React.useEffect(() => {
          if (snapshot?.phase !== 'plain') return;
          const live = new Set(props.attachments.map(item => item.id));
          for (const attachmentId of previous.current) {
            if (!owned.has(attachmentId) && live.has(attachmentId)) {
              shell.removeAttachment(attachmentId);
              scope.conversation.releaseDraftAttachment(attachmentId);
            }
          }
          previous.current = owned;
        }, [snapshot, props.attachments, shell]);
        return React.createElement(entry.component, {...props, attachments:visible});
      }
      dispose = scope.slots.register({name:'conversation.input.attachments', id, priority:-1000,
        store:entry.store, locale:entry.locale,
        inject:sessionId => ({...(entry.inject?.(sessionId) || {}), spectrumSessionId:sessionId})}, Attachments);
    }
    const unsubscribe = scope.slots.subscribe('conversation.input.attachments', sync);
    sync();
    return () => {unsubscribe(); dispose?.();};
  });
}

/** Replace only spectrum chips; keep typed questions, other sources, quotes and commands. */
export function replaceSpectrumReferences(draft, references) {
  const removed = draft.references.filter(item => {
    try {return JSON.parse(item.ref)?.ref?.kind === 'SPECTRUM';} catch {return false;}
  });
  const inserted = references.map(item => item.clipboardText).join(' ');
  const shift = offset => offset + removed.reduce((sum, item, index) => sum +
    (item.offset < offset ? (index === 0 ? inserted.length : 0) - item.length : 0), 0);
  let text = draft.text, insertion = removed[0]?.offset;
  if (removed.length) {
    for (let index=removed.length-1; index>=0; index--) {
      const item = removed[index];
      text = text.slice(0,item.offset) + (index === 0 ? inserted : '') + text.slice(item.offset + item.length);
    }
  } else if (inserted) {
    if (text && !/\s$/.test(text)) text += ' ';
    insertion = text.length; text += inserted + ' ';
  }
  const kept = draft.references.filter(item => !removed.includes(item)).map(item => ({...item, offset:shift(item.offset)}));
  const quotes = draft.quotes?.map(item => ({...item, offset:shift(item.offset)}));
  for (const item of references) {
    kept.push({...item, offset:insertion, length:item.clipboardText.length});
    insertion += item.clipboardText.length + 1;
  }
  return {...draft, text, references:kept.sort((a,b) => a.offset-b.offset), ...(quotes ? {quotes} : {})};
}

/** A source is a readable original page, with the fields needed to interpret it. */
export function SpectrumResourceBody({React, value}) {
  const h = React.createElement, facts = value.spectrum || {};
  const fields = [['样品', facts.sampleName], ['批号', facts.batchNo], ['测试条件', facts.testConditions], ['原文件', facts.originalName]]
    .filter(([, value]) => typeof value === 'string' && value.trim());
  function Page({item}) {
    const [failed, setFailed] = React.useState(false), url = item.source.anchor.read_path;
    return h('figure', {className:'jsd-spectrum-source-page'},
      h('figcaption', null, '原图第 ' + item.source.anchor.page_no + ' 页', h('a', {href:url}, '放大查看')),
      failed ? h('p', {role:'alert'}, '页面暂时无法显示，请重新打开或检查访问权限。') :
        h('a', {href:url, 'aria-label':'放大查看第 ' + item.source.anchor.page_no + ' 页'},
          h('img', {src:url, alt:value.title + '，原始第 ' + item.source.anchor.page_no + ' 页', onError:() => setFailed(true)})));
  }
  return h('article', {className:'jsd-resource jsd-spectrum-source'}, h('h2', null, facts.title || value.title),
    h('p', {className:'jsd-spectrum-hint'}, '分析引用的原始图谱，可核对页面内容与测试条件。'),
    fields.length > 0 && h('dl', {className:'jsd-spectrum-facts'}, fields.flatMap(([label, text]) => [h('dt',{key:label},label),h('dd',{key:label+'-value'},text)])),
    facts.testMethodSnapshot && h('p', {className:'jsd-spectrum-hint'}, '当次条件快照' + (facts.testMethodSnapshot.presetName ? ' · ' + facts.testMethodSnapshot.presetName + ' · V' + facts.testMethodSnapshot.presetRevision : ' · 手工登记')),
    Array.isArray(facts.conditionWarnings) && facts.conditionWarnings.filter(text => typeof text === 'string').map((text, index) => h('p', {key:'condition-warning-' + index, className:'jsd-spectrum-hint'}, text)),
    (value.candidates || []).map(item => h(Page, {key:item.source.anchor.page_no, item})),
    h('a', {href:'/spectrum/view?chartId=' + value.ref.resource_id, target:'_blank', rel:'noopener'}, '在图谱中心查看'));
}

/** Spectrum selection enriches the native composer; submission and history stay native. */
export function registerSpectrumUI(scope, {React, api, Modal, useExpert, marker}) {
  const h = React.createElement, {useEffect, useState} = React;
  registerSpectrumAttachments(scope, {React});
  const limit = 20;
  let entry = null;
  try {
    const value = new URL(location.href).searchParams.get('spectrum');
    if (value !== null) {
      const ids = value ? value.split(',') : [];
      if (ids.length > 12 || ids.some(id => !/^[a-f0-9-]{36}$/i.test(id))) throw new Error('INVALID_SPECTRUM_SELECTION');
      entry = [...new Set(ids)];
    }
  } catch { entry = []; }
  let launch, launched = null;
  const listeners = new Set();
  const subscribe = fn => {listeners.add(fn); return () => listeners.delete(fn);};
  const snapshot = () => launched;
  async function business(path, signal) {
    const response = await fetch('/api/v1/spc/' + path, {credentials:'same-origin', signal, cache:'no-store'});
    const result = await response.json().catch(() => ({}));
    if (!response.ok || result.success === false) throw new Error(result.message || '图谱暂不可读取，请检查访问权限后重试。');
    return result.data;
  }
  const button = (label, onClick, props = {}) => h('button', {type:'button', onClick, ...props,
    className:'jsd-expert-button ' + (props.className || '')}, label);

  function Entry() {
    const [error, setError] = useState(''), [attempt, retry] = useState(0);
    useEffect(() => {
      if (entry === null || launched) return;
      let alive = true;
      launch ||= api('experts', {action:'summon', expert_id:'spectrum-assistant'}).catch(error => {launch = null; throw error;});
      launch.then(async value => {
        if (!alive) return;
        launched = {sessionId:value.session_id, ids:entry}; listeners.forEach(fn => fn());
        entry = null;
        const url = new URL(location.href); url.searchParams.delete('spectrum');
        history.replaceState(history.state, '', url.pathname + url.search + url.hash);
        await scope.uiWorkspace.openSession(value.session_id);
      }).catch(error => {if (alive) setError(error.message);});
      return () => {alive = false;};
    }, [attempt]);
    return error && h('div', {className:'jsd-spectrum-entry-error', role:'alert'}, error,
      button('重试', () => {setError(''); retry(value => value + 1);}));
  }

  function Picker({sessionId, initialSelection, onClose}) {
    const [rows, setRows] = useState([]), [selected, setSelected] = useState({});
    const [query, setQuery] = useState(''), [category, setCategory] = useState(''), [categories, setCategories] = useState([]);
    const [page, setPage] = useState(1), [total, setTotal] = useState(0);
    const [loading, setLoading] = useState(true), [initializing, setInitializing] = useState(true);
    const [busy, setBusy] = useState(false), [error, setError] = useState(''), [attempt, retry] = useState(0);
    const initialized = React.useRef(false);
    useEffect(() => {
      const controller = new AbortController();
      Promise.all([business('categories', controller.signal), Promise.all(initialSelection.map(item => business('charts/' + item.id, controller.signal)))])
        .then(([groups, charts]) => {
          if (controller.signal.aborted) return;
          setCategories(groups || []);
          if (!initialized.current) {
            const changed = charts.some(chart => initialSelection.find(item => item.id === chart.id)?.versionId && initialSelection.find(item => item.id === chart.id).versionId !== chart.sha256);
            setSelected(Object.fromEntries(charts.filter(chart => {
              const previous = initialSelection.find(item => item.id === chart.id);
              return !previous.versionId || previous.versionId === chart.sha256;
            }).map(chart => [chart.id, {chart, pages:initialSelection.find(item => item.id === chart.id).pages ?? Array.from({length:chart.pageCount}, (_,index) => index+1)}])));
            if (changed) setError('部分图谱原文件已更新，请重新勾选后确认。');
            initialized.current = true;
          }
          setInitializing(false);
        }).catch(error => {if (!controller.signal.aborted) {setError(error.message); setInitializing(false);}});
      return () => controller.abort();
    }, [initialSelection, attempt]);
    useEffect(() => {
      const controller = new AbortController(); setLoading(true);
      const timer = setTimeout(() => {
        const params = new URLSearchParams({keyword:query, status:'READY', page:String(page), size:'30'});
        if (category) params.set('categoryId', category);
        business('charts?' + params, controller.signal).then(value => {
          if (!controller.signal.aborted) {setRows(value.items || []); setTotal(value.total || 0); setLoading(false);}
        }).catch(error => {if (!controller.signal.aborted) {setError(error.message); setLoading(false);}});
      }, 180);
      return () => {clearTimeout(timer); controller.abort();};
    }, [query, category, page, attempt]);
    const count = Object.values(selected).reduce((sum, value) => sum + value.pages.length, 0);
    const updatePages = (id, pages) => setSelected(current => ({...current, [id]:{...current[id], pages}}));
    const toggle = chart => {
      setError('');
      setSelected(current => {
        if (current[chart.id]) {const next = {...current}; delete next[chart.id]; return next;}
        if (Object.keys(current).length >= 12) {setError('一次最多选择 12 张图谱。'); return current;}
        return {...current, [chart.id]:{chart, pages:Array.from({length:chart.pageCount}, (_,index) => index+1)}};
      });
    };
    async function attach() {
      if (busy || initializing || count > limit || Object.values(selected).some(item => !item.pages.length)) return;
      setBusy(true); setError('');
      let drafts = [], reference;
      try {
        reference = scope.sessions.retain(sessionId, {source:'spectrum-selection'});
        const binding = await reference.ready;
        if (!binding) throw new Error('工作台会话已关闭，请重新打开后添加。');
        const shell = scope.conversation.input.shellFor(binding);
        if (shell.state.getSnapshot().phase !== 'plain') throw new Error('请等待当前消息发送完成后添加图谱。');
        const before = shell.state.getSnapshot();
        const previous = spectrumDraftSelection(shell.draftSnapshot, scope.conversation.resolveDraftAttachments(before.attachmentIds));
        if (before.attachmentIds.length - previous.attachmentIds.length + count > limit) throw new Error('图片和附件合计最多 20 个，请减少选择。');
        const files = [], resources = [], fileOwners = [], reused = new Set();
        for (const {chart, pages} of Object.values(selected)) {
          const value = await api('resource', {ref:{kind:'SPECTRUM', resource_id:chart.id, version_id:chart.sha256, pages:[...pages].sort((a,b) => a-b)}});
          const resource = {value, spectrumFiles:[]}; resources.push(resource);
          for (const candidate of value.candidates) {
            const page = candidate.source.anchor.page_no;
            const retained = previous.selection.find(item => item.id === chart.id && item.versionId === chart.sha256)?.files[page];
            if (retained) {resource.spectrumFiles.push({page,id:retained}); reused.add(retained); continue;}
            const url = candidate.source.anchor.read_path;
            const response = await fetch(url, {credentials:'same-origin', cache:'no-store'});
            if (!response.ok || !response.headers.get('content-type')?.startsWith('image/png')) throw new Error('图谱页面读取失败，请重新选择后重试。');
            const bytes = await response.arrayBuffer();
            const hash = await sha256Hex(bytes);
            if (hash !== response.headers.get('X-Content-SHA256')) throw new Error('图谱图片校验失败，请重试。');
            const title = chart.title.replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').slice(0, 80);
            files.push(new File([bytes], title + '-' + chart.id.slice(0,8) + '-第' + candidate.source.anchor.page_no + '页.png', {type:'image/png'}));
            fileOwners.push({resource,page});
          }
        }
        const current = shell.state.getSnapshot();
        if (scope.sessions.binding(sessionId) !== binding || current.phase !== 'plain' || current.draftRev !== before.draftRev
            || current.attachmentIds.join() !== before.attachmentIds.join()) throw new Error('问题或附件已变化，请重新打开图谱选择后确认。');
        drafts = scope.conversation.createDrafts(sessionId, files, true);
        drafts.forEach((draft,index) => fileOwners[index].resource.spectrumFiles.push({page:fileOwners[index].page,id:draft.id}));
        const refs = resources.map(({value,spectrumFiles}) => ({source:'企业资料',
          ref:JSON.stringify({ref:value.ref, label:value.title, spectrumFiles}), label:value.title,
          appearance:'file', clipboardText:marker(value.title,value.ref)}));
        const original = shell.draftSnapshot;
        shell.setDraft(replaceSpectrumReferences(original, refs));
        if (!shell.addAttachments(drafts.map(item => item.id))) {shell.setDraft(original); throw new Error('输入框暂时不可编辑，请稍后重试。');}
        for (const id of previous.attachmentIds) if (!reused.has(id)) {shell.removeAttachment(id); scope.conversation.releaseDraftAttachment(id);}
        drafts = []; shell.focus(); onClose();
      } catch (error) {setError(error.message);}
      finally {
        for (const draft of drafts) scope.conversation.releaseDraftAttachment(draft.id);
        reference?.release(); setBusy(false);
      }
    }
    return h(Modal, {open:true, title:'选择图谱与页面', closeLabel:'关闭图谱选择', className:'jsd-spectrum-dialog',
      contentClassName:'jsd-spectrum-picker', backdropBlur:false, onClose:() => {if (!busy) onClose();}},
      h('p', {className:'jsd-spectrum-hint'}, '已选图谱与当前问题同步。可调整页码或取消勾选，确认后更新问题中的图片。'),
      h('div', {className:'jsd-spectrum-filters'},
        h('input', {type:'search', placeholder:'搜索图谱、样品或批号', 'aria-label':'搜索图谱', value:query, disabled:busy,
          onChange:event => {setQuery(event.target.value); setPage(1);}}),
        h('select', {'aria-label':'图谱分类', value:category, disabled:busy, onChange:event => {setCategory(event.target.value); setPage(1);}},
          h('option', {value:''}, '全部分类'), categories.map(item => h('option', {key:item.id, value:item.id}, item.name)))),
      error && h('div', {role:'alert', className:'jsd-expert-error jsd-spectrum-error'}, error, ' ', button('重试', () => {setError(''); retry(value => value + 1);}, {disabled:busy})),
      h('div', {className:'jsd-spectrum-chart-list', 'aria-label':'可选图谱', 'aria-busy':loading},
        loading ? h('p', {role:'status'}, '正在读取图谱…') : !rows.length ? h('p', null, '没有符合条件的图谱。') : rows.map(chart =>
          h('label', {key:chart.id, className:'jsd-spectrum-chart'},
            h('input', {type:'checkbox', checked:Boolean(selected[chart.id]), disabled:busy || initializing, onChange:() => toggle(chart)}),
            h('span', null, h('strong', null, chart.title), h('small', null, [chart.categoryName, chart.sampleName, chart.batchNo, chart.pageCount + ' 页'].filter(Boolean).join(' · ')))))),
      h('div', {className:'jsd-spectrum-pagination'}, h('span', null, total + ' 张图谱'),
        button('上一页', () => setPage(value => value-1), {disabled:busy || page <= 1}), h('span', null, page),
        button('下一页', () => setPage(value => value+1), {disabled:busy || page*30 >= total})),
      h('div', {className:'jsd-spectrum-selected', 'aria-label':'已选图谱页面'}, Object.values(selected).map(({chart, pages}) =>
        h('section', {key:chart.id}, h('div', {className:'jsd-spectrum-selected-title'}, h('strong', null, chart.title),
          button('移除', () => toggle(chart), {disabled:busy, 'aria-label':'移除 ' + chart.title})),
        h('div', {className:'jsd-spectrum-pages', role:'group', 'aria-label':chart.title + ' 页码'},
          Array.from({length:chart.pageCount}, (_,index) => index+1).map(number => h('label', {key:number},
            h('input', {type:'checkbox', checked:pages.includes(number), disabled:busy || (!pages.includes(number) && count >= limit),
              onChange:() => updatePages(chart.id, pages.includes(number) ? pages.filter(value => value !== number) : [...pages, number])}), '第 ' + number + ' 页')))))),
      h('footer', {className:'jsd-spectrum-footer'}, h('span', null, Object.keys(selected).length + ' 张图谱 · ' + count + ' / 20 页'),
        count > limit && h('span', {role:'alert'}, '所选页面超过 20 页，请取消部分页面后确认。'),
        button(busy ? '正在准备图谱…' : '确认选择', () => void attach(), {className:'jsd-expert-primary', disabled:busy || initializing || (!count && !initialSelection.length) || count > limit || Object.values(selected).some(item => !item.pages.length)})));
  }
  function Selector({sessionId, useInput}) {
    const {expert} = useExpert(sessionId), input = useInput(value => value);
    const current = React.useSyncExternalStore(subscribe, snapshot, snapshot);
    const [open, setOpen] = useState(false), [initialSelection, setInitialSelection] = useState([]);
    const selected = spectrumDraftSelection(input).selection;
    useEffect(() => {
      if (current?.sessionId === sessionId) {setInitialSelection(current.ids.map(id => ({id}))); setOpen(true); launched = null; listeners.forEach(fn => fn());}
    }, [current, sessionId]);
    if (expert?.id !== 'spectrum-assistant') return null;
    return h(React.Fragment, null,
      button(selected.length ? '已选 ' + selected.length + ' 张图谱' : '添加图谱', () => {setInitialSelection(selected); setOpen(true);}, {className:'jsd-spectrum-add', disabled:input.phase !== 'plain', 'aria-label':'添加图谱并选择页面'}),
      open && h(Picker, {sessionId, initialSelection, onClose:() => setOpen(false)}));
  }
  scope.slots.inject('shell.overlay', () => scope.slots.register({name:'shell.overlay', id:'enterprise-spectrum-entry'}, Entry));
  scope.slots.inject('conversation.input.left', () => scope.slots.register({name:'conversation.input.left', id:'enterprise-spectrum-picker', order:-15,
    inject:sessionId => ({sessionId})}, Selector));
}
