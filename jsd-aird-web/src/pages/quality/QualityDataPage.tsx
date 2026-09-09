import {
  AppstoreOutlined,
  CopyOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  FileOutlined,
  FolderOpenOutlined,
  PlusOutlined,
  UnorderedListOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import {
  Button,
  Breadcrumb,
  Card,
  Checkbox,
  Empty,
  Form,
  Input,
  message,
  Modal,
  Pagination,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { CategoryCardGrid, type CatalogCategoryCard } from '@/components/catalog-workspace';
import { ProjectRelationPicker } from '@/components/project-relations/ProjectRelationPicker';
import type { ProjectRelationTarget } from '@/services/project/project-resource-api';
import {
  getProjects,
  getProjectStages,
  getStageTasks,
  type Project,
  type ProjectStage,
  type ProjectTask,
} from '@/services/project/project-api';
import { downloadBlob } from '@/services/files/file-api';
import {
  qualityApi,
  type QualityCategory,
  type QualityRecord,
  type QualityType,
  type QualityTypeId,
} from '@/services/quality/quality-api';
import { templateApi } from '@/services/templates/template-api';
import type { TemplateListItem } from '@/features/template-workspace/types';
import './quality-pages.css';
import '@/styles/management-list.css';

type Draft = QualityRecord & { newRow?: boolean };
type ViewMode = 'card' | 'list';
type CreateSourceType = 'BLANK' | 'TEMPLATE';
type CreateFormat = 'WORD' | 'EXCEL';
function qualityIdKey(typeId: QualityTypeId) {
  return typeId === 'standard'
    ? 'fileNo'
    : typeId === 'record'
      ? 'recordNo'
      : typeId === 'coa'
        ? 'reportNo'
        : typeId === 'defect'
          ? 'defectNo'
          : typeId === 'manual'
            ? 'manualNo'
            : typeId === 'msds'
              ? 'msdsNo'
              : 'labelNo';
}

function createQualityInitialValues(definition: QualityType): Record<string, string> {
  const values: Record<string, string> = {};
  const idKey = qualityIdKey(definition.id);
  for (const field of definition.fields) {
    if (field.key === idKey) values[field.key] = `${definition.prefix || 'QC'}-${Date.now().toString(36).toUpperCase()}`;
    else if (field.kind === 'date') values[field.key] = new Date().toISOString().slice(0, 10);
    else if (field.key === 'version') values[field.key] = 'V0.1';
  }
  if (definition.fields.some((field) => field.key === 'fileType')) values.fileType = 'Excel';
  return values;
}

function textValue(value: unknown, fallback = '') {
  if (value === null || value === undefined) return fallback;
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean')
    return String(value);
  try {
    return JSON.stringify(value);
  } catch {
    return fallback;
  }
}

export function QualityDataPage() {
  const navigate = useNavigate();
  const [types, setTypes] = useState<QualityType[]>([]),
    [typeId, setTypeId] = useState<QualityTypeId>('standard'),
    [categories, setCategories] = useState<QualityCategory[]>([]),
    [categoryId, setCategoryId] = useState<string>();
  const [rows, setRows] = useState<QualityRecord[]>([]),
    [draft, setDraft] = useState<Draft[]>([]),
    [deleted, setDeleted] = useState<string[]>([]),
    [selected, setSelected] = useState<Set<string>>(new Set()),
    [editing, setEditing] = useState(false),
    [dirty, setDirty] = useState(false);
  const [view, setView] = useState<ViewMode>('list');
  const [exporting, setExporting] = useState(false);
  const [keyword, setKeyword] = useState(''),
    [page, setPage] = useState(1),
    [size, setSize] = useState(10),
    [total, setTotal] = useState(0);
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [renameOpen, setRenameOpen] = useState(false),
    [renameSaving, setRenameSaving] = useState(false),
    [renaming, setRenaming] = useState<QualityRecord>(),
    [renameName, setRenameName] = useState(''),
    [renameRelations, setRenameRelations] = useState<ProjectRelationTarget[]>([]);
  const [createOpen, setCreateOpen] = useState(false),
    [createSaving, setCreateSaving] = useState(false),
    [createSourceType, setCreateSourceType] = useState<CreateSourceType>('BLANK'),
    [createFormat, setCreateFormat] = useState<CreateFormat>('EXCEL'),
    [templates, setTemplates] = useState<TemplateListItem[]>([]),
    [templatesLoading, setTemplatesLoading] = useState(false),
    [createTypeId, setCreateTypeId] = useState<QualityTypeId>('standard'),
    [createCategories, setCreateCategories] = useState<QualityCategory[]>([]),
    [createCategoryId, setCreateCategoryId] = useState<string>(),
    [createCategoriesLoading, setCreateCategoriesLoading] = useState(false),
    [createProjects, setCreateProjects] = useState<Project[]>([]),
    [createStages, setCreateStages] = useState<ProjectStage[]>([]),
    [createTasks, setCreateTasks] = useState<ProjectTask[]>([]),
    [createProjectsLoading, setCreateProjectsLoading] = useState(false),
    [createStagesLoading, setCreateStagesLoading] = useState(false),
    [createTasksLoading, setCreateTasksLoading] = useState(false),
    [createProjectId, setCreateProjectId] = useState<string>(),
    [createStageId, setCreateStageId] = useState<string>(),
    [createTaskId, setCreateTaskId] = useState<string>();
  const [createForm] = Form.useForm<Record<string, string>>();
  const [msg, holder] = message.useMessage();
  const type = types.find((t) => t.id === typeId);
  const activeRows = editing ? draft : rows;
  const loadCategories = useCallback(async () => {
    const cs = await qualityApi.categories(typeId);
    setCategories(cs);
    setCategoryId((v) => (cs.some((c) => c.id === v) ? v : cs[0]?.id));
  }, [typeId]);
  const loadRows = useCallback(async () => {
    if (!categoryId) return;
    try {
      const r = await qualityApi.records({
        type: typeId,
        categoryId,
        keyword: keyword || undefined,
        page,
        size,
      });
      setRows(r.items);
      setTotal(r.total);
      setSelected(new Set());
    } catch {
      msg.error('品管数据加载失败');
    }
  }, [typeId, categoryId, keyword, page, size, msg]);
  useEffect(() => {
    void qualityApi.types().then(setTypes);
  }, []);
  useEffect(() => {
    void loadCategories();
  }, [loadCategories]);
  useEffect(() => {
    void loadRows();
  }, [loadRows]);
  const openRename = (row: QualityRecord) => {
    setRenaming(row);
    setRenameName(row.displayName || row.sourceFileName || row.businessNo);
    setRenameRelations(
      row.projectId
        ? [
            {
              projectId: row.projectId,
              projectName: row.projectName,
              stageId: row.stageId,
              stageName: row.stageName,
              taskId: row.taskId,
              taskName: row.taskName,
            },
          ]
        : [],
    );
    setRenameOpen(true);
  };
  const saveRename = async () => {
    if (!renaming || !renameName.trim()) {
      msg.warning('请输入品管数据名称');
      return;
    }
    const relation = renameRelations[0];
    setRenameSaving(true);
    try {
      await qualityApi.renameRecord(renaming.id, {
        revision: renaming.lockVersion,
        name: renameName.trim(),
        projectId: relation?.projectId,
        projectName: relation?.projectName,
        stageId: relation?.stageId,
        stageName: relation?.stageName,
        taskId: relation?.taskId,
        taskName: relation?.taskName,
      });
      msg.success('品管数据名称和项目关联已更新');
      setRenameOpen(false);
      setRenaming(undefined);
      await loadRows();
    } catch (error) {
      msg.error(error instanceof Error ? error.message : '品管数据保存失败');
    } finally {
      setRenameSaving(false);
    }
  };
  const guard = () => !dirty || window.confirm('当前表格有未保存修改，确定放弃吗？');
  const switchType = (id: QualityTypeId) => {
    if (!guard()) return;
    setEditing(false);
    setDirty(false);
    setTypeId(id);
    setCategoryId(undefined);
    setFilters({});
    setPage(1);
  };
  const idKey =
    typeId === 'standard'
      ? 'fileNo'
      : typeId === 'record'
        ? 'recordNo'
        : typeId === 'coa'
          ? 'reportNo'
          : typeId === 'defect'
            ? 'defectNo'
            : typeId === 'manual'
              ? 'manualNo'
              : typeId === 'msds'
                ? 'msdsNo'
                : 'labelNo';
  const filterFields = (type?.fields || []).filter((field) => field.key !== idKey).slice(0, 2);
  const visibleRows = activeRows.filter((row) =>
    filterFields.every((field) => {
      const value = filters[field.key];
      return !value || textValue(row.data[field.key]).toLowerCase().includes(value.toLowerCase());
    }),
  );
  const newNo = () => `${type?.prefix || 'QC'}-${Date.now().toString(36).toUpperCase()}`;
  const createType = types.find((item) => item.id === createTypeId);
  const createIdKey = qualityIdKey(createTypeId);
  const loadCreateProjects = async () => {
    setCreateProjectsLoading(true);
    try {
      const result = await getProjects({ page: 1, size: 200 });
      setCreateProjects(result.items);
    } catch {
      msg.error('项目列表加载失败');
    } finally {
      setCreateProjectsLoading(false);
    }
  };
  const changeCreateProject = (nextProjectId?: string) => {
    setCreateProjectId(nextProjectId);
    setCreateStageId(undefined);
    setCreateTaskId(undefined);
    setCreateStages([]);
    setCreateTasks([]);
    if (!nextProjectId) return;
    setCreateStagesLoading(true);
    void getProjectStages(nextProjectId)
      .then(setCreateStages)
      .catch(() => msg.error('阶段列表加载失败'))
      .finally(() => setCreateStagesLoading(false));
  };
  const changeCreateStage = (nextStageId?: string) => {
    setCreateStageId(nextStageId);
    setCreateTaskId(undefined);
    setCreateTasks([]);
    if (!nextStageId) return;
    setCreateTasksLoading(true);
    void getStageTasks(nextStageId)
      .then(setCreateTasks)
      .catch(() => msg.error('任务列表加载失败'))
      .finally(() => setCreateTasksLoading(false));
  };
  const changeCreateType = (nextTypeId: QualityTypeId) => {
    const nextType = types.find((item) => item.id === nextTypeId);
    setCreateTypeId(nextTypeId);
    setCreateCategoryId(undefined);
    setCreateCategories([]);
    setTemplates([]);
    setTemplatesLoading(false);
    setCreateProjectId(undefined);
    setCreateStageId(undefined);
    setCreateTaskId(undefined);
    setCreateStages([]);
    setCreateTasks([]);
    createForm.resetFields();
    if (nextType) createForm.setFieldsValue(createQualityInitialValues(nextType));
    setCreateCategoriesLoading(true);
    void qualityApi
      .categories(nextTypeId)
      .then((nextCategories) => {
        setCreateCategories(nextCategories);
        setCreateCategoryId(nextCategories[0]?.id);
      })
      .catch(() => msg.error('品管保存位置加载失败'))
      .finally(() => setCreateCategoriesLoading(false));
  };
  const changeCreateSourceType = (sourceType: CreateSourceType) => {
    setCreateSourceType(sourceType);
    createForm.setFieldValue('templateVersionId', undefined);
    if (sourceType === 'TEMPLATE') setCreateFormat('EXCEL');
    if (sourceType === 'TEMPLATE' && !templates.length) {
      setTemplatesLoading(true);
      void templateApi
        .list({ status: 'PUBLISHED', format: 'XLSX', page: 1, size: 100 })
        .then((result) => setTemplates(result.items))
        .catch(() => msg.error('模板列表加载失败'))
        .finally(() => setTemplatesLoading(false));
    }
  };
  const openCreate = () => {
    const initialCategoryId = categoryId || categories[0]?.id;
    if (!type || !initialCategoryId) {
      msg.warning('请先选择保存位置');
      return;
    }
    setCreateTypeId(typeId);
    setCreateCategories(categories);
    setCreateCategoryId(initialCategoryId);
    createForm.resetFields();
    createForm.setFieldsValue(createQualityInitialValues(type));
    setCreateSourceType('BLANK');
    setCreateFormat('EXCEL');
    setTemplates([]);
    setTemplatesLoading(false);
    setCreateProjectId(undefined);
    setCreateStageId(undefined);
    setCreateTaskId(undefined);
    setCreateStages([]);
    setCreateTasks([]);
    if (!createProjects.length) void loadCreateProjects();
    setCreateOpen(true);
  };
  const submitCreate = async () => {
    if (!createType || !createCategoryId) {
      msg.warning('请选择数据类型和保存位置');
      return;
    }
    setCreateSaving(true);
    try {
      const values = await createForm.validateFields();
      let templateSnapshot: Record<string, unknown> | undefined;
      if (createSourceType === 'TEMPLATE') {
        const template = templates.find((item) => item.versionId === values.templateVersionId);
        if (!template) throw new Error('请选择已发布的 Excel 模板');
        const workspace = await templateApi.getEditModel(template.versionId);
        templateSnapshot = workspace.snapshotFileId && workspace.snapshotHash
          ? await templateApi.downloadSnapshot(workspace.snapshotFileId)
          : workspace.inlineSnapshot;
        if (!templateSnapshot) throw new Error('所选模板没有可用的 Excel 内容');
      }
      const data: Record<string, unknown> = {};
      for (const field of createType.fields) data[field.key] = values[field.key] ?? '';
      if (createType.fields.some((field) => field.key === 'fileType'))
        data.fileType = createFormat === 'EXCEL' ? 'Excel' : 'Word';
      if (createType.fields.some((field) => field.key === 'version') && !textValue(data.version).trim())
        data.version = 'V0.1';
      const businessNo = textValue(data[createIdKey]).trim() || `${createType.prefix || 'QC'}-${Date.now().toString(36).toUpperCase()}`;
      data[createIdKey] = businessNo;
      const categoryName = createCategories.find((category) => category.id === createCategoryId)?.name || '';
      const project = createProjects.find((item) => item.id === createProjectId);
      const stage = createStages.find((item) => item.id === createStageId);
      const task = createTasks.find((item) => item.id === createTaskId);
      const sameScope = createTypeId === typeId && createCategoryId === categoryId;
      const row: Draft = {
        id: `new-${crypto.randomUUID()}`,
        businessType: createTypeId,
        categoryId: createCategoryId,
        categoryName,
        businessNo,
        data,
        workbookSnapshot: templateSnapshot,
        projectId: project?.id,
        projectName: project?.name,
        stageId: stage?.id,
        stageName: stage?.name,
        taskId: task?.id,
        taskName: task?.name,
        lockVersion: 0,
        createdAt: '',
        updatedAt: '',
        newRow: true,
      };
      setDraft([
        ...(sameScope ? rows.map((item) => ({ ...item, data: { ...item.data } })) : []),
        row,
      ]);
      setTypeId(createTypeId);
      setCategoryId(createCategoryId);
      setCategories(createCategories);
      setSelected(new Set());
      setDeleted([]);
      setView('list');
      setEditing(true);
      setDirty(true);
      setCreateOpen(false);
      msg.success('已创建品管数据草稿，请继续编辑并保存');
    } catch (error) {
      if (error && typeof error === 'object' && 'errorFields' in error) return;
      msg.error(error instanceof Error ? error.message : '创建品管数据失败');
    } finally {
      setCreateSaving(false);
    }
  };
  const addRow = () => {
    if (!type || !categoryId) return;
    setView('list');
    const no = newNo(),
      data: Record<string, unknown> = { [idKey]: no };
    for (const f of type.fields)
      if (f.kind === 'date') data[f.key] = new Date().toISOString().slice(0, 10);
    setDraft((v) => [
      ...v,
      {
        id: `new-${crypto.randomUUID()}`,
        businessType: typeId,
        categoryId,
        categoryName: '',
        businessNo: no,
        data,
        lockVersion: 0,
        createdAt: '',
        updatedAt: '',
        newRow: true,
      },
    ]);
    setEditing(true);
    setDirty(true);
  };
  const update = (i: number, key: string, value: string) => {
    setDraft((v) =>
      v.map((r, x) =>
        x === i
          ? {
              ...r,
              data: { ...r.data, [key]: value },
              businessNo: key === idKey ? value : r.businessNo,
            }
          : r,
      ),
    );
    setDirty(true);
  };
  const save = async () => {
    if (!type || !categoryId) return;
    const activeDraft = draft.filter((r) => !deleted.includes(r.id));
    const businessNumbers = new Set<string>();
    for (const r of activeDraft) {
      const businessNo = textValue(r.businessNo || r.data[idKey]).trim();
      if (!businessNo) return msg.warning(`${type.name}编号不能为空`);
      if (businessNumbers.has(businessNo)) return msg.warning(`${type.name}编号不能重复`);
      businessNumbers.add(businessNo);
      for (const f of type.fields) {
        const value = textValue(r.data[f.key]).trim();
        if (f.required && !value) return msg.warning(`${f.label}不能为空`);
        if (f.kind === 'date' && value) {
          const date = new Date(`${value}T00:00:00`);
          if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || Number.isNaN(date.getTime()))
            return msg.warning(`${f.label}日期格式不正确`);
        }
        if (f.kind === 'number' && value) {
          const number = Number(value);
          if (!Number.isFinite(number)) return msg.warning(`${f.label}必须为数字`);
          if (number < 0) return msg.warning(`${f.label}不能为负数`);
        }
      }
      if (typeId === 'defect' && textValue(r.data.status) === '已关闭') {
        if (!textValue(r.data.cause).trim()) return msg.warning('关闭不良单前必须填写原因分析');
        if (!textValue(r.data.correctiveAction).trim())
          return msg.warning('关闭不良单前必须填写纠正措施');
      }
    }
    try {
      await qualityApi.saveBatch({
        type: typeId,
        categoryId,
        records: draft
          .filter((r) => !deleted.includes(r.id))
          .map((r) => ({
            id: r.newRow ? undefined : r.id,
            businessNo: r.businessNo,
            data: r.data,
            workbookSnapshot: r.workbookSnapshot,
            projectId: r.projectId,
            projectName: r.projectName,
            stageId: r.stageId,
            stageName: r.stageName,
            taskId: r.taskId,
            taskName: r.taskName,
            lockVersion: r.lockVersion,
          })),
        deleteIds: deleted.filter((id) => !id.startsWith('new-')),
      });
      msg.success('品管数据已保存');
      setEditing(false);
      setDirty(false);
      await Promise.all([loadRows(), loadCategories()]);
    } catch (e) {
      msg.error(e instanceof Error ? e.message : '保存失败');
    }
  };
  const changeView = (nextView: ViewMode) => {
    if (editing) return;
    if (nextView !== view) setSelected(new Set());
    setView(nextView);
  };
  const toggleSelected = (id: string) => {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };
  const openRecord = (id: string) => navigate(`/quality/records/${id}`);
  const deleteRecord = (row: QualityRecord) => {
    if (row.newRow || editing) return;
    if (!window.confirm('确定要删除这条品管数据吗？删除后数据将从列表中隐藏。')) return;
    void (async () => {
      try {
        await qualityApi.deleteRecord(row.id);
        msg.success('品管数据已删除');
        await Promise.all([loadRows(), loadCategories()]);
      } catch (error) {
        msg.error(error instanceof Error ? error.message : '删除失败');
      }
    })();
  };
  const copySelected = () => {
    const sourceRows = editing ? draft : rows;
    const chosen = sourceRows.filter((r) => selected.has(r.id));
    if (!chosen.length) {
      msg.warning('请先选择需要复制的数据');
      return;
    }
    const baseRows = editing ? draft : rows.map((r) => ({ ...r, data: { ...r.data } }));
    setDraft([
      ...baseRows,
      ...chosen.map((r) => {
        const no = newNo();
        return {
          ...r,
          id: `new-${crypto.randomUUID()}`,
          businessNo: no,
          data: { ...r.data, [idKey]: no },
          newRow: true,
        };
      }),
    ]);
    setSelected(new Set());
    setEditing(true);
    setView('list');
    setDirty(true);
  };
  const renameSelected = () => {
    const chosen = rows.filter((row) => selected.has(row.id));
    if (chosen.length !== 1) {
      msg.warning('请只选择一条品管数据后重命名');
      return;
    }
    openRename(chosen[0]!);
  };
  const exportSelected = async () => {
    const chosen = rows.filter((row) => selected.has(row.id));
    if (!chosen.length) return;
    setExporting(true);
    try {
      await Promise.all(
        chosen.map(async (row) => {
          const blob = await qualityApi.exportRecord(row.id);
          downloadBlob(
            blob,
            row.sourceFileName || `${row.displayName || row.businessNo || '品管数据'}.xlsx`,
          );
        }),
      );
      msg.success(`已导出 ${chosen.length} 条品管数据`);
    } catch (error) {
      msg.error(error instanceof Error ? error.message : '品管数据导出失败');
    } finally {
      setExporting(false);
    }
  };
  const deleteSelected = () => {
    if (!selected.size) return;
    if (editing) {
      setDeleted((v) => [...v, ...selected]);
      setDraft((v) => v.filter((r) => !selected.has(r.id)));
      setSelected(new Set());
      setDirty(true);
      return;
    }
    const ids = Array.from(selected).filter((id) => !id.startsWith('new-'));
    if (!ids.length) return;
    if (!window.confirm('确定要删除选中的品管数据吗？删除后数据将从列表中隐藏。')) return;
    void Promise.all(ids.map((id) => qualityApi.deleteRecord(id)))
      .then(async () => {
        msg.success('选中的品管数据已删除');
        setSelected(new Set());
        await Promise.all([loadRows(), loadCategories()]);
      })
      .catch((error) => msg.error(error instanceof Error ? error.message : '删除失败'));
  };
  const renderQualityCard = (row: QualityRecord) => {
    const title = row.displayName || row.sourceFileName || row.businessNo;
    const relation =
      [row.projectName, row.stageName, row.taskName].filter(Boolean).join(' · ') || '未关联项目';
    const cardFields = (type?.fields || []).filter((field) => field.key !== idKey).slice(0, 4);
    return (
      <article
        className={selected.has(row.id) ? 'quality-record-card is-selected' : 'quality-record-card'}
        key={row.id}
      >
        <div className="quality-record-card-head">
          <span className="quality-record-card-icon">
            <FileOutlined />
          </span>
          <div className="quality-record-card-title">
            <Button
              type="link"
              className="quality-record-card-name-link"
              title={title}
              onClick={() => openRecord(row.id)}
            >
              {title}
            </Button>
            <Typography.Text type="secondary" ellipsis={{ tooltip: row.businessNo }}>
              {row.businessNo}
            </Typography.Text>
          </div>
          <Tag>{type?.name || '品管数据'}</Tag>
          <Checkbox
            aria-label={`选择品管数据 ${title}`}
            checked={selected.has(row.id)}
            onChange={() => toggleSelected(row.id)}
          />
        </div>
        <div className="quality-record-card-fields">
          {cardFields.map((field) => (
            <div key={field.key}>
              <span>{field.label}</span>
              <Typography.Text ellipsis={{ tooltip: textValue(row.data[field.key]) || '—' }}>
                {textValue(row.data[field.key]) || '—'}
              </Typography.Text>
            </div>
          ))}
          <div className="quality-record-card-field-wide">
            <span>关联项目 / 阶段 / 任务</span>
            <Typography.Text ellipsis={{ tooltip: relation }}>{relation}</Typography.Text>
          </div>
          <div>
            <span>保存位置</span>
            <Typography.Text ellipsis={{ tooltip: row.categoryName || '—' }}>
              {row.categoryName || '—'}
            </Typography.Text>
          </div>
          <div>
            <span>权限可见</span>
            <Typography.Text>
              {row.visibility === 'ALL' ? '全员可见' : row.visibility || '—'}
            </Typography.Text>
          </div>
        </div>
        <div className="quality-record-card-meta">
          {row.sourceFileName || '未关联源文件'} ·{' '}
          {row.createdAt ? new Date(row.createdAt).toLocaleString('zh-CN') : '—'}
        </div>
        <div className="quality-record-card-actions">
          <Button type="link" onClick={() => openRecord(row.id)}>
            查看
          </Button>
          <Button type="link" disabled={editing} onClick={() => openRename(row)}>
            重命名
          </Button>
          {typeId === 'record' && textValue(row.data.judgement) === '不合格' && (
            <Button
              type="link"
              onClick={() =>
                void qualityApi.createDefectFromRecord(row.id).then(
                  () => msg.success('已生成不良报告待处理记录'),
                  (error) => msg.error(error instanceof Error ? error.message : '生成不良报告失败'),
                )
              }
            >
              生成不良报告
            </Button>
          )}
          <Button type="link" danger onClick={() => deleteRecord(row)}>
            删除
          </Button>
        </div>
      </article>
    );
  };
  const categoryModal = (current?: QualityCategory) => {
    let name = current?.name || '',
      description = current?.description || '';
    Modal.confirm({
      title: current ? '编辑分类' : '新增分类',
      width: 520,
      content: (
        <Space direction="vertical" style={{ width: '100%', marginTop: 16 }}>
          <Input
            defaultValue={name}
            maxLength={20}
            placeholder="分类名称"
            onChange={(e) => (name = e.target.value)}
          />
          <Input.TextArea
            defaultValue={description}
            maxLength={80}
            placeholder="分类简介"
            onChange={(e) => (description = e.target.value)}
          />
        </Space>
      ),
      onOk: async () => {
        if (!name.trim() || !description.trim()) throw new Error('请填写分类名称和简介');
        if (current)
          await qualityApi.updateCategory(current.id, {
            name: name.trim(),
            description: description.trim(),
          });
        else
          await qualityApi.createCategory({
            type: typeId,
            name: name.trim(),
            description: description.trim(),
          });
        await loadCategories();
      },
    });
  };
  const removeCategory = async (category: QualityCategory) => {
    try {
      await qualityApi.deleteCategory(category.id);
      await loadCategories();
    } catch {
      msg.warning('该分类下存在数据，请先移动或删除数据');
    }
  };
  const fields = type?.fields || [];
  const createFields = (createType?.fields || []).filter(
    (field) => field.key !== 'fileType' && field.key !== 'version',
  );
  const allSelected = visibleRows.length > 0 && visibleRows.every((r) => selected.has(r.id));
  const someSelected = selected.size > 0 && !allSelected;
  const categoryCards: CatalogCategoryCard[] = categories.map((category) => ({
    id: category.id,
    name: category.name,
    count: category.recordCount,
    description: category.description,
    // Keep the existing 品管部 folder icon while using the experiment
    // catalog card layout and interaction surface.
    icon: <FolderOpenOutlined />,
    tone: 'blue',
    editable: true,
    allowedActions: category.allowedActions ?? ['DELETE'],
  }));
  return (
    <div className="quality-page pm-unified-list-page">
      {holder}
      <div className="page-heading quality-data-page-intro">
        <div>
          <Breadcrumb items={[{ title: '品管部数据中心' }, { title: '数据查看' }]} />
          <Typography.Title level={2}>品管部数据查看</Typography.Title>
          <Typography.Text type="secondary">
            查看已上传并解析的品管部数据，可按分类筛选并进入文档查看页面。
          </Typography.Text>
        </div>
      </div>
      <div className="quality-tabs">
        {types.map((t) => (
          <button
            key={t.id}
            className={`quality-tab${t.id === typeId ? ' active' : ''}`}
            onClick={() => switchType(t.id)}
          >
            {t.name}
          </button>
        ))}
      </div>
      <CategoryCardGrid
        categories={categoryCards}
        activeId={categoryId}
        countLabel="条数据"
        onSelect={(id) => {
          if (!guard()) return;
          setCategoryId(id);
          setFilters({});
          setEditing(false);
          setDirty(false);
          setPage(1);
        }}
        onCreate={() => categoryModal()}
        onRename={(item) => {
          const category = categories.find((candidate) => candidate.id === item.id);
          if (category) categoryModal(category);
        }}
        onDelete={(item) => {
          const category = categories.find((candidate) => candidate.id === item.id);
          if (category) void removeCategory(category);
        }}
      />
      <Card className="content-card quality-filter-card">
        <Space className="quality-filter-space" wrap>
          <Input
            value={keyword}
            placeholder="搜索当前表格"
            onChange={(e) => {
              if (!guard()) return;
              setKeyword(e.target.value);
              setPage(1);
            }}
            style={{ width: 210 }}
          />
          {filterFields.map((field) => {
            const values = Array.from(
              new Set([
                ...field.options,
                ...activeRows.map((row) => textValue(row.data[field.key])).filter(Boolean),
              ]),
            );
            return (
              <Select
                key={field.key}
                allowClear
                showSearch
                placeholder={`筛选${field.label}`}
                aria-label={`筛选${field.label}`}
                value={filters[field.key] || undefined}
                options={values.map((value) => ({ value, label: value }))}
                onChange={(value) => {
                  if (!guard()) return;
                  setFilters((current) => ({ ...current, [field.key]: value || '' }));
                  setPage(1);
                }}
                style={{ width: 150 }}
              />
            );
          })}
          <Button
            onClick={() => {
              if (!guard()) return;
              setKeyword('');
              setFilters({});
              setPage(1);
            }}
          >
            重置
          </Button>
        </Space>
      </Card>
      <div className="quality-list-toolbar pm-batch-row">
        <div className="quality-list-summary">
          <span className="quality-selected">已选 {selected.size} 条</span>
          {selected.size > 0 && (
            <Button type="link" size="small" onClick={() => setSelected(new Set())}>
              清除已选
            </Button>
          )}
        </div>
        <div className="quality-list-actions">
          {editing ? (
            <>
              <Button type="primary" onClick={() => void save()}>
                保存修改
              </Button>
              <Button
                onClick={() => {
                  if (guard()) {
                    setEditing(false);
                    setDirty(false);
                  }
                }}
              >
                取消修改
              </Button>
              <Button onClick={addRow}>新增行</Button>
              <span className="quality-dirty">{dirty ? '● 有未保存修改' : '尚未修改'}</span>
            </>
          ) : (
            <>
              <Button icon={<CopyOutlined />} disabled={!selected.size} onClick={copySelected}>
                复制
              </Button>
              <Button icon={<EditOutlined />} onClick={renameSelected}>
                重命名
              </Button>
              <Button
                icon={<DownloadOutlined />}
                disabled={!selected.size}
                loading={exporting}
                onClick={() => void exportSelected()}
              >
                导出
              </Button>
              <Button
                danger
                icon={<DeleteOutlined />}
                disabled={!selected.size}
                onClick={deleteSelected}
              >
                删除
              </Button>
              <Button icon={<UploadOutlined />} onClick={() => navigate('/quality/upload')}>
                品管数据上传
              </Button>
              <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
                新增品管数据
              </Button>
            </>
          )}
          {!editing && (
            <Space.Compact className="quality-list-view-toggle">
              <Button
                aria-label="卡片视图"
                aria-pressed={view === 'card'}
                title="卡片视图"
                type={view === 'card' ? 'primary' : 'default'}
                icon={<AppstoreOutlined />}
                disabled={editing}
                onClick={() => changeView('card')}
              />
              <Button
                aria-label="列表视图"
                aria-pressed={view === 'list'}
                title="列表视图"
                type={view === 'list' ? 'primary' : 'default'}
                icon={<UnorderedListOutlined />}
                disabled={editing}
                onClick={() => changeView('list')}
              />
            </Space.Compact>
          )}
        </div>
      </div>
      <Card className="content-card quality-record-list-card" styles={{ body: { padding: 0 } }}>
        {view === 'card' ? (
          <div className="quality-record-card-view">
            {visibleRows.length ? (
              <>
                <div className="quality-record-card-grid-head">
                  <Checkbox
                    checked={allSelected}
                    indeterminate={someSelected}
                    onChange={(event) =>
                      setSelected(
                        event.target.checked ? new Set(visibleRows.map((r) => r.id)) : new Set(),
                      )
                    }
                  />
                  <span>全选当前页</span>
                </div>
                <div className="quality-record-card-grid">{visibleRows.map(renderQualityCard)}</div>
              </>
            ) : (
              <Empty description="当前分类暂无符合条件的数据" />
            )}
          </div>
        ) : (
          <div className="quality-grid-wrap">
            {visibleRows.length ? (
              <table className="quality-grid">
                <thead>
                  <tr>
                    <th>
                      <Checkbox
                        checked={allSelected}
                        onChange={(e) =>
                          setSelected(
                            e.target.checked ? new Set(visibleRows.map((r) => r.id)) : new Set(),
                          )
                        }
                      />
                    </th>
                    <th>名称</th>
                    <th>关联项目 / 阶段 / 任务</th>
                    {fields.map((f) => (
                      <th key={f.key}>
                        {f.label}
                        {f.required ? ' *' : ''}
                      </th>
                    ))}
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleRows.map((r) => (
                    <tr key={r.id}>
                      <td>
                        <Checkbox
                          checked={selected.has(r.id)}
                          onChange={() => toggleSelected(r.id)}
                        />
                      </td>
                      <td title={r.displayName || r.sourceFileName || r.businessNo}>
                        {!editing && !r.newRow ? (
                          <Button
                            type="link"
                            className="pm-name-link quality-record-name-link"
                            onClick={() => openRecord(r.id)}
                          >
                            {r.displayName || r.sourceFileName || r.businessNo}
                          </Button>
                        ) : (
                          r.displayName || r.sourceFileName || r.businessNo
                        )}
                      </td>
                      <td>
                        {[r.projectName, r.stageName, r.taskName].filter(Boolean).join(' · ') ||
                          '未关联项目'}
                      </td>
                      {fields.map((f) => (
                        <td key={f.key}>
                          {editing ? (
                            f.kind === 'select' ? (
                              <Select
                                value={textValue(r.data[f.key])}
                                onChange={(v) =>
                                  update(
                                    draft.findIndex((item) => item.id === r.id),
                                    f.key,
                                    v,
                                  )
                                }
                                options={f.options.map((x) => ({ value: x, label: x }))}
                              />
                            ) : f.kind === 'long' ? (
                              <textarea
                                value={textValue(r.data[f.key])}
                                onChange={(e) =>
                                  update(
                                    draft.findIndex((item) => item.id === r.id),
                                    f.key,
                                    e.target.value,
                                  )
                                }
                              />
                            ) : (
                              <input
                                type={
                                  f.kind === 'date'
                                    ? 'date'
                                    : f.kind === 'number'
                                      ? 'number'
                                      : 'text'
                                }
                                disabled={!r.newRow && f.key === idKey}
                                value={textValue(r.data[f.key])}
                                onChange={(e) =>
                                  update(
                                    draft.findIndex((item) => item.id === r.id),
                                    f.key,
                                    e.target.value,
                                  )
                                }
                              />
                            )
                          ) : (
                            textValue(r.data[f.key], '—')
                          )}
                        </td>
                      ))}
                      <td>
                        <Space className="management-table-actions" size={0}>
                          <Button type="link" size="small" onClick={() => openRecord(r.id)}>
                            查看
                          </Button>
                          <Button
                            type="link"
                            size="small"
                            disabled={r.newRow || editing}
                            onClick={() => openRename(r)}
                          >
                            重命名
                          </Button>
                          {typeId === 'record' &&
                            textValue(r.data.judgement) === '不合格' &&
                            !editing && (
                              <Button
                                type="link"
                                size="small"
                                onClick={() =>
                                  void qualityApi.createDefectFromRecord(r.id).then(
                                    () => msg.success('已生成不良报告待处理记录'),
                                    (e) =>
                                      msg.error(
                                        e instanceof Error ? e.message : '生成不良报告失败',
                                      ),
                                  )
                                }
                              >
                                生成不良报告
                              </Button>
                            )}
                          <Button
                            type="link"
                            size="small"
                            danger
                            icon={typeId === 'standard' ? undefined : <DeleteOutlined />}
                            disabled={r.newRow || editing}
                            onClick={() => deleteRecord(r)}
                          >
                            删除
                          </Button>
                        </Space>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <Empty style={{ marginTop: 100 }} description="当前分类暂无符合条件的数据" />
            )}
          </div>
        )}
        <div className="quality-footer">
          <Pagination
            current={page}
            pageSize={size}
            total={total}
            showSizeChanger
            showTotal={(value) => `共 ${value} 条记录`}
            onChange={(p, s) => {
              if (!guard()) return;
              setPage(p);
              setSize(s);
            }}
          />
        </div>
      </Card>
      <Modal
        rootClassName="quality-create-modal"
        open={createOpen}
        title="新增品管数据"
        width={598}
        okText="创建并进入编辑"
        cancelText="取消"
        confirmLoading={createSaving}
        okButtonProps={{ disabled: createSaving }}
        onCancel={() => setCreateOpen(false)}
        onOk={() => void submitCreate()}
      >
        <Form form={createForm} layout="vertical" className="quality-create-form">
          <div className="quality-create-context">
            <label className="quality-create-context-item">
              <span><i>*</i>数据类型</span>
              <Select
                value={createTypeId}
                options={types.map((item) => ({ value: item.id, label: item.name }))}
                onChange={changeCreateType}
              />
            </label>
            <label className="quality-create-context-item">
              <span><i>*</i>保存位置</span>
              <Select
                value={createCategoryId}
                loading={createCategoriesLoading}
                placeholder="请选择保存位置"
                options={createCategories.map((category) => ({
                  value: category.id,
                  label: category.name,
                }))}
                onChange={setCreateCategoryId}
              />
            </label>
          </div>
          <div className="quality-create-grid">
            {createFields.map((field) => (
              <Form.Item
                key={field.key}
                name={field.key}
                label={field.label}
                rules={field.required ? [{ required: true, message: `请输入${field.label}` }] : undefined}
              >
                {field.kind === 'select' ? (
                  <Select
                    options={field.options.map((option) => ({ value: option, label: option }))}
                    placeholder={`请选择${field.label}`}
                  />
                ) : field.kind === 'long' ? (
                  <Input.TextArea rows={3} placeholder={`请输入${field.label}`} />
                ) : (
                  <Input
                    type={
                      field.kind === 'date'
                        ? 'date'
                        : field.kind === 'number'
                          ? 'number'
                          : 'text'
                    }
                    placeholder={`请输入${field.label}`}
                  />
                )}
              </Form.Item>
            ))}
          </div>
          <div className="quality-create-relations">
            <label className="quality-create-relation-item">
              <span>关联项目</span>
              <Select
                allowClear
                value={createProjectId}
                loading={createProjectsLoading}
                placeholder="不关联项目"
                popupMatchSelectWidth={false}
                classNames={{ popup: { root: 'quality-project-relation-dropdown' } }}
                options={createProjects.map((project) => ({
                  value: project.id,
                  label: `${project.projectCode} · ${project.name}`,
                }))}
                onChange={changeCreateProject}
              />
            </label>
            <label className="quality-create-relation-item">
              <span>阶段</span>
              <Select
                allowClear
                value={createStageId}
                disabled={!createProjectId}
                loading={createStagesLoading}
                placeholder="不关联阶段"
                popupMatchSelectWidth={false}
                options={createStages.map((stage) => ({ value: stage.id, label: stage.name }))}
                onChange={changeCreateStage}
              />
            </label>
            <label className="quality-create-relation-item">
              <span>任务</span>
              <Select
                allowClear
                value={createTaskId}
                disabled={!createStageId}
                loading={createTasksLoading}
                placeholder="不关联任务"
                popupMatchSelectWidth={false}
                options={createTasks.map((task) => ({ value: task.id, label: task.name }))}
                onChange={setCreateTaskId}
              />
            </label>
          </div>
          <QualityCreateChoice
            label="选择新建"
            value={createSourceType}
            options={[
              {
                key: 'BLANK',
                title: '空白新建',
                desc: '选择 Word 或 Excel 创建空白品管数据',
              },
              {
                key: 'TEMPLATE',
                title: '选择模板新建',
                desc: '复制已发布模板形成独立品管数据副本',
              },
            ]}
            onChange={(value) => changeCreateSourceType(value as CreateSourceType)}
          />
          {createSourceType === 'TEMPLATE' ? (
            <Form.Item
              name="templateVersionId"
              label="选择模板"
              rules={[{ required: true, message: '请选择已发布的 Excel 模板' }]}
            >
              <Select
                showSearch
                loading={templatesLoading}
                optionFilterProp="label"
                placeholder="请选择已发布的 Excel 模板"
                options={templates.map((template) => ({
                  value: template.versionId,
                  label: `${template.name}（Excel · V${template.versionNo}）`,
                }))}
                notFoundContent={templatesLoading ? '加载中' : '暂无已发布的 Excel 模板'}
              />
            </Form.Item>
          ) : (
            <QualityCreateChoice
              label="文档格式"
              value={createFormat}
              options={[
                {
                key: 'WORD',
                  title: 'Word 品管数据',
                  desc: '正文、章节与文档结构编辑',
                },
                {
                  key: 'EXCEL',
                  title: 'Excel 品管数据',
                  desc: '表格、单元格与字段结构编辑',
                },
              ]}
              onChange={(value) => {
                const format = value as CreateFormat;
                setCreateFormat(format);
                if (type?.fields.some((field) => field.key === 'fileType'))
                  createForm.setFieldValue('fileType', format === 'EXCEL' ? 'Excel' : 'Word');
              }}
            />
          )}
        </Form>
      </Modal>
      <Modal
        open={renameOpen}
        title="重命名并关联项目"
        width={720}
        confirmLoading={renameSaving}
        okText="保存"
        cancelText="取消"
        onCancel={() => setRenameOpen(false)}
        onOk={() => void saveRename()}
      >
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <div>
            <span className="ant-form-item-label">
              <label>品管数据名称</label>
            </span>
            <Input
              value={renameName}
              maxLength={300}
              placeholder="请输入品管数据名称"
              onChange={(event) => setRenameName(event.target.value)}
            />
          </div>
          <div>
            <span className="ant-form-item-label">
              <label>关联项目 / 阶段 / 任务</label>
            </span>
            <div style={{ marginTop: 8 }}>
              <ProjectRelationPicker
                value={renameRelations}
                onChange={setRenameRelations}
                multiple={false}
              />
            </div>
          </div>
        </Space>
      </Modal>
    </div>
  );
}

function QualityCreateChoice({
  label,
  options,
  value,
  onChange,
}: {
  label: string;
  options: Array<{ key: string; title: string; desc: string }>;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <div className="quality-create-choice-section">
      <div className="quality-create-choice-label">
        <i>*</i>
        {label}
      </div>
      <div className="quality-create-choice-grid">
        {options.map((option, index) => (
          <button
            type="button"
            key={option.key}
            className={`quality-create-choice${value === option.key ? ' is-active' : ''}`}
            onClick={() => onChange(option.key)}
          >
            <span className="quality-create-choice-icon">{index === 0 ? '▣' : '↪'}</span>
            <span className="quality-create-choice-copy">
              <b>{option.title}</b>
              <small>{option.desc}</small>
            </span>
          </button>
        ))}
      </div>
    </div>
  );
}
