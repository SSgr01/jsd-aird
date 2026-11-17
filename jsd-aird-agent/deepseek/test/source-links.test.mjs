import test from 'node:test';
import assert from 'node:assert/strict';
import {normalizeOriginalLinks,shortSourceTitle,projectSourceBlocks,describeSourceLink,originalAddress} from '../../enterprise-plugin/src/source-links.mjs';
const origin='http://127.0.0.1:5184';
const id='11111111-1111-1111-1111-111111111111', version='22222222-2222-2222-2222-222222222222';
const url=origin+'/api/v1/knowledge/documents/'+id+'/versions/'+version+'/content';

test('spectrum page citations keep their pinned reference and exact original page',()=>{
 const path='/api/v1/agents/business/images/SPECTRUM/'+id+'?ref='+encodeURIComponent(JSON.stringify({resource_type:'SPECTRUM',resource_id:id,revision:'pinned'}))+'&page=2#page=2';
 const target=origin+path;
 assert.equal(originalAddress(path,origin).href,target);
 const result=projectSourceBlocks([{kind:'text',text:'[样品 A · 第2页]('+target+')'}],origin);
 assert.equal(result.sources.length,1);
 assert.equal(result.sources[0].locations[0].url,target);
 assert.equal(result.sources[0].locations[0].page,2);
 assert.equal(originalAddress('https://untrusted.example'+path,origin),null);
 assert.equal(originalAddress(path.replace('&page=2','&page=0'),origin),null);
});
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

test('scientific parentheses and diagnostic code stay intact; external citations use their supplied title',()=>{
 const external='[**\\[1\\]**](https://example.org/paper "External title")';
 const input='结果 [论文]('+url+'#page=14)（浓度 0.1%/1%）\n'+external+'\n```text\nq61ca9e3880b6327596f1fa60\n```';
 const result=projectSourceBlocks([{kind:'text',text:input}],origin);
 assert.ok(result.blocks[0].text.includes('（浓度 0.1%/1%）'));
 assert.ok(result.blocks[0].text.includes('[External title](https://example.org/paper)'));
 assert.equal(result.links[0].title,'External title');
 assert.ok(result.blocks[0].text.includes('```text\nq61ca9e3880b6327596f1fa60\n```'));
});

test('website citations shorten raw and auto URLs without changing destinations or nearby punctuation',()=>{
 const target='https://time.is/Shanghai?lang=zh#clock';
 const input='当前时间（'+target+'）。[日历](https://example.org/calendar "日历来源") <https://www.example.net/path?q=1&b=2>';
 const result=projectSourceBlocks([{kind:'text',text:input}],origin);
 assert.equal(result.blocks[0].text,'当前时间[time.is]('+target+')。[日历](https://example.org/calendar) [example.net](https://www.example.net/path?q=1&b=2)');
 assert.deepEqual(new Set(result.links.map(link=>link.url)),new Set([target,'https://example.org/calendar','https://www.example.net/path?q=1&b=2']));
 assert.equal(result.links.find(link=>link.url==='https://example.org/calendar').title,'日历来源');
});

test('project names resolve only for exact same-origin project routes with a readable fallback',()=>{
 const target=origin+'/projects/'+id+'?tab=overview#summary';
 const endpoint='/api/v1/projects/'+id;
 const input='项目：'+target+'\n[查看项目](/projects/'+id+')';
 const initial=projectSourceBlocks([{kind:'text',text:input}],origin);
 assert.ok(initial.blocks[0].text.includes('[项目详情]('+target+')'));
 assert.equal(initial.links[0].endpoint,endpoint);
 const names=new Map([[endpoint,'澄禾电子 [UV胶] *配方*']]);
 const result=projectSourceBlocks([{kind:'text',text:input}],origin,names);
 assert.ok(result.blocks[0].text.includes('[项目 · 澄禾电子 \\[UV胶\\] \\*配方\\*]('+target+')'));
 assert.equal(result.links[0].title,'澄禾电子 [UV胶] *配方*');
 assert.equal(result.links[0].url,target);
 assert.equal(describeSourceLink('', 'https://untrusted.org/projects/'+id,origin).kind,'web');
 assert.equal(describeSourceLink('', 'http://127.0.0.1:9999/projects/'+id,origin).endpoint,undefined);
 assert.equal(describeSourceLink('', origin+'/projects/'+id+'/other',origin),null);
 assert.equal(describeSourceLink('', 'javascript:alert(1)',origin),null);
 assert.equal(describeSourceLink('', origin+'/api/v1/files/'+id,origin),null);
});

test('code, images, reference definitions and unfinished links retain their native content',()=>{
 const target=origin+'/projects/'+id;
 const input='`'+target+'`\n```text\nhttps://time.is\n```\n![项目截图]('+target+')\n[时间][clock]\n[clock]: https://time.is "时间"\n[项目]('+target;
 const result=projectSourceBlocks([{kind:'text',text:input}],origin);
 assert.equal(result.blocks[0].text,input);
 assert.equal(result.links.length,0);
});

test('paper title truncation and page labels remain unchanged alongside website and project links',()=>{
 const title='Re-Assemblable, Recyclable, and Self-Healing Epoxy Resin Adhesives';
 const text='['+title+']('+url+'#page=5) ['+title+']('+url+'#page=3)';
 const before=projectSourceBlocks([{kind:'text',text}],origin);
 const mixed=projectSourceBlocks([{kind:'text',text:text+' https://time.is'}],origin);
 assert.ok(mixed.blocks[0].text.startsWith(before.blocks[0].text));
 assert.ok(before.blocks[0].text.includes('[Re-Assemblable, Recyclable,… · p5]('));
 assert.deepEqual(mixed.sources,before.sources);
});

test('source-only parentheses disappear without removing scientific or descriptive parentheses',()=>{
 const input='时间（[time.is](https://time.is/)），也见 ([日历](https://example.org/calendar), [原文]('+url+'#page=3))。\n浓度（0.1%/1%）；说明（参见 [日历](https://example.org/calendar)）。`(https://time.is/)`';
 const actual=projectSourceBlocks([{kind:'text',text:input}],origin).blocks[0].text;
 assert.ok(actual.startsWith('时间[time.is](https://time.is/)，也见 [日历](https://example.org/calendar), [内容标签缺失 · p3]('+url+'#page=3)。'));
 assert.ok(actual.includes('浓度（0.1%/1%）'));
 assert.ok(actual.includes('说明（参见 [日历](https://example.org/calendar)）'));
 assert.ok(actual.endsWith('`(https://time.is/)`'));
});

test('balanced URL parentheses and Markdown-escaped destinations retain complete navigation targets',()=>{
 const target='https://example.org/paper_(2026)?a=1&b=2';
 const input='来源（[论文网页]('+target.replaceAll('&','\\&')+')），另见 '+target+'。';
 const result=projectSourceBlocks([{kind:'text',text:input}],origin);
 assert.equal(result.blocks[0].text,'来源[论文网页]('+target.replaceAll('&','\\&')+')，另见 [example.org]('+target+')。');
 assert.equal(result.links.length,1);
 assert.equal(result.links[0].url,target);
});
