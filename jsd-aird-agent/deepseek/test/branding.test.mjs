import test from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { gzipSync } from 'node:zlib';
import { runInNewContext } from 'node:vm';
import { readFileSync } from 'node:fs';
import { brandIcon, brandScript, brandStaticResponse, productName, staticBrandingTarget } from '../src/branding.mjs';

const response = body => Object.assign(Readable.from([Buffer.from(body)]),{statusCode:200});

test('native locale adaptation preserves executable code, model names and protocol identifiers', () => {
  const input = `const copy = {
    "brand.localBuild": "DSH 本地构建",
    "hero.headline": "探索未至之境",
    "hero.preview": "预览版",
    keyHelp: "来源：{env}，$DSH_HOME/.env",
    sectionIntro: "选择 Agent 的工具，扩展 DSH 的能力。",
    "error.sessionInUse": "请退出其他 DSH 后重试。",
    unavailable: "请在运行 DeepSeek Harness 的主机上启用文档预览服务。",
    guide: ["A plugin adds capabilities to DSH. Use dsh web or dsh plugin."].join("\\n"),
    module: "@deepseek-ai/dsh-client-ui-layout", resource: "dsh-resource://file/session/one",
    model: "DeepSeek-V4.1-Flash", unrelated: "No changes",
  }; copy`;
  const actual = runInNewContext(brandScript(input));
  assert.equal(actual['brand.localBuild'],productName);
  assert.equal(actual['hero.headline'],'让研发更有依据');
  assert.match(actual.keyHelp,/\{env\}/);
  assert.equal(actual.module,'@deepseek-ai/dsh-client-ui-layout');
  assert.equal(actual.resource,'dsh-resource://file/session/one');
  assert.equal(actual.model,'DeepSeek-V4.1-Flash');
  for (const key of ['sectionIntro','error.sessionInUse','unavailable','guide','keyHelp'])
    assert.doesNotMatch(actual[key],/DSH|DeepSeek Harness|\bdsh (?:web|plugin)\b/);

  const module = id => `window.__ModuleLoader__.load({id:"${id}",factory:()=>({
    "trigger": "设置",
    "title": "Settings",
    "shortcut.open": "打开设置",
    "connection.error": "连接异常",
    "versionDetail": "{label}\\xA0: {version}",
  })});`;
  const entries = [];
  runInNewContext(brandScript(module('@deepseek-ai/dsh-client-ui-settings-general')+module('other-plugin')),
    {window:{__ModuleLoader__:{load:entry=>entries.push(entry.factory())}}});
  assert.equal(entries[0].trigger,'工作台设置');
  assert.equal(entries[0].title,'Workbench settings');
  assert.equal(entries[0]['shortcut.open'],'打开工作台设置');
  assert.equal(entries[0]['connection.error'],'连接异常');
  assert.equal(entries[1].trigger,'设置');
  assert.equal(entries[1].title,'Settings');
});

test('only static UI resources are adapted; API, files, sources and source maps keep their bytes', async () => {
  for (const target of ['/api/session/page','/api/workspaceFiles/readBytes','/business/resource',
    '/plugins/native.js.map','/assets/index-native.js.map','/assets/other.js','/uploads/document.js','/api/v1/agents/artifacts/file']) {
    assert.equal(staticBrandingTarget(target),false);
    const upstream = response(Buffer.from('user data: DSH'));
    assert.equal(await brandStaticResponse(target,upstream,{'content-type':'application/javascript'}),undefined);
    assert.equal(upstream.readableDidRead,false);
  }
  const html = '<head><title>DSH Local Build</title><meta name="apple-mobile-web-app-title" content="DSH"></head>';
  const headers = {'content-type':'text/html','content-encoding':'gzip','transfer-encoding':'chunked',etag:'original'};
  const bytes = await brandStaticResponse('/',response(gzipSync(html)),headers);
  assert.match(bytes.toString(),new RegExp(productName));
  assert.doesNotMatch(bytes.toString(),/DSH/);
  assert.equal(headers.etag,undefined);
  assert.equal(headers['content-encoding'],undefined);
  assert.equal(headers['transfer-encoding'],undefined);
  assert.equal(Number(headers['content-length']),bytes.length);
  const javascript = 'const dictionary = {\n "brand.localBuild": "DSH Local Build"\n};';
  const script = await brandStaticResponse('/plugins/combo.js?rev=abc',response(javascript),{'content-type':'text/javascript'});
  assert.match(script.toString(),/AI Workbench · JSD/);
  const combined = '/plugins/??@deepseek-ai/dsh-client-ui-layout/client.js,@deepseek-ai/dsh-client-ui-session/client.js&rev=abc';
  assert.equal(staticBrandingTarget(combined),true);
  const combinedScript = await brandStaticResponse(combined,response(javascript),{'content-type':'text/javascript'});
  assert.match(combinedScript.toString(),/AI Workbench · JSD/);
  assert.equal(staticBrandingTarget('/plugins/??@deepseek-ai/dsh-client-ui-layout/client.js.map&rev=abc'),false);
  const boot = await brandStaticResponse('/assets/index-CnyQSzND.js',response('const copy = ["HARNESS", "Loading plugins…", "dsh-resource://file"]; copy'),{'content-type':'text/javascript'});
  const bootCopy = runInNewContext(boot.toString());
  assert.equal(bootCopy[0],'AI工作台');
  assert.equal(bootCopy[1],'正在打开工作台…');
  assert.equal(bootCopy[2],'dsh-resource://file');
  const manifest = JSON.parse(await brandStaticResponse('/manifest.webmanifest',response('{"name":"DeepSeek Harness"}'),{}));
  assert.equal(manifest.name,productName);
  assert.equal(manifest.short_name,'AI工作台');
  assert.equal(manifest.icons[0].src,'favicon.svg');
  const icon = await brandStaticResponse('/favicon.svg',response('upstream icon'),{'content-type':'image/svg+xml'});
  assert.equal(icon.toString(),brandIcon);
  assert.equal(brandIcon,readFileSync(new URL('../../../jsd-aird-web/public/favicon.svg',import.meta.url),'utf8'));
});
