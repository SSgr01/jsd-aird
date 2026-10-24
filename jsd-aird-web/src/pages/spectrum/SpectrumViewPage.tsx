import { useRequestGuard } from '@/services/query/use-request-guard';
import { DeleteOutlined, DownloadOutlined, EyeOutlined, FolderOpenOutlined, LineChartOutlined, MessageOutlined, RedoOutlined, SearchOutlined, UploadOutlined } from '@ant-design/icons';
import { App, Button, Dropdown, Empty, Input, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';

import { CategoryCardGrid, type CatalogCategoryCard } from '@/components/catalog-workspace/CategoryCardGrid';
import { CatalogListPanel } from '@/components/catalog-workspace/CatalogListPanel';
import { CategoryEditorModal, type CategoryEditorValue } from '@/components/catalog-workspace/CategoryEditorModal';
import { FilePreviewModal, downloadPreviewFile, type FilePreviewDescriptor } from '@/components/file-preview';
import { ProjectRelationPicker } from '@/components/project-relations/ProjectRelationPicker';
import type { ProjectRelationTarget } from '@/services/project/project-resource-api';
import { projectResourceApi } from '@/services/project/project-resource-api';
import { spectrumApi, type SpectrumCategory, type SpectrumChart } from '@/services/spectrum';
import { SpectrumChartEditor } from './SpectrumChartEditor';

const formatSize = (size: number) => size < 1024 * 1024 ? `${Math.ceil(size / 1024)} KB` : `${(size / 1024 / 1024).toFixed(1)} MB`;

export function SpectrumViewPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
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
  const [selectedChartIds, setSelectedChartIds] = useState<string[]>([]);
  const [editingItem, setEditingItem] = useState<SpectrumChart>();
  const [projectEditor, setProjectEditor] = useState<{
    item: SpectrumChart;
    targets: ProjectRelationTarget[];
  }>();

  const beginListRequest = useRequestGuard(JSON.stringify([categoryId,status,keyword,page.current,page.pageSize]));
  const load = useCallback(async () => {
    const request = beginListRequest();
    setLoading(true);
    try {
      const [chartPage, categoryList] = await Promise.all([
        spectrumApi.listCharts({ categoryId: categoryId === 'ALL' ? undefined : categoryId, status, keyword: keyword || undefined, page: page.current, size: page.pageSize }, request.signal),
        spectrumApi.categories(),
      ]);
      if (!request.current()) return;
      setItems(chartPage.items);
      setCategories(categoryList);
      if (categoryId === 'ALL' && !status && !keyword) setAllChartCount(chartPage.total);
      setPage((value) => ({ ...value, current: chartPage.page, pageSize: chartPage.size, total: chartPage.total }));
    } catch (error) { if (request.current()) void message.error(error instanceof Error ? error.message : '图谱列表加载失败'); }
    finally { if (request.current()) setLoading(false); }
  }, [beginListRequest, categoryId, keyword, message, page.current, page.pageSize, status]);

  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    const chartId = searchParams.get('chartId');
    if (!chartId) return;
    void spectrumApi.chart(chartId).then((item) => setPreviewFile(descriptor(item)))
      .catch((error) => void message.error(error instanceof Error ? error.message : '图谱不存在或无法访问'));
  // Deep links are opened once; list filtering changes do not repeat the request.
  }, [message, searchParams]);

  const cards = useMemo<CatalogCategoryCard[]>(() => [
    { id: 'ALL', name: '全部图谱', count: allChartCount, description: '按分类查看客户提供的 PDF 和图谱图片', icon: <LineChartOutlined />, tone: 'blue' },
    ...categories.map((item) => ({ id: item.id, name: item.name, count: item.chartCount, description: item.description, icon: <LineChartOutlined />, tone: 'blue' as const, editable: !item.systemCategory, allowedActions: item.allowedActions })),
  ], [allChartCount, categories]);

  const descriptor = (item: SpectrumChart): FilePreviewDescriptor => ({ fileName: item.originalName, contentType: item.contentType, size: item.size, load: () => spectrumApi.previewBlob(item.id), downloadUrl: spectrumApi.contentUrl(item.id) });

  const saveCategory = async (value: CategoryEditorValue) => {
    setSaving(true);
    try {
      if (editor?.mode === 'NEW') await spectrumApi.createCategory({ name: value.name.trim(), description: value.description?.trim() || undefined });
      else if (editor?.item) await spectrumApi.updateCategory(editor.item.id, { revision: editor.item.revision, name: value.name.trim(), description: value.description?.trim() || undefined });
      setEditor(undefined); await load(); void message.success('图谱分类已保存');
    } catch (error) { void message.error(error instanceof Error ? error.message : '分类保存失败'); }
    finally { setSaving(false); }
  };

  const removeCategory = async () => {
    if (!deleteItem) return;
    setSaving(true);
    try { await spectrumApi.deleteCategory(deleteItem.id, deleteItem.revision); setDeleteItem(undefined); setCategoryId('ALL'); await load(); void message.success('分类已删除'); }
    catch (error) { void message.error(error instanceof Error ? error.message : '分类删除失败'); }
    finally { setSaving(false); }
  };

  const moveChart = async (item: SpectrumChart, nextCategoryId: string) => {
    if (item.categoryId === nextCategoryId) return;
    setSaving(true);
    try {
      await spectrumApi.assignChartCategory(item.id, nextCategoryId, item.revision);
      await load();
      void message.success('图谱分类已更新');
    } catch (error) { void message.error(error instanceof Error ? error.message : '图谱分类更新失败'); }
    finally { setSaving(false); }
  };

  const removeChart = (item: SpectrumChart) => {
    if (!item.allowedActions?.includes('DELETE')) return;
    Modal.confirm({
      title: `删除图谱“${item.title}”？`,
      content: '删除后图谱将从列表中隐藏，原始文件保留。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try { await spectrumApi.deleteChart(item.id, item.revision); await load(); void message.success('图谱已删除'); }
        catch (error) { void message.error(error instanceof Error ? error.message : '图谱删除失败'); throw error; }
      },
    });
  };

  const openProjectEditor = (item: SpectrumChart) => {
    setProjectEditor({ item, targets: [] });
  };

  const saveProjectEditor = async () => {
    if (!projectEditor) return;
    const targets = projectEditor.targets.filter((target) => target.projectId);
    if (!targets.length) { void message.warning('请选择至少一个项目'); return; }
    setSaving(true);
    try {
      await projectResourceApi.addReferences('SPECTRUM_CHART', projectEditor.item.id, targets,
        `${projectEditor.item.categoryName}${projectEditor.item.sampleName ? ` · ${projectEditor.item.sampleName}` : ''}`);
      void message.success('图谱已加入项目参考资料');
      setProjectEditor(undefined);
    } catch (error) { void message.error(error instanceof Error ? error.message : '项目资料保存失败'); }
    finally { setSaving(false); }
  };

  const exportCharts = async (ids: string[]) => {
    if (ids.length > 50) { void message.warning('每次最多导出 50 个图谱，请减少选择数量'); return; }
    try { await spectrumApi.exportCharts(ids); void message.success(`已导出 ${ids.length} 个图谱源文件`); }
    catch (error) { void message.error(error instanceof Error ? error.message : '图谱导出失败'); }
  };

  const resetFilters = () => {
    setCategoryId('ALL');
    setKeyword('');
    setStatus(undefined);
    setPage((current) => ({ ...current, current: 1 }));
  };

  return <div className="business-page spectrum-view-page">
    <div className="page-heading">
      <div><Typography.Title level={2}>图谱查看</Typography.Title><Typography.Text type="secondary">按图谱类型查看和筛选数据。</Typography.Text></div>
      <Button type="primary" icon={<UploadOutlined />} onClick={() => navigate('/spectrum/upload')}>上传图谱</Button>
    </div>
    <CategoryCardGrid categories={cards} activeId={categoryId}
      onSelect={(id) => { setCategoryId(id); setPage((value) => ({ ...value, current: 1 })); }}
      onCreate={() => setEditor({ mode: 'NEW' })}
      onRename={(item) => setEditor({ mode: 'EDIT', item: categories.find((candidate) => candidate.id === item.id) })}
      onDelete={(item) => { const category = categories.find((candidate) => candidate.id === item.id); if (category?.allowedActions?.includes('DELETE')) setDeleteItem(category); }} />
    <CatalogListPanel title={cards.find((item) => item.id === categoryId)?.name || '图谱'} count={page.total}
      filters={<Space wrap><Input allowClear prefix={<SearchOutlined />} placeholder="搜索图谱名称、样品或批号" value={keyword} onChange={(event) => { setKeyword(event.target.value); setPage((value) => ({ ...value, current: 1 })); }} /><Select allowClear placeholder="全部状态" value={status} onChange={(value) => { setStatus(value); setPage((value) => ({ ...value, current: 1 })); }} options={[{ value: 'READY', label: '可分析' }, { value: 'DELETED', label: '已删除' }]} /><Button icon={<RedoOutlined />} onClick={resetFilters}>重置</Button><Button icon={<DownloadOutlined />} disabled={!selectedChartIds.length} onClick={() => void exportCharts(selectedChartIds)}>导出所选 ({selectedChartIds.length})</Button></Space>}
      loading={loading}>
      <Table<SpectrumChart> className="catalog-spectrum-table" rowKey="id" dataSource={items} rowSelection={{ selectedRowKeys: selectedChartIds, onChange: (keys) => setSelectedChartIds(keys.map(String)) }} scroll={{ x: 1580 }} pagination={{ current: page.current, pageSize: page.pageSize, total: page.total, showSizeChanger: true, onChange: (current, pageSize) => setPage({ current, pageSize, total: page.total }) }} locale={{ emptyText: <Empty description="暂无图谱" /> }} columns={[
        { title: '图谱', dataIndex: 'title', width: 280, ellipsis: true, render: (value: string, record) => <Space><LineChartOutlined /><span><Typography.Text strong ellipsis={{ tooltip: value }}>{value}</Typography.Text><br /><Typography.Text type="secondary">{record.originalName}</Typography.Text></span></Space> },
        { title: '分类', dataIndex: 'categoryName', width: 140, ellipsis: true },
        { title: '样品 / 批号', width: 200, ellipsis: true, render: (_: unknown, record) => <span>{record.sampleName || '未填写'}{record.batchNo ? ` · ${record.batchNo}` : ''}</span> },
        { title: '文件', width: 250, render: (_: unknown, record) => `${record.contentType || '图谱文件'} · ${formatSize(record.size)} · ${record.pageCount} 页` },
        { title: '状态', dataIndex: 'status', width: 110, render: (value: string) => <Tag color={value === 'READY' ? 'success' : 'default'}>{value === 'READY' ? '可分析' : value}</Tag> },
        { title: '最近更新', dataIndex: 'updatedAt', width: 180, render: (value: string) => new Date(value).toLocaleString('zh-CN') },
        { title: '操作', width: 640, render: (_: unknown, record) => <Space wrap>
          <Button type="link" icon={<EyeOutlined />} onClick={() => setPreviewFile(descriptor(record))}>预览</Button>
          <Button type="link" icon={<MessageOutlined />} onClick={() => navigate(`/spectrum/chat?chartIds=${record.id}`)}>AI 对话</Button>
          <Button type="link" onClick={() => void downloadPreviewFile(descriptor(record))}>下载源文件</Button>
          <Dropdown trigger={['click']} menu={{ items: categories.filter((category) => category.id !== record.categoryId).map((category) => ({ key: category.id, label: category.name, icon: <FolderOpenOutlined />, onClick: () => void moveChart(record, category.id) })) }}><Button type="link">移动分类</Button></Dropdown>
          <Button type="link" onClick={() => setEditingItem(record)}>编辑</Button>
          <Button type="link" onClick={() => openProjectEditor(record)}>加入参考资料</Button>
          {record.allowedActions?.includes('DELETE') ? <Button type="link" danger icon={<DeleteOutlined />} onClick={() => removeChart(record)}>删除</Button> : null}
        </Space> },
      ]} />
    </CatalogListPanel>
    <CategoryEditorModal open={Boolean(editor)} title={editor?.mode === 'NEW' ? '新增图谱分类' : '编辑图谱分类'} initialValue={editor?.item ? { name: editor.item.name, description: editor.item.description } : { name: '' }} confirmLoading={saving} onCancel={() => setEditor(undefined)} onSubmit={(value) => void saveCategory(value)} />
    {deleteItem && <Modal open title={`删除分类“${deleteItem.name}”`} okText="删除" okButtonProps={{ danger: true }} cancelText="取消" confirmLoading={saving} onCancel={() => setDeleteItem(undefined)} onOk={() => void removeCategory()}><Typography.Paragraph>分类下必须没有图谱才能删除；删除后不会删除原始文件。</Typography.Paragraph></Modal>}
    <FilePreviewModal open={Boolean(previewFile)} file={previewFile} onClose={() => setPreviewFile(undefined)} />
    {editingItem && <SpectrumChartEditor item={editingItem} categories={categories} onCancel={() => setEditingItem(undefined)} onSaved={() => { setEditingItem(undefined); void load(); }} />}
    <Modal open={Boolean(projectEditor)} title="加入项目参考资料" width={780} okText="保存" confirmLoading={saving} onCancel={() => setProjectEditor(undefined)} onOk={() => void saveProjectEditor()}>
      {projectEditor && <ProjectRelationPicker value={projectEditor.targets} onChange={(targets) => setProjectEditor((current) => current ? { ...current, targets } : current)} emptyText="尚未选择项目参考资料" addText="添加参考资料" removeText="移除参考资料" />}
    </Modal>
  </div>;
}
