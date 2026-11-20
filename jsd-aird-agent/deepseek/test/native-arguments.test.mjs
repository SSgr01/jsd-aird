import test from 'node:test';
import assert from 'node:assert/strict';
import {normalizePresentArguments,nativeArgumentsStream} from '../../enterprise-plugin/src/native-arguments.mjs';
const files=[{path:'业务记录.xlsx',description:'原值与出处'}];
const raw=JSON.stringify({files:JSON.stringify(files)}), normalized=JSON.stringify({files});

test('one exact JSON collection decode preserves paths, descriptions and other arguments',()=>{
  assert.equal(normalizePresentArguments(raw),normalized);
  for(const value of ['{',JSON.stringify({files}),JSON.stringify({files:'"[]"'}),
    JSON.stringify({files:'[]'}),JSON.stringify({files:'[{"path":1}]'}),
    JSON.stringify({files:String.raw`\n[{\"description\":\"标注\"缺失\"\",\"path\":\"业务记录.xlsx\"}]\n`}),
    JSON.stringify({files:'[{"path":"a","extra":true}]'})])
    assert.equal(normalizePresentArguments(value),value);
});

test('all stream splits yield matching live and settled arguments with the same call identity',async()=>{
  for(let split=0;split<=raw.length;split++) {
    async function* input(){
      yield {type:'block-start',index:1,blockType:'tool-call'};
      yield {type:'tool-call-delta',index:1,id:'call',name:'present',argumentsDelta:raw.slice(0,split)};
      yield {type:'tool-call-delta',index:1,id:'call',argumentsDelta:raw.slice(split)};
      yield {type:'block-end',index:1,block:{type:'tool-call',id:'call',name:'present',arguments:raw}};
      yield {type:'usage',usage:{totalTokens:12}};
      yield {type:'finish',reason:{kind:'stop'}};
    }
    const output=await Array.fromAsync(nativeArgumentsStream(input()));
    assert.equal(output.filter(x=>x.type==='tool-call-delta').map(x=>x.argumentsDelta).join(''),normalized);
    assert.deepEqual(output.find(x=>x.type==='block-end').block,{type:'tool-call',id:'call',name:'present',arguments:normalized});
    assert.equal(output.at(-2).usage.totalTokens,12);
  }
});

test('unrelated calls, invalid input and interrupted calls remain unchanged',async()=>{
  for(const name of ['present','bash'])for(const args of [raw,'{'])for(const complete of [true,false]) {
    const chunks=[{type:'tool-call-delta',index:0,id:'call',name,argumentsDelta:args},
      ...(complete?[{type:'block-end',index:0,block:{type:'tool-call',id:'call',name,arguments:args}}]:[]),
      {type:'finish',reason:{kind:complete?'stop':'aborted'}}];
    if(name==='present' && args===raw && complete)continue;
    async function* input(){yield* chunks;}
    assert.deepEqual(await Array.fromAsync(nativeArgumentsStream(input())),chunks);
  }
});

test('interleaved tool arguments remain attached to their original block and call',async()=>{
  async function* input(){
    yield {type:'tool-call-delta',index:1,id:'delivery',name:'present',argumentsDelta:raw};
    yield {type:'tool-call-delta',index:2,id:'reader',name:'read',argumentsDelta:'{"file_path":"a"}'};
    yield {type:'block-end',index:2,block:{type:'tool-call',id:'reader',name:'read',arguments:'{"file_path":"a"}'}};
    yield {type:'block-end',index:1,block:{type:'tool-call',id:'delivery',name:'present',arguments:raw}};
  }
  const output=await Array.fromAsync(nativeArgumentsStream(input()));
  assert.equal(output.find(x=>x.type==='tool-call-delta' && x.id==='reader').argumentsDelta,'{"file_path":"a"}');
  assert.equal(output.find(x=>x.type==='tool-call-delta' && x.id==='delivery').argumentsDelta,normalized);
});
