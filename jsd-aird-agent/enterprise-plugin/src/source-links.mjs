/** Presentation only: retain immutable destinations and use the native Markdown renderer. */
export function shortSourceTitle(value = '') {
  return String(value).replace(/\.pdf$/i,'').replace(/^(?:PMC\d+|W\d+)[_\s-]+/i,'')
    .replace(/_?sub_?(\d+)_?sub_?/gi,'$1').replace(/[_\s]+/g,' ').trim().slice(0,64) || '论文';
}
export function originalAddress(value, origin) {
  try {
    const url=new URL(String(value).replace(/\\([&()])/g,'$1'),origin), id='[0-9a-f-]{36}';
    if(url.origin!==new URL(origin).origin) return null;
    if(new RegExp('^/api/v1/knowledge/documents/'+id+'/versions/'+id+'/content$').test(url.pathname)) return url;
    if(new RegExp('^/api/v1/agents/private-sources/'+id+'/content$').test(url.pathname)
      && new RegExp('^'+id+'$').test(url.searchParams.get('version_id')||'')) return url;
  } catch {}
  return null;
}
function labelText(value) {return String(value).replace(/[\[\]`\r\n]/g,' ').trim();}
export function sourceLinkLabel(label, url) {
  const page=new URLSearchParams(url.hash.slice(1)).get('page');
  let topic=labelText(label).replace(/(?:\s*[·・]\s*)?(?:原文\s*[·・]?\s*)?第\s*\d+\s*页\s*$/,'').trim();
  if(!topic || /^(?:https?:|\/api\/|原文$|总入口$|同上链接$)/.test(topic)) topic='论文原文';
  topic=shortSourceTitle(topic);
  return topic+(page&&/^[1-9]\d*$/.test(page)?' · 第'+page+'页':'');
}
export function normalizeOriginalLinks(text, origin='http://localhost', renderLink, renderOtherLink) {
  let base=null, fence=false;
  const address=String.raw`(?:(?:https?:\/\/|\/(?:api\/v1|projects)\/)(?:\\[^\r\n]|[^\s<>\x60\x00()\\]|\((?:[^()\s]|\([^()\s]*\))*\))+)`;
  return String(text).split('\n').map(line=>{
    if(/^\s*(```|~~~)/.test(line)){fence=!fence;return line;}
    if(fence) return line;
    if(renderOtherLink && /^\s{0,3}\[(?:\\.|[^\]\\])+\]:/.test(line))return line;
    if(/^#{1,6}\s/.test(line)) base=null;
    // A new top-level literature entry starts a different source scope.
    if(/^[-*]\s/.test(line)&&!/#page=/.test(line)) base=null;
    const protectedLinks=[];
    const raw=value=>{const token='\u0000SOURCE'+protectedLinks.length+'\u0000';protectedLinks.push(value);return token;};
    const save=(label,url,title)=>raw(renderLink ? renderLink(label,url,title) : '['+labelText(label)+']('+url.href+')');
    // Images and inline programs are content, never bibliographic references.
    line=line.replace(/(`+)([^`]*?)\1|!\[(?:\\.|[^\]\\])*\]\((?:\\.|[^)\\])*\)/g,
      value=>/^`#page=[1-9]\d*`$/.test(value) ? value : raw(value));
    // A streaming Markdown destination must finish before it becomes a citation.
    line=line.replace(/\[(?:\\.|[^\]\\\n])*\]\([^\n)]*$/g,raw);
    const linkPattern=new RegExp(String.raw`\[((?:\\.|[^\]\\\n])*)\]\(\s*<?(${address})>?(?:[ \t]+(?:"((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)'))?\s*\)`,'g');
    line=line.replace(linkPattern,(all,label,target,title,singleTitle)=>{
      const url=originalAddress(target,origin);
      if(!url)return raw(renderOtherLink?.(label,target,title||singleTitle) ?? all);
      if(!url.hash)base=url;
      return save(renderLink ? label : sourceLinkLabel(label,url),url,title||singleTitle);
    });
    if(renderOtherLink) line=line.replace(/<(https?:\/\/[^\s<>]+)>/g,(all,target)=>raw(renderOtherLink('',target) ?? all));
    line=line.replace(new RegExp(address,'g'),target=>{
      // Sentence punctuation is prose, not part of a bare URL. Explicit Markdown
      // destinations above retain their exact punctuation and query strings.
      const clean=renderOtherLink ? target.replace(/[.,;!?，。；！？、：）]+$/u,'') : target;
      const suffix=target.slice(clean.length);
      const url=originalAddress(clean,origin);
      if(!url)return raw((renderOtherLink?.('',clean) ?? clean)+suffix);
      if(!url.hash)base=url;
      return save(sourceLinkLabel('论文原文',url),url)+suffix;
    });
    line=line.replace(/([^；;\n]*?)[：:]\s*(?:同上链接\s*)?`?#page=([1-9]\d*)`?/g,(all,prefix,page)=>{
      // Keep list/indent structure outside the clickable description.
      const match=prefix.match(/^(\s*(?:[-*]\s+)?)(.*)$/), topic=match?.[2]?.trim();
      if(!base||!topic||topic.includes('\u0000'))return all;
      const url=new URL(base);url.hash='page='+page;
      return (match?.[1]||'')+save(topic+' · 第'+page+'页',url);
    });
    // An unbound fragment is not a navigable source: show a readable unresolved location.
    line=line.replace(/`?#page=([1-9]\d*)`?/g,(_all,page)=>'第'+page+'页（来源待确认）');
    return line.replace(/\u0000SOURCE(\d+)\u0000/g,(_all,index)=>protectedLinks[Number(index)]);
  }).join('\n');
}

// Only known, same-origin business routes may resolve a name through our API.
// External sites are labelled locally; displaying a citation never fetches them.
export function describeSourceLink(label, target, origin='http://localhost', title, names=new Map()) {
  let url;
  try { url=new URL(String(target).replace(/\\([&()])/g,'$1'),origin); } catch { return null; }
  if(!/^https?:$/.test(url.protocol))return null;
  const project=url.origin===new URL(origin).origin
    && url.pathname.match(/^\/projects\/([a-f0-9-]{36})\/?$/i);
  const clean=value=>labelText(String(value||'').replace(/\\([\\[\]*_`])/g,'$1').replace(/\*\*|__/g,'')).replace(/\s+/g,' ').trim();
  const authored=clean(label);
  const meaningful=authored&&!/^(?:https?:\/\/|\/|\d+$|来源$|链接$)/i.test(authored);
  if(project) {
    const endpoint='/api/v1/projects/'+project[1];
    const name=names.get(endpoint);
    return {kind:'project',url:url.href,endpoint,title:name|| (meaningful ? authored : '项目详情'),
      label:name ? '项目 · '+name : meaningful ? authored : '项目详情'};
  }
  // Leave unrelated internal API/file links and in-page navigation to native UI.
  if(url.origin===new URL(origin).origin || !/^https?:\/\//i.test(target))return null;
  const hostname=url.hostname.replace(/^www\./i,'');
  const sourceTitle=clean(title);
  return {kind:'web',url:url.href,title:sourceTitle|| (meaningful ? authored : hostname),
    label:meaningful ? authored : sourceTitle||hostname};
}

function markdownSourceLabel(value) {
  // A fetched business name is plain text, never Markdown instructions or HTML.
  return String(value).replace(/\s+/g,' ').replace(/[\\`*_{}\[\]<>!~|]/g,'\\$&');
}

function explicitCitationLabel(label, title) {
  // Labels come from the reference itself, never nearby prose, headings, or a
  // subject dictionary. Missing locator metadata cannot be invented by the UI.
  const clean=value=>labelText(String(value||'').replace(/\\([\\\[\]*_`])/g,'$1').replace(/\*\*|__/g,''))
    .replace(/(?:\s*[·・]\s*)?(?:第\s*\d+\s*页|p\s*\d+)\s*$/i,'').replace(/\s+/g,' ').trim();
  const useful=value=>value&&!/^(?:\d+|原文|论文|论文原文|来源|总入口|同上链接)$/.test(value)&&!/^(?:https?:|\/api\/)/.test(value);
  const named=clean(label), sourceTitle=clean(title);
  const value=useful(named)?named:useful(sourceTitle)?sourceTitle:'内容标签缺失';
  const limit=/[\u3400-\u9fff]/.test(value)?14:28;
  return value.length>limit ? value.slice(0,limit-1).trimEnd()+'…' : value;
}

/** A display projection only; stored answers and immutable evidence URLs are untouched. */
export function projectSourceBlocks(blocks, origin='http://localhost', names=new Map()) {
  const sources=[], links=[], byDocument=new Map(), citations=[], otherCitations=[];
  const projected=blocks.map(block=>{
    if(block.kind!=='text') return block;
    const text=normalizeOriginalLinks(block.text,origin,(label,url,linkTitle)=>{
      const key=url.origin+url.pathname+(url.searchParams.has('version_id') ? '?version_id='+url.searchParams.get('version_id') : '');
      let source=byDocument.get(key);
      const title=labelText(String(linkTitle||label).replace(/\\([\\\[\]*_`])/g,'$1').replace(/\*\*|__/g,''))
        .replace(/(?:\s*[·・]\s*)?(?:原文\s*[·・]?\s*)?第\s*\d+\s*页\s*$/,'')
        .replace(/\.pdf$/i,'').replace(/^(?:PMC\d+|W\d+)[_\s-]+/i,'').replace(/[_\s]+/g,' ').trim();
      const informative=title && !/^(?:\[?\d+\]?$|https?:|\/api\/|原文$|论文原文$|总入口$|同上链接$)/.test(title);
      if(!source){
        source={number:sources.length+1,title:informative ? title : '论文原文',locations:[]};
        byDocument.set(key,source);sources.push(source);
      } else if(informative && (source.title==='论文原文'||title.length>source.title.length)) source.title=title;
      if(!source.locations.some(item=>item.url===url.href)) {
        const page=new URLSearchParams(url.hash.slice(1)).get('page');
        source.locations.push({url:url.href,page:page&&/^[1-9]\d*$/.test(page) ? Number(page) : null});
      }
      const page=new URLSearchParams(url.hash.slice(1)).get('page');
      const topic=explicitCitationLabel(label,source.title);
      const tag=topic+(page&&/^[1-9]\d*$/.test(page) ? ' · p'+page : '');
      const token='\u0000CITE'+citations.length+'\u0000';
      citations.push({source,url:url.href,topic,tag});return token;
    },(label,target,title)=>{
      const link=describeSourceLink(label,target,origin,title,names);
      if(!link)return null;
      if(!links.some(item=>item.url===link.url))links.push(link);
      const token='\u0000LINK'+otherCitations.length+'\u0000';
      otherCitations.push('['+markdownSourceLabel(link.label)+']('+target+')');
      return token;
    });
    // Opaque retrieval IDs following a real citation are diagnostics, not prose.
    const readable=text.replace(/(\u0000CITE\d+\u0000)[ \t]*[（(]\s*`?q[a-f0-9]{24}`?(?:[、,，;；\s]+`?q[a-f0-9]{24}`?)*\s*[）)]/g,'$1')
      // Source-only parentheses are redundant around a chip. Ordinary prose,
      // scientific notation, programs and parentheses inside URLs stay intact.
      .replace(/\([ \t]*(\u0000(?:CITE|LINK)\d+\u0000(?:[ \t,，;；、]*\u0000(?:CITE|LINK)\d+\u0000)*)[ \t]*\)/g,'$1')
      .replace(/（[ \t]*(\u0000(?:CITE|LINK)\d+\u0000(?:[ \t,，;；、]*\u0000(?:CITE|LINK)\d+\u0000)*)[ \t]*）/g,'$1');
    // Only adjacent references collapse; all immutable targets remain in the
    // projection metadata and the stored answer retains the original references.
    const compact=readable.replace(/\u0000CITE\d+\u0000(?:[ \t]*(?:\n[ \t]*)?\u0000CITE\d+\u0000)*/g,run=>{
      const seen=new Set();
      return [...run.matchAll(/\u0000CITE(\d+)\u0000/g)].flatMap(match=>{
        const {source,url,tag}=citations[Number(match[1])],key=source.number+':'+tag;
        if(seen.has(key)) return [];
        seen.add(key);
        return ['['+labelText(tag)+']('+url+')'];
      }).join(' ');
    }).replace(/\u0000LINK(\d+)\u0000/g,(_all,index)=>otherCitations[Number(index)]);
    return compact===block.text ? block : {...block,text:compact};
  });
  return {blocks:projected,sources,links};
}
