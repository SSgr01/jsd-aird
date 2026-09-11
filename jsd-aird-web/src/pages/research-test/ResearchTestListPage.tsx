import {
  App,
  Checkbox,
  Button,
  Breadcrumb,
  Card,
  DatePicker,
  Form,
  Input,
  List,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  Tooltip,
} from 'antd';
import {
  AppstoreOutlined,
  CopyOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  UploadOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { templateApi } from '@/services/templates/template-api';
import type { TemplateListItem } from '@/features/template-workspace/types';
import {
  copyResearchTest,
  createResearchTest,
  deleteResearchTest,
  downloadResearchTest,
  getResearchTest,
  listResearchTests,
  renameResearchTest,
  type ResearchTestSummary,
  type ResearchTestType,
} from '@/services/research-test/research-test-api';
import {
  getProjectStages,
  getProjects,
  getStageTasks,
  type Project,
  type ProjectStage,
  type ProjectTask,
} from '@/services/project/project-api';
import './research-test.css';
import '@/styles/management-list.css';

interface CreateFormValues {
  businessNo?: string;
  name: string;
  category?: string;
  scope?: string;
  ownerName?: string;
  date: dayjs.Dayjs;
  projectId?: string;
  stageId?: string;
  taskId?: string;
  format: 'WORD' | 'EXCEL';
  sourceType: 'BLANK' | 'TEMPLATE';
  visibility: string;
  templateVersionId?: string;
  effectiveFrom?: dayjs.Dayjs;
  effectiveTo?: dayjs.Dayjs;
}

const statusText: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_REVIEW: '待审核',
  RETURNED: '已退回',
  PUBLISHED: '已发布',
  ARCHIVED: '已归档',
};
const standardCategories = ['国家标准', '行业标准', '企业标准', '客户标准', '内部测试方法'];
export function ResearchTestListPage({ type }: { type: ResearchTestType }) {
  const report = type === 'REPORT',
    nav = useNavigate(),
    { message } = App.useApp();
  const [rows, setRows] = useState<ResearchTestSummary[]>([]),
    [loading, setLoading] = useState(false),
    [total, setTotal] = useState(0),
    [page, setPage] = useState(1),
    [size, setSize] = useState(10);
  const [keyword, setKeyword] = useState(''),
    [projectId, setProjectId] = useState<string>(),
    [status, setStatus] = useState<string>(),
    [category, setCategory] = useState<string>(),
    [date, setDate] = useState<dayjs.Dayjs>(),
    [mode, setMode] = useState<'list' | 'card'>('list'),
    [open, setOpen] = useState(false),
    [submitting, setSubmitting] = useState(false),
    [templates, setTemplates] = useState<TemplateListItem[]>([]),
    [form] = Form.useForm<CreateFormValues>();
  const [renameOpen, setRenameOpen] = useState(false),
    [renameSaving, setRenameSaving] = useState(false),
    [renaming, setRenaming] = useState<ResearchTestSummary>(),
    [renameName, setRenameName] = useState(''),
    [renameBusinessNo, setRenameBusinessNo] = useState(''),
    [renameOwnerName, setRenameOwnerName] = useState(''),
    [renameDate, setRenameDate] = useState<dayjs.Dayjs>(),
    [renameCategory, setRenameCategory] = useState(''),
    [renameScope, setRenameScope] = useState(''),
    [renameEffectiveFrom, setRenameEffectiveFrom] = useState<dayjs.Dayjs>(),
    [renameEffectiveTo, setRenameEffectiveTo] = useState<dayjs.Dayjs>(),
    [renameProjectId, setRenameProjectId] = useState<string>(),
    [renameStageId, setRenameStageId] = useState<string>(),
    [renameTaskId, setRenameTaskId] = useState<string>();
  const [reportProjects, setReportProjects] = useState<Project[]>([]),
    [reportProjectsLoading, setReportProjectsLoading] = useState(false),
    [createStages, setCreateStages] = useState<ProjectStage[]>([]),
    [createTasks, setCreateTasks] = useState<ProjectTask[]>([]),
    [createStagesLoading, setCreateStagesLoading] = useState(false),
    [createTasksLoading, setCreateTasksLoading] = useState(false),
    [renameStages, setRenameStages] = useState<ProjectStage[]>([]),
    [renameTasks, setRenameTasks] = useState<ProjectTask[]>([]),
    [renameStagesLoading, setRenameStagesLoading] = useState(false),
    [renameTasksLoading, setRenameTasksLoading] = useState(false);
  const [selected, setSelected] = useState<string[]>([]),
    [copying, setCopying] = useState(false),
    [exporting, setExporting] = useState(false),
    [deleting, setDeleting] = useState(false);
  const sourceType = Form.useWatch('sourceType', form);
  const documentFormat = Form.useWatch('format', form);
  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await listResearchTests(type, {
        keyword,
        projectId,
        status,
        category,
        dateFrom: date?.format('YYYY-MM-DD'),
        dateTo: date?.format('YYYY-MM-DD'),
        page,
        size,
      });
      setRows(result.items);
      setTotal(result.total);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '列表加载失败');
    } finally {
      setLoading(false);
    }
  }, [category, date, keyword, message, page, projectId, size, status, type]);
  useEffect(() => {
    void load();
  }, [load]);
  useEffect(() => {
    setSelected([]);
  }, [type]);
  useEffect(() => {
    setSelected((current) => current.filter((id) => rows.some((row) => row.id === id)));
  }, [rows]);
  const loadReportProjects = async () => {
    if (!report || reportProjects.length || reportProjectsLoading) return;
    setReportProjectsLoading(true);
    try {
      const result = await getProjects({ page: 1, size: 200 });
      setReportProjects(result.items);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '项目列表加载失败');
    } finally {
      setReportProjectsLoading(false);
    }
  };
  const changeCreateProject = (projectId?: string) => {
    form.setFieldsValue({ projectId, stageId: undefined, taskId: undefined });
    setCreateStages([]);
    setCreateTasks([]);
    if (!projectId) return;
    setCreateStagesLoading(true);
    void getProjectStages(projectId)
      .then(setCreateStages)
      .catch((error) => message.error(error instanceof Error ? error.message : '阶段列表加载失败'))
      .finally(() => setCreateStagesLoading(false));
  };
  const changeCreateStage = (stageId?: string) => {
    form.setFieldsValue({ stageId, taskId: undefined });
    setCreateTasks([]);
    if (!stageId) return;
    setCreateTasksLoading(true);
    void getStageTasks(stageId)
      .then(setCreateTasks)
      .catch((error) => message.error(error instanceof Error ? error.message : '任务列表加载失败'))
      .finally(() => setCreateTasksLoading(false));
  };
  const loadRenameRelations = async (projectId?: string, stageId?: string) => {
    setRenameStages([]);
    setRenameTasks([]);
    if (projectId) {
      setRenameStagesLoading(true);
      try {
        setRenameStages(await getProjectStages(projectId));
      } catch (error) {
        message.error(error instanceof Error ? error.message : '阶段列表加载失败');
      } finally {
        setRenameStagesLoading(false);
      }
    }
    if (stageId) {
      setRenameTasksLoading(true);
      try {
        setRenameTasks(await getStageTasks(stageId));
      } catch (error) {
        message.error(error instanceof Error ? error.message : '任务列表加载失败');
      } finally {
        setRenameTasksLoading(false);
      }
    }
  };
  const changeRenameProject = (projectId?: string) => {
    setRenameProjectId(projectId);
    setRenameStageId(undefined);
    setRenameTaskId(undefined);
    void loadRenameRelations(projectId);
  };
  const changeRenameStage = (stageId?: string) => {
    setRenameStageId(stageId);
    setRenameTaskId(undefined);
    setRenameTasks([]);
    if (!stageId) return;
    setRenameTasksLoading(true);
    void getStageTasks(stageId)
      .then(setRenameTasks)
      .catch((error) => message.error(error instanceof Error ? error.message : '任务列表加载失败'))
      .finally(() => setRenameTasksLoading(false));
  };
  const showCreate = () => {
    form.resetFields();
    setCreateStages([]);
    setCreateTasks([]);
    setOpen(true);
    form.setFieldsValue({
      date: dayjs(),
      format: 'WORD',
      sourceType: 'BLANK',
      visibility: 'ALL',
      ownerName: '张三',
      businessNo: report ? undefined : 'STD-' + dayjs().format('YYYYMMDD') + '-001',
      effectiveFrom: report ? undefined : dayjs(),
    });
    void loadReportProjects();
    void templateApi
      .list({ status: 'PUBLISHED', page: 1, size: 100 })
      .then((x) => setTemplates(x.items.filter((item) => matchesResearchTestTemplate(item, type))));
  };
  const submit = async () => {
    setSubmitting(true);
    try {
      const v = await form.validateFields();
      let templateSnapshot: Record<string, unknown> | undefined, templateHash: string | undefined;
      const selected = templates.find((x) => x.versionId === v.templateVersionId);
      if (v.sourceType === 'TEMPLATE') {
        if (!selected) throw new Error('请选择一个已发布模板');
        const w = await templateApi.getEditModel(selected.versionId);
        templateSnapshot =
          w.snapshotFileId && w.snapshotHash
            ? await templateApi.downloadSnapshot(w.snapshotFileId)
            : w.inlineSnapshot;
        templateHash = w.snapshotHash ?? w.workspaceHash;
        if (!templateSnapshot) throw new Error('所选模板没有可用的文档内容');
      }
      const format: 'WORD' | 'EXCEL' = selected?.format === 'XLSX'
        ? 'EXCEL'
        : selected?.format === 'DOCX'
          ? 'WORD'
          : v.format;
      const result = await createResearchTest(type, {
        ...v,
        ...(report
          ? {
              projectId: v.projectId || undefined,
              stageId: v.stageId || undefined,
              taskId: v.taskId || undefined,
            }
          : {}),
        date: report ? v.date?.format('YYYY-MM-DD') : dayjs().format('YYYY-MM-DD'),
        effectiveFrom: v.effectiveFrom?.format('YYYY-MM-DD'),
        effectiveTo: v.effectiveTo?.format('YYYY-MM-DD'),
        format,
        sourceFileId: undefined,
        templateSnapshot,
        templateHash,
        editModel: {
          title: v.name,
          documentFormat: format.toLowerCase(),
          documentSnapshot: templateSnapshot,
        },
      });
      message.success(report ? '报告已创建' : '测试标准已创建');
      setOpen(false);
      nav(`/research-test/${report ? 'reports' : 'standards'}/${result.summary.id}`);
    } catch (error) {
      const validation = error as { errorFields?: Array<{ errors?: string[] }> };
      if (validation.errorFields?.length) {
        const firstError = validation.errorFields.flatMap((field) => field.errors || [])[0];
        message.warning(firstError || '请完善必填信息');
      } else {
        message.error(error instanceof Error ? error.message : '创建失败，请稍后重试');
      }
    } finally {
      setSubmitting(false);
    }
  };
  const remove = async (r: ResearchTestSummary) => {
    await deleteResearchTest(type, r.id, r.lockVersion);
    message.success('已删除');
    void load();
  };
  const copy = async (r: ResearchTestSummary) => {
    await copyResearchTest(type, r.id);
    message.success('已复制为草稿');
    void load();
  };
  const openRename = async (r: ResearchTestSummary) => {
    setRenaming(r);
    setRenameName(r.name);
    setRenameBusinessNo(r.businessNo || '');
    setRenameOwnerName(report ? r.ownerName || '' : '');
    setRenameDate(report && r.businessDate ? dayjs(r.businessDate) : undefined);
    setRenameCategory(report ? '' : r.category || '');
    setRenameScope(report ? '' : r.applicableScope || '');
    setRenameEffectiveFrom(undefined);
    setRenameEffectiveTo(undefined);
    setRenameProjectId(report ? r.projectId : undefined);
    setRenameStageId(report ? r.stageId : undefined);
    setRenameTaskId(report ? r.taskId : undefined);
    setRenameOpen(true);
    void loadReportProjects();
    if (report) void loadRenameRelations(r.projectId, r.stageId);
    try {
      const detail = await getResearchTest(type, r.id);
      const summary = detail.summary;
      setRenaming(summary);
      setRenameName(summary.name);
      setRenameBusinessNo(summary.businessNo || '');
      setRenameOwnerName(report ? summary.ownerName || '' : '');
      setRenameDate(report && summary.businessDate ? dayjs(summary.businessDate) : undefined);
      setRenameCategory(report ? '' : summary.category || '');
      setRenameScope(report ? '' : summary.applicableScope || '');
      setRenameEffectiveFrom(!report && detail.effectiveFrom ? dayjs(detail.effectiveFrom) : undefined);
      setRenameEffectiveTo(!report && detail.effectiveTo ? dayjs(detail.effectiveTo) : undefined);
      setRenameProjectId(report ? summary.projectId : undefined);
      setRenameStageId(report ? summary.stageId : undefined);
      setRenameTaskId(report ? summary.taskId : undefined);
      if (report) void loadRenameRelations(summary.projectId, summary.stageId);
    } catch (error) {
      message.error(error instanceof Error ? error.message : `${report ? '报告' : '测试标准'}信息加载失败`);
      setRenameOpen(false);
    }
  };
  const saveRename = async () => {
    if (!renaming || !renameName.trim()) {
      message.warning(`请输入${report ? '报告' : '测试标准'}名称`);
      return;
    }
    if (report && !renameBusinessNo.trim()) {
      message.warning('请输入报告编号');
      return;
    }
    if (report && !renameOwnerName.trim()) {
      message.warning('请输入负责人');
      return;
    }
    if (report && !renameDate) {
      message.warning('请选择日期');
      return;
    }
    if (!report && !renameBusinessNo.trim()) {
      message.warning('请输入标准编号');
      return;
    }
    if (!report && !renameCategory.trim()) {
      message.warning('请选择标准类别');
      return;
    }
    if (!report && !renameEffectiveFrom) {
      message.warning('请选择生效日期');
      return;
    }
    if (!report && renameEffectiveFrom && renameEffectiveTo && renameEffectiveTo.isBefore(renameEffectiveFrom, 'day')) {
      message.warning('失效日期不能早于生效日期');
      return;
    }
    setRenameSaving(true);
    try {
      await renameResearchTest(type, renaming.id, {
        revision: renaming.lockVersion,
        name: renameName.trim(),
        ...(report
          ? {
              businessNo: renameBusinessNo.trim(),
              ownerName: renameOwnerName.trim(),
              date: renameDate?.format('YYYY-MM-DD'),
            }
          : {
              businessNo: renameBusinessNo.trim(),
              category: renameCategory.trim(),
              scope: renameScope.trim(),
              effectiveFrom: renameEffectiveFrom?.format('YYYY-MM-DD'),
              effectiveTo: renameEffectiveTo?.format('YYYY-MM-DD'),
            }),
        ...(report
          ? {
              projectId: renameProjectId || undefined,
              stageId: renameStageId || undefined,
              taskId: renameTaskId || undefined,
            }
          : {}),
      });
      message.success(report ? '报告信息和项目关联已更新' : '测试标准信息已更新');
      setRenameOpen(false);
      setRenaming(undefined);
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : `${report ? '报告' : '测试标准'}信息保存失败`);
    } finally {
      setRenameSaving(false);
    }
  };
  const selectedRows = useMemo(
    () => selected
      .map((id) => rows.find((row) => row.id === id))
      .filter((row): row is ResearchTestSummary => Boolean(row)),
    [rows, selected],
  );
  const allSelected = rows.length > 0 && rows.every((row) => selected.includes(row.id));
  const indeterminate = rows.some((row) => selected.includes(row.id)) && !allSelected;
  const toggleSelectAll = () => {
    setSelected((current) => {
      if (allSelected) return current.filter((id) => !rows.some((row) => row.id === id));
      return [...new Set([...current, ...rows.map((row) => row.id)])];
    });
  };
  const copySelected = async () => {
    if (!selectedRows.length) return;
    setCopying(true);
    try {
      await Promise.all(selectedRows.map((row) => copyResearchTest(type, row.id)));
      message.success(`已复制 ${selectedRows.length} 条${report ? '报告' : '测试标准'}`);
      setSelected([]);
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '复制失败');
    } finally {
      setCopying(false);
    }
  };
  const renameSelected = () => {
    if (selectedRows.length !== 1) {
      message.warning(`请只选择一条${report ? '报告' : '测试标准'}后重命名`);
      return;
    }
    const row = selectedRows[0];
    if (row) void openRename(row);
  };
  const exportSelected = async () => {
    if (!selectedRows.length) return;
    setExporting(true);
    try {
      await Promise.all(
        selectedRows.map((row) =>
          downloadResearchTest(type, row.id, row.name, row.documentFormat),
        ),
      );
      message.success(`已导出 ${selectedRows.length} 条${report ? '报告' : '测试标准'}`);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '导出失败');
    } finally {
      setExporting(false);
    }
  };
  const deleteSelected = () => {
    const deletable = selectedRows.filter((row) => ['DRAFT', 'RETURNED'].includes(row.status));
    if (!deletable.length) {
      message.warning('仅草稿或已退回记录可以删除');
      return;
    }
    Modal.confirm({
      title: `删除选中的 ${deletable.length} 条${report ? '报告' : '测试标准'}？`,
      content: '删除后记录将从列表中移除。已发布或审核中的记录不会被删除。',
      okText: '确认删除',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        setDeleting(true);
        try {
          await Promise.all(deletable.map((row) => deleteResearchTest(type, row.id, row.lockVersion)));
          message.success(`已删除 ${deletable.length} 条${report ? '报告' : '测试标准'}`);
          setSelected([]);
          await load();
        } catch (error) {
          message.error(error instanceof Error ? error.message : '删除失败');
        } finally {
          setDeleting(false);
        }
      },
    });
  };
  const columns = useMemo(
    () => [
      { title: report ? '报告编号' : '标准编号', dataIndex: 'businessNo', className: report ? undefined : 'research-test-number-column', width: report ? 170 : 145 },
      {
        title: report ? '报告名称' : '标准名称',
        dataIndex: 'name',
        className: 'research-test-name-column',
        width: report ? 360 : 260,
        render: (v: string, r: ResearchTestSummary) => (
          <Button
            type="link"
            className="pm-name-link"
            onClick={() => nav(`/research-test/${report ? 'reports' : 'standards'}/${r.id}`)}
          >
            {v}
          </Button>
        ),
      },
      {
        title: report ? '所属项目' : '标准类别',
        dataIndex: report ? 'projectName' : 'category',
        className: report ? 'research-test-project-column' : 'research-test-category-column',
        width: report ? 300 : 150,
        render: (v?: string) => report ? (v || '未关联项目') : (v || '—'),
      },
      ...(report
        ? [{ title: '负责人', dataIndex: 'ownerName', width: 110 }]
        : [{
            title: '适用对象',
            dataIndex: 'applicableScope',
            className: 'research-test-scope-column',
            width: 220,
            render: (v?: string) => v || '—',
          }]),
      {
        title: report ? '日期' : '发布日期',
        dataIndex: 'businessDate',
        width: report ? 130 : 120,
        render: (v?: string) => v || '—',
      },
      {
        title: '使用状态',
        dataIndex: 'status',
        width: report ? 110 : 105,
        render: (v: string) => (
          <Tag color={v === 'PUBLISHED' ? 'green' : v === 'PENDING_REVIEW' ? 'orange' : 'blue'}>
            {statusText[v] || v}
          </Tag>
        ),
      },
      {
        title: '操作',
        key: 'actions',
        width: report ? 360 : 280,
        render: (_: unknown, r: ResearchTestSummary) => (
          <Space className="management-table-actions" size={0}>
            <Button
              type="link"
              size="small"
              onClick={() => nav(`/research-test/${report ? 'reports' : 'standards'}/${r.id}`)}
            >
              查看
            </Button>
            <Button type="link" size="small" onClick={() => void openRename(r)}>
              重命名
            </Button>
            <Button type="link" size="small" onClick={() => void copy(r)}>
              复制
            </Button>
            <Button
              type="link"
              size="small"
              onClick={() =>
                void downloadResearchTest(
                  report ? 'REPORT' : 'STANDARD',
                  r.id,
                  r.name,
                  r.documentFormat,
                ).catch((e) => message.error(e instanceof Error ? e.message : '下载失败'))
              }
            >
              导出
            </Button>
            <Popconfirm
              title={`确认删除${report ? '该报告' : '该测试标准'}？`}
              description="删除后该记录将从列表中移除。"
              okText="删除"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              onConfirm={() => void remove(r)}
            >
              <Button
                type="link"
                size="small"
                danger
                disabled={!['DRAFT', 'RETURNED'].includes(r.status)}
              >
                删除
              </Button>
            </Popconfirm>
          </Space>
        ),
      },
    ],
    [message, nav, openRename, report],
  );
  return (
    <section className="research-test-page pm-unified-list-page">
      <header className="research-test-head research-test-page-intro">
        <div>
          <Breadcrumb items={[{ title: '研发测试中心' }, { title: report ? '综合测试报告' : '测试标准方法' }]} />
          <Typography.Title level={3}>{report ? '综合测试报告' : '测试标准方法'}</Typography.Title>
          <Typography.Text type="secondary">统一管理测试文档、发布版本与审核同步。</Typography.Text>
        </div>
      </header>
      <Card className="research-test-filter">
        <Space wrap>
          <Input.Search
            allowClear
            placeholder={report ? '搜索报告编号／名称' : '搜索标准编号／名称'}
            style={{ width: 320 }}
            onSearch={(v) => {
              setKeyword(v);
              setPage(1);
            }}
          />
          <Select<string>
            allowClear
            placeholder={report ? '全部项目' : '全部标准类别'}
            style={{ width: 180 }}
            options={
              report
                ? Array.from(
                    new Map(
                      rows
                        .filter((x) => x.projectId && x.projectName)
                        .map((x) => [x.projectId as string, x.projectName as string]),
                    ),
                  ).map(([value, label]) => ({ value, label }))
                : standardCategories.map((v) => ({ value: v, label: v }))
            }
            onChange={(v) => {
              if (report) setProjectId(v);
              else setCategory(v);
              setPage(1);
            }}
          />
          <Select<string>
            allowClear
            placeholder="全部使用状态"
            style={{ width: 160 }}
            options={Object.entries(statusText).map(([value, label]) => ({ value, label }))}
            onChange={(v) => {
              setStatus(v);
              setPage(1);
            }}
          />
          <DatePicker
            value={date}
            onChange={(v) => {
              setDate(v);
              setPage(1);
            }}
          />
          <Button
            className="pm-filter-reset"
            icon={<ReloadOutlined />}
            onClick={() => {
              setKeyword('');
              setProjectId(undefined);
              setCategory(undefined);
              setStatus(undefined);
              setDate(undefined);
              setPage(1);
            }}
          >
            重置
          </Button>
        </Space>
      </Card>
      <div className="research-test-batch-row pm-batch-row">
          <div className="pm-batch-summary">
            <span className="pm-selected">已选 {selected.length} 项</span>
            {selected.length > 0 && (
              <Button type="link" size="small" onClick={() => setSelected([])}>
                清除已选
              </Button>
            )}
          </div>
          <div className="pm-batch-actions">
            <Button
              icon={<CopyOutlined />}
              disabled={!selected.length}
              loading={copying}
              onClick={() => void copySelected()}
            >
              复制
            </Button>
            <Button
              icon={<EditOutlined />}
              onClick={renameSelected}
            >
              重命名
            </Button>
            <Button
              icon={<DownloadOutlined />}
              disabled={!selected.length}
              loading={exporting}
              onClick={() => void exportSelected()}
            >
              导出
            </Button>
            <Button
              danger
              icon={<DeleteOutlined />}
              disabled={!selected.length}
              loading={deleting}
              onClick={deleteSelected}
            >
              删除
            </Button>
            <Button icon={<UploadOutlined />} onClick={() => nav(report ? '/research-test/upload' : '/research-test/standard-upload')}>
              {report ? '上传报告' : '测试标准上传'}
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={showCreate}>
              {report ? '新增报告' : '新增测试标准'}
            </Button>
            <Space.Compact>
              <Tooltip title="卡片视图">
                <Button
                  aria-label="卡片视图"
                  className={mode === 'card' ? 'pm-view-btn active' : 'pm-view-btn'}
                  icon={<AppstoreOutlined />}
                  onClick={() => setMode('card')}
                />
              </Tooltip>
              <Tooltip title="列表视图">
                <Button
                  aria-label="列表视图"
                  className={mode === 'list' ? 'pm-view-btn active' : 'pm-view-btn'}
                  icon={<UnorderedListOutlined />}
                  onClick={() => setMode('list')}
                />
              </Tooltip>
            </Space.Compact>
          </div>
      </div>
      {mode === 'card' ? (
        <List
          className={report ? 'research-test-report-card-list' : undefined}
          loading={loading}
          grid={report
            ? { gutter: 16, xs: 1, sm: 2, md: 3, lg: 4, xl: 5, xxl: 5 }
            : { gutter: 16, xs: 1, sm: 1, md: 2, lg: 3 }}
          dataSource={rows}
          pagination={{
            current: page,
            pageSize: size,
            total,
            showSizeChanger: true,
            showTotal: (value) => `共 ${value} 条记录`,
            onChange: (current, pageSize) => {
              setPage(current);
              setSize(pageSize);
            },
          }}
          header={rows.length > 0 ? (
            <div className="research-test-card-select-all">
              <Checkbox
                checked={allSelected}
                indeterminate={indeterminate}
                onChange={toggleSelectAll}
              />
              <span>全选当前页</span>
            </div>
          ) : undefined}
          renderItem={(r) => (
            <List.Item>
              {report ? (
                <ResearchTestReportCard
                  row={r}
                  selected={selected.includes(r.id)}
                  onToggle={() =>
                    setSelected((current) =>
                      current.includes(r.id)
                        ? current.filter((id) => id !== r.id)
                        : [...current, r.id],
                    )
                  }
                  onView={() => nav(`/research-test/reports/${r.id}`)}
                  onRename={() => void openRename(r)}
                  onCopy={() => void copy(r)}
                  onExport={() =>
                    void downloadResearchTest(type, r.id, r.name, r.documentFormat).catch((e) =>
                      message.error(e instanceof Error ? e.message : '导出失败'),
                    )
                  }
                  onDelete={() => void remove(r)}
                />
              ) : (
                <Card
                  title={<span title={r.name}>{r.name}</span>}
                  extra={
                    <Space size={8}>
                      <Tag>{statusText[r.status]}</Tag>
                      <Checkbox
                        checked={selected.includes(r.id)}
                        onChange={() =>
                          setSelected((current) =>
                            current.includes(r.id)
                              ? current.filter((id) => id !== r.id)
                              : [...current, r.id],
                          )
                        }
                      />
                    </Space>
                  }
                  actions={[
                    <Button
                      key="view"
                      type="link"
                      onClick={() => nav(`/research-test/standards/${r.id}`)}
                    >
                      查看
                    </Button>,
                    <Button key="rename" type="link" onClick={() => void openRename(r)}>
                      重命名
                    </Button>,
                    <Button key="copy" type="link" onClick={() => void copy(r)}>
                      复制
                    </Button>,
                    <Button
                      key="export"
                      type="link"
                      onClick={() =>
                        void downloadResearchTest(type, r.id, r.name, r.documentFormat).catch((e) =>
                          message.error(e instanceof Error ? e.message : '导出失败'),
                        )
                      }
                    >
                      导出
                    </Button>,
                    <Popconfirm
                      key="delete"
                      title="确认删除该测试标准？"
                      description="删除后记录将从列表中移除。"
                      okText="删除"
                      cancelText="取消"
                      okButtonProps={{ danger: true }}
                      onConfirm={() => void remove(r)}
                    >
                      <Button
                        type="link"
                        danger
                        disabled={!['DRAFT', 'RETURNED'].includes(r.status)}
                      >
                        删除
                      </Button>
                    </Popconfirm>,
                  ]}
                >
                  <p>{r.businessNo}</p>
                  <p>{r.category || '未分类'}</p>
                  <Typography.Text type="secondary">
                    {['PUBLISHED', 'ARCHIVED'].includes(r.status) ? `V${r.versionNo}` : '未发布'} ·{' '}
                    {r.applicableScope || '未设置适用对象'}
                  </Typography.Text>
                </Card>
              )}
            </List.Item>
          )}
        />
      ) : (
        <div className="research-test-table-wrap">
          <Table
            rowKey="id"
            className={`research-test-list-table research-test-${report ? 'report' : 'standard'}-table`}
            loading={loading}
            dataSource={rows}
            columns={columns}
            // Keep the data table readable on narrow screens.  The mobile
            // layout scrolls the fixed-width columns instead of squeezing
            // Chinese labels into one character per line.
            scroll={{ x: report ? 'max-content' : 1280 }}
            rowSelection={{
              selectedRowKeys: selected,
              onChange: (keys) => setSelected(keys.map((key) => String(key))),
            }}
            pagination={{
              current: page,
              pageSize: size,
              total,
              showSizeChanger: true,
              showTotal: (value) => `共 ${value} 条记录`,
              onChange: (p, s) => {
                setPage(p);
                setSize(s);
              },
            }}
          />
        </div>
      )}
      <Modal
        rootClassName={report ? 'research-test-create-modal research-test-report-modal' : 'research-test-create-modal'}
        open={open}
        title={report ? '新增报告' : '新增测试标准'}
        width={598}
        okText="创建并进入编辑"
        confirmLoading={submitting}
        okButtonProps={{ disabled: submitting }}
        onCancel={() => setOpen(false)}
        onOk={() => void submit()}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="sourceType" hidden rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="format" hidden rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <div className={report ? 'research-test-form-grid' : 'research-test-create-grid'}>
            <Form.Item
              name="businessNo"
              label={report ? '报告编号' : '标准编号'}
              rules={report ? [] : [{ required: true }]}
            >
              <Input placeholder={report ? '系统自动生成' : '请输入标准编号'} />
            </Form.Item>
            <Form.Item
              name="name"
              label={report ? '报告名称' : '标准名称'}
              rules={[{ required: true }]}
            >
              <Input />
            </Form.Item>
            {report ? (
              <>
                <Form.Item name="ownerName" label="负责人" rules={[{ required: true }]}>
                  <Input />
                </Form.Item>
                <Form.Item name="date" label="日期" rules={[{ required: true }]}>
                  <DatePicker style={{ width: '100%' }} />
                </Form.Item>
              </>
            ) : (
              <>
                <Form.Item name="category" label="标准类别" rules={[{ required: true }]}>
                  <Select options={standardCategories.map((value) => ({ value, label: value }))} />
                </Form.Item>
                <Form.Item name="scope" label="适用对象">
                  <Input />
                </Form.Item>
                <Form.Item name="effectiveFrom" label="生效日期" rules={[{ required: true }]}>
                  <DatePicker style={{ width: '100%' }} />
                </Form.Item>
                <Form.Item name="effectiveTo" label="失效日期">
                  <DatePicker style={{ width: '100%' }} />
                </Form.Item>
              </>
            )}
          </div>
          {report && (
            <div className="research-test-report-relations-grid">
              <Form.Item name="projectId" label="关联项目">
                <Select
                  allowClear
                  showSearch
                  optionFilterProp="label"
                  loading={reportProjectsLoading}
                  popupMatchSelectWidth={false}
                  classNames={{ popup: { root: 'research-test-project-relation-dropdown' } }}
                  placeholder="不关联项目"
                  options={reportProjects.map((item) => ({
                    value: item.id,
                    label: `${item.projectCode} · ${item.name}`,
                  }))}
                  onChange={changeCreateProject}
                />
              </Form.Item>
              <Form.Item name="stageId" label="阶段">
                <Select
                  allowClear
                  loading={createStagesLoading}
                  disabled={!form.getFieldValue('projectId')}
                  placeholder="不关联阶段"
                  options={createStages.map((item) => ({ value: item.id, label: item.name }))}
                  onChange={changeCreateStage}
                />
              </Form.Item>
              <Form.Item name="taskId" label="任务">
                <Select
                  allowClear
                  loading={createTasksLoading}
                  disabled={!form.getFieldValue('stageId')}
                  placeholder="不关联任务"
                  options={createTasks.map((item) => ({ value: item.id, label: item.name }))}
                />
              </Form.Item>
            </div>
          )}
          <CreateChoice
            label="选择新建"
            value={sourceType || 'BLANK'}
            options={[
              {
                key: 'BLANK',
                title: '空白新建',
                desc: `选择 Word 或 Excel 创建空白${report ? '报告' : '测试标准'}`,
              },
              {
                key: 'TEMPLATE',
                title: '选择模板新建',
                desc: `复制已发布模板形成独立${report ? '报告' : '测试标准'}副本`,
              },
            ]}
            onChange={(value) => {
              form.setFieldValue('sourceType', value);
              form.setFieldValue('templateVersionId', undefined);
            }}
          />
          {sourceType === 'TEMPLATE' ? (
            <PublishedTemplatePicker
              templates={templates}
              documentName={report ? '报告' : '测试标准'}
            />
          ) : (
            <CreateChoice
              label="文档格式"
              value={documentFormat || 'WORD'}
              options={[
                {
                  key: 'WORD',
                  title: `Word ${report ? '报告' : '测试标准'}`,
                  desc: '正文、章节与文档结构编辑',
                },
                {
                  key: 'EXCEL',
                  title: `Excel ${report ? '报告' : '测试标准'}`,
                  desc: '表格、单元格与字段结构编辑',
                },
              ]}
              onChange={(value) => form.setFieldValue('format', value)}
            />
          )}
        </Form>
      </Modal>
      <Modal
        rootClassName="research-test-rename-modal"
        open={renameOpen}
        title={report ? '编辑报告' : '编辑测试标准'}
        width={598}
        confirmLoading={renameSaving}
        okText="保存"
        cancelText="取消"
        onCancel={() => setRenameOpen(false)}
        onOk={() => void saveRename()}
      >
        <Form layout="vertical" className="research-test-rename-form">
          {report ? (
            <div className="research-test-rename-form-grid">
              <Form.Item label="报告编号">
                <Input
                  value={renameBusinessNo}
                  maxLength={120}
                  placeholder="请输入报告编号"
                  onChange={(event) => setRenameBusinessNo(event.target.value)}
                />
              </Form.Item>
              <Form.Item label="报告名称">
                <Input
                  value={renameName}
                  maxLength={300}
                  placeholder="请输入报告名称"
                  onChange={(event) => setRenameName(event.target.value)}
                />
              </Form.Item>
              <Form.Item label="负责人">
                <Input
                  value={renameOwnerName}
                  maxLength={120}
                  placeholder="请输入负责人"
                  onChange={(event) => setRenameOwnerName(event.target.value)}
                />
              </Form.Item>
              <Form.Item label="日期">
                <DatePicker
                  value={renameDate}
                  format="YYYY/MM/DD"
                  onChange={setRenameDate}
                  style={{ width: '100%' }}
                />
              </Form.Item>
            </div>
          ) : (
            <div className="research-test-rename-form-grid research-test-standard-rename-grid">
              <Form.Item label="标准编号">
                <Input
                  value={renameBusinessNo}
                  maxLength={120}
                  placeholder="请输入标准编号"
                  onChange={(event) => setRenameBusinessNo(event.target.value)}
                />
              </Form.Item>
              <Form.Item label="标准名称">
                <Input
                  value={renameName}
                  maxLength={300}
                  placeholder="请输入测试标准名称"
                  onChange={(event) => setRenameName(event.target.value)}
                />
              </Form.Item>
              <Form.Item label="标准类别">
                <Select
                  value={renameCategory || undefined}
                  placeholder="请选择标准类别"
                  options={standardCategories.map((value) => ({ value, label: value }))}
                  onChange={(value) => setRenameCategory(value || '')}
                />
              </Form.Item>
              <Form.Item label="适用对象">
                <Input
                  value={renameScope}
                  maxLength={300}
                  placeholder="请输入适用对象（选填）"
                  onChange={(event) => setRenameScope(event.target.value)}
                />
              </Form.Item>
              <Form.Item label="生效日期">
                <DatePicker
                  value={renameEffectiveFrom}
                  format="YYYY/MM/DD"
                  onChange={setRenameEffectiveFrom}
                  style={{ width: '100%' }}
                />
              </Form.Item>
              <Form.Item label="失效日期">
                <DatePicker
                  value={renameEffectiveTo}
                  format="YYYY/MM/DD"
                  onChange={setRenameEffectiveTo}
                  style={{ width: '100%' }}
                />
              </Form.Item>
            </div>
          )}
          {report && (
            <div className="research-test-report-relations-grid research-test-rename-relations">
              <Form.Item label="关联项目">
                <Select
                  allowClear
                  showSearch
                  optionFilterProp="label"
                  loading={reportProjectsLoading}
                  popupMatchSelectWidth={false}
                  classNames={{ popup: { root: 'research-test-project-relation-dropdown' } }}
                  placeholder="不关联项目"
                  value={renameProjectId}
                  options={reportProjects.map((item) => ({
                    value: item.id,
                    label: `${item.projectCode} · ${item.name}`,
                  }))}
                  onChange={changeRenameProject}
                />
              </Form.Item>
              <Form.Item label="阶段">
                <Select
                  allowClear
                  loading={renameStagesLoading}
                  disabled={!renameProjectId}
                  placeholder="不关联阶段"
                  value={renameStageId}
                  options={renameStages.map((item) => ({ value: item.id, label: item.name }))}
                  onChange={changeRenameStage}
                />
              </Form.Item>
              <Form.Item label="任务">
                <Select
                  allowClear
                  loading={renameTasksLoading}
                  disabled={!renameStageId}
                  placeholder="不关联任务"
                  value={renameTaskId}
                  options={renameTasks.map((item) => ({ value: item.id, label: item.name }))}
                  onChange={setRenameTaskId}
                />
              </Form.Item>
            </div>
          )}
        </Form>
      </Modal>
    </section>
  );
}

function CreateChoice({
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
    <div className="research-test-choice-section">
      <label>
        <i>*</i>
        {label}
      </label>
      <div>
        {options.map((option, index) => (
          <button
            type="button"
            className={value === option.key ? 'active' : ''}
            onClick={() => onChange(option.key)}
            key={option.key}
          >
            <span>{index === 0 ? '▣' : '↪'}</span>
            <b>
              {option.title}
              <small>{option.desc}</small>
            </b>
          </button>
        ))}
      </div>
    </div>
  );
}

function ResearchTestReportCard({
  row,
  selected,
  onToggle,
  onView,
  onRename,
  onCopy,
  onExport,
  onDelete,
}: {
  row: ResearchTestSummary;
  selected: boolean;
  onToggle: () => void;
  onView: () => void;
  onRename: () => void;
  onCopy: () => void;
  onExport: () => void;
  onDelete: () => void;
}) {
  return (
    <article className={selected ? 'research-test-report-card is-selected' : 'research-test-report-card'}>
      <div className="research-test-card-head">
        <div className="research-test-card-heading">
          <h4 className="research-test-card-title" title={row.name}>
            <Link className="research-test-card-title-link" to={`/research-test/reports/${row.id}`}>
              {row.name}
            </Link>
          </h4>
          <div className="research-test-card-code">{row.businessNo}</div>
        </div>
        <Checkbox checked={selected} onChange={onToggle} />
      </div>
      <div className="research-test-card-info-grid">
        <div className="research-test-card-info-item">
          <span className="research-test-card-info-label">负责人</span>
          <strong className="research-test-card-info-value">{row.ownerName || '未设置'}</strong>
        </div>
        <div className="research-test-card-info-item">
          <span className="research-test-card-info-label">报告日期</span>
          <strong className="research-test-card-info-value">{row.businessDate || '未设置'}</strong>
        </div>
        <div className="research-test-card-info-item">
          <span className="research-test-card-info-label">所属项目</span>
          <strong className="research-test-card-info-value" title={row.projectName || undefined}>
            {row.projectName || '未关联项目'}
          </strong>
        </div>
        <div className="research-test-card-info-item">
          <span className="research-test-card-info-label">阶段 / 任务</span>
          <strong className="research-test-card-info-value">
            {row.stageName || '未选择'}{row.taskName ? ` / ${row.taskName}` : ''}
          </strong>
        </div>
      </div>
      <div className="research-test-card-tags">
        <Tag color={row.status === 'PUBLISHED' ? 'green' : row.status === 'PENDING_REVIEW' ? 'orange' : 'blue'}>
          {statusText[row.status] || row.status}
        </Tag>
        <Tag>{row.documentFormat === 'EXCEL' ? 'Excel' : 'Word'}</Tag>
      </div>
      <div className="research-test-card-actions">
        <Button type="link" onClick={onView}>查看</Button>
        <Button type="link" onClick={onRename}>重命名</Button>
        <Button type="link" onClick={onCopy}>复制</Button>
        <Button type="link" onClick={onExport}>导出</Button>
        <Popconfirm
          title="确认删除该报告？"
          description="删除后记录将从列表中移除。"
          okText="删除"
          cancelText="取消"
          okButtonProps={{ danger: true }}
          onConfirm={onDelete}
        >
          <Button type="link" danger disabled={!['DRAFT', 'RETURNED'].includes(row.status)}>
            删除
          </Button>
        </Popconfirm>
      </div>
    </article>
  );
}

function PublishedTemplatePicker({
  templates,
  documentName,
}: {
  templates: TemplateListItem[];
  documentName: string;
}) {
  const [categoryId, setCategoryId] = useState<string>();
  const categories = useMemo(() => {
    const values = new Map<string, string>();
    templates.forEach((item) =>
      values.set(item.categoryId ?? '__UNCATEGORIZED__', item.category || '未分类'),
    );
    return [...values.entries()].map(([value, label]) => ({ value, label }));
  }, [templates]);
  const filtered = categoryId
    ? templates.filter((item) => (item.categoryId ?? '__UNCATEGORIZED__') === categoryId)
    : templates;
  return (
    <>
      <Form.Item label="模板分类">
        <Select
          allowClear
          placeholder="全部模板分类"
          options={categories}
          value={categoryId}
          onChange={setCategoryId}
        />
      </Form.Item>
      <Form.Item
        name="templateVersionId"
        label="选择模板"
        rules={[{ required: true, message: `请选择${documentName}模板` }]}
      >
        <Select
          showSearch
          optionFilterProp="label"
          placeholder="请选择已发布模板"
          options={filtered.map((item) => ({
            value: item.versionId,
            label: `${item.name}（${item.format === 'XLSX' ? 'Excel' : 'Word'} · V${item.versionNo}）`,
          }))}
          notFoundContent="暂无该分类的已发布模板"
        />
      </Form.Item>
    </>
  );
}

function matchesResearchTestTemplate(item: TemplateListItem, type: ResearchTestType) {
  const searchable = `${item.name} ${item.category || ''}`.toLowerCase();
  if (searchable.includes('实验')) return false;
  return type === 'STANDARD'
    ? /(测试|检测|检验|标准|方法)/.test(searchable)
    : /(测试|检测|检验|报告)/.test(searchable);
}
