import test from 'node:test';
import assert from 'node:assert/strict';
import {webcrypto} from 'node:crypto';
import {createRequire} from 'node:module';
import {generateBrowserUUID} from '../../enterprise-plugin/src/browser-crypto.mjs';
import {registerMemoryUI} from '../../enterprise-plugin/src/memory-client.mjs';

test('HTTP UUID fallback uses secure random bytes and correct version and variant', () => {
  const cryptoApi = {getRandomValues: webcrypto.getRandomValues.bind(webcrypto)};
  const ids = Array.from({length:100}, () => generateBrowserUUID(cryptoApi));
  for (const id of ids) assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.equal(new Set(ids).size,100);
  assert.equal(generateBrowserUUID({randomUUID:() => 'native-id'}),'native-id');
});

test('missing crypto fails clearly instead of using weak randomness', () => {
  assert.throws(() => generateBrowserUUID({}), /不支持安全随机数/);
  assert.throws(() => generateBrowserUUID(null), /不支持安全随机数/);
});

test('memory save reports missing crypto and HTTP retries preserve the operation key', async () => {
  const require = createRequire(new URL('../../../jsd-aird-web/package.json',import.meta.url));
  const {JSDOM} = require('jsdom');
  const dom = new JSDOM('<html><body></body></html>', {url:'http://192.168.1.119:82/agent/'});
  const saved = new Map();
  for (const [key,value] of Object.entries({window:dom.window,document:dom.window.document,
    navigator:dom.window.navigator,crypto:{},IS_REACT_ACT_ENVIRONMENT:true})) {
    saved.set(key,Object.getOwnPropertyDescriptor(globalThis,key));
    Object.defineProperty(globalThis,key,{value,configurable:true,writable:true});
  }
  let cleanup;
  try {
    const React = require('react');
    const ui = require('@testing-library/react'); cleanup = ui.cleanup;
    let Section;
    const keys = [];
    registerMemoryUI({effect:fn=>fn(),contextMenus:{register(){}},slots:{inject:(_name,fn)=>fn(),
      register(spec,component){if(spec.name==='settings.section')Section=component;}}}, {
      React, Switch:()=>null,
      api:async (_name,body)=> {
        if(body.action==='initialize')return {read_enabled:true,auto_enabled:false,notice_seen:true};
        if(body.action==='list')return {entries:[],next_cursor:null};
        if(body.action==='remember') {
          keys.push(body.idempotency_key);
          if(keys.length===1)throw new Error('临时故障');
          return {status:'SUCCEEDED',operation_id:'saved'};
        }
        throw new Error('Unexpected action '+body.action);
      },
    });
    const view = ui.render(React.createElement(Section));
    ui.fireEvent.click(await view.findByRole('button',{name:'添加记忆'}));
    ui.fireEvent.change(view.getByLabelText('偏好内容'),{target:{value:'请用中文回答'}});
    ui.fireEvent.click(view.getByRole('button',{name:'保存记忆'}));
    await ui.waitFor(()=>assert.ok(view.getByRole('alert').textContent.includes('不支持安全随机数')));
    assert.equal(keys.length,0);
    globalThis.crypto=dom.window.crypto;
    ui.fireEvent.click(view.getByRole('button',{name:'保存记忆'}));
    await ui.waitFor(()=>assert.ok(view.getByRole('alert').textContent.includes('临时故障')));
    ui.fireEvent.click(view.getByRole('button',{name:'保存记忆'}));
    await ui.waitFor(()=>assert.equal(keys.length,2));
    assert.equal(keys[0],keys[1]);
    assert.match(keys[0],/^[0-9a-f-]{36}$/);
    await ui.waitFor(()=>assert.equal(view.queryByLabelText('偏好内容'),null));
  } finally {
    cleanup?.();dom.window.close();
    for(const [key,descriptor] of saved) {
      if(descriptor)Object.defineProperty(globalThis,key,descriptor);else delete globalThis[key];
    }
  }
});
