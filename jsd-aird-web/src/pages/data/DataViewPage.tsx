import { useListPageBounds } from '@/services/query/use-list-page-bounds';
import { useBusinessQuery } from '@/services/query/use-business-query';
import { useListKeyword, useListUrl } from '@/services/query/use-list-url';
import { DatabaseOutlined, DownloadOutlined, EyeOutlined, LinkOutlined, RedoOutlined, ReloadOutlined, SearchOutlined, UploadOutlined } from '@ant-design/icons';
import { App, Button, Dropdown, Empty, Input, Modal, Progress, Select, Space, Table, Tag, Typography } from 'antd';
import { DataPublicationPanel } from '@/features/data-workbench/DataPublicationPanel';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { CategoryCardGrid, type CatalogCategoryCard } from '@/components/catalog-workspace/CategoryCardGrid';
import { CatalogListPanel } from '@/components/catalog-workspace/CatalogListPanel';
import { CategoryEditorModal, type CategoryEditorValue } from '@/components/catalog-workspace/CategoryEditorModal';
import { FilePreviewModal, downloadPreviewFile, type FilePreviewDescriptor } from '@/components/file-preview';
import { Can } from '@/components/auth/Can';
import { usePermission } from '@/components/auth/usePermission';
import { ProjectRelationPicker } from '@/components/project-relations/ProjectRelationPicker';
import '@/components/project-relations/project-relation-picker.css';
import { dataApi, type DataCategory, type DataSourceFile } from '@/services/data/data-api';
import { dataParseProgress } from '@/services/data/data-progress';
import { getProjects } from '@/services/project/project-api';
import { projectResourceApi, type ProjectRelationTarget } from '@/services/project/project-resource-api';

const statusLabels: Record<string, [string, string]> = {
  CREATED: ['待处理', 'default'], QUEUED: ['上传中', 'processing'], PARSING: ['解析中', 'blue'],
  WAITING_SHEET: ['待确认工作表', 'gold'], WAITING_MAPPING: ['待确认字段', 'gold'],
  VALIDATING: ['校验中', 'processing'], WAITING_CONFIRM: ['待核对数据', 'orange'],
  COMMITTING: ['处理中', 'processing'], COMPLETED: ['处理完成', 'success'],
  FAILED: ['处理失败', 'error'], CANCELLED: ['已取消', 'default'],
};
const formalStatusLabels: Record<string, [string, string]> = {
  DRAFT: ['导入草稿', 'default'], PENDING_REVIEW: ['待审核', 'processing'], RETURNED: ['已驳回', 'error'], APPROVED: ['审核通过，待发布', 'processing'], PUBLISHED: ['已发布', 'success'], DISABLED: ['已停用', 'default'],
  CONFIRMED: ['已核对', 'success'], SUPERSEDED: ['已被新版本替代', 'gold'],
  INVALIDATED: ['已失效', 'error'], NOT_CONFIRMED: ['未确认', 'default'],
};
const UNCATEGORIZED_CATEGORY_ID = 'UNCATEGORIZED';

export function DataViewPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const url = useListUrl();
  const [categoryId, setCategoryId] = url.field<string>('categoryId', 'ALL');
  const [keyword, setKeyword] = useListKeyword(url);
  const [status, setStatus] = url.field<string | undefined>('status', undefined);
  const [previewFile, setPreviewFile] = useState<FilePreviewDescriptor>();
  const [editor, setEditor] = useState<{ mode: 'NEW' | 'EDIT'; item?: DataCategory }>();
  const [deleteItem, setDeleteItem] = useState<DataCategory>();
  const [replacementId, setReplacementId] = useState<string>();
  const [saving, setSaving] = useState(false);
  const [projectId, setProjectId] = url.field<string | undefined>('projectId', undefined);
  const [relationEditor, setRelationEditor] = useState<{ item: DataSourceFile; targets: ProjectRelationTarget[] }>();
  const canUpdate = usePermission('data.update');

  const filters = { categoryId: categoryId !== 'ALL' && categoryId !== UNCATEGORIZED_CATEGORY_ID ? categoryId : undefined, uncategorized: categoryId === UNCATEGORIZED_CATEGORY_ID, keyword: keyword || undefined, status, projectId, page: url.number('page',1), size: url.number('size',20) };
  const listQuery = useBusinessQuery('data', filters, signal => dataApi.listSourceFiles(filters, signal), { keepPage:true });
  useListPageBounds(url, listQuery, 20);
  const categoryQuery = useBusinessQuery('data', {kind:'categories'}, signal => dataApi.listCategories(signal), {dictionary:true,live:true});
  const facetsQuery = useBusinessQuery('data', {kind:'facets'}, signal => dataApi.sourceFacets(signal));
  const templateQuery = useBusinessQuery('data', {kind:'templates'}, signal => dataApi.listTemplates(signal), {dictionary:true});
  const projectQuery = useBusinessQuery('projects', {kind:'options',page:1,size:100}, signal => getProjects({page:1,size:100},signal), {dictionary:true});
  const items = listQuery.data?.items ?? [];
  const categories = (categoryQuery.data ?? []).map(item => ({...item, sourceCount: facetsQuery.data?.categories.find(facet => facet.id === item.id)?.sourceCount ?? item.sourceCount}));
  const templates = templateQuery.data ?? []; const projects = projectQuery.data?.items ?? [];
  const allSourceCount = facetsQuery.data?.totalSourceCount ?? 0; const uncategorizedSourceCount = facetsQuery.data?.uncategorizedSourceCount ?? 0;
  const [page,setPage] = url.pagination(listQuery.data?.total ?? 0);
  const loading = listQuery.isPending;
  const load = async () => { await Promise.all([listQuery.refetch(),categoryQuery.refetch(),facetsQuery.refetch()]); };

  const cards = useMemo<CatalogCategoryCard[]>(() => [
    { id: 'ALL', name: '全部来源文件', count: allSourceCount, description: '按文件和导入批次查看', icon: <DatabaseOutlined />, tone: 'blue' },
    { id: UNCATEGORIZED_CATEGORY_ID, name: '未分类', count: uncategorizedSourceCount, description: '尚未归入业务分类的来源文件', icon: <DatabaseOutlined />, tone: 'blue' },
    ...categories.map((item) => ({ id: item.id, name: item.name, count: item.sourceCount, description: item.description, icon: <DatabaseOutlined />, tone: 'blue' as const, editable: true, allowedActions: item.allowedActions })),
  ], [allSourceCount, categories, uncategorizedSourceCount]);

  const resolveFile = (item: DataSourceFile): FilePreviewDescriptor => {
    if (!item.fileObjectId) throw new Error('该导入记录没有关联来源文件')
    return {
      fileName: item.originalName,
      load: () => dataApi.sourcePreviewBlob(item.fileObjectId!),
      downloadUrl: `/api/v1/files/${encodeURIComponent(item.fileObjectId)}/content`,
    }
  };
  const sourceRoute = (item: DataSourceFile) => item.recognitionMode === 'FREEFORM'
    ? `/data/recognition-jobs/${item.importJobId}`
    : `/data/import-jobs/${item.importJobId}`;
  const moveSource = async (item: DataSourceFile, nextCategoryId?: string) => {
    try { await dataApi.assignSourceCategory(item.importJobId, item.revision, nextCategoryId); await load(); void message.success('归档分类已更新'); }
    catch (error) { void message.error(error instanceof Error ? error.message : '归档分类更新失败'); }
  };
  const resetFilters = () => {
    setCategoryId('ALL');
    setKeyword('');
    setStatus(undefined);
    setProjectId(undefined);
    setPage((current) => ({ ...current, current: 1 }));
  };
  const saveCategory = async (value: CategoryEditorValue) => {
    setSaving(true);
    try {
      const description = value.description?.trim() || undefined;
      if (editor?.mode === 'NEW') await dataApi.createCategory({ name: value.name.trim(), description });
      else if (editor?.item) await dataApi.renameCategory(editor.item.id, { revision: editor.item.revision, name: value.name.trim(), description });
      setEditor(undefined); await load(); void message.success('分类已保存');
    } catch (error) { void message.error(error instanceof Error ? error.message : '分类保存失败'); }
    finally { setSaving(false); }
  };
  const removeCategory = async () => {
    if (!deleteItem) return;
    setSaving(true);
    try {
      await dataApi.deleteCategory(deleteItem.id,deleteItem.revision, replacementId);
      setDeleteItem(undefined); setReplacementId(undefined); setCategoryId('ALL'); await load(); void message.success('分类已删除');
    } catch (error) { void message.error(error instanceof Error ? error.message : '分类删除失败'); }
    finally { setSaving(false); }
  };
  const saveRelations = async () => {
    if (!relationEditor) return;
    setSaving(true);
    try {
      await projectResourceApi.replaceLinks('DATA_IMPORT_JOB', relationEditor.item.importJobId,
        relationEditor.targets.filter((target) => target.projectId), relationEditor.item.relatedProjects || []);
      setRelationEditor(undefined); await load(); void message.success('关联项目已更新');
    } catch (error) { void message.error(error instanceof Error ? error.message : '关联项目更新失败'); }
    finally { setSaving(false); }
  };

  return <div className="business-page">
    <div className="page-heading">
      <div><Typography.Title level={2}>数据中心</Typography.Title><Typography.Text type="secondary">查看数据中心导入文件及确认状态。</Typography.Text></div>
      <Can permission="data.create"><Button type="primary" icon={<UploadOutlined />} onClick={() => navigate('/data/upload')}>数据上传</Button></Can>
    </div>
    <CategoryCardGrid categories={cards} activeId={categoryId}
      onSelect={(id) => { setCategoryId(id); setPage((value) => ({ ...value, current: 1 })); }}
      onCreate={canUpdate ? () => setEditor({ mode: 'NEW' }) : undefined}
      onRename={canUpdate ? (item) => setEditor({ mode: 'EDIT', item: categories.find((candidate) => candidate.id === item.id) }) : undefined}
      onDelete={canUpdate ? (item) => { const category = categories.find((candidate) => candidate.id === item.id); if (category?.allowedActions?.includes('DELETE')) setDeleteItem(category); } : undefined} />
    <div className="catalog-sync-note"><ReloadOutlined /> 处理完成后需核对数据、提交审核，通过后发布。可在“审核与版本记录”查看版本和审核意见。</div>
    <CatalogListPanel title={cards.find((item) => item.id === categoryId)?.name || '来源文件'} count={page.total}
      filters={<Space wrap><Input allowClear prefix={<SearchOutlined />} placeholder="搜索来源文件名" value={keyword} onChange={(event) => { setKeyword(event.target.value); setPage((value) => ({ ...value, current: 1 })); }} /><Select allowClear showSearch optionFilterProp="label" popupMatchSelectWidth={false} classNames={{ popup: { root: 'project-relation-picker-dropdown' } }} placeholder="全部项目" value={projectId} onChange={(value) => { setProjectId(value); setPage((current) => ({ ...current, current: 1 })); }} options={projects.map((project) => ({ value: project.id, label: `${project.projectCode} · ${project.name}` }))} /><Select allowClear placeholder="全部状态" value={status} onChange={(value) => { setStatus(value); setPage((value) => ({ ...value, current: 1 })); }} options={[{ label: '审核发布状态', options: ['DRAFT', 'CONFIRMED', 'PENDING_REVIEW', 'RETURNED', 'APPROVED', 'PUBLISHED', 'DISABLED'].map((value) => ({ value: `PUB_${value}`, label: formalStatusLabels[value]?.[0] || value })) }, { label: '文件处理状态', options: Object.entries(statusLabels).map(([value, item]) => ({ value, label: item[0] })) }]} /><Button icon={<RedoOutlined />} onClick={resetFilters}>重置</Button></Space>}
      loading={loading}>
      <Table className="catalog-data-table" scroll={{ x: 1660 }} rowKey="importJobId" dataSource={items} pagination={{ current: page.current, pageSize: page.pageSize, total: page.total, showSizeChanger: true, onChange: (current, pageSize) => setPage({ current, pageSize, total: page.total }) }} locale={{ emptyText: <Empty description="暂无来源文件" /> }} onRow={(record) => ({ onDoubleClick: () => navigate(sourceRoute(record)) })} columns={[
        { title: '来源文件', dataIndex: 'originalName', width: 280, ellipsis: true, render: (value: string, record: DataSourceFile) => <Space><DatabaseOutlined /><Typography.Text strong ellipsis={{ tooltip: value }}>{value}</Typography.Text><Typography.Text type="secondary">{record.sourceFormat}</Typography.Text></Space> },
        { title: '数据核对', dataIndex: 'formalStatus', width: 150, render: (value: string) => <Tag color={formalStatusLabels[value]?.[1]}>{formalStatusLabels[value]?.[0] || value}</Tag> },
        { title: '归档分类', dataIndex: 'categoryName', width: 150, ellipsis: true, render: (value?: string) => value || '未分类' },
        { title: '关联项目', width: 260, render: (_: unknown, record: DataSourceFile) => <Space size={[0, 4]} wrap>{record.relatedProjects?.length ? record.relatedProjects.map((relation) => <Tag color="blue" key={`${relation.projectId}-${relation.stageId || ''}-${relation.taskId || ''}`} onClick={() => navigate(`/projects/${relation.projectId}`)} style={{ cursor: 'pointer' }}>{relation.projectName}{relation.stageName ? ` / ${relation.stageName}` : ''}{relation.taskName ? ` / ${relation.taskName}` : ''}</Tag>) : '—'}</Space> },
        { title: '识别方式', dataIndex: 'templateVersionId', width: 320, ellipsis: true, render: (value: string | undefined, record: DataSourceFile) => { if (record.recognitionMode === 'FREEFORM') return '历史自由识别（只读）'; const item = templates.find((candidate) => candidate.versionId === value); return item ? `${item.name} · ${item.templateCode} · V${item.versionNo}` : value ? `版本 ${value.slice(0, 8)}` : '—'; } },
        { title: '导入任务', dataIndex: 'status', width: 130, render: (value: string) => <Tag color={statusLabels[value]?.[1]}>{statusLabels[value]?.[0] || value}</Tag> },
        { title: '解析进度', dataIndex: 'progress', width: 170, render: (_: number, record: DataSourceFile) => { const percent = dataParseProgress(record); return <Progress percent={percent} size="small" status={record.status === 'FAILED' ? 'exception' : percent === 100 ? 'success' : 'active'} format={(value) => `${value ?? 0}%`} />; } },
        { title: '最近更新', dataIndex: 'updatedAt', width: 180, render: (value: string) => new Date(value).toLocaleString('zh-CN') },
        { title: '操作', width: 500, render: (_: unknown, record: DataSourceFile) => {
          const historicalFreeform = record.recognitionMode === 'FREEFORM';
          return <Space wrap>
            <DataPublicationPanel lazy readOnly={historicalFreeform} jobId={record.importJobId}
              onChanged={() => void load()} onEdit={() => navigate(sourceRoute(record))} />
            {record.fileObjectId && <>
              <Button type="link" icon={<EyeOutlined />} onClick={() => setPreviewFile(resolveFile(record))}>预览</Button>
              <Button type="link" icon={<DownloadOutlined />} onClick={() => void downloadPreviewFile(resolveFile(record))}>下载</Button>
            </>}
            <Button type="link" onClick={() => navigate(sourceRoute(record))}>
              {historicalFreeform ? '查看历史结果' : '查看导入'}
            </Button>
            {record.experimentId && <Button type="link" onClick={() => navigate(`/experiments/${record.experimentId}`)}>查看实验</Button>}
            {!historicalFreeform && <Can permission="data.update"><Dropdown trigger={['click']} menu={{ items: [
              { key: UNCATEGORIZED_CATEGORY_ID, label: '移到未分类', onClick: () => void moveSource(record) },
              ...categories.map((category) => ({ key: category.id, label: category.name, onClick: () => void moveSource(record, category.id) })),
            ] }}><Button type="link">移动分类</Button></Dropdown></Can>}
            {!historicalFreeform && <Can permission="project.assign"><Button type="link" icon={<LinkOutlined />} onClick={() => setRelationEditor({ item: record, targets: (record.relatedProjects || []).map((relation) => ({ projectId: relation.projectId, stageId: relation.stageId, taskId: relation.taskId })) })}>关联项目</Button></Can>}
          </Space>;
        } },
      ]} />
    </CatalogListPanel>
    <CategoryEditorModal open={Boolean(editor)} title={editor?.mode === 'NEW' ? '新增归档分类' : '编辑归档分类'} initialValue={editor?.item ? { name: editor.item.name, description: editor.item.description } : { name: '' }} confirmLoading={saving} onCancel={() => setEditor(undefined)} onSubmit={(value) => void saveCategory(value)} />
    {deleteItem && <Modal open title={`删除分类“${deleteItem.name}”`} okText="删除并迁移" okButtonProps={{ danger: true }} cancelText="取消" confirmLoading={saving} onCancel={() => setDeleteItem(undefined)} onOk={() => void removeCategory()}><Typography.Paragraph>来源文件将迁移到所选分类；未选择时移到“未分类”。</Typography.Paragraph><Select allowClear placeholder="未分类" value={replacementId} onChange={setReplacementId} options={categories.filter((item) => item.id !== deleteItem.id).map((item) => ({ value: item.id, label: item.name }))} style={{ width: '100%' }} /></Modal>}
    <FilePreviewModal open={Boolean(previewFile)} file={previewFile} onClose={() => setPreviewFile(undefined)} showSpreadsheetMerges={false} />
    <Modal open={Boolean(relationEditor)} title="维护数据任务关联项目" width={760} okText="保存" confirmLoading={saving} onCancel={() => setRelationEditor(undefined)} onOk={() => void saveRelations()}>{relationEditor && <ProjectRelationPicker value={relationEditor.targets} onChange={(targets) => setRelationEditor((current) => current ? { ...current, targets } : current)} />}</Modal>
  </div>;
}
