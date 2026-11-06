import test from 'node:test';
import assert from 'node:assert/strict';
import {citationText,citationStream} from '../../enterprise-plugin/src/citation-stream.mjs';
const id='e'+'a'.repeat(24), links={[id]:{label:'原文·第3页',url:'https://example.test/api/original#page=3'}};
const original='事实 [E:'+id+']。';

test('every citation split produces the same native Markdown and settled block',async()=>{
 for(let offset=0;offset<=original.length;offset++){
  async function* input(){
   yield {type:'text-delta',index:0,text:original.slice(0,offset)};
   yield {type:'text-delta',index:0,text:original.slice(offset)};
   yield {type:'block-end',index:0,block:{type:'text',text:original}};
   yield {type:'usage',usage:{totalTokens:10}};
  }
  const output=await Array.fromAsync(citationStream(input(),links));
  assert.equal(output.filter(x=>x.type==='text-delta').map(x=>x.text).join(''),citationText(original,links));
  assert.equal(output.find(x=>x.type==='block-end').block.text,citationText(original,links));
  assert.equal(output.at(-1).usage.totalTokens,10);
 }
});

test('unknown handles and raw tool arguments remain unchanged',async()=>{
 const chunk={type:'tool-call-delta',index:1,argumentsDelta:original};
 async function* input(){yield chunk;}
 assert.deepEqual(await Array.fromAsync(citationStream(input(),links)),[chunk]);
 assert.equal(citationText('[E:e'+'b'.repeat(24)+']',links),'[E:e'+'b'.repeat(24)+']');
});
