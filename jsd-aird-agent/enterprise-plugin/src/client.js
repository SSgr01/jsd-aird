window.__ModuleLoader__.load({ id: '@jsd-aird/enterprise-plugin', factory: require => {
  const React = require('react'), h = React.createElement;
  const { useEffect, useState, useRef } = React;
  const enterpriseTheme = /* @enterprise-theme */ null;
  const sourceLinks = /* @source-links */ null;
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
      get_compute_context:'读取计算上下文',run_model_training:'建立模型',activate_trained_model:'启用模型',
      get_compute_result:'读取计算结果',get_compute_receipt:'查询操作回执',
      run_prediction:'预测配方表现',run_formula_design:'推荐配方',run_experiment_optimization:'推荐实验方案',
      save_experiment_measurements:'保存实测草稿',get_experiment_loop:'读取实验进展',
      continue_experiment_optimization:'继续优化',get_research_briefing_context:'读取项目进展',
      report_check:'核查正式报告',report_save:'保存正式报告',artifact_save:'登记成果',
      evidence_score:'筛选证据',evidence_save:'保存证据',matrix_record:'整理研究依据',note_save:'保存研究笔记'};
    function BusinessTool({block,toolName,phase,useDisclosure}) {
      const {expanded,toggle} = useDisclosure();
      const meta = block.meta?.enterprise ? block.meta : {};
      let value;
      try {value = JSON.parse(block.content?.filter(part=>part.type==='text').map(part=>part.text).join('') || '{}');}
      catch {value = {};}
      const rows = value.candidates || value.hits || value.entries || [];
      const businessText = text => String(text || '').replace(/测试实验|验收|测试|模拟|验证/g,
        word => ({验收:'完成检查',测试:'测定',模拟:'研发参考',验证:'评估'})[word] || word);
      const query = businessText(block.args?.text('query'));
      const failed = block.isError || meta.failed || Boolean(value.error);
      const summary = failed ? '操作未完成' : phase !== 'result' ? query || '处理中' :
        meta.count !== undefined ? meta.count+' 条结果' : businessText(meta.summary) || '已完成';
      const content = rows.slice(0,8).map((row,index)=>h('section',{key:index},
        h('strong',null,row.title || row.name || row.source?.title || '结果 '+(index+1)),
        h('p',{style:{whiteSpace:'pre-wrap'}},String(row.text || row.snippet || row.quote?.text || '').slice(0,1800))));
      const errorText = businessText(meta.summary || value.error || '业务请求未完成，请稍后继续。')
        .replace(/^[A-Z][A-Z_0-9]+:\s*/, '');
      return h(DisclosureRow,{className:'jsd-business-tool',contentClassName:'jsd-tool-header',
        contentLayoutClassName:'jsd-tool-header-content',
        title:titles[toolName] || '企业查询',icon:h(IconSearchOutlineRegular,{size:14}),
        open:expanded,expandable:phase==='result',onToggle:toggle,expandOnRowClick:true,
        running:phase!=='result',collapsedContent:h('span',{className:'jsd-tool-summary',title:summary},' · '+summary)},
        h('div',{className:'jsd-tool-content'},
          failed ? h('p',{role:'status'},errorText) : content.length ? content : h('p',null,businessText(meta.summary) || '业务结果已返回'),
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
