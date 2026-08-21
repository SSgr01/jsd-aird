import { DeleteOutlined, DownloadOutlined, EditOutlined, EyeOutlined, FilePdfOutlined, FileWordOutlined, FileExcelOutlined, FolderOpenOutlined, ReloadOutlined, SafetyCertificateOutlined, StopOutlined, TagsOutlined, UndoOutlined, UploadOutlined } from '@ant-design/icons';
import { App, Button, Dropdown, Empty, Input, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { CategoryCardGrid, CategoryEditorModal, CatalogListPanel, type CatalogCategoryCard, type CategoryEditorValue } from '@/components/catalog-workspace';
import { knowledgeApi, type KnowledgeCategory, type KnowledgeDocument } from '@/services/knowledge';

const scopes = [{ value: 'INTERNAL' as const, label: '内部资料' }, { value: 'EXTERNAL' as const, label: '外部资料' }];
const statusLabels: Record<string, [string, string]> = { QUEUED: ['排队中', 'processing'], PROCESSING: ['解析中', 'processing'], READY: ['已入库', 'success'], FAILED: ['失败', 'error'], REJECTED: ['已拒绝', 'error'], PENDING_PROVIDER: ['解析服务暂不可用', 'warning'] };

function fileIcon(contentType: string) {
  if (contentType.includes('pdf')) return <FilePdfOutlined className="pdf-icon" />;
  if (contentType.includes('word')) return <FileWordOutlined className="word-icon" />;
  if (contentType.includes('sheet') || contentType.includes('excel')) return <FileExcelOutlined className="excel-icon" />;
  return <FileWordOutlined />;
}

export function KnowledgeViewPage() {
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const [scope, setScope] = useState<'INTERNAL' | 'EXTERNAL'>('INTERNAL');
  const [categories, setCategories] = useState<KnowledgeCategory[]>([]);
  const [items, setItems] = useState<KnowledgeDocument[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState<string>();
  const [lifecycleStatus, setLifecycleStatus] = useState<string>();
  const [reviewStatus, setReviewStatus] = useState<string>();
  const [categoryId, setCategoryId] = useState('ALL');
  const [page, setPage] = useState({ current: 1, pageSize: 20, total: 0 });
  const [editor, setEditor] = useState<{ mode: 'NEW' | 'EDIT'; item?: KnowledgeCategory }>();
  const [deleteItem, setDeleteItem] = useState<KnowledgeCategory>();
  const [replacementId, setReplacementId] = useState<string>();
  const [moving, setMoving] = useState<KnowledgeDocument>();
  const [saving, setSaving] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  const [renameItem, setRenameItem] = useState<KnowledgeDocument>();
  const [renameValue, setRenameValue] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [categoryList, result] = await Promise.all([
        knowledgeApi.categories(scope),
        knowledgeApi.list({ keyword: keyword || undefined, status, scope, lifecycleStatus, reviewStatus, categoryId: categoryId === 'ALL' ? undefined : categoryId, page: page.current, size: page.pageSize }),
      ]);
      setCategories(categoryList); setItems(result.items); setPage((value) => ({ ...value, current: result.page, pageSize: result.size, total: result.total })); setSelected([]);
    } catch (error) { void message.error(error instanceof Error ? error.message : '知识库加载失败'); }
    finally { setLoading(false); }
  }, [categoryId, keyword, lifecycleStatus, message, page.current, page.pageSize, reviewStatus, scope, status]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => { setCategoryId('ALL'); setPage((value) => ({ ...value, current: 1 })); }, [scope]);

  const cards = useMemo<CatalogCategoryCard[]>(() => [
    { id: 'ALL', name: scope === 'INTERNAL' ? '全部内部资料' : '全部外部资料', count: page.total, description: '当前资料范围的全部文档', icon: <FolderOpenOutlined />, tone: 'blue' },
    ...categories.map((item) => ({ id: item.id, name: item.name, count: item.documentCount, description: item.description, icon: <FolderOpenOutlined />, tone: 'blue' as const, editable: item.name !== '未分类' })),
  ], [categories, page.total, scope]);

  const saveCategory = async (value: CategoryEditorValue) => {
    setSaving(true);
    try {
      const description = value.description?.trim() || undefined;
      if (editor?.mode === 'NEW') await knowledgeApi.createCategory({ name: value.name.trim(), scope, description });
      else if (editor?.item) await knowledgeApi.renameCategory(editor.item.id, { name: value.name.trim(), description });
      setEditor(undefined); await load(); void message.success('分类已保存');
    }
    catch (error) { void message.error(error instanceof Error ? error.message : '分类保存失败'); }
    finally { setSaving(false); }
  };

  const removeCategory = async () => {
    if (!deleteItem) return;
    setSaving(true);
    try { await knowledgeApi.deleteCategory(deleteItem.id, replacementId); setDeleteItem(undefined); setReplacementId(undefined); setCategoryId('ALL'); await load(); void message.success('分类已删除'); }
    catch (error) { void message.error(error instanceof Error ? error.message : '分类删除失败'); }
    finally { setSaving(false); }
  };

  const moveDocument = async (target: string, document?: KnowledgeDocument) => {
    const current = document || moving;
    if (!current) return;
    setSaving(true);
    try { await knowledgeApi.assignCategory(current.id, target); setMoving(undefined); await load(); void message.success('文档分类已更新'); }
    catch (error) { void message.error(error instanceof Error ? error.message : '文档移动失败'); }
    finally { setSaving(false); }
  };

  const exportDocuments = async () => {
    if (!selected.length || selected.length > 200) { void message.warning('请选择 1-200 个文件'); return; }
    setExporting(true);
    try {
      const blob = await knowledgeApi.exportDocuments(selected);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a'); anchor.href = url; anchor.download = 'knowledge-documents.zip'; document.body.appendChild(anchor); anchor.click(); anchor.remove(); URL.revokeObjectURL(url);
      void message.success(`已导出 ${selected.length} 个知识文件`);
    } catch (error) { void message.error(error instanceof Error ? error.message : '知识文件导出失败'); }
    finally { setExporting(false); }
  };

  const renameDocument = async () => {
    if (!renameItem || !renameValue.trim()) { void message.warning('请输入文件名称'); return; }
    setSaving(true);
    try { await knowledgeApi.rename(renameItem.id, renameValue.trim()); setRenameItem(undefined); await load(); void message.success('文件已重命名'); }
    catch (error) { void message.error(error instanceof Error ? error.message : '文件重命名失败'); }
    finally { setSaving(false); }
  };

  const deleteSelected = () => {
    if (!selected.length) return;
    const draftIds = items.filter((item) => selected.includes(item.id) && !item.currentPublicationId).map((item) => item.id);
    if (!draftIds.length) { void message.warning('已发布文档不能物理删除，请使用停用'); return; }
    modal.confirm({
      title: `删除 ${draftIds.length} 个从未发布的草稿？`,
      content: selected.length > draftIds.length ? `已自动排除 ${selected.length - draftIds.length} 个有发布记录的文档；已发布文档只能停用。` : '删除后草稿版本和解析结果将从知识库移除。',
      okText: '确认删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try { await Promise.all(draftIds.map((id) => knowledgeApi.remove(id))); await load(); void message.success('已删除未发布草稿'); }
        catch (error) { void message.error(error instanceof Error ? error.message : '文件删除失败'); }
      },
    });
  };

  const summarizeBatch = (results: Array<{ success: boolean; message?: string }>, action: string) => {
    const failed = results.filter((item) => !item.success);
    if (failed.length) void message.warning(`${action}完成：成功 ${results.length - failed.length} 项，失败 ${failed.length} 项`);
    else void message.success(`${action}完成：${results.length} 项成功`);
  };

  const batchMove = async (target: string) => {
    if (!selected.length) return;
    try { const results = await knowledgeApi.batchMove(selected, target); summarizeBatch(results, '批量移动'); await load(); }
    catch (error) { void message.error(error instanceof Error ? error.message : '批量移动失败'); }
  };
  const batchTags = (mode: 'ADD' | 'REMOVE') => {
    if (!selected.length) return;
    let value = '';
    const adding = mode === 'ADD';
    modal.confirm({ title: `为 ${selected.length} 个文档${adding ? '添加' : '移除'}标签`, content: <Input placeholder="多个标签用逗号分隔" onChange={(event) => { value = event.target.value; }} />, okText: adding ? '添加标签' : '移除标签', onOk: async () => { const tags = value.split(/[,，]/).map((item) => item.trim()).filter(Boolean); if (!tags.length) throw new Error('请输入至少一个标签'); const results = await knowledgeApi.batchTags(selected, adding ? tags : [], adding ? [] : tags); summarizeBatch(results, adding ? '批量添加标签' : '批量移除标签'); await load(); } });
  };
  const batchAi = (action: 'APPROVE' | 'REVOKE') => {
    if (!selected.length) return;
    modal.confirm({ title: action === 'APPROVE' ? `授权 ${selected.length} 个当前发布版本用于 AI？` : `撤销 ${selected.length} 个当前发布版本的 AI 授权？`, content: '批量操作逐项提交，单项失败不会回滚其他成功项。', onOk: async () => { const results = await knowledgeApi.batchAiUsage(selected, action); summarizeBatch(results, '批量 AI 授权'); await load(); } });
  };
  const lifecycle = (item: KnowledgeDocument) => {
    if (item.lifecycleStatus === 'DISABLED') {
      modal.confirm({ title: `恢复“${item.title}”？`, content: '恢复后将重新纳入当前发布版文件检索；AI 使用仍取决于当前发布版授权。', onOk: async () => { await knowledgeApi.restore(item.id); await load(); } });
      return;
    }
    let reason = '';
    modal.confirm({ title: `停用“${item.title}”？`, content: <Input.TextArea placeholder="停用原因（必填）" onChange={(event) => { reason = event.target.value; }} />, okText: '确认停用', okButtonProps: { danger: true }, onOk: async () => { if (!reason.trim()) throw new Error('停用原因不能为空'); await knowledgeApi.disable(item.id, reason.trim()); await load(); } });
  };

  return <div className="business-page">
    <div className="page-heading"><div><Typography.Title level={2}>知识库查看</Typography.Title><Typography.Text type="secondary">治理逻辑文档、审核版本和当前发布快照；停用会立即退出文件检索与 AI 问答。</Typography.Text></div><Button type="primary" icon={<UploadOutlined />} onClick={() => navigate('/knowledge/library')}>上传文件</Button></div>
    <div className="catalog-tabs" role="tablist" aria-label="资料范围">{scopes.map((item) => <Button key={item.value} type={scope === item.value ? 'primary' : 'default'} role="tab" aria-selected={scope === item.value} onClick={() => setScope(item.value)}>{item.label}</Button>)}</div>
    <CategoryCardGrid categories={cards} activeId={categoryId} onSelect={(id) => { setCategoryId(id); setPage((value) => ({ ...value, current: 1 })); }} onCreate={() => setEditor({ mode: 'NEW' })} onRename={(item) => setEditor({ mode: 'EDIT', item: categories.find((candidate) => candidate.id === item.id) })} onDelete={(item) => setDeleteItem(categories.find((candidate) => candidate.id === item.id))} />
    <CatalogListPanel title={cards.find((item) => item.id === categoryId)?.name || '资料文件'} count={page.total} filters={<Space wrap><Input.Search allowClear placeholder="搜索文档、图片或音频" value={keyword} onChange={(event) => { setKeyword(event.target.value); setPage((value) => ({ ...value, current: 1 })); }} onSearch={() => void load()} /><Select allowClear placeholder="全部解析状态" value={status} onChange={(value) => { setStatus(value); setPage((current) => ({ ...current, current: 1 })); }} options={Object.entries(statusLabels).map(([value, item]) => ({ value, label: item[0] }))} /><Select allowClear placeholder="全部生命周期" value={lifecycleStatus} onChange={setLifecycleStatus} options={[{ value: 'ACTIVE', label: '有效' }, { value: 'DISABLED', label: '已停用' }]} /><Select allowClear placeholder="全部审核状态" value={reviewStatus} onChange={setReviewStatus} options={[{ value: 'PENDING_REVIEW', label: '待审核' }, { value: 'REJECTED', label: '已驳回' }, { value: 'PUBLISHED', label: '已发布' }, { value: 'SUPERSEDED', label: '已替代' }]} /><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button></Space>} actions={<><Typography.Text type="secondary">已选 {selected.length} 项</Typography.Text><Dropdown menu={{ items: categories.map((category) => ({ key: category.id, label: category.name, onClick: () => void batchMove(category.id) })) }}><Button disabled={!selected.length}>批量移动</Button></Dropdown><Dropdown menu={{ items: [{ key: 'add', label: '添加标签', onClick: () => batchTags('ADD') }, { key: 'remove', label: '移除标签', onClick: () => batchTags('REMOVE') }] }}><Button icon={<TagsOutlined />} disabled={!selected.length}>批量标签</Button></Dropdown><Dropdown menu={{ items: [{ key: 'approve', label: '授权当前发布版', onClick: () => batchAi('APPROVE') }, { key: 'revoke', label: '撤销当前发布版授权', danger: true, onClick: () => batchAi('REVOKE') }] }}><Button icon={<SafetyCertificateOutlined />} disabled={!selected.length}>批量 AI 授权</Button></Dropdown><Button loading={exporting} icon={<DownloadOutlined />} disabled={!selected.length || selected.length > 200} onClick={() => void exportDocuments()}>批量导出</Button><Button danger icon={<DeleteOutlined />} disabled={!selected.length} onClick={deleteSelected}>删除未发布草稿</Button></>} loading={loading}>
      <Table className="catalog-knowledge-table" scroll={{ x: 1420 }} rowKey="id" rowSelection={{ selectedRowKeys: selected, onChange: (keys) => setSelected(keys.map(String)) }} dataSource={items} pagination={{ current: page.current, pageSize: page.pageSize, total: page.total, showSizeChanger: true, onChange: (current, pageSize) => setPage({ current, pageSize, total: page.total }) }} locale={{ emptyText: <Empty description="当前分类暂无符合条件的文件" /> }} columns={[{ title: '文件名称', dataIndex: 'title', width: 330, ellipsis: true, render: (_, item) => <Space>{fileIcon(item.contentType)}<span><Typography.Text strong ellipsis={{ tooltip: item.title }}>{item.title}</Typography.Text><span className="binding-path">{item.originalName} · {(item.size / 1024).toFixed(0)} KB · V{item.currentVersionNo}{item.currentPublicationNo ? ` · 发布#${item.currentPublicationNo}` : ''}</span></span></Space> }, { title: '当前分类', dataIndex: 'categoryName', width: 150, ellipsis: true, render: (value?: string) => value || '未分类' }, { title: '生命周期', dataIndex: 'lifecycleStatus', width: 120, render: (value: string) => <Tag color={value === 'ACTIVE' ? 'success' : 'default'}>{value === 'ACTIVE' ? '有效' : '已停用'}</Tag> }, { title: '审核', dataIndex: 'reviewStatus', width: 130, render: (value: string) => <Tag color={value === 'PUBLISHED' ? 'blue' : value === 'REJECTED' ? 'error' : 'gold'}>{value}</Tag> }, { title: 'AI 使用', dataIndex: 'aiStatus', width: 120, render: (value: string) => <Tag color={value === 'APPROVED' ? 'success' : 'default'}>{value === 'APPROVED' ? '已授权' : '待授权'}</Tag> }, { title: '处理状态', dataIndex: 'status', width: 130, render: (value: string) => <Tag color={statusLabels[value]?.[1]}>{statusLabels[value]?.[0] || value}</Tag> }, { title: '操作', width: 390, render: (_, item) => <Space wrap><Button type="link" icon={<EyeOutlined />} onClick={() => navigate(`/knowledge/documents/${item.id}`)}>查看</Button>{item.reviewStatus === 'PENDING_REVIEW' || item.reviewStatus === 'REJECTED' ? <Button type="link" onClick={() => navigate(`/knowledge/review/${item.id}/${item.currentVersionId}`)}>校对</Button> : null}<Button type="link" icon={<EditOutlined />} onClick={() => { setRenameItem(item); setRenameValue(item.title); }}>重命名</Button><Dropdown trigger={['click']} menu={{ items: categories.map((category) => ({ key: category.id, label: category.name, onClick: () => void moveDocument(category.id, item) })) }}><Button type="link">移动</Button></Dropdown><Button type="link" danger={item.lifecycleStatus === 'ACTIVE'} icon={item.lifecycleStatus === 'ACTIVE' ? <StopOutlined /> : <UndoOutlined />} onClick={() => lifecycle(item)}>{item.lifecycleStatus === 'ACTIVE' ? '停用' : '恢复'}</Button></Space> }]} />
    </CatalogListPanel>
    <CategoryEditorModal open={Boolean(editor)} title={editor?.mode === 'NEW' ? '新增知识分类' : '编辑知识分类'} initialValue={editor?.item ? { name: editor.item.name, description: editor.item.description } : { name: '' }} confirmLoading={saving} onCancel={() => setEditor(undefined)} onSubmit={(value) => void saveCategory(value)} />
    {deleteItem && <ModalDelete title={`删除分类“${deleteItem.name}”`} open onCancel={() => setDeleteItem(undefined)} onConfirm={() => void removeCategory()} loading={saving} replacementId={replacementId} onReplacementChange={setReplacementId} options={categories.filter((item) => item.id !== deleteItem.id).map((item) => ({ value: item.id, label: item.name }))} />}
    <Modal open={Boolean(renameItem)} title="重命名文件" okText="保存" cancelText="取消" confirmLoading={saving} onCancel={() => setRenameItem(undefined)} onOk={() => void renameDocument()}><Input aria-label="文件名称" maxLength={260} value={renameValue} onChange={(event) => setRenameValue(event.target.value)} /></Modal>
  </div>;
}

function ModalDelete({ title, open, loading, replacementId, options, onReplacementChange, onCancel, onConfirm }: { title: string; open: boolean; loading: boolean; replacementId?: string; options: Array<{ value: string; label: string }>; onReplacementChange: (value?: string) => void; onCancel: () => void; onConfirm: () => void }) {
  return <Modal open={open} title={title} okText="删除并迁移" okButtonProps={{ danger: true }} cancelText="取消" confirmLoading={loading} onCancel={onCancel} onOk={onConfirm}><Typography.Paragraph>分类中的文件将被迁移到下方分类；未选择时仅允许删除空分类。</Typography.Paragraph><Select allowClear placeholder="选择替代分类" value={replacementId} onChange={onReplacementChange} options={options} style={{ width: '100%' }} /></Modal>;
}
