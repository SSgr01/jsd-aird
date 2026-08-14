/* eslint-disable no-irregular-whitespace */
import { Button, DatePicker, Empty, Form, Input, Modal, Select, Table, Tag, message } from 'antd';
import { FileAddOutlined, SnippetsOutlined, FileWordOutlined, FileExcelOutlined } from '@ant-design/icons';
import { useCallback, useEffect, useMemo, useState } from 'react';
import dayjs from '@/utils/dayjs';
import {
  createTaskExperiment, createProjectTask, deleteTaskExperiment, getProjectStages, getProjectTask, getStageTasks,
  getTaskExperiments, updateProjectTask, updateTaskExperiment,
  type ProjectExperiment, type ProjectStage, type ProjectTask,
} from '@/services/project/project-api';

const taskStatuses: Record<string, string> = { PENDING: '待开始', IN_PROGRESS: '进行中', COMPLETED: '已完成', CANCELLED: '已取消' };
const formatTaskStatus = (s: string) => taskStatuses[s] ?? s;
const experimentStatuses: Record<string, string> = { DRAFT: '草稿', PENDING: '待开始', IN_PROGRESS: '进行中', COMPLETED: '已完成', CANCELLED: '已取消' };
const formatExperimentStatus = (s: string) => experimentStatuses[s] ?? s;

interface ExperimentFormValues {
  experimentCode: string;
  title: string;
  category?: string;
  owner: string;
  experimentDate: dayjs.Dayjs;
}

interface TaskFormValues {
  stageId: string;
  name: string;
  owner?: string;
  plannedDate: dayjs.Dayjs;
  status: string;
}

export function ProjectTaskBoard({ projectId, stage }: { projectId: string; stage: ProjectStage }) {
  const [tasks, setTasks] = useState<ProjectTask[]>([]);
  const [selected, setSelected] = useState<ProjectTask>();
  const [experiments, setExperiments] = useState<ProjectExperiment[]>([]);
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<ExperimentFormValues>();
  const [messageApi, holder] = message.useMessage();
  const draftCode = useMemo(() => `EXP-${dayjs().format('YYYYMMDD')}-${Math.random().toString(36).slice(2, 7).toUpperCase()}`, [open]);

  const [stages, setStages] = useState<ProjectStage[]>([]);
  const [taskOpen, setTaskOpen] = useState(false);
  const [taskSaving, setTaskSaving] = useState(false);
  const [editingTask, setEditingTask] = useState<ProjectTask>();
  const [taskForm] = Form.useForm<TaskFormValues>();
  const [editingExperiment, setEditingExperiment] = useState<ProjectExperiment>();

  const totalExperiments = useMemo(
    () => tasks.reduce((sum, task) => sum + (task.experimentCount ?? 0), 0),
    [tasks],
  );

  const load = useCallback(() => getStageTasks(stage.id).then((data) => {
    setTasks(data);
    setSelected((old) => data.find(({ id }) => id === old?.id) || data[0]);
  }).catch(() => { setTasks([]); setSelected(undefined); }), [stage.id]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => { void getProjectStages(projectId).then(setStages).catch(() => setStages([])); }, [projectId]);
  useEffect(() => {
    if (selected) void getTaskExperiments(selected.id).then(setExperiments).catch(() => setExperiments([]));
    else setExperiments([]);
  }, [selected]);

  const showExperiment = () => {
    if (!selected) return;
    setEditingExperiment(undefined);
    form.setFieldsValue({ owner: selected.owner || '', experimentCode: draftCode, experimentDate: dayjs() });
    setOpen(true);
  };

  const openEditExperiment = async (experiment: ProjectExperiment) => {
    if (!selected) return;
    setEditingExperiment(experiment);
    form.setFieldsValue({
      experimentCode: experiment.experimentCode,
      title: experiment.title,
      category: experiment.category,
      owner: experiment.owner,
      experimentDate: experiment.experimentDate ? dayjs(experiment.experimentDate) : undefined,
    });
    setOpen(true);
  };

  const saveExperiment = async () => {
    if (!selected) return;
    const values = await form.validateFields();
    const payload = {
      experimentCode: values.experimentCode.trim(), title: values.title.trim(), category: values.category, owner: values.owner.trim(),
      experimentDate: values.experimentDate.format('YYYY-MM-DD'),
    };
    setSaving(true);
    try {
      if (editingExperiment) {
        await updateTaskExperiment(editingExperiment.id, { ...payload, version: editingExperiment.version });
        messageApi.success('实验已更新');
      } else {
        await createTaskExperiment(selected.id, {
          ...payload, templateName: '实验报告模板1',
          templateVersion: 'V1.0', workbookContent: JSON.stringify({
            cells: { A1: values.title, A3: '实验人', B3: values.owner, C3: '日期', D3: values.experimentDate.format('YYYY-MM-DD') },
          }),
        });
        messageApi.success('实验草稿已保存并记录日志');
      }
      setOpen(false);
      setEditingExperiment(undefined);
      form.resetFields();
      setExperiments(await getTaskExperiments(selected.id));
      void load();
    } catch (reason) {
      messageApi.error(reason instanceof Error ? reason.message : '实验保存失败');
    } finally { setSaving(false); }
  };

  const openNewTask = () => {
    setEditingTask(undefined);
    taskForm.setFieldsValue({ stageId: stage.id, owner: '', plannedDate: dayjs(), status: 'PENDING' });
    setTaskOpen(true);
  };

  const openEditTask = async (taskId: string) => {
    const data = await getProjectTask(taskId);
    setEditingTask(data);
    taskForm.setFieldsValue({
      stageId: data.stageId, name: data.name, owner: data.owner,
      plannedDate: data.plannedDate ? dayjs(data.plannedDate) : dayjs(), status: data.status || 'PENDING',
    });
    setTaskOpen(true);
  };

  const removeExperiment = (experiment: ProjectExperiment) => {
    Modal.confirm({
      title: '删除实验',
      content: `确定删除实验「${experiment.title}」吗？删除后不可恢复。`,
      okText: '删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteTaskExperiment(experiment.id, experiment.version);
          messageApi.success('实验已删除');
          setExperiments(await getTaskExperiments(experiment.taskId));
          void load();
        } catch (reason) {
          messageApi.error(reason instanceof Error ? reason.message : '删除失败');
        }
      },
    });
  };

  const saveTask = async () => {
    const values = await taskForm.validateFields();
    setTaskSaving(true);
    try {
      const payload = {
        stageId: values.stageId, name: values.name.trim(), owner: values.owner?.trim() || '',
        plannedDate: values.plannedDate.format('YYYY-MM-DD'), status: values.status,
      };
      if (editingTask) await updateProjectTask(editingTask.id, { ...payload, version: editingTask.version });
      else await createProjectTask(projectId, payload);
      messageApi.success(editingTask ? '任务已更新' : '任务已创建并记录日志');
      setTaskOpen(false);
      taskForm.resetFields();
      setEditingTask(undefined);
      void load();
    } catch (reason) {
      messageApi.error(reason instanceof Error ? reason.message : '保存失败');
    } finally { setTaskSaving(false); }
  };

  return <div className="pm-stage-work">
    {holder}
    <div className="pm-stage-work-head"><div><b>{stage.name}任务</b><small>项目 &gt; {stage.name} &gt; {tasks.length}个任务 &gt; {totalExperiments}个实验</small></div><Button type="primary" onClick={openNewTask}>＋ 新增任务</Button></div>
    <div className="pm-task-cards">{tasks.map((task) => <button key={task.id} className={selected?.id === task.id ? 'active' : ''} onClick={() => setSelected(task)}><div className="pm-task-card-head"><b title={task.name}>{task.name}</b><span className="pm-task-card-edit" onClick={(e) => { e.stopPropagation(); void openEditTask(task.id); }}>编辑</span></div><div className="pm-task-card-body"><span>{task.owner || '未设置'}</span><Tag>{task.experimentCount}实验</Tag></div><div className="pm-task-card-status"><span className={`pm-dot pm-dot-${task.status.toLowerCase()}`} />{formatTaskStatus(task.status)}</div></button>)}</div>
    {selected ? <><div className="pm-stage-work-head"><b>当前任务：{selected.name}</b><Button type="primary" onClick={showExperiment}>＋ 新增实验</Button></div><Table rowKey="id" pagination={false} dataSource={experiments} columns={[{ title: '实验编号', dataIndex: 'experimentCode' }, { title: '实验名称', dataIndex: 'title' }, { title: '日期', dataIndex: 'experimentDate' }, { title: '负责人', dataIndex: 'owner' }, { title: '状态', dataIndex: 'status', render: (status: string) => formatExperimentStatus(status) }, { title: '操作', key: 'action', render: (_, experiment) => <><Button type="link" size="small" onClick={() => void openEditExperiment(experiment)}>编辑</Button><Button type="link" size="small" danger onClick={() => removeExperiment(experiment)}>删除</Button></> }]} /></> : <Empty description="当前阶段暂无任务" />}

    <Modal rootClassName="pm-experiment-modal" width="46vw" title={editingExperiment ? '编辑实验' : '新增实验'} open={open} closable
      onCancel={() => setOpen(false)} destroyOnClose footer={<div className="pm-experiment-footer">
        <Button onClick={() => setOpen(false)}>取消</Button>
        <Button type="primary" loading={saving} onClick={() => void saveExperiment()}>{editingExperiment ? '保存' : '创建并进入编辑'}</Button>
      </div>}>
      <div className="pm-experiment-body">
        <Form form={form} layout="vertical" requiredMark={false}>
          <div className="pm-experiment-grid">
            <Form.Item name="experimentCode" label={<FieldLabel text="实验编号" required />} rules={[{ required: true, message: '请输入实验编号' }, { max: 100 }]}><Input placeholder="请输入实验编号" /></Form.Item>
            <Form.Item name="title" label={<FieldLabel text="实验名称" required />} rules={[{ required: true, message: '请输入实验名称' }, { max: 300 }]}><Input placeholder="请输入实验名称" /></Form.Item>
            <Form.Item name="owner" label={<FieldLabel text="实验人" required />} rules={[{ required: true, message: '请输入实验人' }]}><Input placeholder="请输入实验人" /></Form.Item>
            <Form.Item name="experimentDate" label={<FieldLabel text="日期" required />} rules={[{ required: true, message: '请选择日期' }]}><DatePicker style={{ width: '100%' }} format="YYYY/MM/DD" /></Form.Item>
            <Form.Item className="pm-grid-full" label={<FieldLabel text="选择新建" required />}>
              <div className="pm-new-option-cards">
                <button type="button" className="pm-new-option-card pm-new-option-active"><div className="pm-new-option-info"><div className="pm-new-option-title"><FileAddOutlined />空白新建</div><small>选择 Word 或 Excel 创建空白实验</small></div></button>
                <button type="button" className="pm-new-option-card"><div className="pm-new-option-info"><div className="pm-new-option-title"><SnippetsOutlined />选择模版新建</div><small>复制已发布模版形成独立实验副本</small></div></button>
              </div>
            </Form.Item>
            <Form.Item className="pm-grid-full" label={<FieldLabel text="文档格式" required />}>
              <div className="pm-new-option-cards">
                <button type="button" className="pm-new-option-card pm-new-option-active"><div className="pm-new-option-info"><div className="pm-new-option-title"><FileWordOutlined />Word 实验</div><small>正文、章节与文档结构编辑</small></div></button>
                <button type="button" className="pm-new-option-card"><div className="pm-new-option-info"><div className="pm-new-option-title"><FileExcelOutlined />Excel 实验</div><small>表格、单元格与字段结构编辑</small></div></button>
              </div>
            </Form.Item>
          </div>
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
