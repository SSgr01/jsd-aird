/** WorkBuddy-style discovery on native main/menu/modal seats; execution stays native. */
export function registerExpertsUI(scope,{React,api,Modal,Menu,MenuItemButton,tasks}) {
  const h=React.createElement,{useState,useEffect,useSyncExternalStore}=React;
  const PANEL='enterprise-experts',listeners=new Set();
  let dialog=null;
  const subscribe=listener=>{listeners.add(listener);return()=>listeners.delete(listener);};
  const show=value=>{dialog=value;for(const listener of listeners)listener();};
  const action=(label,onClick,props={})=>h('button',{type:'button',...props,onClick,
    className:'jsd-expert-button '+(props.className || '')},label);
  const feedback=error=>({EXPERT_REQUIRES_NEW_SESSION:'请新建会话使用其他专家。',
    SYS_NOT_FOUND:'此专家已不可用，请刷新专家中心。',OPERATION_FORBIDDEN:'此专家已停用，请选择其他专家。'}[error.code] || error.message);
  function ExpertIcon({size=18}) {
    return h('svg',{width:size,height:size,viewBox:'0 0 20 20',fill:'none',stroke:'currentColor',strokeWidth:1.4,'aria-hidden':true},
      h('rect',{x:3,y:5,width:14,height:11,rx:3}),h('path',{d:'M10 2v3M6 16v2m8-2v2M1 9v3m18-3v3M7 12h6'}),
      h('circle',{cx:7,cy:9,r:.6}),h('circle',{cx:13,cy:9,r:.6}));
  }
  function Avatar({expert,small=false}) {
    const paths={
      'spectrum-assistant':[['path',{d:'M3 3v18h18M5 17l3-2 2 1 3-11 3 13 2-6 3-1'}]],
      'project-assistant':[['rect',{x:3.5,y:5,width:17,height:15,rx:2.5}],['path',{d:'M8 3v4m8-4v4M4 10h16M8 14h2m-2 3h2m4-3h2m-2 3h2'}]],
      'customer-assistant':[['path',{d:'M7 16.5 3.5 20v-5A8 8 0 0 1 3 12c0-4.4 4-8 9-8s9 3.6 9 8-4 8-9 8c-1.2 0-2.4-.2-3.5-.6'}],['circle',{cx:12,cy:9.5,r:2}],['path',{d:'M8 16c.6-2.6 7.4-2.6 8 0'}]],
      'experiment-assistant':[['path',{d:'M9 3h6m-5 0v6l-5.5 9.2A2.5 2.5 0 0 0 6.7 22h10.6a2.5 2.5 0 0 0 2.2-3.8L14 9V3M8 15h8'}],['circle',{cx:10,cy:18,r:.7,fill:'currentColor',stroke:'none'}],['circle',{cx:14.5,cy:19,r:.7,fill:'currentColor',stroke:'none'}]],
      'quality-assistant':[['path',{d:'m12 2 8 3.5v6c0 4.6-3.3 8-8 10.5-4.7-2.5-8-5.9-8-10.5v-6L12 2Z'}],['path',{d:'m8 11.5 2.7 2.7L16 9'}]],
      'production-assistant':[['path',{d:'M3 21V10l6 3V8l6 3V4h4l2 17H3Z'}],['path',{d:'M7 17h1m4 0h1m4 0h1M15 7h4'}]],
      'materials-advisor':[['path',{d:'m12 3 7.8 4.5v9L12 21l-7.8-4.5v-9L12 3Zm0 0v7m7.8-2.5-6 3.5m6 5.5-6-3.5M12 21v-7m-7.8 2.5 6-3.5m-6-5.5 6 3.5'}],['circle',{cx:12,cy:12,r:2}]],
      'technical-assistant':[['path',{d:'M12 5.5C8 2.8 4.5 3.5 3 4v15c3.3-1 6-.5 9 1.5 3-2 5.7-2.5 9-1.5V4c-1.5-.5-5-1.2-9 1.5Zm0 0v15M6.5 8l2 .5m-2 3 2 .5m7-3.5 2-.5m-2 4 2-.5'}]],
      'data-assistant':[['rect',{x:3,y:3,width:18,height:18,rx:3}],['path',{d:'M3 9h18M9 9v12m6-12v12M3 15h18M7 6h.01m3 0h.01'}]],
    };
    return h('span',{className:'jsd-expert-avatar'+(small?' jsd-expert-avatar-small':''),'data-expert':expert.id,'aria-hidden':true},
      h('svg',{viewBox:'0 0 24 24',fill:'none',stroke:'currentColor',strokeWidth:1.65,strokeLinecap:'round',strokeLinejoin:'round'},
        (paths[expert.id] || paths['project-assistant']).map(([tag,props],index)=>h(tag,{key:index,...props}))));
  }
  function PinIcon({pinned}) {
    return h('svg',{width:16,height:16,viewBox:'0 0 20 20',fill:pinned?'currentColor':'none',stroke:'currentColor',strokeWidth:1.2,'aria-hidden':true},
      h('path',{d:'m10 2 2.5 5.1 5.6.8-4 4 .9 5.6-5-2.7-5 2.7.9-5.6-4-4 5.6-.8Z'}));
  }
  function useExpert(sessionId) {
    const [state,setState]=useState({sessionId:null,expert:null,loading:true}),[attempt,retry]=useState(0);
    useEffect(()=>{
      const controller=new AbortController();
      setState({sessionId,expert:null,loading:true});
      api('experts',{action:'session',session_id:sessionId},controller.signal)
        .then(value=>{if(!controller.signal.aborted)setState({sessionId,expert:value.expert,loading:false});})
        .catch(error=>{if(!controller.signal.aborted)setState({sessionId,expert:null,loading:false,error:feedback(error)});});
      return()=>controller.abort();
    },[sessionId,attempt]);
    return {...(state.sessionId===sessionId?state:{expert:null,loading:true}),retry:()=>retry(n=>n+1)};
  }
  function useCatalog() {
    const [catalog,setCatalog]=useState(null),[error,setError]=useState(''),[attempt,retry]=useState(0);
    useEffect(()=>{
      const controller=new AbortController();
      setError('');
      api('experts',{action:'list'},controller.signal).then(value=>{if(!controller.signal.aborted)setCatalog(value);})
        .catch(error=>{if(!controller.signal.aborted)setError(feedback(error));});
      return()=>controller.abort();
    },[attempt]);
    return {catalog,setCatalog,error,setError,retry:()=>retry(n=>n+1)};
  }
  async function summon(expert,prompt) {
    const value=await api('experts',{action:'summon',expert_id:expert.id});
    const reference=scope.sessions.retain(value.session_id,{source:'enterprise-expert'});
    try {
      const binding=await reference.ready;
      scope.uiWorkspace.openSession(value.session_id);
      if(prompt && (!binding || scope.conversation.input.requestDraftInitialization(binding,
        {prompt,whenDraftExists:'append'})!=='applied')) throw new Error('专家会话已创建，示例未填入，请打开会话后重试。');
    } finally {reference.release();}
    show(null);
  }
  function ErrorNotice({error,retry,busy}) {
    return error && h('div',{role:'alert',className:'jsd-expert-error'},error,' ',action('重试',retry,{disabled:busy}));
  }
  function WorkConfig({config,skills}) {
    if(!config)return null;
    const {scope:workScope,checks,delivery,pending}=config;
    const names={'project-diagnosis':'项目诊断','meeting-actions':'会议行动项整理','work-briefing':'工作简报编制',
      'customer-requirements':'客户需求整理与比较','business-reply':'业务回复起草','record-validation':'记录整理与核验',
      'experiment-comparison':'实验对比','quality-review':'检测质量核对与文件草稿','production-reconciliation':'生产库存与数量核对',
      'formulation-analysis':'配方分析','research-design':'竞争假设与实验设计','literature-review':'文献调研与研读',
      'technical-comparison':'论文专利与技术路线比较','table-preparation':'表格整理','jsd-experiment-converter':'实验记录标准化转换','spectrum-analysis':'图谱解读与来源核对'};
    return h('section',{className:'jsd-expert-config','aria-label':'默认工作设置'},
      h('h4',null,'默认工作设置'),
      h('dl',null,
        h('dt',null,'工作范围'),h('dd',null,workScope.domains.join('、'),h('p',null,workScope.selection)),
        h('dt',null,'共享技能'),h('dd',null,skills.map(name=>names[name] || name).join('、')),
        h('dt',null,'重点核验'),h('dd',null,checks.required_fields.map(field=>field.label).join('、') || '按实际业务模板核验'),
        Object.keys(checks.unit_targets).length>0 && h(React.Fragment,null,
          h('dt',null,'默认单位'),h('dd',null,Object.values(checks.unit_targets).join('、'))),
        h('dt',null,'检查规则'),h('dd',null,h('ul',null,checks.rules.map(text=>h('li',{key:text},text)))),
        h('dt',null,'默认交付'),h('dd',null,delivery.sections.join('＋'),
          h('p',null,'平台通用结构 · 草稿，按当前任务选用。')),
        pending.length>0 && h(React.Fragment,null,h('dt',null,'待提供'),h('dd',null,pending.join('；')))),
      h('p',{className:'jsd-expert-note'},'公司统一维护。未提供的标准会标记待确认。'));
  }
  function ExpertDetail({expert,sessionId}) {
    const [busy,setBusy]=useState(false),[error,setError]=useState('');
    const lastPrompt=React.useRef();
    async function start(prompt) {
      lastPrompt.current=prompt;
      setBusy(true);setError('');
      try {await summon(expert,prompt);}catch(error){setError(feedback(error));}finally{setBusy(false);}
    }
    return h(Modal,{open:true,title:'专家详情',closeLabel:'关闭专家详情',className:'jsd-expert-dialog',
      contentClassName:'jsd-expert-content',backdropBlur:false,onClose:()=>{if(!busy)show(null);}},
      h('article',{className:'jsd-expert-detail','data-expert-detail':true},
        h('div',{className:'jsd-expert-identity'},h(Avatar,{expert}),h('div',null,h('h3',null,expert.name),h('p',null,'杰事达 · 公司专家')),
          action(busy?'正在准备…':expert.enabled?'召唤专家':'已停用',()=>start(),{disabled:busy || !expert.enabled,className:'jsd-expert-primary'})),
        h('p',{className:'jsd-expert-summary'},expert.summary),
        h('div',{className:'jsd-expert-tags'},expert.capabilities.map(text=>h('span',{key:text},text))),
        h('div',{className:'jsd-expert-meta'},h('span',null,expert.category)),
        h(ErrorNotice,{error,retry:()=>start(lastPrompt.current),busy}),
        h('h4',null,'专家帮你做'),h('p',{className:'jsd-expert-note'},'选择一个任务示例，填入草稿后即可开始。'),
        h('div',{className:'jsd-expert-examples'},expert.examples.map(text=>
          action(h(React.Fragment,null,h('span',null,text),h('span',{'aria-hidden':true},'↗')),()=>start(text),
            {key:text,disabled:busy || !expert.enabled,title:'新建专家会话并填入可编辑草稿'}))),
        h(WorkConfig,{config:expert.work_config,skills:expert.skills}),
        h('details',{className:'jsd-expert-role'},h('summary',null,'专家职责'),h('p',null,expert.instructions)),
        h('p',{className:'jsd-expert-note'},sessionId?'使用其他专家会新开会话，当前草稿和附件会保留。':'可使用你获准访问的资料和主动上传的文件。')));
  }
  function ExpertCenter() {
    const {catalog,setCatalog,error,setError,retry}=useCatalog();
    const [query,setQuery]=useState(''),[category,setCategory]=useState('全部'),[view,setView]=useState('全部');
    const [busy,setBusy]=useState(false);
    async function pin(expert) {
      setBusy(true);setError('');
      try {setCatalog(await api('experts',{action:'pin',expert_id:expert.id,pinned:!catalog.pinned.includes(expert.id)}));}
      catch(error){setError(feedback(error));}finally{setBusy(false);}
    }
    async function start(expert) {
      setBusy(true);setError('');
      try {await summon(expert);}catch(error){setError(feedback(error));}finally{setBusy(false);}
    }
    const categories=['全部',...new Set((catalog?.experts || []).map(row=>row.category))];
    const ids=view==='常用'?catalog?.pinned:view==='最近'?catalog?.recent:null;
    const ordered=ids?ids.map(id=>catalog.experts.find(row=>row.id===id)).filter(Boolean):(catalog?.experts || []);
    const rows=ordered
      .filter(row=>category==='全部' || row.category===category)
      .filter(row=>[row.name,row.summary,...row.capabilities].join(' ').toLowerCase().includes(query.trim().toLowerCase()));
    return h('section',{className:'jsd-expert-page','data-expert-center':true},
      h('header',{className:'jsd-expert-page-header'},h('h1',null,h(ExpertIcon,{size:22}),'专家中心'),
        h('input',{type:'search',value:query,onChange:e=>setQuery(e.target.value),placeholder:'搜索专家','aria-label':'搜索专家'})),
      h('div',{className:'jsd-experts'},
        h('div',{className:'jsd-expert-heading'},h('div',null,h('h2',null,'公司专家'),h('p',null,'找到懂你业务的专家，把工作交给它。')),
          h('div',{className:'jsd-expert-views',role:'group','aria-label':'专家列表'},['全部','常用','最近'].map(name=>
            action(name,()=>setView(name),{key:name,'aria-pressed':view===name})))),
        h('div',{className:'jsd-expert-categories',role:'group','aria-label':'专业领域'},categories.map(name=>
          action(name,()=>setCategory(name),{key:name,'aria-pressed':category===name}))),
        h(ErrorNotice,{error,retry,busy}),
        !catalog ? h('p',{role:'status'},error?'专家列表暂时不可用。':'正在加载公司专家…') : !rows.length ? h('div',{className:'jsd-expert-empty'},
          h('p',null,query?'没有匹配的专家，请试试其他关键词。':view==='常用'?'还没有常用专家。':view==='最近'?'使用过的专家会显示在这里。':'此领域暂无专家。'),
          action('浏览全部专家',()=>{setView('全部');setCategory('全部');setQuery('');})) :
        h('div',{className:'jsd-expert-grid'},rows.map(expert=>h('article',{key:expert.id,className:'jsd-expert-card'},
          h('div',{className:'jsd-expert-card-top'},
            action(h(React.Fragment,null,h(Avatar,{expert}),h('span',null,h('strong',null,expert.name),h('small',null,expert.category))),
              ()=>show({expert}),{className:'jsd-expert-card-title','aria-label':'查看 '+expert.name+' 详情',disabled:busy}),
            action(h(PinIcon,{pinned:catalog.pinned.includes(expert.id)}),()=>pin(expert),{disabled:busy,'aria-pressed':catalog.pinned.includes(expert.id),
              'aria-label':(catalog.pinned.includes(expert.id)?'取消常用：':'设为常用：')+expert.name,className:'jsd-expert-pin'})),
          h('p',null,expert.summary),
          h('div',{className:'jsd-expert-tags'},expert.capabilities.slice(0,2).map(text=>h('span',{key:text},text))),
          h('div',{className:'jsd-expert-card-bottom'},
            action(expert.enabled?'召唤':'已停用',()=>start(expert),{disabled:busy || !expert.enabled})))))));
  }
  function Overlay() {
    const current=useSyncExternalStore(subscribe,()=>dialog);
    return current && h(ExpertDetail,{key:current.expert.id,...current});
  }
  function QuickPicker({expert,sessionId,onClose}) {
    const {catalog,error,retry}=useCatalog(),[query,setQuery]=useState(''),[busy,setBusy]=useState(false),[failure,setFailure]=useState('');
    const all=catalog?.experts || [],term=query.trim().toLowerCase();
    const ids=catalog?[...new Set([...catalog.pinned,...catalog.recent])]:[];
    const rows=term?all.filter(row=>[row.name,row.summary,...row.capabilities].join(' ').toLowerCase().includes(term)):
      (ids.length?ids.map(id=>all.find(row=>row.id===id)).filter(Boolean):all).slice(0,6);
    async function start(row) {
      if(row.id===expert?.id){onClose();return;}
      setBusy(true);setFailure('');
      try {await summon(row);onClose();}catch(error){setFailure(feedback(error));}finally{setBusy(false);}
    }
    return h('div',{className:'jsd-expert-quick'},
      h('input',{type:'search',value:query,onChange:e=>setQuery(e.target.value),placeholder:'搜索专家','aria-label':'快速搜索专家',
        onKeyDown:e=>{if(e.key!=='Escape' && e.key!=='ArrowDown' && e.key!=='ArrowUp' && e.key!=='Tab')e.stopPropagation();}}),
      h(ErrorNotice,{error:error || failure,retry:()=>{setFailure('');retry();},busy}),
      !catalog && !error && h('p',{role:'status'},'正在加载专家…'),
      catalog && !rows.length && h('p',{role:'status'},'没有匹配的专家'),
      rows.map(row=>h(MenuItemButton,{key:row.id,icon:h(Avatar,{expert:row,small:true}),current:row.id===expert?.id,
        disabled:busy || !row.enabled,onSelect:()=>start(row)},row.name,catalog.pinned.includes(row.id)?' · 常用':'')),
      expert && h(MenuItemButton,{onSelect:()=>{onClose();show({expert,sessionId});}},'查看当前专家详情'),
      h(MenuItemButton,{separatorBefore:true,onSelect:()=>{onClose();scope.layout.selectPanel(PANEL);}},'↗ 召唤更多专家'),
      sessionId && h('p',{className:'jsd-expert-note'},'切换专家会新开会话'));
  }
  function Selector({sessionId}) {
    const {expert,error,retry,loading}=useExpert(sessionId),[open,setOpen]=useState(false);
    return h('div',{className:'jsd-expert-selector'},
      h(Menu,{open,onClose:()=>setOpen(false),side:'top',portal:true,autoFocus:false,listClassName:'jsd-expert-quick-menu',
        anchor:action(h(React.Fragment,null,expert?h(Avatar,{expert,small:true}):h(ExpertIcon),h('span',null,expert?.name || '专家'),h('span',{'aria-hidden':true},'⌄')),
          ()=>setOpen(!open),{disabled:loading,'aria-label':expert?'当前专家：'+expert.name+'，选择专家':'选择专家','aria-expanded':open})},
        open && h(QuickPicker,{expert,sessionId,onClose:()=>setOpen(false)})),
      expert?.enabled===false && h('span',{role:'status'},'已停用'),
      error && action('重试',retry,{title:error}));
  }
  function ExpertWelcome({sessionId,useSession,fillPrompt}) {
    const blank=useSession(value=>value.blank),{expert}=useExpert(sessionId),[notice,setNotice]=useState('');
    if(!blank || !expert)return null;
    return h('details',{className:'jsd-expert-welcome','aria-label':expert.name+' 的任务示例'},
      h('summary',{className:'jsd-expert-summary'},'当前专家：'+expert.name+' · 查看任务示例'),
      h('p',null,expert.summary),
      h('div',{className:'jsd-expert-examples'},expert.examples.map(text=>action(h(React.Fragment,null,h('span',null,text),h('span',{'aria-hidden':true},'↗')),
        ()=>setNotice(fillPrompt(text)?'已填入草稿，确认后发送。':'输入框正忙，请稍后再试。'),
        {key:text,disabled:expert.enabled===false,title:'填入可编辑草稿'}))),
      notice && h('p',{role:'status'},notice));
  }
  scope.slots.inject('main',()=>scope.slots.register({name:'main',key:PANEL},ExpertCenter));
  scope.slots.inject('sidebar.panellist',()=>scope.slots.register({name:'sidebar.panellist',id:PANEL,order:-20,label:()=> '专家中心'},ExpertIcon));
  scope.slots.inject('shell.overlay',()=>scope.slots.register({name:'shell.overlay',id:PANEL},Overlay));
  scope.slots.inject('conversation.input.left',()=>scope.slots.register({name:'conversation.input.left',id:'enterprise-expert-selector',order:-20,
    inject:sessionId=>({sessionId}),
  },Selector));
  scope.slots.inject('conversation.input.dock',()=>scope.slots.register({name:'conversation.input.dock',id:'enterprise-expert-welcome',order:-30,
    inject:sessionId=>({sessionId,fillPrompt:prompt=>{
      const binding=scope.sessions.binding(sessionId);
      if(!binding || scope.conversation.input.requestDraftInitialization(binding,{prompt,whenDraftExists:'append'})!=='applied')return false;
      scope.conversation.input.for(scope.sessions.scope(sessionId)).focus();return true;
    }}),
  },ExpertWelcome));
  return {useExpert,startTask:id=>tasks.startWorkbenchTask(id,summon)};
}
