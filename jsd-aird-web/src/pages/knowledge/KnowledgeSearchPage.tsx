import { DatabaseOutlined, DownloadOutlined, EyeOutlined, FileSearchOutlined, FolderOpenOutlined, SearchOutlined } from '@ant-design/icons';
import { Alert, App, Button, Card, Checkbox, Empty, Input, Space, Spin, Tag, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { assistantApi, type FileSearchResult } from '@/services/assistant/assistant-api';
import { FilePreviewModal, downloadPreviewFile, type FilePreviewDescriptor } from '@/components/file-preview';
import { fetchFileBlob } from '@/services/files';
import { dataApi, type DataCategory } from '@/services/data/data-api';
import { knowledgeApi, type KnowledgeCategory } from '@/services/knowledge';

type SearchFile = FileSearchResult['files'][number];

export function KnowledgeSearchPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [searched, setSearched] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [result, setResult] = useState<FileSearchResult>();
  const [knowledgeCategories, setKnowledgeCategories] = useState<KnowledgeCategory[]>([]);
  const [dataCategories, setDataCategories] = useState<DataCategory[]>([]);
  const [selectedKnowledge, setSelectedKnowledge] = useState<string[]>([]);
  const [selectedData, setSelectedData] = useState<string[]>([]);
  const [previewFile, setPreviewFile] = useState<FilePreviewDescriptor>();

  useEffect(() => {
    void Promise.all([knowledgeApi.categories(), dataApi.listCategories()])
      .then(([knowledge, data]) => { setKnowledgeCategories(knowledge); setDataCategories(data); })
      .catch(() => undefined);
  }, []);

  const doSearch = async () => {
    if (!query.trim()) return;
    setLoading(true); setError(undefined); setSearched(true);
    try {
      setResult(await assistantApi.fileSearch({ query: query.trim(), limit: 30, knowledgeCategoryIds: selectedKnowledge, dataCategoryIds: selectedData }));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '检索失败，请稍后重试');
    } finally { setLoading(false); }
  };

  const knowledgeGroups = useMemo(
    () => knowledgeCategories.map((item) => ({ ...item, checked: selectedKnowledge.includes(item.id) })),
    [knowledgeCategories, selectedKnowledge],
  );
  const descriptor = (file: SearchFile): FilePreviewDescriptor => ({
    fileName: file.originalName,
    contentType: file.contentType,
    size: file.size,
    load: () => fetchFileBlob(file.fileObjectId),
  });
  const download = async (file: SearchFile) => {
    try { await downloadPreviewFile(descriptor(file)); void message.success('原文件下载已开始'); }
    catch (reason) { void message.error(reason instanceof Error ? reason.message : '原文件下载失败'); }
  };
  const openDetail = (file: SearchFile) => {
    if (file.sourceModule === 'KNOWLEDGE' && file.logicalDocumentId) navigate(`/knowledge/documents/${file.logicalDocumentId}`);
    else navigate(`/data/import-jobs/${file.fileVersionId}`);
  };

  return <div className="search-workspace-page">
    <Card className="search-hero content-card">
      <Typography.Text>仅返回研发知识库和数据中心的来源文件；一个文件的多个内容命中会聚合在同一张卡片中。</Typography.Text>
      <div className="search-hero-row"><Input size="large" aria-label="文件检索关键词" value={query} onChange={(event) => setQuery(event.target.value)} onPressEnter={() => void doSearch()} placeholder="输入文件名、产品、项目、实验编号或文件内容" /><Button type="primary" size="large" icon={<SearchOutlined />} onClick={() => void doSearch()}>检索文件</Button></div>
    </Card>
    <div className="search-workbench">
      <aside className="search-scope-panel content-card">
        <div className="search-scope-heading"><Typography.Title level={3}>检索范围</Typography.Title><Space><Button size="small" onClick={() => { setSelectedKnowledge(knowledgeCategories.map((item) => item.id)); setSelectedData(dataCategories.map((item) => item.id)); }}>全选</Button><Button size="small" onClick={() => { setSelectedKnowledge([]); setSelectedData([]); }}>清空</Button></Space></div>
        <Typography.Text strong><FolderOpenOutlined /> 研发知识库</Typography.Text>
        <div className="search-scope-list">{knowledgeGroups.map((item) => <Checkbox key={item.id} checked={item.checked} onChange={(event) => setSelectedKnowledge((current) => event.target.checked ? [...current, item.id] : current.filter((id) => id !== item.id))}>{item.name}<Typography.Text type="secondary">{item.documentCount}</Typography.Text></Checkbox>)}</div>
        <Typography.Text strong><DatabaseOutlined /> 数据中心来源文件</Typography.Text>
        <div className="search-scope-list">{dataCategories.map((item) => <Checkbox key={item.id} checked={selectedData.includes(item.id)} onChange={(event) => setSelectedData((current) => event.target.checked ? [...current, item.id] : current.filter((id) => id !== item.id))}>{item.name}<Typography.Text type="secondary">{item.assetCount}</Typography.Text></Checkbox>)}</div>
      </aside>
      <main className="search-result-panel">
        <div className="search-result-heading"><Typography.Title level={3}><FileSearchOutlined /> 来源文件</Typography.Title>{searched && <Typography.Text type="secondary">共 {result?.files.length || 0} 个文件</Typography.Text>}</div>
        {loading && <div className="search-state"><Spin /><Typography.Text type="secondary">正在查询本地全文索引…</Typography.Text></div>}
        {error && <Alert type="error" showIcon message={error} action={<Button onClick={() => void doSearch()}>重试</Button>} />}
        {!loading && !error && searched && !result?.files.length && <Card className="content-card"><Empty description="没有找到来源文件，请调整关键词或检索范围" /></Card>}
        {!loading && !error && !searched && <div className="search-state"><SearchOutlined /><Typography.Text type="secondary">输入关键词开始检索</Typography.Text></div>}
        {!loading && !error && result?.files.map((file) => <Card key={`${file.sourceModule}-${file.fileVersionId}`} className="search-result-card content-card">
          <div className="search-result-card-head"><div><Tag color={file.sourceModule === 'KNOWLEDGE' ? 'blue' : 'purple'}>{file.sourceModule === 'KNOWLEDGE' ? '研发知识文件' : '数据中心来源文件'}</Tag><Typography.Title level={4}>{file.title}</Typography.Title></div><Typography.Text type="secondary">{new Date(file.updatedAt).toLocaleString('zh-CN')}</Typography.Text></div>
          <div className="search-result-meta"><Tag>{file.originalName}</Tag><Tag>V{file.version}</Tag>{file.tags.map((tag) => <Tag color="cyan" key={tag}>{tag}</Tag>)}{file.relatedObjects.map((item) => <Tag key={item.id}>{item.objectType} · {item.name}</Tag>)}</div>
          <Space direction="vertical" size={6} style={{ width: '100%' }}>{file.hits.slice(0, 4).map((hit) => <div key={hit.id} className="search-hit-snippet"><Typography.Paragraph ellipsis={{ rows: 2 }} style={{ margin: 0 }}>{hit.snippet}</Typography.Paragraph><Typography.Text type="secondary">{anchorLabel(hit.anchor)}</Typography.Text></div>)}</Space>
          <Space wrap style={{ marginTop: 12 }}><Button icon={<SearchOutlined />} onClick={() => openDetail(file)}>查看来源</Button><Button icon={<EyeOutlined />} onClick={() => setPreviewFile(descriptor(file))}>预览原文件</Button><Button icon={<DownloadOutlined />} onClick={() => void download(file)}>下载原文件</Button></Space>
        </Card>)}
      </main>
    </div>
    <FilePreviewModal open={Boolean(previewFile)} file={previewFile} onClose={() => setPreviewFile(undefined)} />
  </div>;
}

function anchorLabel(anchor: SearchFile['hits'][number]['anchor']) {
  if (anchor.pageNo) return `第 ${anchor.pageNo} 页${anchor.section ? ` · ${anchor.section}` : ''}`;
  if (anchor.sheetName) return `${anchor.sheetName}${anchor.cellRange ? `!${anchor.cellRange}` : anchor.rowNumber ? ` · 第 ${anchor.rowNumber} 行` : ''}`;
  if (anchor.startTimeMs !== undefined) return `${formatTime(anchor.startTimeMs)} - ${formatTime(anchor.endTimeMs || anchor.startTimeMs)}`;
  return anchor.section || '文件内容命中';
}

function formatTime(milliseconds: number) {
  const seconds = Math.floor(milliseconds / 1000);
  return `${Math.floor(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}`;
}
