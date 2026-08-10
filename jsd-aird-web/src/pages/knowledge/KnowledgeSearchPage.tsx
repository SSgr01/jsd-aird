import { FileSearchOutlined, SearchOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Empty, Input, List, Space, Tag, Typography } from 'antd';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { knowledgeApi, type KnowledgeSearchHit } from '@/services/knowledge';

export function KnowledgeSearchPage() {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [items, setItems] = useState<KnowledgeSearchHit[]>([]);
  const [loading, setLoading] = useState(false);
  const [searched, setSearched] = useState(false);
  const search = async () => {
    if (!query.trim()) return;
    setLoading(true); setSearched(true);
    try { setItems(await knowledgeApi.search(query.trim(), 30)); }
    catch { setItems([]); }
    finally { setLoading(false); }
  };
  return <div className="business-page">
    <div className="page-heading"><div><Typography.Title level={2}>文件检索</Typography.Title><Typography.Text type="secondary">搜索已完成解析的研发文件，点击结果查看原文。</Typography.Text></div></div>
    <Card className="content-card"><Input.Search size="large" enterButton={<><SearchOutlined /> 检索</>} placeholder="输入材料、配方、工艺或检测关键词" value={query} onChange={(event) => setQuery(event.target.value)} onSearch={() => void search()} loading={loading} /></Card>
    {searched && !items.length && !loading ? <Card className="content-card"><Empty description="没有找到匹配内容" /></Card> : <List loading={loading} dataSource={items} locale={{ emptyText: <Empty description="请输入关键词开始检索" /> }} renderItem={(item) => <List.Item actions={[<Button type="link" onClick={() => navigate(`/knowledge/documents/${item.documentId}`)}>查看文件</Button>]}><List.Item.Meta avatar={<FileSearchOutlined style={{ fontSize: 22, color: '#2563eb' }} />} title={<Space><Typography.Text strong>{item.title}</Typography.Text>{item.pageNo && <Tag>第 {item.pageNo} 页</Tag>}{item.section && <Tag>{item.section}</Tag>}</Space>} description={<><Typography.Paragraph ellipsis={{ rows: 3 }} style={{ marginBottom: 4 }}>{item.content}</Typography.Paragraph><Typography.Text type="secondary">{item.originalName} · 匹配度 {(item.score * 100).toFixed(1)}%</Typography.Text></>} /> </List.Item>} />}
    <Alert type="info" showIcon message="文件检索可看到内部已解析内容；AI 问答只会使用已获得 AI 使用授权的文件。" style={{ marginTop: 16 }} />
  </div>;
}
