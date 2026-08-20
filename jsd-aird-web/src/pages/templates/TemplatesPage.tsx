import {
  CopyOutlined,
  DeleteOutlined,
  DownloadOutlined,
  FileExcelOutlined,
  FileTextOutlined,
  FileWordOutlined,
  FolderAddOutlined,
  HistoryOutlined,
  MoreOutlined,
  PlusOutlined,
  ReloadOutlined,
  StopOutlined,
} from '@ant-design/icons';
import {
  App, Button, Card, DatePicker, Dropdown, Empty, Form, Input, Modal, Radio,
  Select, Space, Table, Tag, Typography,
} from 'antd';
import type { TableColumnsType, TablePaginationConfig } from 'antd';
import type { Dayjs } from 'dayjs';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { CategoryCardGrid, type CatalogCategoryCard } from '@/components/catalog-workspace';
import type { TemplateFormat, TemplateListItem, TemplateStatus } from '@/features/template-workspace/types';
import {
  templateApi,
  type CreateTemplateInput,
  type TemplateCategory,
  type TemplateFacetSummary,
} from '@/services/templates/template-api';

const { RangePicker } = DatePicker;
const statusLabels: Record<TemplateStatus, { label: string; color: string }> = {
  DRAFT: { label: '草稿', color: 'gold' },
  PUBLISHED: { label: '已发布', color: 'green' },
  RETIRED: { label: '已停用', color: 'default' },
};

type CreateMode = 'BLANK' | 'IMPORT';
type EditOperation = { type: 'RENAME' | 'COPY'; item: TemplateListItem };

function templateStatus(record: TemplateListItem) {
  const hasUnpublishedChanges = record.hasDraft && record.status !== 'DRAFT';
  return <Space size={4} wrap>
    <Tag color={statusLabels[record.status].color}>{statusLabels[record.status].label}</Tag>
    {hasUnpublishedChanges ? <Tag color="gold">有未发布修改</Tag> : null}
  </Space>;
}

function templateVersion(record: TemplateListItem) {
  if (record.status === 'PUBLISHED' && record.hasDraft) {
    return <Space direction="vertical" size={0}>
      <Typography.Text>发布 V{record.currentPublishedVersionNo ?? record.versionNo}</Typography.Text>
      <Typography.Text type="secondary">草稿 V{record.draftVersionNo ?? record.versionNo}</Typography.Text>
    </Space>;
  }
  if (record.status === 'RETIRED' && record.hasDraft) {
    return <Space direction="vertical" size={0}>
      <Typography.Text>停用 V{record.retiredVersionNo ?? record.versionNo}</Typography.Text>
      <Typography.Text type="secondary">草稿 V{record.draftVersionNo ?? record.versionNo}</Typography.Text>
    </Space>;
  }
  return <>V{record.versionNo}</>;
}

export function TemplatesPage() {
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const [items, setItems] = useState<TemplateListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [searchInput, setSearchInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [format, setFormat] = useState<TemplateFormat>();
  const [status, setStatus] = useState<TemplateStatus>();
  const [categoryId, setCategoryId] = useState<string>();
  const [uncategorized, setUncategorized] = useState(false);
  const [createdBy, setCreatedBy] = useState<string>();
  const [updatedRange, setUpdatedRange] = useState<[Dayjs, Dayjs] | null>(null);
  const [sortBy, setSortBy] = useState<'UPDATED_AT' | 'CREATED_AT' | 'NAME'>('UPDATED_AT');
  const [sortDirection, setSortDirection] = useState<'ASC' | 'DESC'>('DESC');
  const [categoryItems, setCategoryItems] = useState<TemplateCategory[]>([]);
  const [creatorOptions, setCreatorOptions] = useState<Array<{ id: string; displayName: string }>>([]);
  const [createOpen, setCreateOpen] = useState(false);
  const [createMode, setCreateMode] = useState<CreateMode>('BLANK');
  const [creating, setCreating] = useState(false);
  const [categoryEditor, setCategoryEditor] = useState<TemplateCategory | 'NEW'>();
  const [deletingCategory, setDeletingCategory] = useState<TemplateCategory>();
  const [replacementCategoryId, setReplacementCategoryId] = useState<string>();
  const [movingTemplate, setMovingTemplate] = useState<TemplateListItem>();
  const [savingCategory, setSavingCategory] = useState(false);
  const [operation, setOperation] = useState<EditOperation>();
  const [operationName, setOperationName] = useState('');
  const [operationCategoryId, setOperationCategoryId] = useState<string>();
  const [operating, setOperating] = useState(false);
  const [batchOperating, setBatchOperating] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [selectedRecords, setSelectedRecords] = useState<Record<string, TemplateListItem>>({});
  const [batchMoveOpen, setBatchMoveOpen] = useState(false);
  const [batchCategoryId, setBatchCategoryId] = useState<string>();
  const [form] = Form.useForm<CreateTemplateInput>();
  const [categoryForm] = Form.useForm<{ name: string; description?: string | null }>();
  const [facets, setFacets] = useState<TemplateFacetSummary>({
    totalCount: 0, uncategorizedCount: 0, categoryCounts: [],
  });
  const listRequestSequence = useRef(0);
  const facetRequestSequence = useRef(0);

  const listParams = useMemo(() => ({
    keyword: keyword.trim() || undefined, categoryId, uncategorized: uncategorized || undefined, format, status, createdBy,
    updatedFrom: updatedRange?.[0].startOf('day').toISOString(), updatedTo: updatedRange?.[1].endOf('day').toISOString(),
    sortBy, sortDirection, page, size: pageSize,
  }), [categoryId, createdBy, format, keyword, page, pageSize, sortBy, sortDirection, status, uncategorized, updatedRange]);

  const facetParams = useMemo(() => ({
    keyword: keyword.trim() || undefined, format, status, createdBy,
    updatedFrom: updatedRange?.[0].startOf('day').toISOString(), updatedTo: updatedRange?.[1].endOf('day').toISOString(),
  }), [createdBy, format, keyword, status, updatedRange]);

  const load = useCallback(async () => {
    const requestSequence = ++listRequestSequence.current;
    setLoading(true);
    try {
      const result = await templateApi.list(listParams);
      if (requestSequence !== listRequestSequence.current) return;
      setItems(result.items);
      setTotal(result.total);
    } catch (error) {
      if (requestSequence === listRequestSequence.current) {
        void message.error(error instanceof Error ? error.message : '模板列表加载失败');
      }
    } finally {
      if (requestSequence === listRequestSequence.current) setLoading(false);
    }
  }, [listParams, message]);

  const loadFacets = useCallback(async () => {
    const requestSequence = ++facetRequestSequence.current;
    try {
      const result = await templateApi.listFacets(facetParams);
      if (requestSequence === facetRequestSequence.current) setFacets(result);
    } catch (error) {
      if (requestSequence === facetRequestSequence.current) {
        void message.error(error instanceof Error ? error.message : '模板分类统计加载失败');
      }
    }
  }, [facetParams, message]);

  const loadOptions = useCallback(async () => {
    try {
      const [categories, creators] = await Promise.all([
        templateApi.listCategories(), templateApi.filterOptions(),
      ]);
      setCategoryItems(categories); setCreatorOptions(creators);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '模板筛选项加载失败');
    }
  }, [message]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => { void loadFacets(); }, [loadFacets]);
  useEffect(() => { void loadOptions(); }, [loadOptions]);

  const clearSelection = () => setSelectedRecords({});
  const changeFilter = (change: () => void) => { change(); setPage(1); clearSelection(); };
  const selected = Object.values(selectedRecords);

  const categoryCards = useMemo<CatalogCategoryCard[]>(() => {
    const counts = new Map(facets.categoryCounts.map((item) => [item.categoryId, item.count]));
    return [
      { id: 'ALL', name: '全部模板', count: facets.totalCount, description: '每个模板仅展示一条记录', icon: <FileTextOutlined />, tone: 'blue' },
      ...categoryItems.map((item) => ({ id: item.id, name: item.name, count: counts.get(item.id) ?? 0, description: item.description, icon: <FileTextOutlined />, tone: 'blue' as const, editable: true })),
      { id: 'UNCATEGORIZED', name: '未分类', count: facets.uncategorizedCount, description: '尚未归档的模板', icon: <FolderAddOutlined />, tone: 'teal' as const },
    ];
  }, [categoryItems, facets]);

  const applySearch = (value: string) => {
    setSearchInput(value);
    changeFilter(() => setKeyword(value.trim()));
  };

  const resetFilters = () => {
    setSearchInput(''); setKeyword(''); setFormat(undefined); setStatus(undefined); setCategoryId(undefined); setUncategorized(false);
    setCreatedBy(undefined); setUpdatedRange(null); setSortBy('UPDATED_AT'); setSortDirection('DESC');
    setPage(1); clearSelection();
  };

  const refreshCatalog = async () => {
    await Promise.all([load(), loadFacets(), loadOptions()]);
  };

  const exportTemplates = async (params: Parameters<typeof templateApi.exportCsv>[0]) => {
    setExporting(true);
    try {
      await templateApi.exportCsv(params);
      void message.success('模板列表已导出');
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '模板导出失败');
    } finally {
      setExporting(false);
    }
  };

  const create = async () => {
    if (createMode === 'IMPORT') {
      const suffix = categoryId ? `?categoryId=${encodeURIComponent(categoryId)}` : '';
      setCreateOpen(false); navigate(`/templates/upload${suffix}`); return;
    }
    try {
      const input = await form.validateFields();
      setCreating(true);
      const workspace = await templateApi.create(input);
      setCreateOpen(false); form.resetFields();
      navigate(`/templates/${workspace.versionId}/workspace`);
    } catch (error) {
      if (error && typeof error === 'object' && 'errorFields' in error) return;
      void message.error(error instanceof Error ? error.message : '模板创建失败');
    } finally { setCreating(false); }
  };

  const createRevision = async (record: TemplateListItem) => {
    if (record.hasDraft && record.draftVersionId) {
      navigate(`/templates/${record.draftVersionId}/workspace`); return;
    }
    try {
      const source = record.currentPublishedVersionId ?? record.versionId;
      const workspace = await templateApi.createRevision(source);
      void message.success('已创建新的修订草稿');
      navigate(`/templates/${workspace.versionId}/workspace`);
    } catch (error) { void message.error(error instanceof Error ? error.message : '创建修订草稿失败'); }
  };

  const deleteDraft = (record: TemplateListItem) => modal.confirm({
    title: `删除草稿“${record.name}”？`, content: '只删除草稿版本，已发布版本和历史记录仍会保留。',
    okText: '删除草稿', okButtonProps: { danger: true }, cancelText: '取消',
    onOk: async () => {
      try {
        await templateApi.deleteDraft(record.draftVersionId ?? record.versionId);
        await refreshCatalog();
      } catch (error) {
        void message.error(error instanceof Error ? error.message : '草稿删除失败');
        throw error;
      }
    },
  });

  const retire = (record: TemplateListItem) => modal.confirm({
    title: `停用模板“${record.name}”？`, content: '当前发布版将停用；已有草稿会保留。',
    okText: '停用', okButtonProps: { danger: true }, cancelText: '取消',
    onOk: async () => {
      try {
        await templateApi.retire(record.templateId);
        await refreshCatalog();
      } catch (error) {
        void message.error(error instanceof Error ? error.message : '模板停用失败');
        throw error;
      }
    },
  });

  const saveCategory = async (values?: { name: string; description?: string | null }) => {
    const formValues = values ?? await categoryForm.validateFields();
    const editor = categoryEditor; setSavingCategory(true);
    try {
      const value = { name: formValues.name.trim(), description: formValues.description?.trim() || null };
      if (editor === 'NEW') await templateApi.createCategory(value);
      else if (editor) await templateApi.renameCategory(editor.id, value);
      void message.success(editor === 'NEW' ? '分类已创建' : '分类已保存');
      setCategoryEditor(undefined); categoryForm.resetFields(); await refreshCatalog();
    } catch (error) { void message.error(error instanceof Error ? error.message : '分类保存失败'); }
    finally { setSavingCategory(false); }
  };

  const deleteCategory = async () => {
    if (!deletingCategory) return; setSavingCategory(true);
    try {
      await templateApi.deleteCategory(deletingCategory.id, replacementCategoryId);
      if (categoryId === deletingCategory.id) setCategoryId(undefined);
      setDeletingCategory(undefined); setReplacementCategoryId(undefined); setPage(1);
      await refreshCatalog();
    } catch (error) { void message.error(error instanceof Error ? error.message : '分类删除失败'); }
    finally { setSavingCategory(false); }
  };

  const moveTemplate = async (target?: string) => {
    if (!movingTemplate) return; setSavingCategory(true);
    try { await templateApi.assignTemplateCategory(movingTemplate.templateId, target); setMovingTemplate(undefined); await refreshCatalog(); }
    catch (error) { void message.error(error instanceof Error ? error.message : '模板分类更新失败'); }
    finally { setSavingCategory(false); }
  };

  const submitOperation = async () => {
    if (!operation || !operationName.trim()) return; setOperating(true);
    try {
      if (operation.type === 'RENAME') await templateApi.renameTemplate(operation.item.templateId, operationName.trim());
      else await templateApi.copyTemplate(operation.item.versionId, { name: operationName.trim(), categoryId: operationCategoryId });
      setOperation(undefined); await refreshCatalog(); void message.success(operation.type === 'RENAME' ? '模板已重命名' : '模板副本已创建');
    } catch (error) { void message.error(error instanceof Error ? error.message : '操作失败'); }
    finally { setOperating(false); }
  };

  const runBatch = async (action: 'COPY' | 'MOVE' | 'DELETE_DRAFT' | 'RETIRE', targetCategory?: string) => {
    if (!selected.length) return;
    setBatchOperating(true);
    try {
      const results = await templateApi.batchActions({ action, categoryId: targetCategory,
        items: selected.map((item) => ({ templateId: item.templateId, versionId: item.draftVersionId ?? item.versionId })) });
      const successful = new Set(results.filter((item) => item.success).map((item) => item.templateId));
      setSelectedRecords((current) => Object.fromEntries(Object.entries(current).filter(([id]) => !successful.has(id))));
      const failed = results.filter((item) => !item.success);
      if (failed.length) void message.warning(`${results.length - failed.length} 项成功，${failed.length} 项失败；失败项已保留选择`);
      else void message.success(`${results.length} 项操作完成`);
      setBatchMoveOpen(false);
      await refreshCatalog();
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '批量操作失败');
    } finally {
      setBatchOperating(false);
    }
  };

  const tablePagination: TablePaginationConfig = {
    current: page, pageSize, total, showSizeChanger: true, showTotal: (value) => `共 ${value} 个模板`,
    onChange: (nextPage, nextSize) => { setPage(nextSize === pageSize ? nextPage : 1); setPageSize(nextSize); },
  };

  const columns: TableColumnsType<TemplateListItem> = [
    {
      title: '模板', dataIndex: 'name',
      render: (_, record) => <Space>{record.format === 'XLSX' ? <FileExcelOutlined /> : <FileWordOutlined />}<span><Typography.Text strong>{record.name}</Typography.Text><span className="binding-path">{record.templateCode}</span></span></Space>,
    },
    { title: '分类', dataIndex: 'category', render: (_, record) => record.category || '未分类' },
    { title: '状态', key: 'status', render: (_, record) => templateStatus(record) },
    { title: '版本', key: 'version', render: (_, record) => templateVersion(record) },
    { title: '创建人', dataIndex: 'createdByName', responsive: ['md'], render: (_, record) => record.createdByName || '—' },
    { title: '最近更新', dataIndex: 'updatedAt', responsive: ['sm'], render: (_, record) => new Date(record.updatedAt).toLocaleString('zh-CN') },
    {
      title: '操作', key: 'actions', render: (_, record) => <Space size={0} onClick={(event) => event.stopPropagation()}>
        <Button type="link" onClick={() => navigate(`/templates/${record.versionId}/workspace`)}>{record.hasDraft || record.status === 'DRAFT' ? '编辑模板' : '查看模板'}</Button>
        <Dropdown trigger={['click']} menu={{ items: [
          { key: 'copy', icon: <CopyOutlined />, label: '复制模板' }, { key: 'rename', label: '重命名' },
          { key: 'move', icon: <FolderAddOutlined />, label: '移动分类' }, { key: 'history', icon: <HistoryOutlined />, label: '版本历史' },
          ...(record.hasDraft || record.status === 'DRAFT' ? [{ key: 'delete', danger: true, icon: <DeleteOutlined />, label: '删除草稿' }] : [{ key: 'revision', icon: <CopyOutlined />, label: '新建修订版' }]),
          ...(record.status === 'PUBLISHED' ? [{ key: 'retire', danger: true, icon: <StopOutlined />, label: '停用模板' }] : []),
        ], onClick: ({ key }) => {
          if (key === 'copy' || key === 'rename') { setOperation({ type: key === 'copy' ? 'COPY' : 'RENAME', item: record }); setOperationName(key === 'copy' ? `${record.name} - 副本` : record.name); setOperationCategoryId(record.categoryId); }
          if (key === 'move') setMovingTemplate(record); if (key === 'history') navigate(`/templates/${record.versionId}/workspace?view=versions`);
          if (key === 'delete') deleteDraft(record); if (key === 'revision') void createRevision(record); if (key === 'retire') retire(record);
        } }}><Button type="text" aria-label="更多模板操作" icon={<MoreOutlined />} /></Dropdown>
      </Space>,
    },
  ];

  return <Space direction="vertical" size={16} className="page-stack templates-catalog-page">
    <Card className="content-card"><Space direction="vertical" size={4}>
      <Typography.Title level={2} className="page-title">模板库</Typography.Title>
      <Typography.Text type="secondary">一条记录代表一个模板；状态与发布、草稿版本分列显示。</Typography.Text>
    </Space></Card>

    <CategoryCardGrid categories={categoryCards} activeId={uncategorized ? 'UNCATEGORIZED' : categoryId ?? 'ALL'}
      onSelect={(id) => changeFilter(() => { setCategoryId(id === 'ALL' || id === 'UNCATEGORIZED' ? undefined : id); setUncategorized(id === 'UNCATEGORIZED'); })}
      onCreate={() => { categoryForm.resetFields(); setCategoryEditor('NEW'); }}
      onRename={(item) => { const target = categoryItems.find((value) => value.id === item.id); if (target) { categoryForm.setFieldsValue(target); setCategoryEditor(target); } }}
      onDelete={(item) => setDeletingCategory(categoryItems.find((value) => value.id === item.id))}
      countLabel="个模板" />

    <Card className="content-card"><Space wrap size={10}>
      <Input.Search allowClear placeholder="名称或编码" value={searchInput}
        onChange={(event) => { setSearchInput(event.target.value); clearSelection(); }}
        onClear={() => applySearch('')} onSearch={applySearch} style={{ width: 230 }} />
      <Select allowClear placeholder="全部分类" value={uncategorized ? 'UNCATEGORIZED' : categoryId} onChange={(value) => changeFilter(() => { setUncategorized(value === 'UNCATEGORIZED'); setCategoryId(value === 'UNCATEGORIZED' ? undefined : value); })} options={[...categoryItems.map((item) => ({ value: item.id, label: item.name })), { value: 'UNCATEGORIZED', label: '未分类' }]} style={{ width: 150 }} />
      <Select allowClear placeholder="全部格式" value={format} onChange={(value) => changeFilter(() => setFormat(value))} options={[{ value: 'XLSX', label: 'Excel' }, { value: 'DOCX', label: 'Word' }]} style={{ width: 130 }} />
      <Select allowClear placeholder="全部状态" value={status} onChange={(value) => changeFilter(() => setStatus(value))} options={Object.entries(statusLabels).map(([value, item]) => ({ value, label: item.label }))} style={{ width: 130 }} />
      <Select allowClear placeholder="创建人" value={createdBy} onChange={(value) => changeFilter(() => setCreatedBy(value))} options={creatorOptions.map((item) => ({ value: item.id, label: item.displayName }))} style={{ width: 140 }} />
      <RangePicker value={updatedRange} onChange={(values) => changeFilter(() => setUpdatedRange(values ? [values[0]!, values[1]!] : null))} />
      <Select value={sortBy} onChange={(value) => changeFilter(() => setSortBy(value))} options={[{ value: 'UPDATED_AT', label: '最近更新' }, { value: 'CREATED_AT', label: '创建时间' }, { value: 'NAME', label: '名称' }]} style={{ width: 130 }} />
      <Select value={sortDirection} onChange={(value) => changeFilter(() => setSortDirection(value))} options={[{ value: 'DESC', label: '降序' }, { value: 'ASC', label: '升序' }]} style={{ width: 100 }} />
      <Button onClick={resetFilters}>重置</Button><Button icon={<ReloadOutlined />} onClick={() => void refreshCatalog()}>刷新</Button>
      <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新建模板</Button>
      <Button icon={<DownloadOutlined />} loading={exporting} onClick={() => void exportTemplates(listParams)}>导出当前筛选结果</Button>
    </Space></Card>

    {selected.length > 0 && <Card size="small" className="content-card"><Space wrap>
      <Typography.Text strong>已选择 {selected.length} 个模板</Typography.Text>
      <Button icon={<CopyOutlined />} loading={batchOperating} onClick={() => void runBatch('COPY')}>批量复制</Button>
      <Button icon={<FolderAddOutlined />} disabled={batchOperating} onClick={() => setBatchMoveOpen(true)}>移动分类</Button>
      <Button danger icon={<DeleteOutlined />} loading={batchOperating} onClick={() => void runBatch('DELETE_DRAFT')}>删除纯草稿</Button>
      <Button danger icon={<StopOutlined />} loading={batchOperating} onClick={() => void runBatch('RETIRE')}>停用发布版</Button>
      <Button icon={<DownloadOutlined />} loading={exporting} onClick={() => void exportTemplates({ ...listParams, templateIds: selected.map((item) => item.templateId) })}>导出选中</Button>
      <Button type="link" disabled={batchOperating} onClick={clearSelection}>取消选择</Button>
    </Space></Card>}

    <Card className="content-card" styles={{ body: { padding: 0 } }}><Table rowKey="templateId" loading={loading} dataSource={items}
      locale={{ emptyText: <Empty description="暂无模板" /> }} pagination={tablePagination}
      rowSelection={{ selectedRowKeys: Object.keys(selectedRecords), preserveSelectedRowKeys: true,
        onChange: (keys, rows) => setSelectedRecords((current) => {
          const next = { ...current }; const currentPageIds = new Set(items.map((item) => item.templateId));
          currentPageIds.forEach((id) => { if (!keys.includes(id)) delete next[id]; }); rows.forEach((item) => { next[item.templateId] = item; }); return next;
        }) }}
      onRow={(record) => ({ onDoubleClick: () => navigate(`/templates/${record.versionId}/workspace`) })}
      columns={columns} />
    </Card>

    <Modal title="新建模板" open={createOpen} confirmLoading={creating} okText={createMode === 'BLANK' ? '创建并进入工作台' : '前往文件导入'} onOk={() => void create()} onCancel={() => setCreateOpen(false)} destroyOnHidden>
      <Radio.Group value={createMode} onChange={(event) => setCreateMode(event.target.value as CreateMode)} optionType="button" buttonStyle="solid" options={[{ value: 'BLANK', label: '空白新建' }, { value: 'IMPORT', label: 'Word、Excel 文件导入' }]} style={{ marginBottom: 20 }} />
      {createMode === 'BLANK' ? <Form form={form} layout="vertical" initialValues={{ format: 'XLSX' }}>
        <Form.Item name="name" label="模板名称" rules={[{ required: true, whitespace: true, message: '请输入模板名称' }]}><Input autoFocus maxLength={160} /></Form.Item>
        <Form.Item name="format" label="编辑格式" rules={[{ required: true }]}><Select options={[{ value: 'XLSX', label: 'Excel 模板' }, { value: 'DOCX', label: 'Word 模板' }]} /></Form.Item>
        <Form.Item name="category" label="分类"><Select allowClear options={categoryItems.map((item) => ({ value: item.name, label: item.name }))} /></Form.Item>
      </Form> : <Typography.Paragraph type="secondary">前往模板上传页，选择分类并批量导入 XLSX 或 DOCX 文件。原上传地址保持兼容。</Typography.Paragraph>}
    </Modal>

    <Modal title={categoryEditor === 'NEW' ? '新建分类' : '编辑分类'} open={Boolean(categoryEditor)} confirmLoading={savingCategory} onOk={() => categoryForm.submit()} onCancel={() => { setCategoryEditor(undefined); categoryForm.resetFields(); }} forceRender>
      <Form form={categoryForm} layout="vertical" onFinish={(values) => void saveCategory(values)}>
        <Form.Item name="name" label="分类名称" rules={[{ required: true, whitespace: true, message: '请输入分类名称' }]}><Input maxLength={120} /></Form.Item>
        <Form.Item name="description" label="分类简介"><Input.TextArea rows={3} maxLength={240} showCount /></Form.Item>
      </Form>
    </Modal>

    <Modal title={deletingCategory ? `删除分类“${deletingCategory.name}”` : '删除分类'} open={Boolean(deletingCategory)} confirmLoading={savingCategory} okText="删除并迁移" okButtonProps={{ danger: true }} onOk={() => void deleteCategory()} onCancel={() => setDeletingCategory(undefined)}>
      <Typography.Paragraph>模板不会被删除。请选择迁移目标，留空则移到未分类。</Typography.Paragraph><Select allowClear value={replacementCategoryId} onChange={setReplacementCategoryId} options={categoryItems.filter((item) => item.id !== deletingCategory?.id).map((item) => ({ value: item.id, label: item.name }))} style={{ width: '100%' }} />
    </Modal>

    <Modal title={movingTemplate ? `移动“${movingTemplate.name}”` : '移动模板'} open={Boolean(movingTemplate)} footer={null} onCancel={() => setMovingTemplate(undefined)}><Space direction="vertical" style={{ width: '100%' }}><Button block onClick={() => void moveTemplate(undefined)}>移到未分类</Button>{categoryItems.map((item) => <Button block key={item.id} onClick={() => void moveTemplate(item.id)}>{item.name}</Button>)}</Space></Modal>

    <Modal title={operation?.type === 'COPY' ? '复制模板' : '重命名模板'} open={Boolean(operation)} confirmLoading={operating} onOk={() => void submitOperation()} onCancel={() => setOperation(undefined)} destroyOnHidden>
      <Space direction="vertical" style={{ width: '100%' }}><Typography.Text>模板名称</Typography.Text><Input value={operationName} maxLength={160} onChange={(event) => setOperationName(event.target.value)} />{operation?.type === 'COPY' ? <><Typography.Text>副本分类</Typography.Text><Select allowClear value={operationCategoryId} onChange={setOperationCategoryId} options={categoryItems.map((item) => ({ value: item.id, label: item.name }))} style={{ width: '100%' }} /></> : null}</Space>
    </Modal>

    <Modal title="批量移动分类" open={batchMoveOpen} confirmLoading={batchOperating} onOk={() => void runBatch('MOVE', batchCategoryId)} onCancel={() => setBatchMoveOpen(false)}><Select allowClear placeholder="未分类" value={batchCategoryId} onChange={setBatchCategoryId} options={categoryItems.map((item) => ({ value: item.id, label: item.name }))} style={{ width: '100%' }} /></Modal>
  </Space>;
}
