window.__ModuleLoader__.load({ id: '@jsd-aird/enterprise-plugin', factory: require => {
  const React = require('react'), h = React.createElement;
  const { useEffect, useState, useRef } = React;
  const enterpriseTheme = /* @enterprise-theme */ null;
  const base = '/agent/';
  async function api(action, body, signal) {
    const response = await fetch(base + 'business/' + action, { method: 'POST',
      headers: { 'content-type': 'application/json' }, body: JSON.stringify(body), signal });
    if (!response.ok) throw new Error('暂时无法读取，请检查登录和资料权限。');
    return response.json();
  }
  const button = (label, onClick, more = {}) => h('button', { type: 'button', onClick, ...more }, label);
  function Navigation({ wide }) {
    const links = [
      ['/', '企业首页', 'M3 10 12 3l9 7M5 9v12h5v-7h4v7h5V9'],
      ['/projects', '项目', 'M3 7h7l2 2h9v11H3zM3 7V4h7l2 3'],
      ['/experiments', '实验', 'M9 3h6M10 3v7l-6 9q-1 2 2 2h12q3 0 2-2l-6-9V3M7 15h10'],
      ['/knowledge/library', '知识库', 'M12 5q-5-3-10-1v15q5-2 10 1 5-3 10-1V4q-5-2-10 1v15'],
    ];
    return h('nav', { className: 'jsd-nav', 'data-wide':String(wide !== false), 'aria-label': '企业导航' },
      links.map(([href,label,path]) => h('a',{key:href,href,title:label,'aria-label':label},
        h('svg',{viewBox:'0 0 24 24',fill:'none',stroke:'currentColor',strokeWidth:1.5,
          strokeLinecap:'round',strokeLinejoin:'round','aria-hidden':true},h('path',{d:path})),
        wide !== false && h('span',null,label))));
  }
  function BrandMark({size,className = ''}) {
    return h('span',{className:'jsd-brand-mark '+className,'aria-hidden':true},
      h('svg',{width:size,height:size,viewBox:'0 0 24 24',fill:'none',stroke:'currentColor',strokeWidth:1.5},
        [ [3,3], [14,3], [3,14], [14,14] ].map(([x,y])=>h('rect',{key:x+'-'+y,x,y,width:7,height:7,rx:1.5}))));
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
    function BusinessTool({block,toolName,phase,useDisclosure,inspect}) {
      const {expanded,toggle} = useDisclosure();
      const meta = block.meta?.enterprise ? block.meta : {};
      let value;
      try {value = JSON.parse(block.content?.filter(part=>part.type==='text').map(part=>part.text).join('') || '{}');}
      catch {value = {};}
      const rows = value.candidates || value.hits || value.entries || [];
      const query = block.args?.text('query');
      const summary = block.isError ? '操作未完成' : phase !== 'result' ? query || '处理中' :
        meta.count !== undefined ? meta.count+' 条结果' : meta.summary || '已完成';
      const content = rows.slice(0,8).map((row,index)=>h('section',{key:index},
        h('strong',null,row.title || row.name || row.source?.title || '结果 '+(index+1)),
        h('p',{style:{whiteSpace:'pre-wrap'}},String(row.text || row.snippet || row.quote?.text || '').slice(0,1800))));
      return h(DisclosureRow,{title:titles[toolName] || '企业查询',icon:h(IconSearchOutlineRegular,{size:14}),
        open:expanded,expandable:phase==='result',onToggle:toggle,expandOnRowClick:true,
        running:phase!=='result',collapsedContent:h('span',null,' · '+summary)},
        h('div',{style:{padding:'8px 24px',fontSize:13,lineHeight:1.65}},
          block.isError ? h('p',{role:'status'},'操作未完成，可展开轨迹查看详情。') : content.length ? content : h('p',null,meta.summary || '业务结果已返回'),
          (meta.sources || []).map((source,index)=>h('p',{key:'source-'+index},h('a',{href:source.url},source.label))),
          inspect && button('查看轨迹',inspect)));
    }
    scope.slots.inject('tool.call.toolview',function* () {
      for (const key of Object.keys(titles)) yield scope.slots.register({name:'tool.call.toolview',key},BusinessTool);
    });
  }
  function registerInboxActions(scope) {
    function InboxActions({useProjection,useInput,sessionId,putBack,notify}) {
      const queue = useProjection('inbox')?.['next-turn'] || [];
      const phase = useInput(value => value.phase);
      const [selected,setSelected] = useState(''), [busy,setBusy] = useState(false);
      const index = Math.max(0,queue.findIndex(item=>item.id===selected)), item = queue[index];
      if (!item) return null;
      const disabled = busy || phase !== 'plain';
      async function act(kind,direction) {
        setBusy(true);
        let retained = false;
        try {
          const text = kind==='take' ? item.content.map(block=>block.text).join('') : undefined;
          // Keep the native draft before removing its durable queue occurrence.
          // A lost response leaves a visible copy, never a silently lost message.
          if (kind==='take') {putBack(text);retained=true;}
          await api('inbox',{session_id:sessionId,item_id:item.id,kind,direction,expected_text:text,
            expected_ids:queue.map(value=>value.id)});
          if (kind!=='take') setSelected(item.id);
        } catch {notify(retained ? '文字已保留在输入框，队列操作尚未确认；请核对后再发送。' :
          '队列已变化或操作未完成，请核对当前消息后继续。');}
        finally {setBusy(false);}
      }
      return h('div',{className:'jsd-inbox-actions',role:'group','aria-label':'原生队列操作'},
        h('select',{'aria-label':'选择排队消息',value:item.id,disabled,onChange:event=>setSelected(event.target.value)},
          queue.map((row,position)=>h('option',{key:row.id,value:row.id},(position+1)+'. '+
            (row.content.filter(block=>block.type==='text').map(block=>block.text).join('').slice(0,50)||'附件消息')))),
        button('上移',()=>act('move',-1),{disabled:disabled||index===0}),
        button('下移',()=>act('move',1),{disabled:disabled||index===queue.length-1}),
        button('放回输入框',()=>act('take'),{disabled:disabled||!item.content.every(block=>block.type==='text'),
          title:'保留现有输入；含附件的消息继续使用原生队列操作'}));
    }
    scope.slots.inject('conversation.input.dock',()=>scope.slots.register({
      name:'conversation.input.dock',id:'enterprise-inbox-actions',order:21,
      inject:sessionId=>{
        const actx=scope.sessions.scope(sessionId), input=scope.conversation.input.for(actx);
        return {sessionId,notify:text=>input.notify('error',text),putBack:text=>{
          const binding=scope.sessions.binding(sessionId);
          if (!binding || scope.conversation.input.requestDraftInitialization(binding,
            {prompt:text,whenDraftExists:'append'}) !== 'applied') throw new Error('INPUT_BUSY');
          input.focus();
        }};
      },
    },InboxActions));
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
    ctx.slots.inject('sidebar.brand.name',()=>ctx.slots.register({name:'sidebar.brand.name'},()=>h('span',{className:'jsd-brand-name'},'研发工作台')));
    ctx.slots.inject('conversation.hero.brand.mark',()=>ctx.slots.register({name:'conversation.hero.brand.mark'},BrandMark));
    ctx.slots.inject('conversation.header.leading',()=>ctx.slots.register({name:'conversation.header.leading',id:'enterprise-page-title',order:-10},
      ()=>h('h1',{className:'jsd-page-title'},'AI 问答')));
    ctx.slots.inject('sidebar.footer.action',()=>ctx.slots.register({name:'sidebar.footer.action',id:'enterprise-navigation'},Navigation));
    ctx.inject(['inputTriggers','uiConversation','sidebarRight','sidebarRightTabs'],registerInputs);
    ctx.inject(['sidebarRight','sidebarRightTabs'],registerOriginals);
    ctx.inject(['conversation','sessions'],registerInboxActions);
    ctx.inject(['conversation','sessions'],registerWelcomeCards);
    registerBusinessToolViews(ctx);
  }};
}});
