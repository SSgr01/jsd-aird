import { Button, DatePicker, Empty, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag, message } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import dayjs from '@/utils/dayjs';
import { Can } from '@/components/auth/Can';
import { createProjectExperiment, deleteProjectExperiment, listProjectExperiments, type ExperimentSummary } from '@/services/experiments/experiment-api';
import { projectTemplateApi, type ProjectTemplateOption } from '@/services/project/project-template-api';
import {
  createProjectTask, deleteProjectTask, getProjectStages, getProjectTask, getStageTasks,
  updateProjectTask,
  projectPriorities, type ProjectPriority, type ProjectStage, type ProjectTask,
} from '@/services/project/project-api';
import '../experiments/experiments.css';
import '@/styles/management-list.css';

const taskStatuses: Record<string, string> = { PENDING: '待开始', IN_PROGRESS: '进行中', COMPLETED: '已完成', CANCELLED: '已取消' };
const formatTaskStatus = (s: string) => taskStatuses[s] ?? s;
const experimentStatuses: Record<string, string> = { DRAFT: '草稿', PENDING: '待开始', IN_PROGRESS: '进行中', PENDING_REVIEW: '待审核', RETURNED: '已退回', COMPLETED: '已完成', VOIDED: '已作废', CANCELLED: '已取消' };
const formatExperimentStatus = (s: string) => experimentStatuses[s] ?? s;

interface ExperimentFormValues {
  experimentNo: string;
  title: string;
  category?: string;
  ownerName: string;
  experimentDate: dayjs.Dayjs;
  projectId: string;
  stageId?: string;
  taskId?: string;
  templateVersionId?: string;
}

type CreateMode = 'blank' | 'template';
type DocumentFormat = 'word' | 'excel';

interface TaskFormValues {
  stageId: string;
  name: string;
  owner?: string;
  priority: ProjectPriority;
  plannedDate: dayjs.Dayjs;
  status: string;
}

export function ProjectTaskBoard({ projectId, stage, onTaskSaved }: {
  projectId: string;
  stage: ProjectStage;
  onTaskSaved?: (stageId: string) => void | Promise<void>;
}) {
  const nav = useNavigate();
  const location = useLocation();
  const [tasks, setTasks] = useState<ProjectTask[]>([]);
  const [selected, setSelected] = useState<ProjectTask>();
  const [experiments, setExperiments] = useState<ExperimentSummary[]>([]);
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<ExperimentFormValues>();
  const [messageApi, holder] = message.useMessage();
  const draftCode = useMemo(() => `EXP-${dayjs().format('YYYYMMDD')}-${Math.random().toString(36).slice(2, 7).toUpperCase()}`, [open]);
  const [createMode, setCreateMode] = useState<CreateMode>('blank');
  const [format, setFormat] = useState<DocumentFormat>('word');
  const [templates, setTemplates] = useState<ProjectTemplateOption[]>([]);
  const [templatesLoading, setTemplatesLoading] = useState(false);

  const [stages, setStages] = useState<ProjectStage[]>([]);
  const [experimentTasks, setExperimentTasks] = useState<ProjectTask[]>([]);
  const [searchParams] = useSearchParams();
  const focusedTaskId = searchParams.get('taskId');
  const focusedTaskRef = useRef<HTMLButtonElement>(null);
  const [taskOpen, setTaskOpen] = useState(false);
  const [taskSaving, setTaskSaving] = useState(false);
  const [deletingTaskId, setDeletingTaskId] = useState<string>();
  const [deletingExperimentId, setDeletingExperimentId] = useState<string>();
  const [editingTask, setEditingTask] = useState<ProjectTask>();
  const [taskForm] = Form.useForm<TaskFormValues>();

  const totalExperiments = useMemo(
    () => tasks.reduce((sum, task) => sum + (task.experimentCount ?? 0), 0),
    [tasks],
  );

  const load = useCallback(() => getStageTasks(stage.id).then((data) => {
    setTasks(data);
    const target = focusedTaskId ? data.find(({ id }) => id === focusedTaskId) : undefined;
    setSelected(target || ((old) => data.find(({ id }) => id === old?.id) || data[0]));
  }).catch(() => { setTasks([]); setSelected(undefined); }), [stage.id, focusedTaskId]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (focusedTaskId && focusedTaskRef.current) {
      focusedTaskRef.current.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  }, [focusedTaskId, tasks]);
  useEffect(() => { void getProjectStages(projectId).then(setStages).catch(() => setStages([])); }, [projectId]);
  const refreshExperiments = useCallback(async (taskId = selected?.id) => {
    if (!taskId) {
      setExperiments([]);
      return;
    }
    try {
      const result = await listProjectExperiments(projectId, { stageId: stage.id, taskId, page: 1, size: 100 });
      setExperiments(result.items);
    } catch {
      setExperiments([]);
    }
  }, [projectId, selected?.id, stage.id]);
  useEffect(() => { void refreshExperiments(); }, [refreshExperiments]);

  const showExperiment = () => {
    if (!selected) return;
    setCreateMode('blank');
    setFormat('word');
    setTemplates([]);
    form.resetFields();
    setExperimentTasks(tasks);
    form.setFieldsValue({
      ownerName: selected.owner || '',
      experimentNo: draftCode,
      experimentDate: dayjs(),
      projectId,
      stageId: stage.id,
      taskId: selected.id,
    });
    setOpen(true);
  };

  const changeCreateMode = (mode: CreateMode) => {
    setCreateMode(mode);
    form.setFieldValue('templateVersionId', undefined);
    if (mode === 'template' && !templates.length) {
      setTemplatesLoading(true);
      void projectTemplateApi.list(projectId)
        .then(setTemplates)
        .catch(() => messageApi.error('模板列表加载失败'))
        .finally(() => setTemplatesLoading(false));
    }
  };

  const saveExperiment = async () => {
    if (!selected) return;
    const values = await form.validateFields();
    setSaving(true);
    try {
      const selectedTemplate = createMode === 'template'
        ? templates.find((item) => item.versionId === values.templateVersionId)
        : undefined;
      let templateSnapshot: Record<string, unknown> | undefined;
      let templateSnapshotHash: string | undefined;
      if (createMode === 'template') {
        if (!values.templateVersionId || !selectedTemplate) throw new Error('请选择实验模板');
        const template = await projectTemplateApi.getEditModel(projectId, values.templateVersionId);
        templateSnapshot = template.snapshot;
        templateSnapshotHash = template.snapshotHash;
        if (!templateSnapshot) throw new Error('所选模板没有可用的文档内容');
      }
      const documentFormat: DocumentFormat = selectedTemplate?.format === 'XLSX'
        ? 'excel' : selectedTemplate?.format === 'DOCX' ? 'word' : format;
      await createProjectExperiment(projectId, {
        experimentNo: values.experimentNo.trim(), title: values.title.trim(),
        categoryName: values.category?.trim() || undefined, sourceType: createMode === 'template' ? 'TEMPLATE' : 'PROJECT',
        stageId: values.stageId, taskId: values.taskId, ownerName: values.ownerName.trim(),
        experimentDate: values.experimentDate.format('YYYY-MM-DD'),
        templateVersionId: values.templateVersionId,
        templateSnapshotHash, templateSnapshot,
        editModel: {
          title: values.title.trim(), purpose: '', plan: '', documentFormat,
          documentSnapshot: templateSnapshot, dynamicValues: {}, formulaItems: [], processSteps: [],
          testResults: [], events: [], conclusion: { resultStatus: '', mainConclusion: '', failureCategory: '' },
        },
      });
      messageApi.success('实验已创建');
      setOpen(false);
      form.resetFields();
      await refreshExperiments(selected.id);
      await load();
    } catch (reason) {
      messageApi.error(reason instanceof Error ? reason.message : '实验保存失败');
    } finally { setSaving(false); }
  };

  const openNewTask = () => {
    setEditingTask(undefined);
    taskForm.resetFields();
    taskForm.setFieldsValue({ stageId: stage.id, owner: '', priority: 'MEDIUM', plannedDate: dayjs(), status: 'PENDING' });
    setTaskOpen(true);
  };

  const openEditTask = async (taskId: string) => {
    const data = await getProjectTask(taskId);
    setEditingTask(data);
    taskForm.setFieldsValue({
      stageId: data.stageId, name: data.name, owner: data.owner,
      priority: data.priority || 'MEDIUM', plannedDate: data.plannedDate ? dayjs(data.plannedDate) : dayjs(), status: data.status || 'PENDING',
    });
    setTaskOpen(true);
  };

  const saveTask = async () => {
    let values: TaskFormValues;
    try {
      values = await taskForm.validateFields();
    } catch {
      return;
    }
    setTaskSaving(true);
    try {
      const payload = {
        stageId: values.stageId, name: values.name.trim(), owner: values.owner?.trim() || '',
        priority: values.priority, plannedDate: values.plannedDate.format('YYYY-MM-DD'), status: values.status,
      };
      const savedTask = editingTask
        ? await updateProjectTask(editingTask.id, { ...payload, version: editingTask.version })
        : await createProjectTask(projectId, payload);
      messageApi.success(editingTask ? '任务已更新' : '任务已创建并记录日志');
      setTaskOpen(false);
      taskForm.resetFields();
      setEditingTask(undefined);
      // The stage-level callback refreshes stage metadata (for example task
      // counts), but it does not remount this board. Always reload the task
      // list here so a newly created/updated task is visible immediately.
      if (onTaskSaved) await onTaskSaved(savedTask.stageId);
      await load();
    } catch (reason) {
      messageApi.error(reason instanceof Error ? reason.message : '保存失败');
    } finally { setTaskSaving(false); }
  };

  const deleteTask = async (task: ProjectTask) => {
    setDeletingTaskId(task.id);
    try {
      await deleteProjectTask(task.id, task.version);
      messageApi.success('任务已删除');
      setSelected(undefined);
      setExperiments([]);
      await load();
    } catch (reason) {
      messageApi.error(reason instanceof Error ? reason.message : '任务删除失败');
    } finally {
      setDeletingTaskId(undefined);
    }
  };

  const deleteExperiment = async (experiment: ExperimentSummary) => {
    setDeletingExperimentId(experiment.id);
    try {
      await deleteProjectExperiment(projectId, experiment.id, experiment.revision);
      setExperiments((current) => current.filter(({ id }) => id !== experiment.id));
      messageApi.success('实验已删除');
      await load();
    } catch (reason) {
      messageApi.error(reason instanceof Error ? reason.message : '实验删除失败');
    } finally {
      setDeletingExperimentId(undefined);
    }
  };

  return <div className="pm-stage-work">
    {holder}
    <div className="pm-stage-work-head"><div><b>{stage.name}任务</b><small>项目 &gt; {stage.name} &gt; {tasks.length}个任务 &gt; {totalExperiments}个实验</small></div><Button type="primary" onClick={openNewTask}>＋ 新增任务</Button></div>
    <div className="pm-task-cards">{tasks.map((task) => <button key={task.id} ref={task.id === focusedTaskId ? focusedTaskRef : undefined} className={`${selected?.id === task.id ? 'active' : ''}${task.id === focusedTaskId ? ' pm-task-focused' : ''}`} onClick={() => setSelected(task)}><div className="pm-task-card-head"><b title={task.name}>{task.name}</b><span className="pm-task-card-edit" onClick={(e) => { e.stopPropagation(); void openEditTask(task.id); }}>编辑</span><Can permission="project.delete"><Popconfirm title="确认删除该任务？" description="任务下存在实验时不能删除。" okText="删除" cancelText="取消" okButtonProps={{ danger: true, loading: deletingTaskId === task.id }} onConfirm={() => void deleteTask(task)}><span className="pm-task-card-delete" role="button" tabIndex={0} aria-label={`删除任务${task.name}`} onClick={(e) => e.stopPropagation()}>删除</span></Popconfirm></Can></div><div className="pm-task-card-body"><span>{task.owner || '未设置'}</span><Tag>{task.experimentCount}实验</Tag></div><div className="pm-task-card-status"><span className={`pm-dot pm-dot-${task.status.toLowerCase()}`} />{formatTaskStatus(task.status)}</div></button>)}</div>
    {selected ? <><div className="pm-stage-work-head"><b>当前任务：{selected.name}</b><Button type="primary" onClick={showExperiment}>＋ 新增实验</Button></div><Table rowKey="id" pagination={false} dataSource={experiments} columns={[{ title: '实验编号', dataIndex: 'experimentNo' }, { title: '实验名称', dataIndex: 'title' }, { title: '日期', dataIndex: 'experimentDate' }, { title: '负责人', dataIndex: 'ownerName' }, { title: '状态', dataIndex: 'status', render: (status: string) => formatExperimentStatus(status) }, { title: '操作', key: 'action', width: 120, fixed: 'right', render: (_, experiment) => <Space className="management-table-actions" size={0}><Button type="link" size="small" onClick={() => nav(`/experiments/${experiment.id}`, { state: { returnTo: `${location.pathname}${location.search}` } })}>查看</Button><Can permission="project.delete"><Popconfirm title="确认删除该实验？" description="删除后实验记录将从列表中移除。" okText="删除" cancelText="取消" okButtonProps={{ danger: true, loading: deletingExperimentId === experiment.id }} onConfirm={() => void deleteExperiment(experiment)}><Button type="link" danger size="small">删除</Button></Popconfirm></Can></Space> }]} /></> : <Empty description="当前阶段暂无任务" />}

    <Modal rootClassName="eln-create-modal" width={598} centered title="新增实验" open={open} closable
      styles={{ body: { maxHeight: 'calc(100vh - 240px)', overflowY: 'auto' } }}
      onCancel={() => setOpen(false)} destroyOnClose footer={<div className="pm-experiment-footer">
        <Button onClick={() => setOpen(false)}>取消</Button>
        <Button type="primary" loading={saving} onClick={() => void saveExperiment()}>创建</Button>
      </div>}>
      <div>
        <Form form={form} layout="vertical" requiredMark>
          <div className="eln-create-grid">
            <Form.Item className="eln-create-primary-field" name="experimentNo" label="实验编号" rules={[{ required: true, message: '请输入实验编号' }, { max: 100 }]}><Input placeholder="请输入实验编号" /></Form.Item>
            <Form.Item className="eln-create-primary-field" name="title" label="实验名称" rules={[{ required: true, whitespace: true, message: '请输入实验名称' }, { max: 300 }]}><Input placeholder="请输入实验名称" /></Form.Item>
            <Form.Item name="ownerName" label="实验人" rules={[{ required: true, whitespace: true, message: '请输入实验人' }]}><Input placeholder="请输入实验人" /></Form.Item>
            <Form.Item name="experimentDate" label="日期" rules={[{ required: true, message: '请选择日期' }]}><DatePicker style={{ width: '100%' }} format="YYYY/MM/DD" /></Form.Item>
          </div>
          <div className="eln-relation-grid">
            <Form.Item name="projectId" label="关联项目">
              <Select
                disabled
                options={[{ value: projectId, label: `${stage.projectCode} · ${stage.projectName}` }]}
              />
            </Form.Item>
            <Form.Item name="stageId" label="阶段">
              <Select
                disabled
                options={[stage, ...stages.filter((item) => item.id !== stage.id)]
                  .map((item) => ({ value: item.id, label: item.name }))}
              />
            </Form.Item>
            <Form.Item name="taskId" label="任务">
              <Select
                disabled
                options={experimentTasks.map((item) => ({ value: item.id, label: item.name }))}
              />
            </Form.Item>
          </div>
          <>
            <Choice label="选择新建" options={[{ key: 'blank', title: '空白新建', desc: '选择 Word 或 Excel 创建空白实验' }, { key: 'template', title: '选择模板新建', desc: '复制已发布模板形成独立实验副本' }]} value={createMode} onChange={(value) => changeCreateMode(value as CreateMode)} />
            {createMode === 'template' ? <Form.Item name="templateVersionId" label="选择模板" rules={[{ required: true, message: '请选择实验模板' }]}><Select showSearch loading={templatesLoading} placeholder="请选择已发布模板" optionFilterProp="label" options={templates.map((item) => ({ value: item.versionId, label: `${item.name}（${item.format === 'XLSX' ? 'Excel' : 'Word'} · V${item.versionNo}）` }))} notFoundContent={templatesLoading ? '加载中' : '暂无已发布模板'} /></Form.Item> : <Choice label="文档格式" options={[{ key: 'word', title: 'Word 实验', desc: '正文、章节与文档结构编辑' }, { key: 'excel', title: 'Excel 实验', desc: '表格、单元格与字段结构编辑' }]} value={format} onChange={(value) => setFormat(value as DocumentFormat)} />}
          </>
        </Form>
      </div>
    </Modal>

    <Modal rootClassName="pm-task-modal" width="min(560px,92vw)" title={editingTask ? '编辑项目任务' : '新增项目任务'} open={taskOpen}
      onCancel={() => setTaskOpen(false)} destroyOnHidden footer={<div className="pm-experiment-footer">
        <Button onClick={() => setTaskOpen(false)}>取消</Button>
        <Button type="primary" loading={taskSaving} onClick={() => void saveTask()}>{editingTask ? '保存' : '创建'}</Button>
      </div>}>
      <div className="pm-task-form-body">
        <Form form={taskForm} layout="vertical" requiredMark={false}>
          <Form.Item name="stageId" label={<FieldLabel text="关联阶段" required />} rules={[{ required: true, message: '请选择阶段' }]}>
            <Select placeholder="请选择阶段" options={stages.map((s) => ({ value: s.id, label: s.name }))} />
          </Form.Item>
          <Form.Item name="name" label={<FieldLabel text="任务名称" required />} rules={[{ required: true, message: '请输入任务名称' }, { max: 300 }]}>
            <Input placeholder="请输入任务名称" />
          </Form.Item>
          <Form.Item name="owner" label={<FieldLabel text="负责人" />}>
            <Input placeholder="请输入负责人" />
          </Form.Item>
          <Form.Item name="priority" label={<FieldLabel text="优先级" required />} rules={[{ required: true, message: '请选择优先级' }]}>
            <Select placeholder="请选择优先级" options={projectPriorities} />
          </Form.Item>
          <Form.Item name="plannedDate" label={<FieldLabel text="计划日期" required />} rules={[{ required: true, message: '请选择计划日期' }]}>
            <DatePicker style={{ width: '100%' }} format="YYYY/MM/DD" />
          </Form.Item>
          <Form.Item name="status" label={<FieldLabel text="状态" required />} rules={[{ required: true }]}>
            <Select options={Object.entries(taskStatuses).map(([value, label]) => ({ value, label }))} />
          </Form.Item>
        </Form>
      </div>
    </Modal>
  </div>;
}

function FieldLabel({ text, kind, required }: { text: string; kind?: string; required?: boolean }) {
  return <span>{text}{required && <i className="pm-required"> *</i>}{kind && <small className="pm-field-kind">{kind}</small>}</span>;
}

function Choice({ label, options, value, onChange }: { label: string; options: Array<{ key: string; title: string; desc: string }>; value: string; onChange: (value: string) => void }) {
  return <div className="eln-choice-section"><label><i>*</i>{label}</label><div>{options.map((option, index) => <button type="button" className={value === option.key ? 'active' : ''} onClick={() => onChange(option.key)} key={option.key}><span>{index === 0 ? '▣' : '↪'}</span><b>{option.title}<small>{option.desc}</small></b></button>)}</div></div>;
}
