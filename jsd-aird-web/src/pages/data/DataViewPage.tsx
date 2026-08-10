import { DatabaseOutlined, DownloadOutlined, ReloadOutlined, UploadOutlined } from '@ant-design/icons';
import { App, Button, Dropdown, Empty, Input, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { CategoryCardGrid, CategoryEditorModal, CatalogListPanel, type CatalogCategoryCard, type CategoryEditorValue } from '@/components/catalog-workspace';
import { dataApi, dataTypeOptions, type DataAsset, type DataCategory, type DataTemplateOption, type DataType } from '@/services/data/data-api';

const statusLabels: Record<string, [string, string]> = { ACTIVE: ['已发布', 'success'], ARCHIVED: ['已归档', 'default'], DRAFT: ['草稿', 'processing'] };

export function DataViewPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [categories, setCategories] = useState<DataCategory[]>([]);
  const [items, setItems] = useState<DataAsset[]>([]);
  const [categoryId, setCategoryId] = useState('ALL');
  const [status, setStatus] = useState<string>();
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState({ current: 1, pageSize: 20, total: 0 });
  const [selected, setSelected] = useState<string[]>([]);
  const [exportOpen, setExportOpen] = useState(false);
  const [exportTemplates, setExportTemplates] = useState<DataTemplateOption[]>([]);
  const [exportType, setExportType] = useState<DataType>();
  const [exportVersion, setExportVersion] = useState<string>();
  const [exportLoading, setExportLoading] = useState(false);
  const [editor, setEditor] = useState<{ mode: 'NEW' | 'EDIT'; item?: DataCategory }>();
  const [deleteItem, setDeleteItem] = useState<DataCategory>();
  const [replacementId, setReplacementId] = useState<string>();
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [categoryList, result] = await Promise.all([
        dataApi.listCategories(),
        dataApi.listAssets({ categoryId: categoryId === 'ALL' ? undefined : categoryId, status, keyword: keyword || undefined, page: page.current, size: page.pageSize }),
      ]);
      setCategories(categoryList); setItems(result.items); setPage((value) => ({ ...value, current: result.page, pageSize: result.size, total: result.total })); setSelected([]);
    } catch (error) { void message.error(error instanceof Error ? error.message : '数据资产加载失败'); }
    finally { setLoading(false); }
  }, [categoryId, keyword, message, page.current, page.pageSize, status]);
  useEffect(() => { void load(); }, [load]);

  const cards = useMemo<CatalogCategoryCard[]>(() => [
    { id: 'ALL', name: '全部数据', count: categories.reduce((sum, item) => sum + item.assetCount, 0), description: '正式数据资产统一查看', icon: <DatabaseOutlined />, tone: 'blue' },
    ...categories.map((item, index) => ({ id: item.id, name: item.name, count: item.assetCount, description: item.targetDataType ? dataTypeOptions.find((option) => option.value === item.targetDataType)?.label : '自定义数据分类', icon: <DatabaseOutlined />, tone: (['green', 'blue', 'violet', 'orange', 'teal'] as const)[index % 5], editable: true })),
  ], [categories]);
  const selectedItems = items.filter((item) => selected.includes(item.id));

  const openExport = () => {
    const types = new Set(selectedItems.map((item) => item.targetDataType));
    if (types.size !== 1) { void message.warning('请选择同一数据类型的资产'); return; }
    const type = [...types][0]; setExportType(type); setExportVersion(undefined); setExportOpen(true);
    void dataApi.listTemplates(type).then((templates) => setExportTemplates(templates.filter((item) => item.format === 'XLSX'))).catch((error) => void message.error(error instanceof Error ? error.message : '导出模板加载失败'));
  };

  const downloadExport = async () => {
    if (!exportType || !exportVersion || !selectedItems.length) { void message.warning('请选择已发布数据中心模板'); return; }
    setExportLoading(true);
    try { const blob = await dataApi.exportAssets({ targetDataType: exportType, templateVersionId: exportVersion, assetIds: selectedItems.map((item) => item.id) }); const url = URL.createObjectURL(blob); const anchor = document.createElement('a'); anchor.href = url; anchor.download = 'data-assets.zip'; document.body.appendChild(anchor); anchor.click(); anchor.remove(); URL.revokeObjectURL(url); setExportOpen(false); void message.success(`已导出 ${selectedItems.length} 个资产文件`); }
    catch (error) { void message.error(error instanceof Error ? error.message : '批量文件导出失败'); }
    finally { setExportLoading(false); }
  };

  const saveCategory = async (value: CategoryEditorValue) => {
    setSaving(true);
    try { if (editor?.mode === 'NEW') await dataApi.createCategory({ name: value.name.trim(), targetDataType: value.targetDataType as DataType | undefined }); else if (editor?.item) await dataApi.renameCategory(editor.item.id, value.name.trim()); setEditor(undefined); await load(); void message.success('分类已保存'); }
    catch (error) { void message.error(error instanceof Error ? error.message : '分类保存失败'); }
    finally { setSaving(false); }
  };
  const removeCategory = async () => {
    if (!deleteItem) return;
    setSaving(true);
    try { await dataApi.deleteCategory(deleteItem.id, replacementId); setDeleteItem(undefined); setReplacementId(undefined); setCategoryId('ALL'); await load(); void message.success('分类已删除'); }
    catch (error) { void message.error(error instanceof Error ? error.message : '分类删除失败'); }
    finally { setSaving(false); }
  };
  const moveAsset = async (asset: DataAsset, nextId: string) => {
    try { await dataApi.assignCategory(asset.id, nextId); await load(); void message.success('资产分类已更新'); }
    catch (error) { void message.error(error instanceof Error ? error.message : '资产分类更新失败'); }
  };

  return <div className="business-page">
    <div className="page-heading"><div><Typography.Title level={2}>数据查看</Typography.Title><Typography.Text type="secondary">按固定数据类型和自定义分类查看正式数据资产，数据与数据上传实时同步。</Typography.Text></div><Button type="primary" icon={<UploadOutlined />} onClick={() => navigate('/data/upload')}>数据上传</Button></div>
    <CategoryCardGrid categories={cards} activeId={categoryId} onSelect={(id) => { setCategoryId(id); setPage((value) => ({ ...value, current: 1 })); }} onCreate={() => setEditor({ mode: 'NEW' })} onRename={(item) => setEditor({ mode: 'EDIT', item: categories.find((candidate) => candidate.id === item.id) })} onDelete={(item) => setDeleteItem(categories.find((candidate) => candidate.id === item.id))} />
    <div className="catalog-sync-note"><ReloadOutlined /> 数据查看与数据上传实时同步；上传成功并完成正式提交后，资产会自动进入对应分类。</div>
    <CatalogListPanel title={cards.find((item) => item.id === categoryId)?.name || '数据资产'} count={page.total} filters={<Space wrap><Input.Search allowClear placeholder="搜索资产编号或名称" value={keyword} onChange={(event) => { setKeyword(event.target.value); setPage((value) => ({ ...value, current: 1 })); }} onSearch={() => void load()} /><Select allowClear placeholder="全部状态" value={status} onChange={(value) => { setStatus(value); setPage((current) => ({ ...current, current: 1 })); }} options={Object.entries(statusLabels).map(([value, item]) => ({ value, label: item[0] }))} /><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button></Space>} actions={<><Typography.Text type="secondary">已选 {selected.length} 项</Typography.Text><Button onClick={() => setSelected(items.map((item) => item.id))} disabled={!items.length}>选择当前结果</Button><Button onClick={() => setSelected([])} disabled={!selected.length}>清空选择</Button><Button type="primary" icon={<DownloadOutlined />} disabled={!selected.length} onClick={openExport}>批量导出文件</Button></>} loading={loading}>
      <Table rowKey="id" rowSelection={{ selectedRowKeys: selected, onChange: (keys) => setSelected(keys.map(String)) }} dataSource={items} pagination={{ current: page.current, pageSize: page.pageSize, total: page.total, showSizeChanger: true, onChange: (current, pageSize) => setPage({ current, pageSize, total: page.total }) }} locale={{ emptyText: <Empty description="当前分类暂无正式数据资产" /> }} onRow={(record) => ({ onDoubleClick: () => navigate(`/data/assets/${record.id}`) })} columns={[{ title: '资产名称', dataIndex: 'displayName', render: (value: string | undefined) => <Space><DatabaseOutlined /><Typography.Text strong>{value || '未命名资产'}</Typography.Text></Space> }, { title: '数据类型', dataIndex: 'targetDataType', render: (value: DataType) => dataTypeOptions.find((option) => option.value === value)?.label || value }, { title: '资产编码', dataIndex: 'assetKey' }, { title: '分类', dataIndex: 'categoryName', render: (value?: string) => value || '未分类' }, { title: '状态', dataIndex: 'status', render: (value: string) => <Tag color={statusLabels[value]?.[1]}>{statusLabels[value]?.[0] || value}</Tag> }, { title: '最近更新', dataIndex: 'updatedAt', render: (value: string) => new Date(value).toLocaleString('zh-CN') }, { title: '操作', width: 250, render: (_, record) => <Space><Button type="link" onClick={() => navigate(`/data/assets/${record.id}`)}>查看详情</Button><Dropdown trigger={['click']} menu={{ items: categories.filter((category) => !category.targetDataType || category.targetDataType === record.targetDataType).map((category) => ({ key: category.id, label: category.name, onClick: () => void moveAsset(record, category.id) })) }}><Button type="link">移动分类</Button></Dropdown></Space> }]} />
    </CatalogListPanel>
    <CategoryEditorModal open={Boolean(editor)} title={editor?.mode === 'NEW' ? '新增数据分类' : '重命名数据分类'} initialValue={editor?.item ? { name: editor.item.name, targetDataType: editor.item.targetDataType } : { name: '' }} targetDataTypeOptions={dataTypeOptions} confirmLoading={saving} onCancel={() => setEditor(undefined)} onSubmit={(value) => void saveCategory(value)} />
    {deleteItem && <Modal open title={`删除分类“${deleteItem.name}”`} okText="删除并迁移" okButtonProps={{ danger: true }} cancelText="取消" confirmLoading={saving} onCancel={() => setDeleteItem(undefined)} onOk={() => void removeCategory()}><Typography.Paragraph>分类中的资产将被迁移到下方分类；未选择时仅允许删除空分类。</Typography.Paragraph><Select allowClear placeholder="选择替代分类" value={replacementId} onChange={setReplacementId} options={categories.filter((item) => item.id !== deleteItem.id).map((item) => ({ value: item.id, label: item.name }))} style={{ width: '100%' }} /></Modal>}
    <Modal open={exportOpen} title={`批量导出 ${selectedItems.length} 个资产`} okText="生成 ZIP" cancelText="取消" confirmLoading={exportLoading} onOk={() => void downloadExport()} onCancel={() => setExportOpen(false)}><Typography.Paragraph type="secondary">每个正式资产生成一个 XLSX，ZIP 内同时附带 manifest.csv。</Typography.Paragraph><Select aria-label="导出模板" showSearch optionFilterProp="label" placeholder="选择已发布数据中心 XLSX 模板" value={exportVersion} onChange={setExportVersion} options={exportTemplates.map((item) => ({ value: item.versionId, label: `${item.name} · ${item.templateCode} · V${item.versionNo}` }))} style={{ width: '100%' }} /></Modal>
  </div>;
}
