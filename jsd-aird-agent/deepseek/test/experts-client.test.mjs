import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {registerExpertsUI} from '../../enterprise-plugin/src/experts-client.mjs';

test('composer and current list row clear the expert independently without nested buttons or auto sending',async()=>{
  const require=createRequire(new URL('../../../jsd-aird-web/package.json',import.meta.url));
  const {JSDOM}=require('jsdom'),dom=new JSDOM('<html><body></body></html>');
  const saved=new Map();
  for(const [key,value] of Object.entries({window:dom.window,document:dom.window.document,navigator:dom.window.navigator,IS_REACT_ACT_ENVIRONMENT:true})) {
    saved.set(key,Object.getOwnPropertyDescriptor(globalThis,key));Object.defineProperty(globalThis,key,{value,configurable:true,writable:true});
  }
  let cleanup;
  try {
    const React=require('react'),testing=require('@testing-library/react');cleanup=testing.cleanup;
    const h=React.createElement,expert={id:'customer-assistant',name:'客户跟进助理',enabled:true,capabilities:[]};
    const catalog=[expert,...Array.from({length:6},(_,i)=>({id:'expert-'+i,name:'专家'+i,enabled:true,capabilities:[]}))];
    const calls=[],opened=[],entries=[];
    let released=0;
    const scope={slots:{inject:(_name,fn)=>fn(),register:(options,component)=>{entries.push({options,component});}},
      sessions:{retain:()=>({ready:Promise.resolve({}),release:()=>released++})},
      uiWorkspace:{openSession:id=>opened.push(id)},layout:{selectPanel:()=>{}},conversation:{input:{requestDraftInitialization:()=>assert.fail('must not send or fill a draft')}}};
    const api=async(_route,body)=>{calls.push(body);return body.action==='session'?{expert}:body.action==='list'?{experts:catalog,pinned:['expert-0'],recent:[expert.id,'expert-1']}:{session_id:'ordinary-session'};};
    const Menu=({anchor,children,open})=>h(React.Fragment,null,anchor,open&&h('div',{role:'menu'},children));
    const MenuItemButton=({children,onSelect,disabled})=>h('button',{role:'menuitem',disabled,onClick:onSelect},children);
    registerExpertsUI(scope,{React,api,Menu,MenuItemButton,Modal:()=>null});
    const Selector=entries.find(e=>e.options.id==='enterprise-expert-selector').component;
    const view=testing.render(h(Selector,{sessionId:'expert-session'}));
    await testing.waitFor(()=>assert.ok(view.getByRole('button',{name:'取消专家选择'})));
    assert.equal(view.getByRole('button',{name:'取消专家选择'}).hasAttribute('aria-busy'),false,'clear must not match the native send/stop styling contract');
    assert.equal(view.container.querySelector('button button'),null);
    await testing.act(async()=>testing.fireEvent.click(view.getByRole('button',{name:'取消专家选择'})));
    assert.equal(calls.filter(b=>b.action==='general').length,1);
    assert.deepEqual(opened,['ordinary-session']);
    assert.equal(released,1);
    await testing.act(async()=>testing.fireEvent.click(view.getByRole('button',{name:/当前专家/})));
    await testing.waitFor(()=>assert.ok(view.getByRole('menuitem',{name:'取消选择客户跟进助理'})));
    await testing.waitFor(()=>assert.ok(view.getByRole('menuitem',{name:'专家5'})));
    for(const row of catalog)assert.ok(view.getByRole('menuitem',{name:new RegExp('^'+row.name)}));
    assert.equal(view.queryByText('不选择专家'),null);
    assert.equal(view.container.querySelector('button button'),null);
    await testing.act(async()=>testing.fireEvent.click(view.getByRole('menuitem',{name:'取消选择客户跟进助理'})));
    assert.equal(calls.filter(b=>b.action==='general').length,2);
    assert.equal(calls.filter(b=>b.action==='summon').length,0);
    assert.equal(released,2);
    assert.deepEqual(opened,['ordinary-session','ordinary-session']);
  } finally {
    cleanup?.();dom.window.close();for(const [key,value] of saved){if(value)Object.defineProperty(globalThis,key,value);else delete globalThis[key];}
  }
});
