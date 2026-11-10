import { gunzipSync, brotliDecompressSync, inflateSync } from 'node:zlib';
import { readFileSync } from 'node:fs';

export const productName = 'AI工作台 · 杰事达';
const englishName = 'AI Workbench · JSD';
// The business application owns the brand asset; the gateway serves the same bytes.
export const brandIcon = readFileSync(new URL('../../../jsd-aird-web/public/favicon.svg', import.meta.url), 'utf8');
const icons = new Set(['/favicon.svg','/favicon-dark.svg','/apple-touch-icon.png','/icon-192.png','/icon-512.png','/icon-maskable-512.png']);
const chinese = value => /\p{Script=Han}/u.test(value) && !/\p{Script=Hiragana}|\p{Script=Katakana}/u.test(value);
const branded = /\bDSH\b|DeepSeek Harness|\bdsh (?:web|plugin)\b/;

const copy = {
  'brand.localBuild': [productName, englishName],
  'hero.headline': ['让研发更有依据', 'Research with evidence'],
  'hero.preview': ['AI 助手', 'AI Assistant'],
  sectionIntro: ['使用研发助手处理资料、分析问题，并在当前权限内联动企业数据。', 'Use the research assistant to read documents, analyze questions and work with authorized enterprise data.'],
  keyHelp: ['优先使用已保存的凭据。环境后备顺序：{env}。凭据由企业服务端管理。', 'Saved credentials take priority. Environment fallback order: {env}. Credentials are managed by the enterprise server.'],
  welcomeBody: ['在 AI工作台中阅读文献、分析实验、查询项目并生成交付文件。资料访问与业务操作遵循当前企业权限。', 'Read literature, analyze experiments, query projects and deliver files. Data access and business actions follow your current enterprise permissions.'],
  'error.sessionInUse': ['当前会话正由另一个连接使用，请等待任务结束或关闭其他连接后重试。', 'This session is in use by another connection. Wait for its task to finish or close that connection and try again.'],
  'error.application-origin': ['请使用企业导航打开系统页面。', 'Use enterprise navigation to open application pages.'],
  unavailable: ['Office 预览暂时不可用，请稍后重试或下载原文件。', 'Office preview is temporarily unavailable. Try again later or download the original file.'],
};

/** Presentation overlay for the pinned native client. Keys, module IDs and
 * protocols stay native. Business responses and user-authored content never
 * enter this function. No DOM replacement or private locale state is used. */
export function brandScript(source) {
  // Native client bundles use JSON string literals. Only known locale values
  // and human-readable branded literals are adapted, never identifiers/URLs.
  const properties = /^([\t ]*(?:"([^"\r\n]+)"|([A-Za-z_$][\w$]*))[\t ]*:[\t ]*)("(?:\\.|[^"\\\r\n])*")/gm;
  source = source.replace(properties, (match, prefix, quotedKey, bareKey, literal) => {
    const key = quotedKey || bareKey, choices = copy[key];
    if (key === 'browser.home' && ['"主目录"', '"Home"'].includes(literal)) return prefix + JSON.stringify('/workspaces');
    if (!choices) return match;
    let value;
    try { value = JSON.parse(literal); } catch { return match; }
    if (!['brand.localBuild','hero.headline','hero.preview'].includes(key)
        && !branded.test(value) && !(key === 'keyHelp' && value.includes('$DSH_HOME'))) return match;
    // Keep other native translations; their product names are adapted below.
    if (!chinese(value) && /\p{Script=Han}|\p{Script=Cyrillic}/u.test(value)) return match;
    return prefix + JSON.stringify(choices[chinese(value) ? 0 : 1]);
  });
  source = source.replace(/"(?:\\.|[^"\\\r\n])*"/g, literal => {
    if (!branded.test(literal)) return literal;
    let value;
    try { value = JSON.parse(literal); } catch { return literal; }
    if (!branded.test(value) || /(?:https?:|dsh-resource:|@deepseek-ai\/|\$DSH_HOME)/.test(value)) return literal;
    return JSON.stringify(value.replace(/DeepSeek Harness|\bDSH\b/g, chinese(value) ? 'AI工作台' : englishName)
      .replace(/\bdsh web\b/g, chinese(value) ? '网页工作台' : 'Web workspace')
      .replace(/\bdsh plugin\b/g, chinese(value) ? '插件管理' : 'plugin management'));
  });
  // Keep the native settings launcher, dialog, shortcuts and accessibility text.
  // Rename only this pinned module's known chrome strings, including combo responses.
  return source.split(/(?=window\.__ModuleLoader__\.load\(\{)/).map(module => {
    if (!/^window\.__ModuleLoader__\.load\(\{\s*(?:id|"id")\s*:\s*"@deepseek-ai\/dsh-client-ui-settings-general"\s*,/.test(module)) return module;
    return module.replace(properties, (match, prefix, quotedKey, bareKey, literal) => {
      const key = quotedKey || bareKey;
      if (!['trigger','title','shortcut.open'].includes(key)) return match;
      let value;
      try { value = JSON.parse(literal); } catch { return match; }
      if (['trigger','title'].includes(key) && ['设置','Settings'].includes(value))
        return prefix + JSON.stringify(value === '设置' ? '工作台设置' : 'Workbench settings');
      if (key === 'shortcut.open' && ['打开设置','Open settings'].includes(value))
        return prefix + JSON.stringify(value === '打开设置' ? '打开工作台设置' : 'Open workbench settings');
      return match;
    });
  }).join('');
}

function pluginScriptTarget(target) {
  const {pathname, search} = new URL(target, 'http://local');
  // The native client loads its locale modules through a combined script URL.
  // Its pathname is /plugins/; module filenames live after the second '?'.
  return /^\/plugins\/.+\.js$/.test(pathname)
    || (pathname === '/plugins/' && search.startsWith('??')
      && search.slice(2).split('&', 1)[0].split(',').every(module => /\.js$/.test(module)));
}

const bootScriptTarget = path => /^\/assets\/index-[\w-]+\.js$/.test(path);

export function staticBrandingTarget(target) {
  const path = new URL(target, 'http://local').pathname;
  return path === '/' || path === '/index.html' || path === '/manifest.webmanifest'
    || icons.has(path) || pluginScriptTarget(target) || bootScriptTarget(path);
}

/** Adapt only successful native static resources; all API/file bytes stream unchanged. */
export async function brandStaticResponse(target, upstream, headers) {
  if (upstream.statusCode !== 200 || !staticBrandingTarget(target)) return;
  const path = new URL(target, 'http://local').pathname;
  const type = String(headers['content-type'] || '');
  const script = (pluginScriptTarget(target) || bootScriptTarget(path)) && /(?:javascript|ecmascript)/i.test(type);
  const html = ['/', '/index.html'].includes(path) && /text\/html/i.test(type);
  if (!script && !html && path !== '/manifest.webmanifest' && !icons.has(path)) return;
  const chunks = []; let size = 0;
  const maximum = 32 * 1024 * 1024;
  for await (const chunk of upstream) {
    size += chunk.length;
    if (size > maximum) throw new Error('UI_RESOURCE_TOO_LARGE');
    chunks.push(chunk);
  }
  let bytes = Buffer.concat(chunks);
  const encoding = headers['content-encoding'];
  const decompress = {gzip:gunzipSync, br:brotliDecompressSync, deflate:inflateSync}[encoding];
  if (decompress) bytes = decompress(bytes, {maxOutputLength:maximum});
  else if (encoding && encoding !== 'identity') throw new Error('UI_ENCODING_UNSUPPORTED');
  let body;
  if (icons.has(path)) {
    body = brandIcon;
    headers['content-type'] = 'image/svg+xml; charset=utf-8';
  } else if (path === '/manifest.webmanifest') {
    const manifest = JSON.parse(bytes.toString('utf8'));
    body = JSON.stringify({...manifest, name:productName, short_name:'AI工作台',
      background_color:'#faf9f8', theme_color:'#766381',
      icons:[{src:'favicon.svg', sizes:'any', type:'image/svg+xml', purpose:'any maskable'}]});
  } else if (html) {
    body = bytes.toString('utf8').replace(/<title>[^<]*<\/title>/i, '<title>'+productName+'</title>')
      .replace(/(<meta\b[^>]*name="apple-mobile-web-app-title"[^>]*content=")[^"]*(")/i, '$1'+productName+'$2')
      .replace(/(rel="apple-touch-icon"[^>]*href=")[^"]*(")/i, '$1favicon.svg$2');
  } else if (bootScriptTarget(path)) {
    // The native boot page precedes plugins; adapt only its visible copy.
    body = bytes.toString('utf8').replaceAll('"HARNESS"', '"AI工作台"')
      .replaceAll('"Loading plugins…"', '"正在打开工作台…"');
  } else body = brandScript(bytes.toString('utf8'));
  const result = Buffer.from(body);
  for (const key of ['content-encoding','content-length','transfer-encoding','etag','last-modified']) delete headers[key];
  headers['content-length'] = String(result.length);
  return result;
}
