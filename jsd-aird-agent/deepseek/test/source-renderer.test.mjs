import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import * as sourceLinks from '../../enterprise-plugin/src/source-links.mjs';

test('source projection keeps native reasoning and image renderers with authorized child seats',()=>{
 const source=readFileSync(new URL('../../enterprise-plugin/src/client.js',import.meta.url),'utf8');
 const start=source.indexOf('  function registerSourceMarkdown(scope) {');
 const code=source.slice(start,source.indexOf('  function registerOriginals(scope)',start));
 const nativeAssistant=()=>{}, nativeReasoning=()=>{}, nativeImages=()=>{};
 const entries=new Map([
  ['conversation.chat.node',[{options:{key:'assistant-step'},component:nativeAssistant}]],
  ['conversation.chat.reasoning.body',[{options:{},component:nativeReasoning}]],
  ['conversation.chat.message.images',[{options:{},component:nativeImages}]],
 ]);
 const slots={
  entries:name=>entries.get(name)||[],entriesOfSlot:name=>entries.get(name)||[],
  subscribe:()=>()=>{},inject:(_name,fn)=>fn(),
  register:(options,component)=>{entries.set(options.name,[{options,component}]);return ()=>{};},
 };
 const React={useMemo:fn=>fn(),useCallback:fn=>fn};
 const h=(component,props,...children)=>({component,props,children});
 new Function('React','h','sourceLinks','location',code+';return registerSourceMarkdown;')(
  React,h,sourceLinks,{origin:'http://localhost'})({slots});
 const wrapper=entries.get('conversation.chat.node')[0];
 const calls=[];
 const block={kind:'image',file:'figure.png'};
 const rendered=wrapper.component({node:{data:{blocks:[block,{kind:'text',text:'Hello'}]}},
  renderSlot:(name,owner)=>{assert.ok(wrapper.options.children[name]);calls.push([name,owner]);},
  loadImage:'native-loader',useDisclosure:'native-disclosure'});
 assert.equal(rendered.component,nativeAssistant);
 assert.equal(rendered.props.node.data.blocks[0],block);
 assert.equal(rendered.props.loadImage,'native-loader');
 assert.equal(rendered.props.useDisclosure,'native-disclosure');
 for(const [native,seat,component] of [
  ['conversation.chat.reasoning.body','enterprise.source.reasoning',nativeReasoning],
  ['conversation.chat.message.images','enterprise.source.images',nativeImages],
 ]){
  const owner={text:'reasoning',images:['figure.png']};
  rendered.props.renderSlot(native,owner);
  assert.deepEqual(calls.at(-1),[seat,owner]);
  assert.equal(entries.get(seat)[0].component,component);
 }
 const url='http://localhost/api/v1/knowledge/documents/11111111-1111-1111-1111-111111111111/versions/22222222-2222-2222-2222-222222222222/content#page=5';
 const props={node:{data:{blocks:[block,{kind:'text',text:'实验方法 [实验方法]('+url+' "Full paper title")'}]}},renderSlot:()=>{}};
 const compact=wrapper.component(props);
 assert.equal(compact.props.className,'jsd-cited-answer');
 assert.equal(compact.children[0].component,nativeAssistant);
 assert.equal(compact.children[0].props.node.data.blocks[0],block);
 assert.equal(compact.children[0].props.node.data.blocks[1].text,'实验方法 [实验方法 · p5]('+url+')');
 assert.equal(compact.children.length,1);
 assert.equal(compact.props.onClickCapture,undefined);
 const attrs={};
 const link={textContent:'实验方法 · p5',href:url,setAttribute:(key,value)=>{attrs[key]=value;}};
 compact.props.ref({querySelectorAll:()=>[link]});
 assert.equal(link.title,'Full paper title · 第 5 页');
 assert.ok(Object.hasOwn(attrs,'data-jsd-citation'));
 assert.equal(attrs['aria-haspopup'],undefined);
});
