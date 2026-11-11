import test from 'node:test';
import assert from 'node:assert/strict';
import {normalizeOriginalLinks,shortSourceTitle,projectSourceBlocks} from '../../enterprise-plugin/src/source-links.mjs';
const origin='http://127.0.0.1:5184';
const id='11111111-1111-1111-1111-111111111111', version='22222222-2222-2222-2222-222222222222';
const url=origin+'/api/v1/knowledge/documents/'+id+'/versions/'+version+'/content';
test('same-document page shortcuts become descriptive native Markdown links',()=>{
 const input='- 总入口：'+url+'\n  - 配方与固化工艺：`#page=3`\n  - 雾度方法：`#page=4`；耐磨方法：`#page=4`';
 const actual=normalizeOriginalLinks(input,origin);
 assert.ok(actual.includes('[配方与固化工艺 · 第3页]('+url+'#page=3)'));
 assert.ok(actual.includes('[雾度方法 · 第4页]('+url+'#page=4)'));
 assert.ok(actual.includes('[耐磨方法 · 第4页]('+url+'#page=4)'));
 assert.ok(actual.includes('[论文原文]('+url+')'));
 assert.equal(normalizeOriginalLinks(actual,origin),actual);
});
test('unbound locations never borrow a previous paper across headings and source entries',()=>{
 const actual=normalizeOriginalLinks(url+'\n# 另一篇论文\n配方：`#page=3`',origin);
 assert.ok(actual.includes('第3页（来源待确认）'));
 assert.ok(!actual.includes(url+'#page=3'));
});
test('fixed versions, evidence identity, external links and fenced programs retain their destinations',()=>{
 const target=url+'#page=3&evidence=e'+'a'.repeat(24);
 const input='[原文·第3页]('+target+')\n[外部](https://example.org/paper#page=4)\n```js\nconst x="'+url+'#page=5";\n```';
 const actual=normalizeOriginalLinks(input,origin);
 assert.ok(actual.includes('[论文原文 · 第3页]('+target+')'));
 assert.ok(actual.includes('[外部](https://example.org/paper#page=4)'));
 assert.ok(actual.includes('const x="'+url+'#page=5";'));
 assert.ok(!normalizeOriginalLinks('https://untrusted.org'+new URL(url).pathname,origin).includes('[论文原文]'));
});
test('source labels remove machine filenames without inventing section names',()=>{
 assert.equal(shortSourceTitle('PMC10744861_Preparation_Nano-SiO_sub_2_sub_.pdf'),'Preparation Nano-SiO2');
});

test('adjacent citations compact by document version and retain every page and evidence target',()=>{
 const title='Re-Assemblable, Recyclable, and Self-Healing Epoxy Resin Adhesives';
 const targets=[url+'#page=5&evidence=figure',url+'#page=5&evidence=body',url+'#page=3&evidence=method',url+'#page=4&evidence=cure'];
 const labels=['Figure 1','Figure 1','实验方法','固化条件'];
 const input='Figure 1 展示合成路线。'+targets.map((target,index)=>'['+labels[index]+']('+target.replaceAll('&','\\&')+' "'+title+'")').join(' ');
 const original={kind:'text',text:input};
 const result=projectSourceBlocks([original],origin);
 assert.equal(result.blocks[0].text,'Figure 1 展示合成路线。[Figure 1 · p5]('+targets[0]+') [实验方法 · p3]('+targets[2]+') [固化条件 · p4]('+targets[3]+')');
 assert.equal(result.sources.length,1);
 assert.equal(result.sources[0].title,title);
 assert.deepEqual(result.sources[0].locations.map(place=>place.url),targets);
 assert.deepEqual(result.sources[0].locations.map(place=>place.page),[5,5,3,4]);
 assert.equal(original.text,input);
});

test('source identities span text blocks, while different content and versions stay separate',()=>{
 const other=url.replace(version,'33333333-3333-3333-3333-333333333333');
 const image={kind:'image',file:'Figure1.png'};
 const input=[{kind:'text',text:'[Paper]('+url+'#page=3)'},image,
  {kind:'text',text:'Table 2 [Table 2]('+url+'#page=5)[Figure 8]('+other+'#page=5)'}];
 const result=projectSourceBlocks(input,origin);
 assert.equal(result.sources.length,2);
 assert.equal(result.blocks[1],image);
 assert.equal(result.blocks[2].text,'Table 2 [Table 2 · p5]('+url+'#page=5) [Figure 8 · p5]('+other+'#page=5)');
 assert.equal(result.sources[0].locations.length,2);
});

test('media, mathematical and chemical notation, code, external links, and unfinished streams retain content',()=>{
 const content='![原图]('+url+'#page=5)\n\n$E=mc^2$\n\n$$\\ce{A + B ->[DMAP] C}$$\n\n```smiles\nNc1ccccc1\n```\n\n`[原文]('+url+')`\n\n[外部](https://example.org/paper)\n\n[未完成]('+url+'#page=';
 const input={kind:'text',text:content};
 const result=projectSourceBlocks([input],origin);
 assert.equal(result.blocks[0],input);
 assert.deepEqual(result.sources,[]);
});

test('same private source versions deduplicate, and identical destinations need only one location',()=>{
 const target=origin+'/api/v1/agents/private-sources/'+id+'/content?version_id='+version+'#page=2&evidence=one';
 const result=projectSourceBlocks([{kind:'text',text:'[实验方法]('+target+')[实验方法]('+target+')'}],origin);
 assert.equal(result.sources.length,1);
 assert.equal(result.sources[0].locations.length,1);
 assert.equal(result.blocks[0].text,'[实验方法 · p2]('+target+')');
});

test('legacy bold escaped citation labels with titles do not produce nested links or opaque query IDs',()=>{
 const title='The Applicability of 2-amino-4,6-diphenyl-pyridine-3-carbonitrile';
 const target=url+'#page=14&evidence=e3e054628417f9d4059a471ae';
 const input='关键证据：Table 4 表头与六行 [**\\[1\\]**]('+target.replaceAll('&','\\&')+' "'+title+'")（q61ca9e3880b6327596f1fa60、q72e58bcf7fc1dca39948e3f8）；Fig 8 图注 [**\\[1\\]**]('+url+'#page=15 "'+title+'").';
 const result=projectSourceBlocks([{kind:'text',text:input}],origin);
 assert.ok(result.blocks[0].text.includes('[The Applicability'));
 assert.ok(result.blocks[0].text.includes(' · p14]('+target+')；Fig 8 图注 '));
 assert.ok(result.blocks[0].text.includes(' · p15]('+url+'#page=15).'));
 assert.ok(!result.blocks[0].text.includes('[Table 4 · p14]('));
 assert.ok(!result.blocks[0].text.includes('[Figure 8 · p15]('));
 assert.equal(result.sources[0].title,title);
 assert.equal(result.sources[0].locations.length,2);
 assert.ok(!result.blocks[0].text.includes('q61ca'));
 assert.ok(!result.blocks[0].text.includes('\\['));
});

test('content and page tags follow explicit locations and method labels without number-only references',()=>{
 const input='S5 的氧化电位低于 S6。[Table 4]('+url+'#page=14 "Complete source")\n不同配方的响应见 Fig. 8。[Figure 8]('+url+'#page=15 "Complete source")\n固化温度为 80 °C。[实验方法]('+url+'#page=4 "Complete source")';
 const actual=projectSourceBlocks([{kind:'text',text:input}],origin).blocks[0].text;
 assert.ok(actual.includes('[Table 4 · p14]('));
 assert.ok(actual.includes('[Figure 8 · p15]('));
 assert.ok(actual.includes('[实验方法 · p4]('));
 assert.ok(!actual.includes('[1]('));
});

test('cross-page discussion never borrows a table label from a heading for a different PDF page',()=>{
 const input='## Table 1 原图（第4页）\n\nS6 会降低灵敏度。[传感器研究]('+url+'#page=9)';
 const actual=projectSourceBlocks([{kind:'text',text:input}],origin).blocks[0].text;
 assert.ok(actual.includes('[传感器研究 · p9]('));
 assert.ok(!actual.includes('[Table 1 · p9]('));
});

test('labels are domain independent and never inferred from nearby claims or same-page headings',()=>{
 const input='## Table 4 第14页\n\n氧化电位、灵敏度、实验方法。[**\\[1\\]**]('+url+'#page=14 "Actual source title")\n[卫星轨道拟合残差]('+url+'#page=15 "Actual source title")';
 const actual=projectSourceBlocks([{kind:'text',text:input}],origin).blocks[0].text;
 assert.ok(actual.includes('[Actual source title · p14]('));
 assert.ok(actual.includes('[卫星轨道拟合残差 · p15]('));
 assert.ok(!actual.includes('[Table 4 · p14]('));
 assert.ok(!actual.includes('[实验方法 · p14]('));
});

test('same-document references to different pages retain separate direct navigation',()=>{
 const input='[Explicit source]('+url+'#page=3)[Explicit source]('+url+'#page=4)[Explicit source]('+url+'#page=4)';
 const actual=projectSourceBlocks([{kind:'text',text:input}],origin).blocks[0].text;
 assert.equal(actual,'[Explicit source · p3]('+url+'#page=3) [Explicit source · p4]('+url+'#page=4)');
});

test('ordinary scientific parentheses, external quoted links, and diagnostic code are untouched',()=>{
 const external='[**\\[1\\]**](https://example.org/paper "External title")';
 const input='结果 [论文]('+url+'#page=14)（浓度 0.1%/1%）\n'+external+'\n```text\nq61ca9e3880b6327596f1fa60\n```';
 const result=projectSourceBlocks([{kind:'text',text:input}],origin);
 assert.ok(result.blocks[0].text.includes('（浓度 0.1%/1%）'));
 assert.ok(result.blocks[0].text.includes(external));
 assert.ok(result.blocks[0].text.includes('```text\nq61ca9e3880b6327596f1fa60\n```'));
});
