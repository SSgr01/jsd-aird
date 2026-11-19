import test from 'node:test';
import assert from 'node:assert/strict';
import {SlotCore} from '../../../vendor/deepseek-harness/packages/client/ui-slots/src/index.ts';
import {createRequire} from 'node:module';
import {createHash, webcrypto} from 'node:crypto';
import {registerSpectrumUI, registerSpectrumAttachments, spectrumAttachmentPresentation, spectrumDraftSelection, replaceSpectrumReferences, SpectrumResourceBody} from '../../enterprise-plugin/src/spectrum-client.mjs';

test('image SHA-256 matches native hashing with and without SubtleCrypto', async () => {
  for (const length of [0,1,3,55,56,63,64,65,127,128,1024,1024*1024]) {
    const bytes = Uint8Array.from({length}, (_, i) => (i * 137 + 29) & 255);
    const expected = createHash('sha256').update(bytes).digest('hex');
    assert.equal(await spectrumImageHash(bytes.buffer, {}), expected);
    assert.equal(await spectrumImageHash(bytes.buffer, null), expected);
    assert.equal(await spectrumImageHash(bytes.buffer, webcrypto), expected);
  }
  assert.equal(await spectrumImageHash(new TextEncoder().encode('abc').buffer, {}),
    'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
});

import {sha256Hex as spectrumImageHash} from '../../enterprise-plugin/src/browser-crypto.mjs';

const id = '5a045345-db60-47f6-9a77-fd5629bc6b0c';
const ref = {kind:'SPECTRUM',resource_id:id,version_id:'a'.repeat(64),pages:[2,4]};
function chip(reference = ref) {
  const clipboardText = '[样品图谱](jsd-resource:original)';
  return {source:'企业资料',ref:JSON.stringify({ref:reference,spectrumFiles:[{page:2,id:'page-two'}]}),label:'样品图谱',clipboardText,offset:0,length:clipboardText.length};
}

test('reopening reads exact selected pages from the native draft and reuses only its live images', () => {
  const draft = {references:[chip()]};
  const attachments = [{id:'page-two',file:{name:'original.png'}},
    {id:'page-four',file:{name:'样品图谱-'+id.slice(0,8)+'-第4页.png'}},
    {id:'other-image',file:{name:'photo.png'}}];
  const result = spectrumDraftSelection(draft,attachments);
  assert.deepEqual(result.selection,[{id,versionId:ref.version_id,pages:[2,4],files:{2:'page-two',4:'page-four'}}]);
  assert.deepEqual(result.attachmentIds,['page-two','page-four']);
  assert.deepEqual(spectrumDraftSelection({occurrences:[]},attachments).selection,[]);
  assert.deepEqual(spectrumDraftSelection(draft,[]).selection[0].files,{});
});

test('changing and clearing spectrum references preserves questions, quotes, other sources and commands', () => {
  const spectrum = chip(), other = {...chip({kind:'PROJECT',resource_id:'project'}),clipboardText:'[项目]',length:4};
  const text = '/task 请比较 '+spectrum.clipboardText+'\n[项目]\n> 原文摘录\n继续保留我的问题';
  spectrum.offset = text.indexOf(spectrum.clipboardText);
  other.offset = text.indexOf('[项目]');
  const draft = {text,references:[spectrum,other],command:{token:'/task '},quotes:[{offset:text.indexOf('> 原文摘录'),length:6,text:'原文摘录'}]};
  const revised = replaceSpectrumReferences(draft,[chip({...ref,pages:[4]})]);
  assert.equal(replaceSpectrumReferences(revised,[chip({...ref,pages:[4]})]).text,revised.text);
  assert.equal(spectrumDraftSelection(revised).selection[0].pages[0],4);
  assert.equal(revised.references.length,2);
  assert.ok(revised.text.includes('请比较') && revised.text.includes('继续保留我的问题'));
  assert.deepEqual(revised.command,draft.command);
  assert.equal(revised.text.slice(revised.quotes[0].offset,revised.quotes[0].offset+6),'> 原文摘录');
  for (const item of revised.references) assert.equal(revised.text.slice(item.offset,item.offset+item.length),item.clipboardText);
  const cleared = replaceSpectrumReferences(revised,[]);
  assert.equal(cleared.references.length,1);
  assert.deepEqual(spectrumDraftSelection(cleared).selection,[]);
  assert.equal(cleared.references[0].clipboardText,'[项目]');
});

test('source panel shows the original page and useful sample fields without dumping internal metadata', () => {
  const require = createRequire(new URL('../../../jsd-aird-web/package.json',import.meta.url));
  const React = require('react'), {renderToStaticMarkup} = require('react-dom/server');
  const url = '/api/v1/agents/business/images/SPECTRUM/'+id+'?ref=pinned&page=2#page=2';
  const html = renderToStaticMarkup(React.createElement(SpectrumResourceBody,{React,value:{ref,title:'样品图谱 · 第2页',
    spectrum:{title:'样品图谱',sampleName:'UA-338',batchNo:'B01',testConditions:'25°C',originalName:'原始图谱.PDF',sha256:'internal-hash'},
    candidates:[{text:'fileObjectId: internal-id; page_read_hint: tool instructions',source:{anchor:{page_no:2,read_path:url}}}]}}));
  for (const label of ['样品图谱','UA-338','B01','25°C','原图第 2 页','放大查看','<img']) assert.ok(html.includes(label));
  for (const hidden of ['固定版本','fileObjectId','page_read_hint','internal-hash','tool instructions']) assert.ok(!html.includes(hidden));
});

test('spectrum extension coexists with the native header and expert overlay', () => {
  const slots = new SlotCore(), dispose = [];
  slots.register({name:'root', children:{
    'conversation.header.leading':{kind:'single', scope:'root'},
    'shell.overlay':{kind:'list', scope:'root'},
    'conversation.input.left':{kind:'list', scope:'session'},
  }}, () => null);
  const nativeHeader = () => null, expertOverlay = () => null;
  slots.register({name:'conversation.header.leading'}, nativeHeader);
  slots.register({name:'shell.overlay', id:'experts'}, expertOverlay);
  const scope = {slots:{
    inject:(_name, callback) => callback(),
    register:(...args) => { const release = slots.register(...args); dispose.push(release); return release; },
  }};
  registerSpectrumUI(scope, {React:{}, api:() => {throw new Error('Mounting must not submit a request');}});
  assert.equal(slots.entriesOfSlot('conversation.header.leading')[0].component, nativeHeader);
  assert.equal(slots.entriesOfSlot('shell.overlay').length, 2);
  assert.equal(slots.entriesOfSlot('conversation.input.left').length, 1);
  for (const release of dispose) release();
  assert.equal(slots.entriesOfSlot('shell.overlay')[0].component, expertOverlay);
  assert.equal(slots.entriesOfSlot('conversation.input.left').length, 0);
});

test('picker reopens with the draft selection, updates images without duplicates, and can clear just the spectra', {timeout:60000}, async () => {
  const require = createRequire(new URL('../../../jsd-aird-web/package.json',import.meta.url));
  const {JSDOM} = require('jsdom'), dom = new JSDOM('<html><body></body></html>',{url:'http://192.168.1.119:82/agent/'});
  const saved = new Map();
  for (const [key,value] of Object.entries({window:dom.window,document:dom.window.document,navigator:dom.window.navigator,location:dom.window.location,crypto:dom.window.crypto,IS_REACT_ACT_ENVIRONMENT:true})) {
    saved.set(key,Object.getOwnPropertyDescriptor(globalThis,key)); Object.defineProperty(globalThis,key,{value,configurable:true,writable:true});
  }
  const originalFetch = globalThis.fetch;
  let cleanup;
  try {
    const React = require('react'), {render,fireEvent,waitFor,cleanup:release} = require('@testing-library/react'); cleanup = release;
    const chart = {id,title:'样品图谱',sha256:ref.version_id,pageCount:4,categoryName:'液相 HPLC/GPC',sampleName:'UA-338'};
    const first = chip({...ref,pages:[2]}); first.offset = 4;
    let document = {text:'请比较 '+first.clipboardText+' 说明依据',references:[first]};
    let state = {phase:'plain',draftRev:0,attachmentIds:['page-two','personal-file'],occurrences:document.references};
    const listeners = new Set(), files = new Map([['page-two',{id:'page-two',file:{name:'existing.png'}}],['personal-file',{id:'personal-file',file:{name:'notes.txt'}}]]);
    let created = 0;
    const publish = patch => {state = {...state,...patch};listeners.forEach(fn => fn());};
    const shell = {state:{getSnapshot:() => state},get draftSnapshot(){return document;},focus(){},
      setDraft(value){document = value;publish({draftRev:state.draftRev+1,occurrences:value.references});},
      addAttachments(ids){publish({attachmentIds:[...state.attachmentIds,...ids]});return true;},
      removeAttachment(id){publish({attachmentIds:state.attachmentIds.filter(value => value !== id)});return true;}};
    let Selector;
    const binding = {}, scope = {slots:{inject:(_name,fn) => fn(),register(options,component){if(options.name === 'conversation.input.left')Selector=component;}},
      sessions:{binding:() => binding,retain:() => ({ready:Promise.resolve(binding),release(){}})},
      conversation:{input:{shellFor:() => shell},resolveDraftAttachments:ids => ids.map(id => files.get(id)).filter(Boolean),
        createDrafts(_session,images){return images.map(file => {const value={id:'new-'+(++created),file};files.set(value.id,value);return value;});},
        releaseDraftAttachment:id => files.delete(id)}};
    const bytes = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a/KkAAAAASUVORK5CYII=','base64');
    assert.equal(globalThis.crypto.subtle, undefined);
    const hash = createHash('sha256').update(bytes).digest('hex');
    let corrupt = true;
    globalThis.fetch = async path => {
      if (path === '/image') return new Response(bytes,{headers:{'content-type':'image/png','X-Content-SHA256':corrupt ? '0'.repeat(64) : hash}});
      const value = path.includes('categories') ? [] : path.includes('charts/') ? chart : {items:[chart],total:1};
      return Response.json({success:true,data:value});
    };
    registerSpectrumUI(scope,{React,Modal:({children,title}) => React.createElement('section',{'aria-label':title},children),
      useExpert:() => ({expert:{id:'spectrum-assistant'}}), marker:(_label,value) => '[图谱:'+value.pages.join(',')+']',
      api:async(_action,{ref:selection}) => ({ref:selection,title:chart.title,candidates:selection.pages.map(page => ({source:{anchor:{page_no:page,read_path:'/image'}}}))})});
    const view = render(React.createElement(Selector,{sessionId:'test',useInput:selector => selector(React.useSyncExternalStore(fn => {listeners.add(fn);return () => listeners.delete(fn);},() => state))}));
    const open = async () => {fireEvent.click(view.getByRole('button',{name:'添加图谱并选择页面'}));await waitFor(() => assert.equal(view.getByLabelText('第 2 页').checked,true));};
    await open();
    fireEvent.click(view.getByRole('button',{name:'确认选择'}));
    await waitFor(() => assert.equal(Boolean(view.queryByLabelText('选择图谱与页面')),false));
    assert.deepEqual(state.attachmentIds,['page-two','personal-file']); assert.equal(created,0);
    await open();
    fireEvent.click(view.getByLabelText('第 2 页')); fireEvent.click(view.getByLabelText('第 4 页'));
    fireEvent.click(view.getByRole('button',{name:'确认选择'}));
    await waitFor(() => assert.ok(view.getByRole('alert').textContent.includes('图谱图片校验失败')));
    assert.equal(created,0); assert.deepEqual(state.attachmentIds,['page-two','personal-file']);
    corrupt = false;
    fireEvent.click(view.getByRole('button',{name:'确认选择'}));
    await waitFor(() => assert.equal(Boolean(view.queryByLabelText('选择图谱与页面')),false));
    assert.equal(created,1); assert.deepEqual(state.attachmentIds,['personal-file','new-1']);
    assert.deepEqual(spectrumDraftSelection(document).selection[0].pages,[4]);
    assert.ok(document.text.includes('请比较') && document.text.includes('说明依据')); assert.ok(!files.has('page-two'));
    fireEvent.click(view.getByRole('button',{name:'添加图谱并选择页面'}));
    await waitFor(() => assert.equal(view.getByLabelText('第 4 页').checked,true));
    fireEvent.click(view.getByRole('button',{name:'移除 样品图谱'}));
    fireEvent.click(view.getByRole('button',{name:'确认选择'}));
    await waitFor(() => assert.equal(Boolean(view.queryByLabelText('选择图谱与页面')),false));
    assert.deepEqual(state.attachmentIds,['personal-file']); assert.equal(document.references.length,0);
    assert.ok(document.text.includes('说明依据')); assert.equal(files.size,1);
  } finally {
    cleanup?.(); globalThis.fetch = originalFetch; dom.window.close();
    for (const [key,descriptor] of saved) {if(descriptor)Object.defineProperty(globalThis,key,descriptor);else delete globalThis[key];}
  }
});

test('spectrum model images are hidden only when their live source reference is present', () => {
  const images=[{id:'page-two',file:{name:'original.png'}},{id:'personal',file:{name:'photo.png'}}];
  const shown=spectrumAttachmentPresentation({references:[chip()]},images);
  assert.deepEqual(shown.visible,[images[1]]);
  assert.deepEqual([...shown.owned],['page-two']);
  assert.deepEqual(spectrumAttachmentPresentation({references:[]},images).visible,images);
  assert.equal(images.length,2,'presentation never removes model payloads');
});

test('native attachment seat preserves image payloads and removes owned images when their reference is deleted', async () => {
  const require=createRequire(new URL('../../../jsd-aird-web/package.json',import.meta.url));
  const {JSDOM}=require('jsdom'),dom=new JSDOM('<html><body></body></html>');
  const saved=new Map();
  for(const [key,value] of Object.entries({window:dom.window,document:dom.window.document,navigator:dom.window.navigator,IS_REACT_ACT_ENVIRONMENT:true})) {
    saved.set(key,Object.getOwnPropertyDescriptor(globalThis,key));Object.defineProperty(globalThis,key,{value,configurable:true,writable:true});
  }
  let cleanup;
  try {
    const React=require('react'),testing=require('@testing-library/react');cleanup=testing.cleanup;
    let draft={references:[chip()]},snapshot={phase:'plain',draftRev:0};
    const listeners=new Set(),released=[];
    const files=[{id:'page-two',file:{name:'spectrum.png'}},{id:'personal',file:{name:'notes.txt'}}];
    const shell={state:{getSnapshot:()=>snapshot,subscribe:fn=>{listeners.add(fn);return()=>listeners.delete(fn);}},
      get draftSnapshot(){return draft;},removeAttachment:id=>released.push('remove:'+id)};
    let lastProps,wrapped;
    const native={options:{},locale:'conversation',inject:()=>({readFileDrag:'native-drag'}),component:props=>{
      lastProps=props;return React.createElement('div',null,props.attachments.map(a=>React.createElement('span',{key:a.id},a.file.name)));
    }};
    const scope={sessions:{binding:()=>({})},conversation:{input:{shellFor:()=>shell},releaseDraftAttachment:id=>released.push('release:'+id)},slots:{
      inject:(_name,fn)=>fn(),entries:()=>[native],subscribe:()=>()=>{},register:(options,component)=>{wrapped={options,component};return()=>{};}
    }};
    registerSpectrumAttachments(scope,{React});
    const onAddFiles=()=>{},onRemoveAttachment=()=>{};
    const view=testing.render(React.createElement(wrapped.component,{...wrapped.options.inject('session'),attachments:files,onAddFiles,onRemoveAttachment}));
    assert.equal(view.queryByText('spectrum.png'),null);
    assert.ok(view.getByText('notes.txt'));
    assert.equal(files.length,2);assert.deepEqual(released,[]);
    assert.equal(lastProps.onAddFiles,onAddFiles);assert.equal(lastProps.onRemoveAttachment,onRemoveAttachment);
    assert.equal(lastProps.readFileDrag,'native-drag');assert.equal(wrapped.options.locale,native.locale);
    await testing.act(async()=>{draft={references:[]};snapshot={phase:'plain',draftRev:1};listeners.forEach(fn=>fn());});
    assert.deepEqual(released,['remove:page-two','release:page-two']);
  } finally {
    cleanup?.();dom.window.close();for(const [key,value] of saved){if(value)Object.defineProperty(globalThis,key,value);else delete globalThis[key];}
  }
});

test('attachment wrapper keeps the native seat through real single-slot shadowing', async () => {
  const slots=new SlotCore();slots.register({name:'root',children:{'conversation.input.attachments':{kind:'single',scope:'session-maybe'}}},()=>null);
  const Native=()=>null;const nativeOff=slots.register({name:'conversation.input.attachments'},Native);
  let teardown;
  const scope={slots:{entries:name=>slots.entries(name),subscribe:(...args)=>slots.subscribe(...args),
    register:(...args)=>slots.register(...args),inject:(_name,callback)=>{teardown=callback();}}};
  registerSpectrumAttachments(scope,{React:{}});
  assert.equal(slots.entries('conversation.input.attachments').length,2);
  assert.equal(slots.entriesOfSlot('conversation.input.attachments')[0].options.id,'enterprise-spectrum-attachments');
  nativeOff();await new Promise(resolve=>setTimeout(resolve,0));assert.equal(slots.entries('conversation.input.attachments').length,0);
  teardown();
});
