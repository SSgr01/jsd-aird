/** Browser settings and session controls. All authority is checked by Control. */
export function registerMemoryUI(scope,{React,api,Switch,Modal,Toast}) {
  const h=React.createElement, {useState,useEffect,useRef,useSyncExternalStore}=React;
  const settingsListeners=new Set(), sessionListeners=new Set();
  let dialogSession=null, memoryAvailable;
  function showSessionMemory(sessionId) {
    dialogSession=sessionId;
    for(const listener of sessionListeners)listener();
  }
  const subscribeSession=listener=>{sessionListeners.add(listener);return()=>sessionListeners.delete(listener);};
  const call=async(body,signal)=>{
    let value;
    try {value=await api('memory',body,signal);}
    catch(e){if(e.code==='MEMORY_NOT_ENABLED')memoryAvailable=false;throw e;}
    memoryAvailable=true;
    if(['initialize','set-settings'].includes(body.action))for(const listener of settingsListeners)listener(value);
    return value;
  };
  const status={PENDING:'整理中',SUCCEEDED:'已保存',ACTIVE:'已保存',UNKNOWN:'待核对',FAILED:'未能完成',CANCELLED:'已停止'};
  const category={preference:'偏好',decision:'确认的决定',experience:'工作经验'};
  const errorMessage=error=>({MEMORY_REVISION_CHANGED:'记忆已被更新，请刷新后再编辑。',MEMORY_BUDGET_EXCEEDED:'今日记忆处理额度已用尽，作业会保留等待。',
    MEMORY_SOURCE_EXCLUDED:'该来源已停止参与记忆。',MEMORY_NOT_ENABLED:'记忆服务尚未启用。'}[error.code] || error.message || '暂时无法读取记忆，请稍后重试。');
  const action=(label,onClick,props={})=>h('button',{type:'button',onClick,...props,className:'jsd-memory-button '+(props.className || '')},label);
  const pending=value=>['PENDING','UNKNOWN'].includes(value);
  const dateLabel=value=>value && !Number.isNaN(Date.parse(value))
    ? new Intl.DateTimeFormat('zh-CN',{month:'short',day:'numeric'}).format(new Date(value)) : '';
  function Toggle({label,description,checked,onChange,disabled}) {
    return h('div',{className:'jsd-memory-switch'},h('div',{className:'jsd-memory-switch-copy'},h('strong',null,label),h('p',null,description)),
      h(Switch,{label,checked:!!checked,disabled,onChange,className:'jsd-memory-toggle'}));
  }
  async function openSource(entry) {
    const {source}=await call({action:'source',entry_id:entry.id,content_revision:entry.content_revision});
    if(source.session_id)await scope.uiWorkspace.openSession(source.session_id);
  }
  function MemorySection() {
    const [settings,setSettings]=useState(),[rows,setRows]=useState([]),[cursor,setCursor]=useState(null);
    const [busy,setBusy]=useState(false),[error,setError]=useState(''),[receipt,setReceipt]=useState();
    const [editing,setEditing]=useState(),[text,setText]=useState(''),[adding,setAdding]=useState(false),key=useRef();
    const alive=useRef(true),loading=useRef(false),pages=useRef(1);
    async function load(append=false,refresh=false) {
      if(loading.current)return;
      loading.current=true;setBusy(true);setError('');
      try {
        const prefs=await call({action:'initialize'});
        const entries=[];
        let next=append?cursor:null,read=0;
        do {
          const page=await call({action:'list',limit:20,...(next?{cursor:next}:{})});
          entries.push(...page.entries);next=page.next_cursor;read++;
        } while(refresh && next && read<pages.current);
        let updatedReceipt;
        if(receipt && ['PENDING','UNKNOWN'].includes(receipt.status)) {
          updatedReceipt=entries.find(entry=>entry.operation_id===receipt.operation_id)?.receipt
            || await call({action:'status',operation_id:receipt.operation_id});
        }
        if(alive.current) {
          pages.current=append?pages.current+1:read;
          setSettings(prefs);setRows(old=>append?[...old,...entries]:entries);setCursor(next);
          if(updatedReceipt)setReceipt(old=>old?.operation_id===updatedReceipt.operation_id?updatedReceipt:old);
        }
      } catch(e) {if(alive.current)setError(errorMessage(e));}
      finally {loading.current=false;if(alive.current)setBusy(false);}
    }
    useEffect(()=>{alive.current=true;void load();return()=>{alive.current=false;};},[]);
    useEffect(()=>{
      if(!rows.some(row=>['PENDING','UNKNOWN'].includes(row.receipt?.status)) && !['PENDING','UNKNOWN'].includes(receipt?.status))return;
      const timer=setTimeout(()=>void load(false,true),4000);return()=>clearTimeout(timer);
    },[rows,receipt]);
    async function change(patch) {
      setBusy(true);setError('');
      try {setSettings(await call({action:'set-settings',revision:settings.revision,patch}));}
      catch(e){setError(errorMessage(e));}finally{setBusy(false);}
    }
    async function mutate(body) {
      setBusy(true);setError('');
      try {
        const result=await call(body);setReceipt(result);setEditing(undefined);setAdding(false);setText('');key.current=undefined;
        await load();
      } catch(e){setError(errorMessage(e));}finally{setBusy(false);}
    }
    function add() {setEditing(undefined);setText('');setAdding(true);key.current=undefined;setError('');}
    function edit(entry) {setAdding(false);setEditing(entry);setText(entry.text);setError('');}
    function save(event) {
      event.preventDefault();
      key.current ||= crypto.randomUUID();
      return mutate(editing ? {action:'correct',entry_id:editing.id,revision:editing.revision,text}
        :{action:'remember',category:'preference',text,idempotency_key:key.current});
    }
    function editor() {
      return h('form',{className:'jsd-memory-editor',onSubmit:save},
        h('label',null,editing?'更正记忆':'添加记忆',h('textarea',{value:text,onChange:e=>setText(e.target.value),required:true,maxLength:12000,rows:3,
          autoFocus:true,placeholder:'写下希望助手长期记住的工作偏好…','aria-label':editing?'更正记忆内容':'偏好内容'})),
        h('div',{className:'jsd-memory-editor-footer'},h('span',null,'保存后可随时更正或遗忘。'),
          h('div',null,action('取消',()=>{setEditing(undefined);setAdding(false);},{disabled:busy,className:'jsd-memory-text-button'}),
            h('button',{type:'submit',className:'jsd-memory-button jsd-memory-primary',disabled:busy || !text.trim()},busy?'保存中…':'保存记忆'))));
    }
    return h('div',{className:'jsd-memory-settings','data-ui':'memory'},
      h('header',{className:'jsd-memory-header'},h('div',null,h('h2',null,'个人记忆'),h('span',{className:'jsd-memory-privacy'},'仅本人可见')),
        h('p',null,'保存工作偏好与确认的决定，让之后的对话少一些重复。')),
      error && h('div',{role:'alert',className:'jsd-memory-feedback jsd-memory-error'},h('span',null,error),action('重试',()=>load(),{disabled:busy,className:'jsd-memory-text-button'})),
      !settings && !error && h('div',{className:'jsd-memory-loading',role:'status','aria-label':'正在读取记忆设置'},
        h('div',null,h('span'),h('span')),h('div',null,h('span'),h('span'))),
      settings && h(React.Fragment,null,
        h('section',{className:'jsd-memory-controls','aria-label':'记忆设置'},
          h(Toggle,{label:'读取个人记忆',description:'在新对话中参考已保存的偏好与历史。',checked:settings.read_enabled,disabled:busy,onChange:value=>change({read_enabled:value})}),
          h(Toggle,{label:'自动整理记忆',description:'整理新对话中的偏好和决定，离开页面后继续。',checked:settings.auto_enabled,disabled:busy,onChange:value=>change({auto_enabled:value})})),
        !settings.notice_seen && h('aside',{className:'jsd-memory-notice'},h('p',null,'两项开关独立生效，记忆随时可更正或遗忘。'),
          action('知道了',()=>change({notice_seen:true}),{disabled:busy,className:'jsd-memory-text-button'})),
        h('section',{className:'jsd-memory-collection','aria-label':'已保存的记忆'},
          h('div',{className:'jsd-memory-list-heading'},h('div',null,h('h3',null,'已保存的记忆'),
            h('span',{className:'jsd-memory-count'},rows.length+(cursor?' +':'')+' 条')),
            action('添加记忆',add,{disabled:busy || adding || !!editing,className:adding || editing?'':'jsd-memory-primary'})),
          receipt && h('div',{role:'status',className:'jsd-memory-feedback jsd-memory-receipt','data-state':receipt.status},
            h('span',null,h('span',{className:'jsd-memory-status-dot','aria-hidden':true}),receipt.summary,
              receipt.usage_pending && h('small',null,'用量待核对')),
            pending(receipt.status) && action('检查进度',async()=>{
              try {setReceipt(await call({action:'status',operation_id:receipt.operation_id}));await load();}catch(e){setError(errorMessage(e));}
            },{disabled:busy,className:'jsd-memory-text-button'})),
          adding && editor(),
          !rows.length && !adding && h('div',{className:'jsd-memory-empty'},
            h('h4',null,'把常用的工作约定留在这里'),h('p',null,'添加一条偏好，或在对话中告诉助手需要长期记住的事。'),
            h('p',{className:'jsd-memory-example'},'例如：研发回答先列结论，再列依据。')),
          h('ul',{className:'jsd-memory-list'},rows.map(entry=>h('li',{key:entry.id,className:'jsd-memory-entry','data-editing':editing?.id===entry.id || undefined},
            h('div',{className:'jsd-memory-entry-body'},
              h('div',{className:'jsd-memory-entry-heading'},h('span',null,category[entry.category]),
                h('span',{className:'jsd-memory-status','data-state':entry.receipt?.status || entry.status},
                  h('span',{className:'jsd-memory-status-dot','aria-hidden':true}),status[entry.receipt?.status || entry.status] || '待核对')),
              editing?.id===entry.id ? editor() : h('p',{className:'jsd-memory-entry-text'},entry.text || '正在整理，完成后即可查看。'),
              h('div',{className:'jsd-memory-entry-meta'},
                entry.source.session_id ? action('原始会话'+(entry.source.turn?' · 第 '+entry.source.turn+' 轮':''),()=>openSource(entry).catch(e=>setError(errorMessage(e))),
                  {disabled:busy || entry.status!=='ACTIVE',className:'jsd-memory-source jsd-memory-text-button','aria-label':'查看原始会话'})
                  : h('span',null,'本人添加'),
                dateLabel(entry.updated_at) && h('time',{dateTime:entry.updated_at,title:new Date(entry.updated_at).toLocaleString('zh-CN')},dateLabel(entry.updated_at)))),
            editing?.id!==entry.id && h('div',{className:'jsd-memory-entry-actions'},
              action('编辑',()=>edit(entry),{disabled:busy || !entry.text,className:'jsd-memory-text-button'}),
              action('遗忘',()=>mutate({action:'forget',entry_id:entry.id,revision:entry.revision}),{disabled:busy,className:'jsd-memory-text-button jsd-memory-danger'}))))),
          cursor && action('加载更多',()=>load(true),{disabled:busy,className:'jsd-memory-more'}))));
  }
  function FirstUseNotice() {
    const [prefs,setPrefs]=useState(),[dismissed,setDismissed]=useState(false),saving=useRef(false);
    useEffect(()=>{
      settingsListeners.add(setPrefs);
      const controller=new AbortController();
      void call({action:'initialize'},controller.signal).catch(()=>{});
      return()=>{controller.abort();settingsListeners.delete(setPrefs);};
    },[]);
    if(!prefs || prefs.notice_seen || dismissed || (!prefs.read_enabled && !prefs.auto_enabled))return null;
    async function notice(disable) {
      if(saving.current)return;
      saving.current=true;
      try {
        await call({action:'set-settings',revision:prefs.revision,patch:{notice_seen:true,...(disable?{read_enabled:false,auto_enabled:false}:{})}});
        if(disable)scope.contextMenus.notify('已关闭个人记忆',true);
      } catch(e){setDismissed(true);scope.contextMenus.notify(errorMessage(e),false);}
      finally{saving.current=false;}
    }
    return h(Toast,{text:'个人记忆会保留工作偏好和确认的决定，供之后的会话参考。',holdMs:10000,onDone:()=>setDismissed(true),
      actions:[{label:'知道了',prefix:' ',onClick:()=>notice(false)},{label:'关闭个人记忆',prefix:' · ',onClick:()=>notice(true)}]});
  }
  function SessionMemory({sessionId}) {
    const [value,setValue]=useState(),[prefs,setPrefs]=useState(),[error,setError]=useState(''),[busy,setBusy]=useState(false);
    const [attempt,retry]=useState(0);
    useEffect(()=>{
      const controller=new AbortController();
      setError('');
      settingsListeners.add(setPrefs);
      void Promise.all([call({action:'initialize'},controller.signal),call({action:'session-settings',session_id:sessionId},controller.signal)])
        .then(([,settings])=>{if(!controller.signal.aborted)setValue(settings);})
        .catch(e=>{if(!controller.signal.aborted)setError(errorMessage(e));});
      return()=>{controller.abort();settingsListeners.delete(setPrefs);};
    },[sessionId,attempt]);
    async function update(patch) {
      setBusy(true);setError('');
      try {setValue(await call({action:'set-session',session_id:sessionId,patch}));}
      catch(e){setError(errorMessage(e));}finally{setBusy(false);}
    }
    const ready=value && prefs;
    return h(Modal,{open:true,title:'本会话记忆',closeLabel:'关闭',description:'更改只对这个会话生效。',onClose:()=>showSessionMemory(null),backdropBlur:false},
      h('div',{className:'jsd-session-memory','data-ui':'session-memory'},
        !ready && !error && h('p',{role:'status'},'正在读取会话设置…'),
        ready && h('div',null,
          h(Toggle,{label:'本会话读取记忆',description:prefs.read_enabled?'允许使用本人已授权的记忆。':'全局读取已关闭。',checked:prefs.read_enabled && value.read_enabled,disabled:busy || !prefs.read_enabled,onChange:read_enabled=>update({read_enabled})}),
          h(Toggle,{label:'本会话参与整理',description:prefs.auto_enabled?'关闭后，本会话不再产生新增记忆。':'全局自动整理已关闭。',checked:value.participate,disabled:busy,onChange:participate=>update({participate})})),
        error && h('div',{role:'alert',className:'jsd-memory-feedback jsd-memory-error'},h('span',null,error),
          !ready && action('重试',()=>retry(count=>count+1),{className:'jsd-memory-text-button'}))));
  }
  function MemoryOverlay() {
    const sessionId=useSyncExternalStore(subscribeSession,()=>dialogSession);
    return h(React.Fragment,null,h(FirstUseNotice),sessionId && h(SessionMemory,{key:sessionId,sessionId}));
  }
  function MemoryIcon() {
    return h('svg',{width:16,height:16,viewBox:'0 0 16 16',fill:'none',stroke:'currentColor',
      strokeWidth:1.25,strokeLinecap:'round',strokeLinejoin:'round','aria-hidden':true},
      h('rect',{x:4,y:2.5,width:9,height:11,rx:1.5}),
      h('path',{d:'M2.75 5h2.5m-2.5 3h2.5m-2.5 3h2.5M7.5 6h3m-3 3h2'}));
  }
  scope.slots.inject('settings.section',()=>scope.slots.register({name:'settings.section',id:'memory',order:20,label:()=> '记忆'},MemorySection));
  scope.slots.inject('shell.overlay',()=>scope.slots.register({name:'shell.overlay',id:'personal-memory'},MemoryOverlay));
  scope.slots.inject('contextMenu.icon',()=>scope.slots.register({name:'contextMenu.icon',key:'enterprise.personal-memory'},MemoryIcon));
  scope.slots.inject('settings.section.icon',()=>scope.slots.register({name:'settings.section.icon',id:'memory'},MemoryIcon));
  for(const target of ['sessionHeader','session'])scope.effect(()=>scope.contextMenus.register({
    id:'enterprise.personal-memory',target,order:400,
    resolve:({sessionId})=>sessionId && memoryAvailable!==false ? [{id:'settings',label:'本会话记忆',
      icon:{key:'enterprise.personal-memory'},run:()=>showSessionMemory(sessionId)}] : [],
  }));
}
