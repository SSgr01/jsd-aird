import { CameraFilled, CloudUploadOutlined, DeleteOutlined, ExperimentOutlined, FolderOpenFilled, SearchOutlined } from '@ant-design/icons';
import { App, Button, Input, Select, TreeSelect } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { listCategories, stageExperimentFile, type Category } from '@/services/experiments/experiment-api';
import { getProjectStages, getProjects, getStageTasks, type ProjectStage, type ProjectTask } from '@/services/project/project-api';
import './experiments.css';

type LinkNode = {
  value: string;
  label: string;
  isLeaf?: boolean;
  selectable?: boolean;
  children?: LinkNode[];
};

export function ExperimentUploadPage() {
  const { message } = App.useApp();
  const [files, setFiles] = useState<File[]>([]);
  const [uploaded, setUploaded] = useState<{ name: string; size: number; createdAt: string }[]>([]);
  const [uploading, setUploading] = useState(false);
  const [categories, setCategories] = useState<Category[]>([]);
  const [location, setLocation] = useState<string>();
  const [link, setLink] = useState<string>();
  const [linkTree, setLinkTree] = useState<LinkNode[]>([]);
  const [linkLoaded, setLinkLoaded] = useState(false);
  const [linkOpen, setLinkOpen] = useState(false);
  const [expandedKeys, setExpandedKeys] = useState<string[]>([]);
  const prevExpandedRef = useRef<string[]>([]);
  useEffect(() => {
    void listCategories().then(list => {
      setCategories(list);
      setLocation(list[0]?.name);
    });
  }, []);
  const loadProjects = async (): Promise<LinkNode[]> => {
    const page = await getProjects({ page: 1, size: 200 });
    return (page.items || []).map<LinkNode>(p => ({
      value: `project:${p.id}`,
      label: `${p.projectCode}·${p.name}`,
      selectable: false,
      isLeaf: false,
    }));
  };
  const loadStages = async (projectId: string): Promise<LinkNode[]> => {
    const stages: ProjectStage[] = await getProjectStages(projectId);
    return stages.map<LinkNode>(s => ({
      value: `stage:${s.id}`,
      label: s.name,
      selectable: false,
      isLeaf: false,
    }));
  };
  const loadTasks = async (stageId: string): Promise<LinkNode[]> => {
    const tasks: ProjectTask[] = await getStageTasks(stageId);
    return tasks.map<LinkNode>(t => ({
      value: `task:${t.id}`,
      label: t.name,
      isLeaf: true,
      selectable: true,
    }));
  };
  const attachChildren = (nodes: LinkNode[], value: string, children: LinkNode[]): LinkNode[] => nodes.map(n => {
    if (n.value === value) {
      return { ...n, children };
    }
    if (n.children?.length) {
      return { ...n, children: attachChildren(n.children, value, children) };
    }
    return n;
  });
  const handleTreeExpand = (keys: Array<string | number>) => {
    const keyStrs = keys.map(String);
    const prev = prevExpandedRef.current;
    const newly = keyStrs.filter(k => !prev.includes(k));
    setExpandedKeys(keyStrs);
    prevExpandedRef.current = keyStrs;
    const target = newly[0];
    if (!target) return;
    const [type, id] = target.split(':');
    if (!type || !id) return;
    const load = type === 'project' ? loadStages(id) : type === 'stage' ? loadTasks(id) : Promise.resolve([] as LinkNode[]);
    void load.then(children => {
      setLinkTree(prev => attachChildren(prev, target, children));
    });
  };
  const startUpload = async () => {
    if (!files.length) {
      void message.warning('请先选择要上传的文件');
      return;
    }
    setUploading(true);
    const ok: { name: string; size: number }[] = [];
    try {
      for (const file of files) {
        await stageExperimentFile(file);
        ok.push({ name: file.name, size: file.size });
      }
      const now = new Date().toLocaleString('zh-CN');
      setUploaded(prev => [...ok.map(f => ({ name: f.name, size: f.size, createdAt: now })), ...prev]);
      setFiles([]);
      void message.success(`已成功上传 ${ok.length} 个文件`);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '文件上传失败');
    } finally {
      setUploading(false);
    }
  };
  return <div className="eln-upload-page">
    <section className="eln-upload-panel">
      <h2>基础分类</h2>
      <div className="eln-upload-fields">
        <label><span>保存位置</span><Select value={location} onChange={setLocation} placeholder="请选择实验分类" options={categories.map(c => ({ value: c.name, label: c.name }))}/></label>
        <label><span>关联项目 / 阶段 / 任务</span><TreeSelect value={link} onChange={setLink} placeholder="未关联项目" treeData={linkTree} treeExpandedKeys={expandedKeys} onTreeExpand={handleTreeExpand} treeCheckable={false} allowClear treeNodeFilterProp="label" style={{ minWidth: 280 }} open={linkOpen} onDropdownVisibleChange={(open) => { setLinkOpen(open); if (open && !linkLoaded) { setLinkLoaded(true); void loadProjects().then(setLinkTree); } }}/></label>
        <label><span>权限可见</span><Select value="全员可见" options={[{value:'全员可见',label:'全员可见'},{value:'项目组可见',label:'项目组可见'}]}/></label>
      </div>
      <label className="eln-dropzone"><input type="file" multiple hidden onChange={e=>setFiles(Array.from(e.target.files||[]))}/><span className="eln-drop-icon"><CloudUploadOutlined/></span><b>拖拽文件到此处，或<span>点击选择文件</span></b><small>支持 PDF / Word / Excel / CSV / 图片，支持批量上传</small></label>
      <div className="eln-preview-head"><h2>文件预览区 <em>({files.length})</em></h2><div className="eln-preview-actions"><Button onClick={()=>setFiles([])}>清空</Button><Button icon={<CameraFilled/>}>拍照录入</Button><Button type="primary" icon={<CloudUploadOutlined/>} loading={uploading} onClick={()=>void startUpload()}>开始上传</Button></div></div>
      <div className="eln-preview-list">{files.length?files.map(f => {
        const ext = f.name.split('.').pop()?.toLowerCase() || '';
        const isXls = ext === 'xls' || ext === 'xlsx';
        const sizeKb = f.size > 0 ? (f.size / 1024).toFixed(1) : '0.0';
        return <div className="eln-preview-card" key={f.name}>
          <span className={`eln-preview-icon ${isXls ? 'excel' : 'file'}`}>{isXls ? 'X' : 'F'}</span>
          <div className="eln-preview-info"><b>{f.name}</b><small>{sizeKb} KB</small></div>
          <button className="eln-preview-remove" onClick={() => setFiles(files.filter(x => x.name !== f.name))} aria-label="删除"><DeleteOutlined/></button>
        </div>;
      }):<div className="eln-preview-empty"><FolderOpenFilled/><span>暂无待上传文件，点击上方区域选择文件</span></div>}</div>
    </section>
    <section className="eln-upload-panel eln-uploaded"><h2>已上传文件 <em>({uploaded.length})</em></h2><Input prefix={<SearchOutlined/>} placeholder="搜索文件名称"/>
      {uploaded.length ? (
        <div className="eln-preview-list">
          {uploaded.map(u => {
            const ext = u.name.split('.').pop()?.toLowerCase() || '';
            const isXls = ext === 'xls' || ext === 'xlsx';
            const sizeKb = u.size > 0 ? (u.size / 1024).toFixed(1) : '0.0';
            return <div className="eln-preview-card" key={u.name}><span className={`eln-preview-icon ${isXls ? 'excel' : 'file'}`}>{isXls ? 'X' : 'F'}</span><div className="eln-preview-info"><b>{u.name}</b><small>{sizeKb} KB · {u.createdAt}</small></div></div>;
          })}
        </div>
      ) : (
        <div className="eln-uploaded-empty"><ExperimentOutlined/><span>当前模块暂无已上传文件</span></div>
      )}
      <div className="eln-upload-pager">共 {uploaded.length} 条　<select><option>10条/页</option></select>　<button disabled>‹</button><b>1</b><button disabled>›</button>　跳至 <input value="1" readOnly/> 页</div>
    </section>
  </div>;
}
