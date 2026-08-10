import { DatabaseOutlined, DownloadOutlined, FileSearchOutlined, FolderOpenOutlined, LinkOutlined, SearchOutlined, StarOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Checkbox, Empty, Input, Space, Spin, Tag, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { assistantApi, type FileSearchResult } from '@/services/assistant/assistant-api';
import { dataApi, type DataCategory } from '@/services/data/data-api';
import { knowledgeApi, type KnowledgeCategory } from '@/services/knowledge';

export function KnowledgeSearchPage() {
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

  useEffect(() => { void Promise.all([knowledgeApi.categories(), dataApi.listCategories()]).then(([knowledge, data]) => { setKnowledgeCategories(knowledge); setDataCategories(data); }).catch(() => undefined); }, []);
  const total = (result?.knowledgeHits?.length || 0) + (result?.dataHits?.length || 0);
  const doSearch = async () => {
    if (!query.trim()) return;
    setLoading(true); setError(undefined); setSearched(true);
    try { setResult(await assistantApi.fileSearch({ query: query.trim(), aiOnly: false, limit: 30, knowledgeCategoryIds: selectedKnowledge, dataCategoryIds: selectedData })); }
    catch (err) { setError(err instanceof Error ? err.message : '检索失败，请稍后重试'); }
    finally { setLoading(false); }
  };
  const knowledgeGroups = useMemo(() => knowledgeCategories.map((item) => ({ ...item, checked: selectedKnowledge.includes(item.id) })), [knowledgeCategories, selectedKnowledge]);
  return <div className="search-workspace-page">
    <Card className="search-hero content-card"><Typography.Text>支持文件名、分类、产品、项目、配方、工艺、实验编号和内容摘要检索；新增分类及上传文件会自动进入查询范围。</Typography.Text><div className="search-hero-row"><Input size="large" aria-label="文件检索关键词" value={query} onChange={(event) => setQuery(event.target.value)} onPressEnter={() => void doSearch()} placeholder="输入文件名、材料、项目或实验编号" /><Button type="primary" size="large" icon={<SearchOutlined />} onClick={() => void doSearch()}>检索</Button></div></Card>
    <div className="search-workbench">
      <aside className="search-scope-panel content-card"><div className="search-scope-heading"><Typography.Title level={3}>检索范围</Typography.Title><Space><Button size="small" onClick={() => { setSelectedKnowledge(knowledgeCategories.map((item) => item.id)); setSelectedData(dataCategories.map((item) => item.id)); }}>全选</Button><Button size="small" onClick={() => { setSelectedKnowledge([]); setSelectedData([]); }}>清空</Button></Space></div><Typography.Text strong><FolderOpenOutlined /> 研发知识库</Typography.Text><div className="search-scope-list">{knowledgeGroups.map((item) => <Checkbox key={item.id} checked={item.checked} onChange={(event) => setSelectedKnowledge((current) => event.target.checked ? [...current, item.id] : current.filter((id) => id !== item.id))}>{item.name}<Typography.Text type="secondary">{item.documentCount}</Typography.Text></Checkbox>)}</div><Typography.Text strong><DatabaseOutlined /> 数据中心</Typography.Text><div className="search-scope-list">{dataCategories.map((item) => <Checkbox key={item.id} checked={selectedData.includes(item.id)} onChange={(event) => setSelectedData((current) => event.target.checked ? [...current, item.id] : current.filter((id) => id !== item.id))}>{item.name}<Typography.Text type="secondary">{item.assetCount}</Typography.Text></Checkbox>)}</div></aside>
      <main className="search-result-panel"><div className="search-result-heading"><Typography.Title level={3}><FileSearchOutlined /> 同步检索结果</Typography.Title>{searched && <Typography.Text type="secondary">共 {total} 条结果</Typography.Text>}</div>{loading && <div className="search-state"><Spin /><Typography.Text type="secondary">正在检索授权内容…</Typography.Text></div>}{error && <Alert type="error" showIcon message={error} action={<Button onClick={() => void doSearch()}>重试</Button>} />}{!loading && !error && searched && total === 0 && <Card className="content-card"><Empty description="没有找到匹配内容，请调整关键词或检索范围" /></Card>}{!loading && !error && !searched && <div className="search-state"><SearchOutlined /><Typography.Text type="secondary">输入关键词开始检索</Typography.Text></div>}{!loading && !error && result?.knowledgeHits?.map((item, index) => <SearchResultCard key={`${item.chunkId}-${index}`} kind="文献资料" title={item.title} fileName={item.originalName} content={item.content || item.snippet} date="知识库" onView={() => item.documentId && navigate(`/knowledge/documents/${item.documentId}`)} />)}{!loading && !error && result?.dataHits?.map((item) => <SearchResultCard key={item.entryId} kind="数据资产" title={item.assetName || '数据资产'} fileName={item.fieldCode || '结构化字段'} content={item.content} date="数据中心" onView={() => navigate(`/data/assets/${item.assetId}`)} />)}</main>
    </div>
  </div>;
}

function SearchResultCard({ kind, title, fileName, content, date, onView }: { kind: string; title: string; fileName: string; content?: string; date: string; onView: () => void }) {
  return <Card className="search-result-card content-card"><div className="search-result-card-head"><div><Tag color="blue">{kind}</Tag><Typography.Title level={4}>{title}</Typography.Title></div><Typography.Text type="secondary">{date}</Typography.Text></div><div className="search-result-meta"><Tag>{fileName}</Tag><Tag color="cyan">受控来源</Tag></div><Typography.Paragraph ellipsis={{ rows: 2 }}>{content || '暂无摘要'}</Typography.Paragraph><Space wrap><Button icon={<SearchOutlined />} onClick={onView}>查看</Button><Button icon={<DownloadOutlined />} disabled>下载</Button><Button icon={<LinkOutlined />} disabled>关联项目</Button><Button icon={<StarOutlined />} disabled>加入资料参考</Button></Space></Card>;
}
