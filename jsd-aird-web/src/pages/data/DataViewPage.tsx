import { DatabaseOutlined, DownloadOutlined, EyeOutlined, ReloadOutlined, UploadOutlined } from '@ant-design/icons';
import { App, Button, Dropdown, Empty, Input, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { CategoryCardGrid, type CatalogCategoryCard } from '@/components/catalog-workspace/CategoryCardGrid';
import { CatalogListPanel } from '@/components/catalog-workspace/CatalogListPanel';
import { CategoryEditorModal, type CategoryEditorValue } from '@/components/catalog-workspace/CategoryEditorModal';
import { FilePreviewModal, downloadPreviewFile, type FilePreviewDescriptor } from '@/components/file-preview';
import { dataApi, type DataCategory, type DataSourceFile } from '@/services/data/data-api';

const statusLabels: Record<string, [string, string]> = {
  CREATED: ['待处理', 'default'], QUEUED: ['上传中', 'processing'], PARSING: ['解析中', 'blue'],
  WAITING_SHEET: ['待确认工作表', 'gold'], WAITING_MAPPING: ['待确认字段', 'gold'],
  VALIDATING: ['校验中', 'processing'], WAITING_CONFIRM: ['待确认提交', 'orange'],
  COMMITTING: ['入库中', 'processing'], COMPLETED: ['已入库', 'success'],
  FAILED: ['处理失败', 'error'], CANCELLED: ['已取消', 'default'],
};

export function DataViewPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [categoryId, setCategoryId] = useState('ALL');
  const [categories, setCategories] = useState<DataCategory[]>([]);
  const [items, setItems] = useState<DataSourceFile[]>([]);
  const [allSourceCount, setAllSourceCount] = useState(0);
  const [templates, setTemplates] = useState<Array<{ versionId: string; name: string; templateCode: string; versionNo: number }>>([]);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState<string>();
  const [page, setPage] = useState({ current: 1, pageSize: 20, total: 0 });
  const [loading, setLoading] = useState(false);
  const [previewFile, setPreviewFile] = useState<FilePreviewDescriptor>();
  const [editor, setEditor] = useState<{ mode: 'NEW' | 'EDIT'; item?: DataCategory }>();
  const [deleteItem, setDeleteItem] = useState<DataCategory>();
  const [replacementId, setReplacementId] = useState<string>();
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [sourcePage, categoryList] = await Promise.all([
        dataApi.listSourceFiles({ categoryId: categoryId === 'ALL' ? undefined : categoryId, status, keyword: keyword || undefined, page: page.current, size: page.pageSize }),
        dataApi.listCategories(),
      ]);
      setItems(sourcePage.items);
      setCategories(categoryList);
      if (categoryId === 'ALL' && !status && !keyword) setAllSourceCount(sourcePage.total);
      setPage((value) => ({ ...value, current: sourcePage.page, pageSize: sourcePage.size, total: sourcePage.total }));
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '来源文件加载失败');
    } finally { setLoading(false); }
  }, [categoryId, keyword, message, page.current, page.pageSize, status]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => { void dataApi.listTemplates().then(setTemplates).catch(() => setTemplates([])); }, []);

  const cards = useMemo<CatalogCategoryCard[]>(() => [
    { id: 'ALL', name: '全部来源文件', count: allSourceCount, description: '按上传文件和导入批次查看', icon: <DatabaseOutlined />, tone: 'blue' },
    ...categories.map((item) => ({ id: item.id, name: item.name, count: item.sourceCount, description: item.description, icon: <DatabaseOutlined />, tone: 'blue' as const, editable: true })),
  ], [allSourceCount, categories]);

  const resolveFile = (item: DataSourceFile): FilePreviewDescriptor => ({ fileName: item.originalName, load: () => dataApi.sourceBlob(item.fileObjectId), downloadUrl: `/api/v1/files/${encodeURIComponent(item.fileObjectId)}/content` });
  const moveSource = async (item: DataSourceFile, nextCategoryId: string) => {
    try { await dataApi.assignSourceCategory(item.importJobId, nextCategoryId); await load(); void message.success('归档分类已更新'); }
    catch (error) { void message.error(error instanceof Error ? error.message : '归档分类更新失败'); }
  };
  const saveCategory = async (value: CategoryEditorValue) => {
    setSaving(true);
    try {
      const description = value.description?.trim() || undefined;
      if (editor?.mode === 'NEW') await dataApi.createCategory({ name: value.name.trim(), description });
      else if (editor?.item) await dataApi.renameCategory(editor.item.id, { name: value.name.trim(), description });
      setEditor(undefined); await load(); void message.success('分类已保存');
    } catch (error) { void message.error(error instanceof Error ? error.message : '分类保存失败'); }
    finally { setSaving(false); }
  };
  const removeCategory = async () => {
    if (!deleteItem) return;
    setSaving(true);
    try {
      await dataApi.deleteCategory(deleteItem.id, replacementId);
      setDeleteItem(undefined); setReplacementId(undefined); setCategoryId('ALL'); await load(); void message.success('分类已删除');
    } catch (error) { void message.error(error instanceof Error ? error.message : '分类删除失败'); }
    finally { setSaving(false); }
  };

  return <div className="business-page">
    <div className="page-heading">
      <div><Typography.Title level={2}>数据查看</Typography.Title><Typography.Text type="secondary">按归档分类查看已上传的来源文件和导入结果。</Typography.Text></div>
      <Button type="primary" icon={<UploadOutlined />} onClick={() => navigate('/data/upload')}>数据上传</Button>
    </div>
    <CategoryCardGrid categories={cards} activeId={categoryId}
      onSelect={(id) => { setCategoryId(id); setPage((value) => ({ ...value, current: 1 })); }}
      onCreate={() => setEditor({ mode: 'NEW' })}
      onRename={(item) => setEditor({ mode: 'EDIT', item: categories.find((candidate) => candidate.id === item.id) })}
      onDelete={(item) => setDeleteItem(categories.find((candidate) => candidate.id === item.id))} />
    <div className="catalog-sync-note"><ReloadOutlined /> 列表按来源文件聚合；一个 Excel/CSV 只显示一条导入批次记录，字段值和识别记录在详情中查看。</div>
    <CatalogListPanel title={cards.find((item) => item.id === categoryId)?.name || '来源文件'} count={page.total}
      filters={<Space wrap><Input.Search allowClear placeholder="搜索来源文件名" value={keyword} onChange={(event) => { setKeyword(event.target.value); setPage((value) => ({ ...value, current: 1 })); }} onSearch={() => void load()} /><Select allowClear placeholder="全部状态" value={status} onChange={(value) => { setStatus(value); setPage((value) => ({ ...value, current: 1 })); }} options={Object.entries(statusLabels).map(([value, item]) => ({ value, label: item[0] }))} /><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button></Space>}
      loading={loading}>
      <Table className="catalog-data-table" scroll={{ x: 1380 }} rowKey="importJobId" dataSource={items} pagination={{ current: page.current, pageSize: page.pageSize, total: page.total, showSizeChanger: true, onChange: (current, pageSize) => setPage({ current, pageSize, total: page.total }) }} locale={{ emptyText: <Empty description="暂无来源文件" /> }} onRow={(record) => ({ onDoubleClick: () => navigate(`/data/import-jobs/${record.importJobId}`) })} columns={[
        { title: '来源文件', dataIndex: 'originalName', width: 280, ellipsis: true, render: (value: string, record: DataSourceFile) => <Space><DatabaseOutlined /><Typography.Text strong ellipsis={{ tooltip: value }}>{value}</Typography.Text><Typography.Text type="secondary">{record.sourceFormat}</Typography.Text></Space> },
        { title: '归档分类', dataIndex: 'categoryName', width: 150, ellipsis: true, render: (value?: string) => value || '未分类' },
        { title: '导入模板', dataIndex: 'templateVersionId', width: 320, ellipsis: true, render: (value: string) => { const item = templates.find((candidate) => candidate.versionId === value); return item ? `${item.name} · ${item.templateCode} · V${item.versionNo}` : `版本 ${value.slice(0, 8)}`; } },
        { title: '状态', dataIndex: 'status', width: 130, render: (value: string) => <Tag color={statusLabels[value]?.[1]}>{statusLabels[value]?.[0] || value}</Tag> },
        { title: '工作表', dataIndex: 'sheetCount', width: 100 },
        { title: '识别记录', dataIndex: 'recordCount', width: 110 },
        { title: '字段值', dataIndex: 'fieldCount', width: 100 },
        { title: '最近更新', dataIndex: 'updatedAt', width: 180, render: (value: string) => new Date(value).toLocaleString('zh-CN') },
        { title: '操作', width: 360, render: (_: unknown, record: DataSourceFile) => <Space wrap><Button type="link" icon={<EyeOutlined />} onClick={() => setPreviewFile(resolveFile(record))}>预览</Button><Button type="link" icon={<DownloadOutlined />} onClick={() => void downloadPreviewFile(resolveFile(record))}>下载</Button><Button type="link" onClick={() => navigate(`/data/import-jobs/${record.importJobId}`)}>查看详情</Button><Dropdown trigger={['click']} menu={{ items: categories.map((category) => ({ key: category.id, label: category.name, onClick: () => void moveSource(record, category.id) })) }}><Button type="link">移动分类</Button></Dropdown></Space> },
      ]} />
    </CatalogListPanel>
    <CategoryEditorModal open={Boolean(editor)} title={editor?.mode === 'NEW' ? '新增归档分类' : '编辑归档分类'} initialValue={editor?.item ? { name: editor.item.name, description: editor.item.description } : { name: '' }} confirmLoading={saving} onCancel={() => setEditor(undefined)} onSubmit={(value) => void saveCategory(value)} />
    {deleteItem && <Modal open title={`删除分类“${deleteItem.name}”`} okText="删除并迁移" okButtonProps={{ danger: true }} cancelText="取消" confirmLoading={saving} onCancel={() => setDeleteItem(undefined)} onOk={() => void removeCategory()}><Typography.Paragraph>分类中的来源文件将被迁移到下方分类；未选择时仅允许删除空分类。</Typography.Paragraph><Select allowClear placeholder="选择替代分类" value={replacementId} onChange={setReplacementId} options={categories.filter((item) => item.id !== deleteItem.id).map((item) => ({ value: item.id, label: item.name }))} style={{ width: '100%' }} /></Modal>}
    <FilePreviewModal open={Boolean(previewFile)} file={previewFile} onClose={() => setPreviewFile(undefined)} />
  </div>;
}
