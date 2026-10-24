import { useListPageBounds } from '@/services/query/use-list-page-bounds';
import { selectionSnapshot } from '@/services/query/selection-snapshot';
import { useBusinessQuery } from '@/services/query/use-business-query';
import { useListKeyword, useListUrl } from '@/services/query/use-list-url';
import { DeleteOutlined, DownloadOutlined, EditOutlined, EyeOutlined, FilePdfOutlined, FileWordOutlined, FileExcelOutlined, FolderOpenOutlined, LinkOutlined, RedoOutlined, SearchOutlined, SafetyCertificateOutlined, StopOutlined, TagsOutlined, UndoOutlined, UploadOutlined } from '@ant-design/icons';
import { App, Button, Dropdown, Empty, Input, Modal, Select, Space, Table, Tag, Tooltip, Typography } from 'antd';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { CategoryCardGrid, CategoryEditorModal, CatalogListPanel, type CatalogCategoryCard, type CategoryEditorValue } from '@/components/catalog-workspace';
import { Can } from '@/components/auth/Can';
import { usePermission } from '@/components/auth/usePermission';
import { ProjectRelationPicker } from '@/components/project-relations/ProjectRelationPicker';
import '@/components/project-relations/project-relation-picker.css';
import { downloadBlob } from '@/services/files/file-api';
import { knowledgeApi, type KnowledgeCategory, type KnowledgeDocument, type RelatedProject } from '@/services/knowledge';
import { getProjects } from '@/services/project/project-api';
import { projectResourceApi, type ProjectRelationTarget } from '@/services/project/project-resource-api';
import { KnowledgeReviewQueuePage } from './KnowledgeReviewQueuePage';
import { KnowledgeDocumentEditor } from './KnowledgeDocumentEditor';
import { KnowledgeBatchTagsModal } from './KnowledgeBatchTagsModal';

const scopes = [{ value: 'INTERNAL' as const, label: '内部资料' }, { value: 'EXTERNAL' as const, label: '外部资料' }];
const reviewStatuses = ['PENDING_REVIEW', 'REJECTED', 'PUBLISHED', 'SUPERSEDED'];
const reviewStatusLabels: Record<string, [string, string]> = {
  PENDING_REVIEW: ['待校对', 'gold'],
  REJECTED: ['已驳回', 'error'],
  PUBLISHED: ['已发布', 'blue'],
  SUPERSEDED: ['已被新版本替代', 'default'],
};
const statusLabels: Record<string, [string, string]> = { QUEUED: ['排队中', 'processing'], PROCESSING: ['解析中', 'processing'], READY: ['已入库', 'success'], FAILED: ['失败', 'error'], REJECTED: ['已拒绝', 'error'], PENDING_PROVIDER: ['解析服务暂不可用', 'warning'] };

function fileIcon(contentType: string) {
  if (contentType.includes('pdf')) return <FilePdfOutlined className="pdf-icon" />;
  if (contentType.includes('word')) return <FileWordOutlined className="word-icon" />;
  if (contentType.includes('sheet') || contentType.includes('excel')) return <FileExcelOutlined className="excel-icon" />;
  return <FileWordOutlined />;
}

function relatedProjectLabel(relation: RelatedProject) {
  return `${relation.projectName}${relation.stageName ? ` / ${relation.stageName}` : ''}${relation.taskName ? ` / ${relation.taskName}` : ''}`;
}

function RelatedProjectsCell({ relations, onOpen }: { relations: RelatedProject[]; onOpen: (projectId: string) => void }) {
  if (!relations.length) return <>-</>;
  const first = relations[0]!;
  const remaining = relations.slice(1);
  const open = () => onOpen(first.projectId);
  return <div className="knowledge-related-projects"><Tooltip title={relatedProjectLabel(first)}><Tag className="knowledge-related-project-tag" color="blue" role="link" tabIndex={0} onClick={open} onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); open(); } }}>{relatedProjectLabel(first)}</Tag></Tooltip>{remaining.length > 0 && <Dropdown trigger={['click']} menu={{ items: remaining.map((relation) => ({ key: `${relation.projectId}-${relation.stageId || ''}-${relation.taskId || ''}`, label: relatedProjectLabel(relation), onClick: () => onOpen(relation.projectId) })) }}><Button aria-label={`查看另外 ${remaining.length} 个关联项目`} className="knowledge-related-project-count" size="small" type="link">+{remaining.length}</Button></Dropdown>}</div>;
}

export function KnowledgeViewPage() {
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const url = useListUrl();
  const [scope, setScope] = url.field<'INTERNAL' | 'EXTERNAL'>('scope', 'INTERNAL');
  const [keyword, setKeyword] = useListKeyword(url);
  const [status, setStatus] = url.field<string | undefined>('status', undefined);
  const [lifecycleStatus, setLifecycleStatus] = url.field<string | undefined>('lifecycleStatus', undefined);
  const [reviewStatus, setReviewStatus] = url.field<string | undefined>('reviewStatus', undefined);
  const [categoryId, setCategoryId] = url.field<string>('categoryId', 'ALL');
  const [editor, setEditor] = useState<{ mode: 'NEW' | 'EDIT'; item?: KnowledgeCategory }>();
  const [deleteItem, setDeleteItem] = useState<KnowledgeCategory>();
  const [replacementId, setReplacementId] = useState<string>();
  const [moving, setMoving] = useState<KnowledgeDocument>();
  const [saving, setSaving] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [selectedDocuments, setSelectedDocuments] = useState<KnowledgeDocument[]>([]);
  const selected = selectedDocuments.map(item => item.id);
  const setSelected = (ids: string[]) => setSelectedDocuments(previous => selectionSnapshot(previous, ids, items));
  const [editingDocument, setEditingDocument] = useState<KnowledgeDocument>();
  const [batchTagsOpen, setBatchTagsOpen] = useState(false);
  const [projectId, setProjectId] = url.field<string | undefined>('projectId', undefined);
  const [relationEditor, setRelationEditor] = useState<{ item: KnowledgeDocument; targets: ProjectRelationTarget[] }>();
  const canViewCatalog = usePermission('knowledge.view');
  const canReview = usePermission('knowledge.review');
  const canUpdate = usePermission('knowledge.update');

  const filters = {keyword:keyword||undefined,status,scope,lifecycleStatus,reviewStatus,projectId,categoryId:categoryId==='ALL'?undefined:categoryId,page:url.number('page',1),size:url.number('size',20)};
  const listQuery = useBusinessQuery('knowledge',filters,signal=>knowledgeApi.list(filters,signal),{keepPage:true,enabled:canViewCatalog});
  useListPageBounds(url, listQuery, 20);
  const categoryQuery = useBusinessQuery('knowledge',{kind:'categories',scope},signal=>knowledgeApi.categories(scope,signal),{dictionary:true,live:true,enabled:canViewCatalog});
  const projectQuery = useBusinessQuery('projects',{kind:'options',page:1,size:100},signal=>getProjects({page:1,size:100},signal),{dictionary:true,enabled:canViewCatalog});
  const items = listQuery.data?.items ?? []; const categories = categoryQuery.data ?? []; const projects = projectQuery.data?.items ?? [];
  const tagOptions = useMemo(() => [...new Set(items.flatMap((item) => item.tags || []))], [items]);
  const [page,setPage] = url.pagination(listQuery.data?.total ?? 0);
  const loading = listQuery.isPending && canViewCatalog;
  const load = async () => { await Promise.all([listQuery.refetch(), categoryQuery.refetch()]); setSelected([]); };

  const changeReviewStatus = (value?: string) => setReviewStatus(value && reviewStatuses.includes(value) ? value : undefined);

  const resetFilters = () => {
    setKeyword('');
    setStatus(undefined);
    setLifecycleStatus(undefined);
    setReviewStatus(undefined);
    setCategoryId('ALL');
    setProjectId(undefined);
    setPage((current) => ({ ...current, current: 1 }));
    setSelected([]);
  };


  const cards = useMemo<CatalogCategoryCard[]>(() => [
    { id: 'ALL', name: scope === 'INTERNAL' ? '全部内部资料' : '全部外部资料', count: page.total, description: '当前资料范围的全部文档', icon: <FolderOpenOutlined />, tone: 'blue' },
    ...categories.map((item) => ({ id: item.id, name: item.name, count: item.documentCount, description: item.description, icon: <FolderOpenOutlined />, tone: 'blue' as const, editable: item.name !== '未分类', allowedActions: item.allowedActions })),
  ], [categories, page.total, scope]);

  const saveCategory = async (value: CategoryEditorValue) => {
    setSaving(true);
    try {
      const description = value.description?.trim() || undefined;
      if (editor?.mode === 'NEW') await knowledgeApi.createCategory({ name: value.name.trim(), scope, description });
      else if (editor?.item) await knowledgeApi.renameCategory(editor.item.id, { revision: editor.item.revision, name: value.name.trim(), description });
      setEditor(undefined); await load(); void message.success('分类已保存');
    }
    catch (error) { void message.error(error instanceof Error ? error.message : '分类保存失败'); }
    finally { setSaving(false); }
  };

  const removeCategory = async () => {
    if (!deleteItem) return;
    setSaving(true);
    try { await knowledgeApi.deleteCategory(deleteItem.id,deleteItem.revision, replacementId); setDeleteItem(undefined); setReplacementId(undefined); setCategoryId('ALL'); await load(); void message.success('分类已删除'); }
    catch (error) { void message.error(error instanceof Error ? error.message : '分类删除失败'); }
    finally { setSaving(false); }
  };

  const moveDocument = async (target: string, document?: KnowledgeDocument) => {
    const current = document || moving;
    if (!current) return;
    setSaving(true);
    try { await knowledgeApi.assignCategory(current.id, target,current.revision); setMoving(undefined); await load(); void message.success('文档分类已更新'); }
    catch (error) { void message.error(error instanceof Error ? error.message : '文档移动失败'); }
    finally { setSaving(false); }
  };

  const exportDocuments = async () => {
    if (!selected.length || selected.length > 200) { void message.warning('请选择 1-200 个文件'); return; }
    setExporting(true);
    try {
      const { blob, fileName } = await knowledgeApi.exportDocuments(selected);
      downloadBlob(blob, fileName);
      void message.success(`已导出 ${selected.length} 个知识文件`);
    } catch (error) { void message.error(error instanceof Error ? error.message : '知识文件导出失败'); }
    finally { setExporting(false); }
  };



  const deleteSelected = () => {
    if (!selected.length) return;
    const draftIds = selectedDocuments.filter((item) => item.allowedActions?.includes('DELETE')).map((item) => item.id);
    const excludedDocuments = selectedDocuments.filter((item) => !item.allowedActions?.includes('DELETE'));
    const blockedReasons = [
      excludedDocuments.some((item) => item.currentPublicationId || item.reviewStatus === 'PUBLISHED' || item.reviewStatus === 'SUPERSEDED')
        ? '已发布文档不能物理删除，请使用停用'
        : undefined,
      excludedDocuments.some((item) => (item.relatedProjects || []).length > 0)
        ? '已关联项目的知识资料不可物理删除，请先解除项目关联'
        : undefined,
    ].filter((reason): reason is string => Boolean(reason));
    if (!draftIds.length) {
      void message.warning(blockedReasons.join('；') || '所选资料当前不可删除');
      return;
    }
    modal.confirm({
      title: `删除 ${draftIds.length} 个从未发布的草稿？`,
      content: excludedDocuments.length
        ? `已自动排除 ${excludedDocuments.length} 个不可删除的文档；${blockedReasons.join('；') || '这些文档当前不可删除。'}`
        : '删除后草稿版本和解析结果将从知识库移除。',
      okText: '确认删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          const targets = selectedDocuments.filter(item => draftIds.includes(item.id));
          if (targets.some(item => !item.revision)) throw new Error('原数据版本缺失');
          const results = await Promise.allSettled(targets.map(item => knowledgeApi.remove(item.id, item.revision)));
          await load();
          const failure = results.find(result => result.status === 'rejected');
          if (failure?.status === 'rejected') throw failure.reason;
          void message.success('已删除未发布草稿');
        }
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
    try { const results = await knowledgeApi.batchMove(selectedDocuments, target); summarizeBatch(results, '批量移动'); await load(); }
    catch (error) { void message.error(error instanceof Error ? error.message : '批量移动失败'); }
  };

  const batchAi = (action: 'APPROVE' | 'REVOKE') => {
    if (!selected.length) return;
    modal.confirm({ title: action === 'APPROVE' ? `授权 ${selected.length} 个当前发布版本用于 AI？` : `撤销 ${selected.length} 个当前发布版本的 AI 授权？`, content: '批量操作逐项提交，单项失败不会回滚其他成功项。', onOk: async () => { const results = await knowledgeApi.batchAiUsage(selectedDocuments, action); summarizeBatch(results, '批量 AI 授权'); await load(); } });
  };
  const lifecycle = (item: KnowledgeDocument) => {
    if (item.allowedActions?.includes('RESTORE')) {
      modal.confirm({ title: `恢复“${item.title}”？`, content: '恢复后将重新纳入当前发布版文件检索；AI 使用仍取决于当前发布版授权。', onOk: async () => { await knowledgeApi.restore(item.id,item.revision); await load(); } });
      return;
    }
    if (!item.allowedActions?.includes('DISABLE')) return;
    let reason = '';
    modal.confirm({ title: `停用“${item.title}”？`, content: <Space direction="vertical" style={{ width: '100%' }}><Typography.Text>停用后，该文件将不再参与文件检索和 AI 问答。确定停用吗？</Typography.Text><Input.TextArea placeholder="停用原因（必填）" onChange={(event) => { reason = event.target.value; }} /></Space>, okText: '确认停用', okButtonProps: { danger: true }, onOk: async () => { if (!reason.trim()) throw new Error('停用原因不能为空'); await knowledgeApi.disable(item.id, reason.trim(),item.revision); await load(); } });
  };
  const saveRelations = async () => {
    if (!relationEditor) return;
    setSaving(true);
    try {
      await projectResourceApi.replaceLinks('KNOWLEDGE_DOCUMENT', relationEditor.item.id,
        relationEditor.targets.filter((target) => target.projectId), relationEditor.item.relatedProjects || []);
      setRelationEditor(undefined); await load(); void message.success('关联项目已更新');
    } catch (error) { void message.error(error instanceof Error ? error.message : '关联项目更新失败'); }
    finally { setSaving(false); }
  };

  return <div className="business-page knowledge-view-page">
    <div className="page-heading"><div><Typography.Title level={2}>知识库查看</Typography.Title><Typography.Text type="secondary">管理知识文件、校对内容和发布状态。</Typography.Text></div><Can permission="knowledge.upload"><Button type="primary" icon={<UploadOutlined />} onClick={() => navigate('/knowledge/library')}>上传文件</Button></Can></div>
    {canViewCatalog ? <>
    <div className="catalog-tabs" role="tablist" aria-label="资料范围">{scopes.map((item) => <Button key={item.value} type={scope === item.value ? 'primary' : 'default'} role="tab" aria-selected={scope === item.value} onClick={() => { setScope(item.value); setCategoryId('ALL'); }}>{item.label}</Button>)}</div>
    <CategoryCardGrid categories={cards} activeId={categoryId} onSelect={(id) => { setCategoryId(id); setPage((value) => ({ ...value, current: 1 })); }} onCreate={canUpdate ? () => setEditor({ mode: 'NEW' }) : undefined} onRename={canUpdate ? (item) => setEditor({ mode: 'EDIT', item: categories.find((candidate) => candidate.id === item.id) }) : undefined} onDelete={canUpdate ? (item) => { const category = categories.find((candidate) => candidate.id === item.id); if (category?.allowedActions?.includes('DELETE')) setDeleteItem(category); } : undefined} />
    <CatalogListPanel title={cards.find((item) => item.id === categoryId)?.name || '资料文件'} count={page.total} filters={<Space wrap><Input allowClear prefix={<SearchOutlined />} placeholder="搜索文档、图片或音频" value={keyword} onChange={(event) => { setKeyword(event.target.value); setPage((value) => ({ ...value, current: 1 })); }} /><Select allowClear showSearch optionFilterProp="label" popupMatchSelectWidth={false} classNames={{ popup: { root: 'project-relation-picker-dropdown' } }} placeholder="全部项目" value={projectId} onChange={(value) => { setProjectId(value); setPage((current) => ({ ...current, current: 1 })); }} options={projects.map((project) => ({ value: project.id, label: `${project.projectCode} · ${project.name}` }))} /><Select allowClear placeholder="全部解析状态" value={status} onChange={(value) => { setStatus(value); setPage((current) => ({ ...current, current: 1 })); }} options={Object.entries(statusLabels).map(([value, item]) => ({ value, label: item[0] }))} /><Select allowClear placeholder="全部生命周期" value={lifecycleStatus} onChange={(value) => { setLifecycleStatus(value); setPage((current) => ({ ...current, current: 1 })); }} options={[{ value: 'ACTIVE', label: '有效' }, { value: 'DISABLED', label: '已停用' }]} /><Select allowClear placeholder="审核状态" value={reviewStatus} onChange={changeReviewStatus} options={[{ value: 'PENDING_REVIEW', label: '待校对' }, { value: 'REJECTED', label: '已驳回' }, { value: 'PUBLISHED', label: '已发布' }, { value: 'SUPERSEDED', label: '已替代' }]} /><Button icon={<RedoOutlined />} onClick={resetFilters}>重置</Button></Space>} actions={<><Typography.Text type="secondary">已选 {selected.length} 项</Typography.Text><Can permission="knowledge.update"><Dropdown menu={{ items: categories.map((category) => ({ key: category.id, label: category.name, onClick: () => void batchMove(category.id) })) }}><Button disabled={!selected.length}>批量移动</Button></Dropdown><Button icon={<TagsOutlined />} disabled={!selected.length} onClick={() => setBatchTagsOpen(true)}>批量标签</Button></Can><Can permission="knowledge.approve"><Dropdown menu={{ items: [{ key: 'approve', label: '授权当前发布版', onClick: () => batchAi('APPROVE') }, { key: 'revoke', label: '撤销当前发布版授权', danger: true, onClick: () => batchAi('REVOKE') }] }}><Button icon={<SafetyCertificateOutlined />} disabled={!selected.length}>批量 AI 授权</Button></Dropdown></Can><Can permission="knowledge.export"><Button loading={exporting} icon={<DownloadOutlined />} disabled={!selected.length || selected.length > 200} onClick={() => void exportDocuments()}>批量导出</Button></Can><Can permission="knowledge.delete"><Button danger icon={<DeleteOutlined />} disabled={!selected.length} onClick={deleteSelected}>删除未发布草稿</Button></Can></>} loading={loading} onRetry={() => void load()}>
      <Table className="catalog-knowledge-table" scroll={{ x: 1800 }} rowKey="id" rowSelection={{ selectedRowKeys: selected, onChange: (keys) => setSelected(keys.map(String)) }} dataSource={items} pagination={{ current: page.current, pageSize: page.pageSize, total: page.total, showSizeChanger: true, onChange: (current, pageSize) => setPage({ current, pageSize, total: page.total }) }} locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前分类暂无符合条件的文件"><Can permission="knowledge.upload"><Button type="primary" ghost icon={<UploadOutlined />} onClick={() => navigate('/knowledge/library')}>上传文件</Button></Can></Empty> }} columns={[{ title: '文件名称', dataIndex: 'title', width: 330, ellipsis: true, render: (_, item) => <Space>{fileIcon(item.contentType)}<span><Typography.Text strong ellipsis={{ tooltip: item.title }}>{item.title}</Typography.Text><span className="binding-path">{item.originalName} · {(item.size / 1024).toFixed(0)} KB · V{item.currentVersionNo}{item.currentPublicationNo ? ` · 发布#${item.currentPublicationNo}` : ''}</span></span></Space> }, { title: '标签', width: 220, render: (_: unknown, item: KnowledgeDocument) => <Space wrap>{(item.tags || []).map((tag) => <Tag key={tag}>{tag}</Tag>)}</Space> }, { title: '当前分类', dataIndex: 'categoryName', width: 150, ellipsis: true, render: (value?: string) => value || '未分类' }, { title: '关联项目', width: 240, className: 'knowledge-related-projects-column', render: (_: unknown, item: KnowledgeDocument) => <RelatedProjectsCell relations={item.relatedProjects || []} onOpen={(projectId) => navigate(`/projects/${projectId}`)} /> }, { title: '生命周期', dataIndex: 'lifecycleStatus', width: 120, render: (value: string) => <Tag color={value === 'ACTIVE' ? 'success' : 'default'}>{value === 'ACTIVE' ? '有效' : '已停用'}</Tag> }, { title: '审核', dataIndex: 'reviewStatus', width: 130, render: (value: string) => { const [label, color] = reviewStatusLabels[value] || [value, 'default']; return <Tag color={color}>{label}</Tag>; } }, { title: 'AI 使用', dataIndex: 'aiStatus', width: 120, render: (value: string) => <Tag color={value === 'APPROVED' ? 'success' : 'default'}>{value === 'APPROVED' ? '已授权' : '待授权'}</Tag> }, { title: '处理状态', dataIndex: 'status', width: 130, render: (value: string) => <Tag color={statusLabels[value]?.[1]}>{statusLabels[value]?.[0] || value}</Tag> }, { title: '操作', width: 450, render: (_, item) => <Space wrap><Button type="link" icon={<EyeOutlined />} onClick={() => navigate(`/knowledge/documents/${item.id}`)}>查看</Button><Can permission="knowledge.review">{item.reviewStatus === 'PENDING_REVIEW' || item.reviewStatus === 'REJECTED' ? <Button type="link" onClick={() => navigate(`/knowledge/review/${item.id}/${item.currentVersionId}`)}>校对</Button> : null}</Can><Can permission="knowledge.update"><Button type="link" icon={<EditOutlined />} onClick={() => setEditingDocument(item)}>编辑</Button><Dropdown trigger={['click']} menu={{ items: categories.map((category) => ({ key: category.id, label: category.name, onClick: () => void moveDocument(category.id, item) })) }}><Button type="link">移动</Button></Dropdown>{item.allowedActions?.includes('DISABLE') || item.allowedActions?.includes('RESTORE') ? <Button type="link" danger={item.allowedActions.includes('DISABLE')} icon={item.allowedActions.includes('DISABLE') ? <StopOutlined /> : <UndoOutlined />} onClick={() => lifecycle(item)}>{item.allowedActions.includes('DISABLE') ? '停用' : '恢复'}</Button> : null}</Can><Can permission="project.assign"><Button type="link" icon={<LinkOutlined />} onClick={() => setRelationEditor({ item, targets: (item.relatedProjects || []).map((relation) => ({ projectId: relation.projectId, stageId: relation.stageId, taskId: relation.taskId })) })}>关联项目</Button></Can></Space> }]} />
    </CatalogListPanel>
    <CategoryEditorModal open={Boolean(editor)} title={editor?.mode === 'NEW' ? '新增知识分类' : '编辑知识分类'} initialValue={editor?.item ? { name: editor.item.name, description: editor.item.description } : { name: '' }} confirmLoading={saving} onCancel={() => setEditor(undefined)} onSubmit={(value) => void saveCategory(value)} />
    {deleteItem && <ModalDelete title={`删除分类“${deleteItem.name}”`} open onCancel={() => setDeleteItem(undefined)} onConfirm={() => void removeCategory()} loading={saving} replacementId={replacementId} onReplacementChange={setReplacementId} options={categories.filter((item) => item.id !== deleteItem.id).map((item) => ({ value: item.id, label: item.name }))} />}
    {editingDocument && <KnowledgeDocumentEditor key={editingDocument.id} item={editingDocument} tagOptions={tagOptions} onCancel={() => setEditingDocument(undefined)} onSaved={() => { setEditingDocument(undefined); void load(); }} />}
    {batchTagsOpen && <KnowledgeBatchTagsModal documents={selectedDocuments} tagOptions={tagOptions} onCancel={() => setBatchTagsOpen(false)} onSaved={() => { setBatchTagsOpen(false); void load(); }} />}
    <Modal open={Boolean(relationEditor)} title="维护知识文档关联项目" width={760} okText="保存" confirmLoading={saving} onCancel={() => setRelationEditor(undefined)} onOk={() => void saveRelations()}>{relationEditor && <ProjectRelationPicker value={relationEditor.targets} onChange={(targets) => setRelationEditor((current) => current ? { ...current, targets } : current)} />}</Modal>
    </> : canReview ? <KnowledgeReviewQueuePage embedded initialStatus={reviewStatus} /> : <Empty description="没有查看或审核知识库的权限" />}
  </div>;
}

function ModalDelete({ title, open, loading, replacementId, options, onReplacementChange, onCancel, onConfirm }: { title: string; open: boolean; loading: boolean; replacementId?: string; options: Array<{ value: string; label: string }>; onReplacementChange: (value?: string) => void; onCancel: () => void; onConfirm: () => void }) {
  return <Modal open={open} title={title} okText="删除并迁移" okButtonProps={{ danger: true }} cancelText="取消" confirmLoading={loading} onCancel={onCancel} onOk={onConfirm}><Typography.Paragraph>文件会迁移到所选分类；未选择时自动移到“未分类”。</Typography.Paragraph><Select allowClear placeholder="未分类" value={replacementId} onChange={onReplacementChange} options={options} style={{ width: '100%' }} /></Modal>;
}
