import test from 'node:test';
import assert from 'node:assert/strict';
import {researchText,researchWordingStream} from '../../enterprise-plugin/src/research-wording.mjs';

test('business narrative keeps template names and original source text',()=>{
  const text='交叉验证与模拟测试，验收结果\n测试实验\n> 原文：模拟测试\n```js\nconst 验证 = 1;\n```\n[原文测试](https://example.org/验证) 与 `原始验证`';
  assert.equal(researchText(text),'交叉评估与研发参考测定，交付检查结果\n测试实验\n> 原文：模拟测试\n```js\nconst 验证 = 1;\n```\n[原文测试](https://example.org/验证) 与 `原始验证`');
});

test('streamed and completed text agree across split terms without changing other blocks',async()=>{
  const reasoning={type:'reasoning-delta',index:1,text:'验证原文'};
  const tool={type:'block-end',index:2,block:{type:'tool-call',arguments:{value:'测试'}}};
  async function* input(){yield reasoning;yield {type:'text-delta',index:0,text:'交叉验'};yield {type:'text-delta',index:0,text:'证\n实测结'};yield {type:'text-delta',index:0,text:'果'};yield {type:'block-end',index:0,block:{type:'text',text:'交叉验证\n实测结果'}};yield tool;}
  const output=[];for await(const chunk of researchWordingStream(input()))output.push(chunk);
  assert.equal(output.filter(item=>item.type==='text-delta').map(item=>item.text).join(''),'交叉评估\n实测结果');
  assert.equal(output.find(item=>item.type==='block-end'&&item.index===0).block.text,'交叉评估\n实测结果');
  assert.deepEqual(output[0],reasoning);assert.deepEqual(output.at(-1),tool);
});
