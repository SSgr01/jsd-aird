window.__ModuleLoader__.load({ id: '@jsd-aird/enterprise-plugin', factory: require => {
  const React = require('react'), h = React.createElement;
  const { useEffect, useState, useRef } = React;
  const enterpriseTheme = {"tokens":{"--dsw-alias-bg-base":{"light":"#faf9f8","dark":"#1c1b1e"},"--dsw-alias-bg-layer-1":{"light":"#ffffff","dark":"#242327"},"--dsw-alias-bg-layer-2":{"light":"#f7f5f4","dark":"#2c2a2f"},"--dsw-alias-bg-layer-3":{"light":"#eeebed","dark":"#353238"},"--dsw-alias-bg-overlay":{"light":"#ffffff","dark":"#2c2a2f"},"--dsw-alias-bg-document-preview":{"light":"#f0edee","dark":"#171619"},"--dsw-alias-label-document-preview":{"light":"#716d76","dark":"#b8b1bd"},"--dsw-alias-border-l1":{"light":"#eee9e8","dark":"#343137"},"--dsw-alias-border-l2":{"light":"#e5dfe3","dark":"#403b44"},"--dsw-alias-border-l2-darkmode-thin":{"light":"#e5dfe3","dark":"#403b44"},"--dsw-alias-border-l3":{"light":"#e5dfe3","dark":"#403b44"},"--dsw-alias-border-l4":{"light":"#dcd5dc","dark":"#58505d"},"--dsw-alias-brand-primary":{"light":"#29282d","dark":"#efebf1"},"--dsw-alias-brand-primary-invert":{"light":"#ffffff","dark":"#29282d"},"--dsw-alias-button-primary-fill":{"light":"#29282d","dark":"#efebf1"},"--dsw-alias-button-primary-hover":{"light":"#45424a","dark":"#d5cddc"},"--dsw-alias-button-info-fill":{"light":"#29282d","dark":"#45424a"},"--dsw-alias-button-info-hover":{"light":"#45424a","dark":"#5a575f"},"--dsw-alias-interactive-bg-hover":{"light":"rgb(41 40 45 / 6%)","dark":"rgb(239 235 241 / 8%)"},"--dsw-alias-interactive-bg-hover-solid":{"light":"#f7f5f4","dark":"#302e33"},"--dsw-alias-interactive-bg-hover-accent":{"light":"#eeebed","dark":"#3b383f"},"--dsw-alias-interactive-bg-active":{"light":"#eeebed","dark":"#403d44"},"--dsw-alias-label-primary":{"light":"#29282d","dark":"#efebf1"},"--dsw-alias-label-primary-bluish":{"light":"#45424a","dark":"#d5cddc"},"--dsw-alias-label-primary-dimmed":{"light":"#45424a","dark":"#d5cddc"},"--dsw-alias-label-primary-foreground":{"light":"#ffffff","dark":"#29282d"},"--dsw-alias-label-primary-inverted":{"light":"#ffffff","dark":"#29282d"},"--dsw-alias-label-secondary":{"light":"#716d76","dark":"#b8b1bd"},"--dsw-alias-label-tertiary":{"light":"#7e7782","dark":"#aaa1b0"},"--dsw-alias-label-caption":{"light":"#827a85","dark":"#a59bab"},"--dsw-alias-label-deep-diving":{"light":"#766381","dark":"#b9a3c9"},"--dsw-alias-label-deep-diving-shimmer":{"light":"#aaa0b0","dark":"#8f8199"},"--dsw-alias-link":{"light":"#29282d","dark":"#efebf1"},"--dsw-alias-menu-icon":{"light":"#716d76","dark":"#b8b1bd"},"--dsw-alias-markdown-citation":{"light":"#f2edf5","dark":"#3b3143"},"--dsw-alias-markdown-code-block":{"light":"#f3f0f2","dark":"#242126"},"--dsw-alias-markdown-code-block-banner":{"light":"#eeebed","dark":"#2e2933"},"--dsw-alias-markdown-inline-code":{"light":"#f2edf0","dark":"#332b39"},"--dsw-alias-markdown-tag":{"light":"#f2edf5","dark":"#3b3143"},"--dsw-alias-scrollbar-bg-l1":{"light":"#ded7df","dark":"#514858"},"--dsw-alias-scrollbar-bg-l2":{"light":"#ded7df","dark":"#514858"},"--dsw-alias-scrollbar-hover-l1":{"light":"#c0b3c6","dark":"#73627f"},"--dsw-alias-scrollbar-hover-l2":{"light":"#c0b3c6","dark":"#73627f"},"--dsw-alias-state-business-primary":{"light":"#29282d","dark":"#efebf1"},"--dsw-alias-state-business-tertiary":{"light":"#f7f5f4","dark":"#3b383f"},"--dsw-alias-state-done-primary":{"light":"#29282d","dark":"#efebf1"},"--dsw-alias-state-error-primary":{"light":"#b84a55","dark":"#ee969f"},"--dsw-alias-state-error-secondary":{"light":"#d97a84","dark":"#c96d78"},"--dsw-alias-state-success-primary":{"light":"#347552","dark":"#85cda5"},"--dsw-alias-state-success-secondary":{"light":"#559b74","dark":"#65ae87"},"--dsw-alias-state-success-tertiary":{"light":"#eaf4ed","dark":"#233c30"},"--dsw-alias-state-warn-primary":{"light":"#92671f","dark":"#e0b96d"},"--dsw-alias-state-warn-secondary":{"light":"#b58a43","dark":"#bf9955"},"--dsw-alias-state-warn-tertiary":{"light":"#faf2e5","dark":"#403522"},"--dsw-alias-state-warn-label":{"light":"#92671f","dark":"#e0b96d"},"--dsw-specific-bubble":{"light":"#f0ebf3","dark":"#322b39"},"--dsw-specific-bubble-highlight":{"light":"#e6dceb","dark":"#493954"},"--dsw-specific-input-major":{"light":"#fffdfc","dark":"#29262d"},"--dsw-specific-selector":{"light":"#f7f5f4","dark":"#302b34"},"--dsw-specific-sidebar-fill":{"light":"#fffdfc","dark":"#201e23"},"--dsw-specific-sidebar-nav-item-active":{"light":"#eeebed","dark":"#3b3440"},"--dsw-specific-sidebar-nav-item-active-accent":{"light":"#f2edf5","dark":"#3b3143"},"--dsw-specific-tip":{"light":"#f7f5f4","dark":"#302b34"},"--dsw-menu-surface-fill":{"light":"#ffffff","dark":"#2c2a2f"},"--dsw-alias-menu-group-header-fill":{"light":"#f7f5f4","dark":"#302b34"}},"css":"/* Enterprise visual layer. Select native public data attributes and our own\r\n   slots only; upstream CSS-module names and interaction state stay untouched. */\r\n/* The server does not expose desktop Explorer or physical transcript moves. */\r\nbody[data-jsd-workbench] div:has(> [data-dsham-open-explorer=\"true\"]),\r\nbody[data-jsd-workbench] .dsham_moveSessionWrap {\r\n  display: none;\r\n}\r\nbody[data-jsd-workbench] {\r\n  --dsh-font-family-text-default: 'Noto Sans SC Variable', 'Microsoft YaHei UI', 'PingFang SC', 'Segoe UI', sans-serif;\r\n  --dsw-font-family-brand: var(--dsw-font-family);\r\n  --dsw-radius-panel: 20px;\r\n  --jsd-focus: #45424a;\r\n  --jsd-composer-shadow: rgb(76 48 65 / 5%);\r\n  /* The wash follows the explicitly requested UI-opz / SETO brand reference. */\r\n  --jsd-workspace-wash:\r\n    radial-gradient(ellipse 70% 68% at 54% 3%, rgb(221 207 238 / 22%), transparent 80%),\r\n    radial-gradient(ellipse 100% 78% at 0% 100%, rgb(247 206 176 / 34%), transparent 76%),\r\n    radial-gradient(ellipse 105% 72% at 100% 100%, rgb(238 198 213 / 30%), transparent 78%);\r\n}\r\n\r\nbody[data-jsd-workbench][data-ds-dark-theme] {\r\n  --jsd-focus: #d5cddc;\r\n  --jsd-composer-shadow: rgb(0 0 0 / 18%);\r\n  --jsd-workspace-wash:\r\n    radial-gradient(ellipse 70% 68% at 54% 3%, rgb(128 100 148 / 10%), transparent 80%),\r\n    radial-gradient(ellipse 100% 78% at 0% 100%, rgb(147 107 87 / 10%), transparent 76%);\r\n}\r\n\r\nbody[data-jsd-workbench] [data-conversation-content] {\r\n  background-image: var(--jsd-workspace-wash);\r\n}\r\n\r\nbody[data-jsd-workbench] [data-composer-card] {\r\n  --dsw-elevation-stroke-color: var(--dsw-alias-border-l2);\r\n  box-shadow: 0 4px 24px var(--jsd-composer-shadow), 0 0 0 1px var(--dsw-alias-border-l2);\r\n  transition: box-shadow 160ms ease;\r\n}\r\n\r\nbody[data-jsd-workbench] [data-composer-card]:focus-within {\r\n  box-shadow: 0 4px 24px var(--jsd-composer-shadow), 0 0 0 1px var(--jsd-focus), 0 0 0 4px color-mix(in srgb, var(--jsd-focus) 12%, transparent);\r\n}\r\n\r\n.jsd-brand-mark {\r\n  display: inline-flex;\r\n  flex: none;\r\n  color: var(--dsw-alias-state-business-primary);\r\n}\r\n\r\n.jsd-brand-mark img { display: block; }\r\n.jsd-business-tool .jsd-tool-header {\r\n  flex: 1;\r\n  min-width: 0;\r\n  overflow: hidden;\r\n  font-size: var(--dsh-content-font-size-secondary, 13px);\r\n  line-height: calc(24px + var(--dsh-content-font-delta, 0px));\r\n}\r\n.jsd-business-tool .jsd-tool-header-content { min-width: 0; overflow: hidden; }\r\n.jsd-tool-summary {\r\n  display: block;\r\n  flex: 1;\r\n  min-width: 0;\r\n  overflow: hidden;\r\n  white-space: nowrap;\r\n  text-overflow: ellipsis;\r\n}\r\n.jsd-tool-content {\r\n  padding: 8px 24px;\r\n  font: var(--dsh-content-font-size, 14px)/1.75 var(--dsw-font-family);\r\n  overflow-wrap: anywhere;\r\n}\r\n.jsd-tool-content p { margin: 0 0 8px; }\r\n.jsd-brand-name { font-weight: 600; letter-spacing: -.02em; }\r\n.jsd-page-title {\r\n  margin: 0 20px 0 0;\r\n  padding-inline-start: max(0px, calc(var(--dsh-frame-leading-clearance, 0px) - 20px));\r\n  font: 600 18px/30px var(--dsw-font-family);\r\n  color: var(--dsw-alias-label-primary);\r\n  white-space: nowrap;\r\n}\r\n\r\n.jsd-starters {\r\n  display: grid;\r\n  grid-template-columns: repeat(3, minmax(0, 1fr));\r\n  gap: 12px;\r\n  margin-block: 12px;\r\n  margin-inline: var(--dsh-composer-side-clearance, 16px);\r\n}\r\n.jsd-starters button {\r\n  display: grid;\r\n  align-content: start;\r\n  justify-items: start;\r\n  gap: 8px;\r\n  min-width: 0;\r\n  padding: 20px 18px;\r\n  border: 1px solid var(--dsw-alias-border-l2);\r\n  border-radius: 12px;\r\n  background: var(--dsw-alias-bg-layer-1);\r\n  color: var(--dsw-alias-label-primary);\r\n  font: inherit;\r\n  text-align: left;\r\n  cursor: pointer;\r\n  transition: border-color 160ms ease, box-shadow 160ms ease;\r\n}\r\n.jsd-starters button:hover {\r\n  border-color: var(--jsd-focus);\r\n  box-shadow: 0 3px 12px var(--jsd-composer-shadow);\r\n}\r\n.jsd-starters button:focus-visible {\r\n  outline: 2px solid var(--jsd-focus);\r\n  outline-offset: 3px;\r\n}\r\n.jsd-starters button:disabled { opacity: .5; cursor: default; }\r\n.jsd-starter-art { display: block; width: 36px; height: 36px; margin-bottom: 10px; }\r\n.jsd-starter-art svg { display: block; width: 100%; height: 100%; }\r\n.jsd-starters strong { font-size: 14px; line-height: 20px; font-weight: 500; }\r\n.jsd-starter-description { color: var(--dsw-alias-label-secondary); font-size: 12px; line-height: 20px; text-wrap: pretty; }\r\n\r\n.jsd-nav {\r\n  display: grid;\r\n  grid-template-columns: minmax(0, 1fr);\r\n  width: 100%;\r\n  padding: 10px 4px;\r\n  border-top: 1px solid var(--dsw-alias-border-l1);\r\n  box-sizing: border-box;\r\n}\r\n\r\n.jsd-nav a {\r\n  display: flex;\r\n  align-items: center;\r\n  gap: 8px;\r\n  min-height: 42px;\r\n  padding: 0 8px;\r\n  border-radius: var(--dsw-radius-sm);\r\n  color: var(--dsw-alias-label-secondary);\r\n  font-size: 14px;\r\n  line-height: 22px;\r\n  text-decoration: none;\r\n  transition: background-color 160ms ease, color 160ms ease;\r\n}\r\n\r\n.jsd-nav a:hover {\r\n  color: var(--dsw-alias-label-primary);\r\n  background: var(--dsw-alias-interactive-bg-hover);\r\n}\r\n\r\n.jsd-nav a:focus-visible,\r\n.jsd-resource a:focus-visible,\r\n.jsd-continue-action:focus-visible {\r\n  outline: 2px solid var(--jsd-focus);\r\n  outline-offset: 2px;\r\n}\r\n\r\n.jsd-nav[data-wide='false'] { grid-template-columns: 1fr; padding-inline: 0; }\r\n.jsd-nav[data-wide='false'] a { justify-content: center; padding: 0; }\r\n.jsd-nav svg { flex: none; width: 16px; height: 16px; }\r\n\r\n.jsd-resource {\r\n  padding: 24px;\r\n  font: var(--dsh-content-font-size, 14px)/1.8 var(--dsw-font-family);\r\n  overflow: auto;\r\n  height: 100%;\r\n  box-sizing: border-box;\r\n  color: var(--dsw-alias-label-primary);\r\n}\r\n.jsd-resource h2 { margin: 0 0 12px; font-size: 20px; font-weight: 600; }\r\n.jsd-resource pre { font: inherit; white-space: pre-wrap; overflow-wrap: anywhere; }\r\n.jsd-resource section { margin-block: 20px; }\r\n.jsd-resource a, .jsd-original-toolbar a { color: var(--dsw-alias-link); }\r\n.jsd-resource small { color: var(--dsw-alias-label-secondary); }\r\n.jsd-original { height: 100%; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }\r\n.jsd-original-toolbar {\r\n  display: flex;\r\n  justify-content: space-between;\r\n  gap: 16px;\r\n  padding: 12px 16px;\r\n  border-bottom: 1px solid var(--dsw-alias-border-l1);\r\n  background: var(--dsw-alias-bg-layer-1);\r\n  font-size: 13px;\r\n}\r\n/* Keep the native primary action's layout seat; the empty paused state uses\r\n   the input extension to place Continue in that same seat. */\r\nbody[data-jsd-workbench] [data-composer-card]:has(.jsd-continue-action) button[aria-busy]:disabled:not([aria-expanded]):not(.jsd-continue-action) {\r\n  visibility: hidden;\r\n}\r\n.jsd-continue-action {\r\n  position: absolute;\r\n  right: 8px;\r\n  bottom: 8px;\r\n  z-index: 1;\r\n  display: grid;\r\n  place-items: center;\r\n  width: 34px;\r\n  height: 34px;\r\n  padding: 0;\r\n  border: 0;\r\n  border-radius: 999px;\r\n  corner-shape: round;\r\n  background: var(--dsw-alias-button-info-fill);\r\n  color: #fff;\r\n  cursor: pointer;\r\n}\r\n.jsd-continue-action > svg:not(.jsd-continue-spinner) { transform: scale(1.5); }\r\n.jsd-continue-action:hover:not(:disabled) { background: var(--dsw-alias-button-info-hover); }\r\n.jsd-continue-action:disabled { opacity: .4; cursor: default; }\r\n.jsd-continue-action[aria-busy='true']:disabled { opacity: 1; cursor: progress; }\r\n.jsd-continue-spinner { animation: jsd-continue-spin 800ms linear infinite; }\r\n@keyframes jsd-continue-spin { to { transform: rotate(360deg); } }\r\n\r\n@media (max-width: 767px) {\r\n  .jsd-starters { grid-template-columns: minmax(0, 1fr); gap: 8px; margin-block: 6px; }\r\n  .jsd-starters button { grid-template-columns: 32px minmax(0, 1fr); align-items: center; gap: 1px 12px; padding: 12px 14px; }\r\n  .jsd-starter-art { grid-row: span 2; width: 30px; height: 30px; margin: 0; }\r\n  .jsd-page-title { font-size: 16px; margin-right: 8px; }\r\n  .jsd-resource { padding: 16px; }\r\n  .jsd-nav a { min-height: 44px; }\r\n}\r\n\r\n@media (prefers-reduced-motion: reduce) {\r\n  body[data-jsd-workbench] [data-composer-card], .jsd-nav a, .jsd-starters button { transition: none; }\r\n  .jsd-continue-spinner { animation: none; }\r\n}\r\n\r\n/* Source links stay ordinary native Markdown links and retain keyboard/menu behavior. */\r\nbody[data-jsd-workbench] a[href*=\"/api/v1/knowledge/documents/\"][href*=\"/content\"],\r\nbody[data-jsd-workbench] a[href*=\"/api/v1/agents/private-sources/\"][href*=\"/content\"],\r\n.jsd-source-link {\r\n  color: inherit;\r\n  font-size: inherit;\r\n  text-decoration: underline;\r\n  text-decoration-color: var(--dsw-alias-border-l2, #d3d0cd);\r\n  text-underline-offset: .2em;\r\n  overflow-wrap: anywhere;\r\n}\r\nbody[data-jsd-workbench] a[href*=\"/content\"]:hover, .jsd-source-link:hover {\r\n  text-decoration-color: currentColor;\r\n}\r\nbody[data-jsd-workbench] a[href*=\"/content\"]:focus-visible, .jsd-source-link:focus-visible {\r\n  outline: 2px solid currentColor;\r\n  outline-offset: 3px;\r\n  border-radius: 2px;\r\n}\r\n\r\n/* Citation chrome stays inline; the native message owns all layout and media. */\r\n.jsd-cited-answer { display: contents; }\r\nbody[data-jsd-workbench] .jsd-cited-answer a[data-jsd-citation] {\r\n  color: var(--dsw-alias-label-secondary, inherit);\r\n  background: var(--dsw-alias-interactive-bg-hover, rgba(128,128,128,.12));\r\n  border-radius: 4px;\r\n  font-size: .78em;\r\n  font-weight: 400;\r\n  line-height: 1.5;\r\n  font-variant-numeric: tabular-nums;\r\n  white-space: nowrap;\r\n  vertical-align: baseline;\r\n  text-decoration: none;\r\n  padding: 1px 6px;\r\n  margin-inline: 2px;\r\n}\r\nbody[data-jsd-workbench] .jsd-cited-answer a[data-jsd-citation] > svg { display: none; }\r\nbody[data-jsd-workbench] .jsd-cited-answer a[data-jsd-citation]:hover { color: var(--dsw-alias-label-primary, inherit); text-decoration: none; }\r\n"};
  const sourceLinks = (()=>{/** Presentation only: retain immutable destinations and use the native Markdown renderer. */
function shortSourceTitle(value = '') {
  return String(value).replace(/\.pdf$/i,'').replace(/^(?:PMC\d+|W\d+)[_\s-]+/i,'')
    .replace(/_?sub_?(\d+)_?sub_?/gi,'$1').replace(/[_\s]+/g,' ').trim().slice(0,64) || '论文';
}
function originalAddress(value, origin) {
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
function sourceLinkLabel(label, url) {
  const page=new URLSearchParams(url.hash.slice(1)).get('page');
  let topic=labelText(label).replace(/(?:\s*[·・]\s*)?(?:原文\s*[·・]?\s*)?第\s*\d+\s*页\s*$/,'').trim();
  if(!topic || /^(?:https?:|\/api\/|原文$|总入口$|同上链接$)/.test(topic)) topic='论文原文';
  topic=shortSourceTitle(topic);
  return topic+(page&&/^[1-9]\d*$/.test(page)?' · 第'+page+'页':'');
}
function normalizeOriginalLinks(text, origin='http://localhost', renderLink) {
  let base=null, fence=false;
  const address=String.raw`(?:https?:\/\/[^\s<>\x60)]+|\/api\/v1\/[^\s<>\x60)]+)`;
  return String(text).split('\n').map(line=>{
    if(/^\s*(```|~~~)/.test(line)){fence=!fence;return line;}
    if(fence) return line;
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
      const url=originalAddress(target,origin);if(!url)return raw(all);
      if(!url.hash)base=url;
      return save(renderLink ? label : sourceLinkLabel(label,url),url,title||singleTitle);
    });
    line=line.replace(new RegExp(address,'g'),target=>{
      const url=originalAddress(target,origin);if(!url)return raw(target);
      if(!url.hash)base=url;
      return save(sourceLinkLabel('论文原文',url),url);
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
function projectSourceBlocks(blocks, origin='http://localhost') {
  const sources=[], byDocument=new Map(), citations=[];
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
    });
    // Opaque retrieval IDs following a real citation are diagnostics, not prose.
    const readable=text.replace(/(\u0000CITE\d+\u0000)[ \t]*[（(]\s*`?q[a-f0-9]{24}`?(?:[、,，;；\s]+`?q[a-f0-9]{24}`?)*\s*[）)]/g,'$1');
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
    });
    return compact===block.text ? block : {...block,text:compact};
  });
  return {blocks:projected,sources};
}
;return {normalizeOriginalLinks,sourceLinkLabel,shortSourceTitle,projectSourceBlocks};})();
  const base = '/agent/';
  async function api(action, body, signal) {
    const response = await fetch(base + 'business/' + action, { method: 'POST',
      headers: { 'content-type': 'application/json' }, body: JSON.stringify(body), signal });
    if (!response.ok) throw new Error('暂时无法读取，请检查登录和资料权限。');
    return response.json();
  }
  const button = (label, onClick, more = {}) => h('button', { type: 'button', onClick, ...more }, label);
  function returnDestination() {
    try {
      const value = sessionStorage.getItem('jsd.ai-workbench.return') || document.referrer;
      if (value) {
        const url = new URL(value, location.origin);
        // Navigation only: resource access is still checked by the business page.
        if (url.origin === location.origin && /^\/(?:dashboard|partners|templates|projects|experiments|research-test|quality|spectrum|production-orders|knowledge|data|inventory|system)(?:\/|$)/.test(url.pathname)) {
          const record = /^\/(projects|experiments)\/[0-9a-f]{8}-[0-9a-f-]{27}(?:\/|$)/i.exec(url.pathname);
          return {href:url.pathname+url.search+url.hash,label:record ? record[1] === 'projects' ? '返回项目' : '返回实验' : '返回系统'};
        }
      }
    } catch { /* A missing or invalid source must not prevent returning to the system. */ }
    return {href:'/dashboard',label:'返回系统'};
  }
  function Navigation({ wide }) {
    const {IconChevronLeftOutlineRegular} = require('@deepseek-ai/dsh-client-ui-primitives');
    const {href,label} = returnDestination();
    return h('nav', { className: 'jsd-nav', 'data-wide':String(wide !== false), 'aria-label': '返回业务系统' },
      h('a',{href,title:label,'aria-label':label},
        h(IconChevronLeftOutlineRegular,{size:16}),wide !== false && h('span',null,label)));
  }
  function BrandMark({size,className = ''}) {
    return h('span',{className:'jsd-brand-mark '+className,'aria-hidden':true},
      h('img',{src:base+'favicon.svg',width:size,height:size,alt:'',draggable:false}));
  }
  const HeroContext = React.createContext(false);
  function HeroFrame({hero,children}) {
    return h(HeroContext.Provider,{value:hero},children);
  }
  function StarterArt({kind}) {
    const id = 'jsd-starter-'+React.useId();
    const pearl = 'url(#'+id+'-pearl)', peach = 'url(#'+id+'-peach)';
    return h('svg',{viewBox:'0 0 40 40',fill:'none','aria-hidden':true},
      h('defs',null,
        h('linearGradient',{id:id+'-pearl',x1:8,y1:3,x2:32,y2:37,gradientUnits:'userSpaceOnUse'},
          h('stop',{stopColor:'#E7DCEC'}),h('stop',{offset:'.5',stopColor:'#BDA8CE'}),h('stop',{offset:1,stopColor:'#E6C3CB'})),
        h('linearGradient',{id:id+'-peach',x1:12,y1:12,x2:30,y2:34,gradientUnits:'userSpaceOnUse'},
          h('stop',{stopColor:'#FFE6C5'}),h('stop',{offset:1,stopColor:'#D9ACAA'}))),
      kind==='read' ? h(React.Fragment,null,
        h('path',{d:'M11 5h13l7 7v21a3 3 0 0 1-3 3H11a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3Z',fill:pearl,stroke:'#EEE2F1'}),
        h('path',{d:'M24 5v8h7',fill:'#F4EAF3'}),
        h('path',{d:'M13 18h12m-12 5h12m-12 5h7',stroke:'#FFF5EE',strokeWidth:2,strokeLinecap:'round'})) :
      kind==='compare' ? h(React.Fragment,null,
        h('rect',{x:4,y:8,width:14,height:24,rx:4,fill:pearl,stroke:'#EEE2F1'}),
        h('rect',{x:22,y:8,width:14,height:24,rx:4,fill:peach,stroke:'#F7E6DA'}),
        h('path',{d:'M9 15h4m-4 5h4m14-5h4m-4 5h4',stroke:'#FFF9F6',strokeWidth:1.5,strokeLinecap:'round'}),
        h('path',{d:'m17 24 3 3 3-3',stroke:'#A18AAB',strokeWidth:1.4,strokeLinecap:'round',strokeLinejoin:'round'})) :
      h(React.Fragment,null,
        h('rect',{x:5,y:20,width:8,height:14,rx:3,fill:peach,stroke:'#F6E2D6'}),
        h('rect',{x:16,y:6,width:8,height:28,rx:3,fill:pearl,stroke:'#EEE2F1'}),
        h('rect',{x:27,y:13,width:8,height:21,rx:3,fill:peach,stroke:'#F6E2D6'}),
        h('path',{d:'M19 10v19M30 17v12M8 24v5',stroke:'#FFF8F0',strokeOpacity:.6,strokeLinecap:'round'})));
  }
  function registerWelcomeCards(scope) {
    const starters = [
      {kind:'read',title:'阅读文献',description:'提炼主要结论与关键依据',prompt:'这份文献的主要结论是什么？'},
      {kind:'compare',title:'比较方法',description:'对比实验方案与试验条件',prompt:'请比较我提供的资料或实验记录中的方法，说明条件差异与适用范围。'},
      {kind:'data',title:'整理数据',description:'梳理关键数据与试验结果',prompt:'请整理我提供的资料或实验记录中的关键试验条件与结果。'},
    ];
    function WelcomeCards({useInput,fillPrompt}) {
      const hero = React.useContext(HeroContext), phase = useInput(value=>value.phase);
      if (!hero) return null;
      return h('div',{className:'jsd-starters',role:'group','aria-label':'研发任务建议'},
        starters.map(item=>h('button',{key:item.kind,type:'button','aria-label':item.title,
          title:'填入输入框，确认后发送',disabled:phase!=='plain',onClick:()=>fillPrompt(item.prompt)},
          h('span',{className:'jsd-starter-art'},h(StarterArt,{kind:item.kind})),
          h('strong',null,item.title),h('span',{className:'jsd-starter-description'},item.description))));
    }
    scope.slots.inject('conversation.hero.frame',()=>scope.slots.register({
      name:'conversation.hero.frame',id:'enterprise-welcome',
    },HeroFrame));
    scope.slots.inject('conversation.input.dock',()=>scope.slots.register({
      name:'conversation.input.dock',id:'enterprise-welcome-cards',order:-20,
      inject:sessionId=>({fillPrompt:prompt=>{
        const binding=scope.sessions.binding(sessionId), input=scope.conversation.input.for(scope.sessions.scope(sessionId));
        if (!binding || scope.conversation.input.requestDraftInitialization(binding,
          {prompt,whenDraftExists:'append'}) !== 'applied') {
          input.notify('info','输入框正忙，请稍后再试。'); return;
        }
        input.focus();
      }}),
    },WelcomeCards));
  }
  const encode = value => btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(value))))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const decode = token => JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(token.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0))));
  const marker = (label, value) => '[' + label.replace(/[\[\]\n]/g, ' ').slice(0, 240) + '](jsd-resource:' + encode(value) + ')';
  const addressFor = value => 'dsh-resource://enterprise/' + encode(value);
  const refOnly = value => {const {only, ...ref} = value; return ref;};
  const originalPdfSlot = '@jsd-aird/original.pdf';
  const nativePdfKey = '@deepseek-ai/dsh-client-ui-sidebar-documentpreview/pdf';
  // Only immutable, same-origin source endpoints enter this adapter. Content
  // authorization remains in Java; the PDF renderer and sidebar belong to dsh.
  function originalUrl(value) {
    try {
      const url = new URL(value, location.origin), id = '[0-9a-f-]{36}';
      if (url.origin !== location.origin) return null;
      const privateSource = new RegExp('^/api/v1/agents/private-sources/'+id+'/content$').test(url.pathname)
        && new RegExp('^'+id+'$').test(url.searchParams.get('version_id') || '');
      const publishedSource = new RegExp('^/api/v1/knowledge/documents/'+id+'/versions/'+id+'/content$').test(url.pathname);
      return privateSource || publishedSource ? url : null;
    } catch { return null; }
  }
  function OriginalBody({useTabInfo, renderSlot}) {
    const {tab} = useTabInfo(), root = useRef(null);
    const url = originalUrl(decode(tab.navigation.address.split('/').at(-1)));
    const address = url?.href, page = Number(new URLSearchParams(url?.hash.slice(1)).get('page')) || 1;
    const [content, setContent] = useState(null), [error, setError] = useState('');
    useEffect(() => {
      const controller = new AbortController(); setContent(null); setError('');
      if (!address) {setError('原文地址无效。'); return;}
      fetch(address, {signal:controller.signal, cache:'no-store', credentials:'same-origin'})
        .then(async response => {
          if (!response.ok) throw new Error('无法读取原文，请检查当前权限和版本。');
          const mime = response.headers.get('content-type')?.split(';')[0];
          if (mime !== 'application/pdf') throw new Error('此原文格式请使用原文件入口打开。');
          const data = new Uint8Array(await response.arrayBuffer());
          if (!controller.signal.aborted) setContent({kind:'bytes',data});
        }).catch(e => {if (!controller.signal.aborted) setError(e.message);});
      return () => controller.abort();
    }, [address]);
    useEffect(() => {
      if (!content || !root.current || !tab.visible) return;
      const node = root.current;
      const locate = () => {
        const target = node.querySelector('[data-pdf-page="'+page+'"]');
        if (!target) return;
        target.scrollIntoView({block:'start'});
        if (target.querySelector('canvas')?.closest('[hidden]') === null) observer.disconnect();
      };
      const observer = new MutationObserver(locate);
      observer.observe(node,{childList:true,subtree:true,attributes:true,attributeFilter:['hidden']});
      locate(); return () => observer.disconnect();
    }, [content, page, tab.visible]);
    return h('section',{ref:root,className:'jsd-original'},
      h('div',{className:'jsd-original-toolbar'},h('span',null,'原文 · 第 '+page+' 页'),
        address && h('a',{href:address,download:true},'下载原文件')),
      error ? h('p',{role:'alert'},error) : !content ? h('p',{role:'status'},'读取原文…') :
        renderSlot(originalPdfSlot, {resourceAddress:address,content,wrap:false,
          scrollportRef:()=>{},addResource:()=>{},setResources:()=>{}},
          {entryKey:nativePdfKey,hookContext:useTabInfo}));
  }
  function registerSourceMarkdown(scope) {
    const sourceTitles=new Map();
    // Separate seats retain the native child components without redeclaring
    // slots owned by ui-chat. The wrapper only projects assistant text.
    const seats = {
      'conversation.chat.reasoning.body':'enterprise.source.reasoning',
      'conversation.chat.message.images':'enterprise.source.images',
    };
    scope.slots.inject('conversation.chat.node', () => {
      let current, dispose;
      const sync = () => {
        const entry = scope.slots.entries('conversation.chat.node').find(item =>
          item.options.key === 'assistant-step' && (item.options.priority || 0) === 0);
        if (!entry || entry === current) return;
        current = entry; dispose?.();
        function ReadableSources(props) {
          const data=props.node.data;
          const {blocks,sources}=React.useMemo(()=>sourceLinks.projectSourceBlocks(data.blocks,location.origin),[data.blocks]);
          const renderSlot=React.useCallback((name,owner,options)=>
            props.renderSlot(seats[name] || name,owner,options),[props.renderSlot]);
          const native=h(entry.component,{...props,renderSlot,node:{...props.node,data:{...data,blocks}}});
          if(!sources.length) return native;
          const showSourceTitle=event=>{
            const link=event.target.closest?.('a[data-jsd-citation]');
            if(!link)return;
            const item=sources.find(candidate=>candidate.locations.some(place=>place.url===link.href));
            const id=new URL(link.href).pathname.match(/^\/api\/v1\/knowledge\/documents\/([a-f0-9-]{36})\/versions\//)?.[1];
            if(!item||!id)return;
            if(!sourceTitles.has(id)) sourceTitles.set(id,fetch('/api/v1/knowledge/documents/'+id,{credentials:'same-origin'})
              .then(response=>response.ok ? response.json() : null)
              .then(value=>String(value?.data?.title||'').replace(/\.pdf$/i,'').replace(/^(?:PMC\d+|W\d+)[_\s-]+/i,'').replace(/[_\s]+/g,' ').trim())
              .catch(()=>''));
            void sourceTitles.get(id).then(title=>{
              if(!title||!link.isConnected)return;
              const page=item.locations.find(place=>place.url===link.href)?.page;
              link.title=title+(page ? ' · 第 '+page+' 页' : '');
              link.setAttribute('aria-label','查看 '+link.textContent.trim()+'：'+link.title);
            });
          };
          return h('div',{className:'jsd-cited-answer',ref:node=>{
            node?.querySelectorAll('a[href]').forEach(link=>{
              const item=sources.find(candidate=>candidate.locations.some(place=>place.url===link.href));
              if(!item) return;
              const place=item.locations.find(candidate=>candidate.url===link.href);
              const title=item.title+(place.page ? ' · 第 '+place.page+' 页' : '');
              link.title=title;link.setAttribute('aria-label','查看 '+link.textContent.trim()+'：'+title);
              link.setAttribute('data-jsd-citation','');
            });
          },onPointerOverCapture:showSourceTitle,onFocusCapture:showSourceTitle},native);
        }
        dispose=scope.slots.register({name:'conversation.chat.node',key:'assistant-step',priority:-100,
          children:Object.fromEntries(Object.values(seats).map(name=>[name,{kind:'single',scope:'session'}])),
          inject:entry.inject,locale:entry.locale,store:entry.store},ReadableSources);
      };
      const unsubscribe=scope.slots.subscribe('conversation.chat.node',sync);
      sync();return ()=>{unsubscribe();dispose?.();};
    });
    for (const [native,seat] of Object.entries(seats)) {
      scope.slots.inject(seat, () => {
        let current, dispose;
        const sync=()=>{
          const entry=scope.slots.entriesOfSlot(native)[0];
          if(entry===current)return;
          dispose?.();current=entry;
          dispose=entry && scope.slots.register({name:seat,inject:entry.inject,
            locale:entry.locale,store:entry.store},entry.component);
        };
        const unsubscribe=scope.slots.subscribe(native,sync);
        sync();return ()=>{unsubscribe();dispose?.();};
      });
    }
  }
  function registerOriginals(scope) {
    scope.effect(() => scope.sidebarRightTabs.register({id:'@jsd-aird/original',kind:'enterprise-original',
      patterns:['dsh-resource://enterprise-original/**'],title:()=> '原文'}));
    scope.slots.inject('sidebar.right.pane.tab', () => scope.slots.register({
      name:'sidebar.right.pane.tab',key:'@jsd-aird/original',children:{
        [originalPdfSlot]:{kind:'keyed',scope:'session',inject:{hooks:{tabInfo:(_standard,info)=>info}}},
      },
    },OriginalBody));
    // Each child slot has one owner. Reuse the native registration in our own
    // seat, including its viewing store, locale and tab lifetime injection.
    // Subscribe because the native lazy renderer may register after this entry.
    scope.slots.inject(originalPdfSlot, () => {
      let current, dispose;
      const sync = () => {
        const entry = scope.slots.entriesOfSlot('sidebar.right.tab.document')
          .find(item => item.options.key === nativePdfKey);
        if (entry === current) return;
        dispose?.(); current = entry;
        dispose = entry && scope.slots.register({name:originalPdfSlot,key:nativePdfKey,
          store:entry.store,inject:entry.inject,locale:entry.locale},entry.component);
      };
      const unsubscribe = scope.slots.subscribe('sidebar.right.tab.document',sync);
      sync();
      return () => {unsubscribe();dispose?.();};
    });
    scope.effect(() => {
      const open = event => {
        if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
        const link = event.target.closest?.('a[href]');
        if (!link || link.hasAttribute('download')) return;
        const url = originalUrl(link.href);
        if (!url) return;
        event.preventDefault(); event.stopPropagation();
        scope.sidebarRight.openResource('dsh-resource://enterprise-original/'+encode(url.href));
      };
      document.addEventListener('click',open,true);
      return () => document.removeEventListener('click',open,true);
    });
  }
  function ResourceBody({ useTabInfo }) {
    const {tab} = useTabInfo();
    const token = tab.navigation.address.split('/').at(-1);
    const [value, setValue] = useState(null), [error, setError] = useState(''), [offset, setOffset] = useState(0);
    useEffect(() => {setOffset(0);}, [token]);
    useEffect(() => {
      const controller = new AbortController(); setValue(null); setError('');
      api('resource', {ref:refOnly(decode(token)),offset}, controller.signal).then(setValue)
        .catch(e => {if (!controller.signal.aborted) setError(e.message);});
      return () => controller.abort();
    }, [token, offset]);
    if (error) return h('p', {role:'alert',className:'jsd-resource'}, error);
    if (!value) return h('p',{className:'jsd-resource',role:'status'},'读取资料…');
    const ref = value.ref;
    const edit = ref.kind === 'PROJECT' ? '/projects/' + ref.resource_id : ref.kind === 'EXPERIMENT' ? '/experiments/' + ref.resource_id : null;
    return h('article',{className:'jsd-resource'},h('h2',null,value.title),h('p',null,'固定版本'),
      edit && h('a',{href:edit,target:'_blank',rel:'noopener'},'打开业务页面'),
      (value.candidates || []).map((item,index) => h('section',{key:index},
        h('small',null,item.source?.anchor?.page_no ? '第 ' + item.source.anchor.page_no + ' 页' : ''),h('pre',null,item.text))),
      offset > 0 && button('上一段',()=>setOffset(Math.max(0,offset-8))),
      value.next_offset != null && button('下一段',()=>setOffset(value.next_offset)));
  }
  function registerInputs(scope) {
    const open = value => scope.sidebarRight.openResource(addressFor(value));
    scope.sidebarRightTabs.register({id:'@jsd-aird/enterprise-resource',kind:'enterprise-resource',
      patterns:['dsh-resource://enterprise/**'],title:()=> '资料原文'});
    scope.slots.inject('sidebar.right.pane.tab', () => scope.slots.register({
      name:'sidebar.right.pane.tab',key:'@jsd-aird/enterprise-resource',
    },ResourceBody));
    const {ReferenceChip} = require('@deepseek-ai/dsh-client-ui-primitives');
    scope.uiConversation.userText.register({id:'enterprise-resource',order:0,match(text,from) {
      const expression = /\[([^\]\n]{1,240})\]\(jsd-resource:([A-Za-z0-9_-]{1,2048})\)/g; expression.lastIndex=from;
      const match = expression.exec(text);
      return match ? {id:'enterprise-resource',start:match.index,end:match.index+match[0].length,data:{label:match[1],token:match[2]}} : undefined;
    }});
    scope.slots.inject('conversation.user.text.node',()=>scope.slots.register({
      name:'conversation.user.text.node',key:'enterprise-resource',priority:100,
    },({node,interactive})=>h(ReferenceChip,{appearance:'file',label:node.data.label,title:node.data.label,
      onActivate:interactive === false ? undefined : ()=>open(decode(node.data.token))})));
    scope.effect(()=>scope.inputTriggers.registerSource({
      trigger:'@',name:'企业资料',order:-10,
      async candidates({sessionId},{query,signal}) {
        const result = await api('context',{session_id:sessionId,query},signal);
        return (result.choices || []).flatMap(item => {
          const row = {name:item.title,label:item.title,section:item.section,description:'引用固定版本',icon:'file',
            value:JSON.stringify({ref:item.ref,label:item.title})};
          return item.ref.kind === 'KB_PUBLICATION' ? [row,{...row,name:'只检索：'+item.title,
            label:'只检索：'+item.title,description:'仅限制本次请求',section:'仅本次检索',
            value:JSON.stringify({ref:{...item.ref,only:true},label:'只检索：'+item.title})}] : [row];
        });
      },
      onPick({candidate}) {
        const value = JSON.parse(candidate.value);
        return {insert:{source:'企业资料',ref:candidate.value,label:value.label,appearance:'file',
          clipboardText:marker(value.label,value.ref)}};
      },
      codec:{
        clipboardText(value) {const item=JSON.parse(value);return marker(item.label,item.ref);},
        async serialize(value,signal) {
          const item=JSON.parse(value), verified=await api('resource',{ref:refOnly(item.ref)},signal);
          return marker(item.ref.only ? '只检索：'+verified.title : verified.title,{...verified.ref,...(item.ref.only ? {only:true} : {})});
        },
      },
      openReference(_session,{ref}) {open(JSON.parse(ref).ref);return true;},
    }));
  }
  function registerBusinessToolViews(scope) {
    const {DisclosureRow,IconSearchOutlineRegular} = require('@deepseek-ai/dsh-client-ui-primitives');
    const titles = {tool_search:'查找企业能力',retrieve_context:'检索研发资料',knowledge_search:'检索文献',
      document_outline:'查看文献目录',document_read:'阅读文献',table_read:'读取表格',evidence_read:'核对原文',
      private_document_outline:'查看附件目录',private_document_read:'阅读附件',private_document_search:'检索附件',
      bibliography_lookup:'查询书目信息',literature_search:'检索论文',literature_expand:'查找相关论文',
      literature_acquire:'获取原文',literature_acquire_status:'检查原文状态',
      search_projects:'查找项目',get_project_snapshot:'读取项目',list_project_stages:'读取项目阶段',
      list_project_tasks:'读取项目任务',get_task_detail:'读取任务详情',search_experiments:'查找实验',
      get_experiment_record:'读取实验版本',create_experiment_draft:'创建实验草稿',
      report_check:'核查正式报告',report_save:'保存正式报告',artifact_save:'登记成果',
      evidence_score:'筛选证据',evidence_save:'保存证据',matrix_record:'整理研究依据',note_save:'保存研究笔记'};
    function BusinessTool({block,toolName,phase,useDisclosure}) {
      const {expanded,toggle} = useDisclosure();
      const meta = block.meta?.enterprise ? block.meta : {};
      let value;
      try {value = JSON.parse(block.content?.filter(part=>part.type==='text').map(part=>part.text).join('') || '{}');}
      catch {value = {};}
      const rows = value.candidates || value.hits || value.entries || [];
      const query = block.args?.text('query');
      const failed = block.isError || meta.failed || Boolean(value.error);
      const summary = failed ? '操作未完成' : phase !== 'result' ? query || '处理中' :
        meta.count !== undefined ? meta.count+' 条结果' : meta.summary || '已完成';
      const content = rows.slice(0,8).map((row,index)=>h('section',{key:index},
        h('strong',null,row.title || row.name || row.source?.title || '结果 '+(index+1)),
        h('p',{style:{whiteSpace:'pre-wrap'}},String(row.text || row.snippet || row.quote?.text || '').slice(0,1800))));
      const errorText = String(meta.summary || value.error || '业务请求未完成，请稍后继续。')
        .replace(/^[A-Z][A-Z_0-9]+:\s*/, '');
      return h(DisclosureRow,{className:'jsd-business-tool',contentClassName:'jsd-tool-header',
        contentLayoutClassName:'jsd-tool-header-content',
        title:titles[toolName] || '企业查询',icon:h(IconSearchOutlineRegular,{size:14}),
        open:expanded,expandable:phase==='result',onToggle:toggle,expandOnRowClick:true,
        running:phase!=='result',collapsedContent:h('span',{className:'jsd-tool-summary',title:summary},' · '+summary)},
        h('div',{className:'jsd-tool-content'},
          failed ? h('p',{role:'status'},errorText) : content.length ? content : h('p',null,meta.summary || '业务结果已返回'),
          (meta.sources || []).map((source,index)=>h('p',{key:'source-'+index},h('a',{href:source.url,className:'jsd-source-link',title:source.label},
            sourceLinks.sourceLinkLabel(source.label,new URL(source.url,location.origin)))))));
    }
    scope.slots.inject('tool.call.toolview',function* () {
      for (const key of Object.keys(titles)) yield scope.slots.register({name:'tool.call.toolview',key},BusinessTool);
    });
  }
  function latestTurnBoundary(window) {
    return window.entries.findLast(row=>row.type==='event' &&
      (row.event.type==='turn/start' || row.event.type==='turn/end'))?.event;
  }
  function continuationLabel(boundary) {
    if (boundary?.type!=='turn/end') return null;
    const reason=boundary.data.reason;
    if (reason.kind==='interrupted') return '任务已中断';
    if (reason.kind==='max-tokens') return '回答已截断';
    if (reason.kind==='aborted' && ['user','disposed','legacy'].includes(reason.reason.kind)) return '任务已暂停';
    return null;
  }
  function idleSession(session) {
    return session.openState==='open' && !session.running && !session.removed && !session.blank && session.subagent===null;
  }
  function registerDeletedSessionNavigation(scope) {
    function DeletedSessionNavigation({useSession,next}) {
      const removed=useSession(value=>value.removed), handled=useRef(false);
      useEffect(()=>{
        if (!removed) handled.current=false;
        else if (!handled.current) {handled.current=true;next();}
      },[removed,next]);
      return null;
    }
    scope.slots.inject('conversation.input.right',()=>scope.slots.register({
      name:'conversation.input.right',id:'enterprise-deleted-session-navigation',
      inject:()=>({next:()=>scope.uiWorkspace.startSession()}),
    },DeletedSessionNavigation));
  }
  function registerPluginMenuPolicy(scope) {
    scope.effect(()=>{
      let observer;
      // The pinned addon's legacy menu has public data markers; its newer
      // native menu omits them. Limit this presentation policy to an open
      // workspace menu. The corresponding server operations stay denied.
      const hideDesktopAction=()=>{
        for (const item of document.querySelectorAll('[role="menuitem"]')) {
          if (['打开目录','Open folder','Открыть папку'].includes(item.textContent.trim()))
            (item.parentElement?.childElementCount===1 ? item.parentElement : item).style.display='none';
        }
      };
      const update=()=>{
        observer?.disconnect();observer=undefined;
        if (scope.contextMenus.active.getSnapshot()?.kind!=='workspace') return;
        hideDesktopAction();observer=new MutationObserver(hideDesktopAction);
        observer.observe(document.body,{childList:true,subtree:true});
      };
      const unsubscribe=scope.contextMenus.active.subscribe(update);update();
      return ()=>{unsubscribe();observer?.disconnect();};
    });
  }
  function registerContinuation(scope) {
    const pending=new Map();
    const {IconTriangleRightFillRegular}=require('@deepseek-ai/dsh-client-ui-primitives');
    function Continuation({useSession,useInput,useEvents,useComposerBlock,useAccess,resume}) {
      const session=useSession(value=>value), input=useInput(value=>value);
      const boundary=useEvents(latestTurnBoundary), block=useComposerBlock(value=>value);
      const allowed=useAccess('session/prompt');
      const [busy,setBusy]=useState(false), [accepted,setAccepted]=useState(null);
      const label=continuationLabel(boundary);
      if (!label || !idleSession(session) || allowed===false || input.draft.trim()!=='' || input.attachmentIds.length>0) return null;
      const sending=busy || accepted===boundary.seq;
      const disabled=sending || input.phase!=='plain' || block!==undefined || session.pendingSubmissions.length>0;
      async function act() {
        if (disabled) return;
        setBusy(true);
        try {if (await resume(boundary.seq)) setAccepted(boundary.seq);}
        finally {setBusy(false);}
      }
      return button(sending ? h('svg',{className:'jsd-continue-spinner',viewBox:'0 0 16 16',width:16,height:16,'aria-hidden':true},
        h('circle',{cx:8,cy:8,r:6,fill:'none',stroke:'currentColor',strokeOpacity:.35,strokeWidth:2}),
        h('path',{d:'M8 2a6 6 0 0 1 6 6',fill:'none',stroke:'currentColor',strokeWidth:2,strokeLinecap:'round'})) :
        h(IconTriangleRightFillRegular,{size:16,'aria-hidden':true}),act,
        {className:'jsd-continue-action',disabled,'aria-label':'继续任务','aria-busy':sending,
          title:sending ? '继续中…' : '继续任务',onMouseDown:event=>event.preventDefault()});
    }
    scope.slots.inject('conversation.input.right',()=>scope.slots.register({
      name:'conversation.input.right',id:'enterprise-continuation',
      inject:sessionId=>{
        const binding=scope.sessions.binding(sessionId), input=scope.conversation.input.for(scope.sessions.scope(sessionId));
        const block=scope.conversation.blocks.storeFor(sessionId);
        return {hooks:{events:binding.eventSource,composerBlock:block},resume:async seq=>{
          const current=scope.sessions.binding(sessionId);
          if (current!==binding) return false;
          const boundary=latestTurnBoundary(binding.eventSource.getSnapshot());
          const session=binding.session.getSnapshot();
          const draft=input.state.getSnapshot();
          if (boundary?.seq!==seq || !continuationLabel(boundary) || !idleSession(session)
            || session.pendingSubmissions.length>0 || draft.phase!=='plain' || draft.draft.trim()!=='' || draft.attachmentIds.length>0
            || block.getSnapshot()!==undefined) return false;
          const requestId='enterprise-continue-'+sessionId+'-'+seq;
          if (pending.has(requestId)) return pending.get(requestId);
          // One native request per stopped boundary, also across remounts and lost responses.
          // Sending directly leaves the editor's draft, references and attachments intact.
          const operation=(async()=>{
            try {
              const result=await binding.session.prompt([{type:'text',text:'继续'}],'queue',undefined,requestId);
              return result.ok;
            } catch {
              input.notify('error','继续请求尚未确认，请检查连接后重试。');
              return false;
            }
          })();
          pending.set(requestId,operation);
          try {return await operation;}
          finally {pending.delete(requestId);}
        }};
      },
    },Continuation));
  }
  function registerAdmissionLabel(scope) {
    scope.effect(() => scope.resources.register({
      protocol:'enterprise-run-state',
      async *open(address,{signal}) {
        const sessionId=decodeURIComponent(new URL(address).pathname.slice(1));
        while(!signal.aborted) {
          try {
            const value=await api('run-state',{session_id:sessionId},signal);
            if(signal.aborted)return;
            yield {ok:true,value};
          } catch {
            if(signal.aborted)return;
            yield {ok:false,error:{code:'RUN_STATE_UNAVAILABLE',message:'正在重新连接，原请求已保留'}};
          }
          await new Promise(resolve=>{
            const done=()=>{clearTimeout(timer);signal.removeEventListener('abort',done);resolve();};
            const timer=setTimeout(done,1500);
            signal.addEventListener('abort',done,{once:true});
            if(signal.aborted)done();
          });
        }
      },
    }));
    function AdmissionLabel({sessionId,label,useResource}) {
      const state=useResource('dsh-resource://enterprise-run-state/'+encodeURIComponent(sessionId));
      const run=state.value?.run;
      const text=run?.status==='QUEUED'
        ? run.admission==='GRANTED' ? '正在准备任务…' : '当前任务较多，正在排队'
        : label;
      return h('span',{...(run?.status==='QUEUED' ? {role:'status','aria-live':'polite'}:{})},text);
    }
    scope.slots.inject('conversation.chat.running-label',()=>scope.slots.register({
      name:'conversation.chat.running-label',priority:-100,
    },AdmissionLabel));
  }
  return { inject: ['slots','theme'], apply(ctx) {
    ctx.effect(() => ctx.theme.overrideTokens('@jsd-aird/enterprise-plugin',enterpriseTheme.tokens));
    ctx.effect(() => {
      const style = document.createElement('style');
      style.dataset.jsdTheme = ''; style.textContent = enterpriseTheme.css;
      const previous = document.body.getAttribute('data-jsd-workbench');
      document.body.setAttribute('data-jsd-workbench',''); document.head.append(style);
      return () => {
        style.remove();
        if (previous === null) document.body.removeAttribute('data-jsd-workbench');
        else document.body.setAttribute('data-jsd-workbench',previous);
      };
    });
    ctx.slots.inject('sidebar.brand.mark',()=>ctx.slots.register({name:'sidebar.brand.mark'},BrandMark));
    ctx.slots.inject('sidebar.brand.name',()=>ctx.slots.register({name:'sidebar.brand.name'},()=>h('span',{className:'jsd-brand-name'},'AI工作台')));
    ctx.slots.inject('conversation.hero.brand.mark',()=>ctx.slots.register({name:'conversation.hero.brand.mark'},BrandMark));
    ctx.slots.inject('conversation.header.leading',()=>ctx.slots.register({name:'conversation.header.leading',id:'enterprise-page-title',order:-10},
      ()=>h('h1',{className:'jsd-page-title'},'AI工作台')));
    ctx.slots.inject('sidebar.footer.action',()=>ctx.slots.register({name:'sidebar.footer.action',id:'enterprise-navigation'},Navigation));
    ctx.inject(['inputTriggers','uiConversation','sidebarRight','sidebarRightTabs'],registerInputs);
    ctx.inject(['sidebarRight','sidebarRightTabs'],registerOriginals);
    ctx.inject(['resources'],registerAdmissionLabel);
    ctx.inject(['conversation','sessions'],registerContinuation);
    ctx.inject(['uiWorkspace'],registerDeletedSessionNavigation);
    ctx.inject(['contextMenus'],registerPluginMenuPolicy);
    ctx.inject(['conversation','sessions'],registerWelcomeCards);
    registerSourceMarkdown(ctx);
    registerBusinessToolViews(ctx);
  }};
}});
