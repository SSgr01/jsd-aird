import test from 'node:test';
import assert from 'node:assert/strict';
import {citationText,citationStream} from '../../enterprise-plugin/src/citation-stream.mjs';
const id='e'+'a'.repeat(24), links={[id]:{label:'原文·第3页',url:'https://example.test/api/original#page=3'}};
const original='事实 [E:'+id+']。';

test('every citation split produces the same native Markdown and settled block',async()=>{
 for(const text of [original,original.replace('[E:','〔E:').replace(']','〕')]) {
 for(let offset=0;offset<=text.length;offset++){
  async function* input(){
   yield {type:'text-delta',index:0,text:text.slice(0,offset)};
   yield {type:'text-delta',index:0,text:text.slice(offset)};
   yield {type:'block-end',index:0,block:{type:'text',text}};
   yield {type:'usage',usage:{totalTokens:10}};
  }
  const output=await Array.fromAsync(citationStream(input(),links));
  assert.equal(output.filter(x=>x.type==='text-delta').map(x=>x.text).join(''),citationText(original,links));
  assert.equal(output.find(x=>x.type==='block-end').block.text,citationText(original,links));
  assert.equal(output.at(-1).usage.totalTokens,10);
 }
 }
});

test('unknown handles and raw tool arguments remain unchanged',async()=>{
 const chunk={type:'tool-call-delta',index:1,argumentsDelta:original};
 async function* input(){yield chunk;}
 assert.deepEqual(await Array.fromAsync(citationStream(input(),links)),[chunk]);
 assert.equal(citationText('[E:e'+'b'.repeat(24)+']',links),'[E:e'+'b'.repeat(24)+']');
 assert.equal(citationText('〔E:e'+'b'.repeat(24)+'〕',links),'〔E:e'+'b'.repeat(24)+'〕');
});

test('exact quote aliases use their authoritative evidence link across every stream split',async()=>{
 const qid='q'+'c'.repeat(24), aliases={...links,[qid]:{...links[id],evidence_id:id}};
 const text='事实 [E:'+qid+']。', expected=citationText(original,links);
 for(let offset=0;offset<=text.length;offset++){
  async function* input(){
   yield {type:'text-delta',index:0,text:text.slice(0,offset)};
   yield {type:'text-delta',index:0,text:text.slice(offset)};
   yield {type:'block-end',index:0,block:{type:'text',text}};
  }
  const output=await Array.fromAsync(citationStream(input(),aliases));
  assert.equal(output.filter(x=>x.type==='text-delta').map(x=>x.text).join(''),expected);
  assert.equal(output.at(-1).block.text,expected);
 }
 const unknown='[E:q'+'d'.repeat(24)+']';
 assert.equal(citationText(unknown,aliases),unknown);
});

test('known citation lists and annotations render across every split while mixed unknown lists remain intact',async()=>{
 const qid='q'+'c'.repeat(24), aliases={...links,[qid]:{...links[id],evidence_id:id}};
 for(const text of ['事实 [E:'+id+'、E:'+qid+']。','事实 〔 E: '+qid+'，E:'+id+'〕。','事实 [E:'+qid+' 所在段落]。']) {
  const expected=citationText(text,aliases);
  assert.doesNotMatch(expected,/\[E:|〔/);
  for(let offset=0;offset<=text.length;offset++) {
   async function* input(){
    yield {type:'text-delta',index:0,text:text.slice(0,offset)};
    yield {type:'text-delta',index:0,text:text.slice(offset)};
    yield {type:'block-end',index:0,block:{type:'text',text}};
   }
   const output=await Array.fromAsync(citationStream(input(),aliases));
   assert.equal(output.filter(x=>x.type==='text-delta').map(x=>x.text).join(''),expected);
   assert.equal(output.at(-1).block.text,expected);
  }
 }
 const invalid='[E:'+id+'、E:unknown]';
 assert.equal(citationText(invalid,aliases),invalid);
});
