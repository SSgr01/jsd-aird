import { EyeOutlined, LineChartOutlined, MessageOutlined, ReloadOutlined, UploadOutlined } from '@ant-design/icons';
import { App, Button, Empty, Input, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { CategoryCardGrid, type CatalogCategoryCard } from '@/components/catalog-workspace/CategoryCardGrid';
import { CatalogListPanel } from '@/components/catalog-workspace/CatalogListPanel';
import { CategoryEditorModal, type CategoryEditorValue } from '@/components/catalog-workspace/CategoryEditorModal';
import { FilePreviewModal, downloadPreviewFile, type FilePreviewDescriptor } from '@/components/file-preview';
import { spectrumApi, type SpectrumCategory, type SpectrumChart } from '@/services/spectrum';

const formatSize = (size: number) => size < 1024 * 1024 ? `${Math.ceil(size / 1024)} KB` : `${(size / 1024 / 1024).toFixed(1)} MB`;

export function SpectrumViewPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [categoryId, setCategoryId] = useState('ALL');
  const [categories, setCategories] = useState<SpectrumCategory[]>([]);
  const [items, setItems] = useState<SpectrumChart[]>([]);
  const [allChartCount, setAllChartCount] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState<string>();
  const [page, setPage] = useState({ current: 1, pageSize: 20, total: 0 });
  const [loading, setLoading] = useState(false);
  const [previewFile, setPreviewFile] = useState<FilePreviewDescriptor>();
  const [editor, setEditor] = useState<{ mode: 'NEW' | 'EDIT'; item?: SpectrumCategory }>();
  const [deleteItem, setDeleteItem] = useState<SpectrumCategory>();
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [chartPage, categoryList] = await Promise.all([
        spectrumApi.listCharts({ categoryId: categoryId === 'ALL' ? undefined : categoryId, status, keyword: keyword || undefined, page: page.current, size: page.pageSize }),
        spectrumApi.categories(),
      ]);
      setItems(chartPage.items);
      setCategories(categoryList);
      if (categoryId === 'ALL' && !status && !keyword) setAllChartCount(chartPage.total);
      setPage((value) => ({ ...value, current: chartPage.page, pageSize: chartPage.size, total: chartPage.total }));
    } catch (error) { void message.error(error instanceof Error ? error.message : '图谱列表加载失败'); }
    finally { setLoading(false); }
  }, [categoryId, keyword, message, page.current, page.pageSize, status]);

  useEffect(() => { void load(); }, [load]);

  const cards = useMemo<CatalogCategoryCard[]>(() => [
    { id: 'ALL', name: '全部图谱', count: allChartCount, description: '按分类查看客户提供的 PDF 和图谱图片', icon: <LineChartOutlined />, tone: 'blue' },
    ...categories.map((item) => ({ id: item.id, name: item.name, count: item.chartCount, description: item.description, icon: <LineChartOutlined />, tone: 'blue' as const, editable: !item.systemCategory })),
  ], [allChartCount, categories]);

  const descriptor = (item: SpectrumChart): FilePreviewDescriptor => ({ fileName: item.originalName, contentType: item.contentType, size: item.size, load: () => spectrumApi.contentBlob(item.id) });

  const saveCategory = async (value: CategoryEditorValue) => {
    setSaving(true);
    try {
      if (editor?.mode === 'NEW') await spectrumApi.createCategory({ name: value.name.trim(), description: value.description?.trim() || undefined });
      else if (editor?.item) await spectrumApi.updateCategory(editor.item.id, { name: value.name.trim(), description: value.description?.trim() || undefined });
      setEditor(undefined); await load(); void message.success('图谱分类已保存');
    } catch (error) { void message.error(error instanceof Error ? error.message : '分类保存失败'); }
    finally { setSaving(false); }
  };

  const removeCategory = async () => {
    if (!deleteItem) return;
    setSaving(true);
    try { await spectrumApi.deleteCategory(deleteItem.id); setDeleteItem(undefined); setCategoryId('ALL'); await load(); void message.success('分类已删除'); }
    catch (error) { void message.error(error instanceof Error ? error.message : '分类删除失败'); }
    finally { setSaving(false); }
  };

  return <div className="business-page">
    <div className="page-heading">
      <div><Typography.Title level={2}>图谱查看</Typography.Title><Typography.Text type="secondary">按 IR、UV、HPLC/GPC、GC、纳米粒径和力学等分类查看，分类可继续扩展。</Typography.Text></div>
      <Button type="primary" icon={<UploadOutlined />} onClick={() => navigate('/spectrum/upload')}>上传图谱</Button>
    </div>
    <CategoryCardGrid categories={cards} activeId={categoryId}
      onSelect={(id) => { setCategoryId(id); setPage((value) => ({ ...value, current: 1 })); }}
      onCreate={() => setEditor({ mode: 'NEW' })}
      onRename={(item) => setEditor({ mode: 'EDIT', item: categories.find((candidate) => candidate.id === item.id) })}
      onDelete={(item) => setDeleteItem(categories.find((candidate) => candidate.id === item.id))} />
    <CatalogListPanel title={cards.find((item) => item.id === categoryId)?.name || '图谱'} count={page.total}
      filters={<Space wrap><Input.Search allowClear placeholder="搜索图谱名称、样品或批号" value={keyword} onChange={(event) => { setKeyword(event.target.value); setPage((value) => ({ ...value, current: 1 })); }} onSearch={() => void load()} /><Select allowClear placeholder="全部状态" value={status} onChange={(value) => { setStatus(value); setPage((value) => ({ ...value, current: 1 })); }} options={[{ value: 'READY', label: '可分析' }, { value: 'DELETED', label: '已删除' }]} /><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button></Space>}
      loading={loading}>
      <Table<SpectrumChart> rowKey="id" dataSource={items} pagination={{ current: page.current, pageSize: page.pageSize, total: page.total, showSizeChanger: true, onChange: (current, pageSize) => setPage({ current, pageSize, total: page.total }) }} locale={{ emptyText: <Empty description="暂无图谱" /> }} columns={[
        { title: '图谱', dataIndex: 'title', render: (value: string, record) => <Space><LineChartOutlined /><span><Typography.Text strong>{value}</Typography.Text><br /><Typography.Text type="secondary">{record.originalName}</Typography.Text></span></Space> },
        { title: '分类', dataIndex: 'categoryName' },
        { title: '样品 / 批号', render: (_: unknown, record) => <span>{record.sampleName || '未填写'}{record.batchNo ? ` · ${record.batchNo}` : ''}</span> },
        { title: '文件', render: (_: unknown, record) => `${record.contentType || '图谱文件'} · ${formatSize(record.size)} · ${record.pageCount} 页` },
        { title: '状态', dataIndex: 'status', render: (value: string) => <Tag color={value === 'READY' ? 'success' : 'default'}>{value === 'READY' ? '可分析' : value}</Tag> },
        { title: '最近更新', dataIndex: 'updatedAt', render: (value: string) => new Date(value).toLocaleString('zh-CN') },
        { title: '操作', width: 300, render: (_: unknown, record) => <Space wrap><Button type="link" icon={<EyeOutlined />} onClick={() => setPreviewFile(descriptor(record))}>预览</Button><Button type="link" icon={<MessageOutlined />} onClick={() => navigate(`/spectrum/chat?chartIds=${record.id}`)}>AI 对话</Button><Button type="link" onClick={() => void downloadPreviewFile(descriptor(record))}>下载</Button></Space> },
      ]} />
    </CatalogListPanel>
    <CategoryEditorModal open={Boolean(editor)} title={editor?.mode === 'NEW' ? '新增图谱分类' : '编辑图谱分类'} initialValue={editor?.item ? { name: editor.item.name, description: editor.item.description } : { name: '' }} confirmLoading={saving} onCancel={() => setEditor(undefined)} onSubmit={(value) => void saveCategory(value)} />
    {deleteItem && <Modal open title={`删除分类“${deleteItem.name}”`} okText="删除" okButtonProps={{ danger: true }} cancelText="取消" confirmLoading={saving} onCancel={() => setDeleteItem(undefined)} onOk={() => void removeCategory()}><Typography.Paragraph>分类下必须没有图谱才能删除；删除后不会删除原始文件。</Typography.Paragraph></Modal>}
    <FilePreviewModal open={Boolean(previewFile)} file={previewFile} onClose={() => setPreviewFile(undefined)} />
  </div>;
}
