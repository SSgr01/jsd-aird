import { ArrowLeftOutlined, ArrowRightOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import { Alert, Button, DatePicker, Empty, Form, Input, Modal, Select, Skeleton, Space, Tag, message } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import dayjs from '@/utils/dayjs';
import { ProjectTaskBoard } from './ProjectTaskBoard';

import {
  createStage,
  formatStageStatus,
  getProjectStages,
  reorderStages,
  stageStatuses,
  updateStage,
  type ProjectStage,
  type StageInput,
  type StageStatus,
} from '@/services/project/project-api';

interface StageFormValues {
  name: string;
  stageCode?: string;
  status: StageStatus;
  owner?: string;
  dates?: [dayjs.Dayjs, dayjs.Dayjs];
  description?: string;
  transitionReason?: string;
}

export function ProjectStageBoard({ projectId }: { projectId: string }) {
  const [stages, setStages] = useState<ProjectStage[]>([]);
  const [selectedStageId, setSelectedStageId] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const [editing, setEditing] = useState<ProjectStage>();
  const [open, setOpen] = useState(false);
  const [dragId, setDragId] = useState<string>();
  const [searchParams] = useSearchParams();
  const focusedId = searchParams.get('stageId');
  const focusedRef = useRef<HTMLDivElement>(null);
  const [form] = Form.useForm<StageFormValues>();
  const [messageApi, holder] = message.useMessage();

  const load = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      const data = await getProjectStages(projectId);
      setStages(data);
      setSelectedStageId((value) => value && data.some(({ id }) => id === value) ? value : data[0]?.id);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '阶段加载失败');
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (focusedId && typeof focusedRef.current?.scrollIntoView === 'function') {
      focusedRef.current.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  }, [focusedId, stages]);

  const openCreate = () => {
    setEditing(undefined);
    form.resetFields();
    form.setFieldsValue({ status: 'PENDING' });
    setOpen(true);
  };

  const openEditSelected = () => {
    const stage = stages.find(({ id }) => id === selectedStageId);
    if (!stage) return;
    setEditing(stage);
    form.resetFields();
    form.setFieldsValue({
      name: stage.name,
      stageCode: stage.stageCode,
      status: stage.status,
      owner: stage.owner,
      dates:
        stage.plannedStart && stage.plannedEnd
          ? [dayjs(stage.plannedStart), dayjs(stage.plannedEnd)]
          : undefined,
      description: stage.description,
    });
    setOpen(true);
  };

  const submit = async () => {
    const values = await form.validateFields();
    const input: StageInput = {
      name: values.name.trim(), stageCode: values.stageCode?.trim() || undefined, status: values.status,
      owner: values.owner?.trim() || undefined, description: values.description?.trim() || undefined,
      plannedStart: values.dates?.[0].format('YYYY-MM-DD'), plannedEnd: values.dates?.[1].format('YYYY-MM-DD'),
      transitionReason: values.transitionReason?.trim() || undefined, version: editing?.version,
    };
    setSaving(true);
    try {
      if (editing) await updateStage(editing.id, input); else await createStage(projectId, input);
      setOpen(false);
      messageApi.success(editing ? '阶段已更新' : '阶段已创建');
      await load();
    } catch (reason) {
      messageApi.error(reason instanceof Error ? reason.message : '阶段保存失败');
    } finally { setSaving(false); }
  };

  const saveOrder = async (next: ProjectStage[]) => {
    const previous = stages;
    setStages(next);
    try { setStages(await reorderStages(projectId, next)); }
    catch (reason) { setStages(previous); messageApi.error(reason instanceof Error ? reason.message : '排序失败，请刷新后重试'); }
  };

  const dropOn = (targetId: string) => {
    if (!dragId || dragId === targetId) return;
    const from = stages.findIndex(({ id }) => id === dragId);
    const to = stages.findIndex(({ id }) => id === targetId);
    if (from < 0 || to < 0) return;
    const next = [...stages];
    const [item] = next.splice(from, 1);
    if (!item) return;
    next.splice(to, 0, item);
    setDragId(undefined);
    void saveOrder(next);
  };

  const moveSelected = (direction: -1 | 1) => {
    const index = stages.findIndex(({ id }) => id === selectedStageId);
    const target = index + direction;
    if (index < 0 || target < 0 || target >= stages.length) return;
    const next = [...stages];
    const [item] = next.splice(index, 1);
    if (!item) return;
    next.splice(target, 0, item);
    void saveOrder(next);
  };

  return (
    <section className="pm-stage-overview" aria-label="项目阶段预览">
      {holder}
      <div className="pm-stage-overview-head">
        <div className="pm-stage-overview-title"><strong>项目预览图</strong><span>拖拽或使用左右按钮调整顺序</span></div>
        <Space.Compact>
          <Button
            icon={<ArrowLeftOutlined />}
            disabled={!selectedStageId || stages.findIndex(({ id }) => id === selectedStageId) <= 0}
            onClick={() => moveSelected(-1)}
          >
            左移
          </Button>
          <Button
            icon={<ArrowRightOutlined />}
            disabled={!selectedStageId || stages.findIndex(({ id }) => id === selectedStageId) < 0 || stages.findIndex(({ id }) => id === selectedStageId) >= stages.length - 1}
            onClick={() => moveSelected(1)}
          >
            右移
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增阶段</Button>
          <Button icon={<EditOutlined />} disabled={!selectedStageId} onClick={openEditSelected}>
            编辑阶段
          </Button>
        </Space.Compact>
      </div>
      {loading ? <div className="pm-stage-loading"><Skeleton active paragraph={{ rows: 2 }} /></div> : null}
      {!loading && error ? <Alert type="error" showIcon message="阶段加载失败" description={error} action={<Button onClick={() => void load()}>重试</Button>} /> : null}
      {!loading && !error && !stages.length ? <Empty className="pm-stage-empty" description="暂无阶段" /> : null}
      {!loading && !error && stages.length ? (
        <div className="pm-stage-flow">
          {stages.map((stage, index) => (
            <div className="pm-stage-wrap" key={stage.id}>
              <div
                ref={stage.id === focusedId ? focusedRef : undefined}
                className={`pm-stage-card${stage.id === focusedId ? ' pm-stage-focused' : ''}${stage.id === selectedStageId ? ' pm-stage-selected' : ''}`}
                draggable onDragStart={() => setDragId(stage.id)} onDragOver={(event) => event.preventDefault()}
                onDrop={() => dropOn(stage.id)}
                onClick={() => setSelectedStageId(stage.id)}
              >
                <div className="pm-stage-card-head"><strong title={stage.name}>{stage.name}</strong><Tag>阶段{stage.orderNo}</Tag></div>
                <div className="pm-stage-card-body">
                  <span className="pm-stage-status">
                    <span className={`pm-dot pm-dot-${stage.status.toLowerCase()}`} />
                    {formatStageStatus(stage.status)}
                  </span>
                  <b>{stage.taskCount}任务</b>
                </div>
                <div className="pm-stage-card-stats">
                  <span>{stage.experimentCount ?? 0}实验</span>
                  <span>{stage.materialCount ?? 0}资料</span>
                </div>
              </div>
              {index < stages.length - 1 ? <span className="pm-stage-arrow">→</span> : null}
            </div>
          ))}
        </div>
      ) : null}
      {stages.find(({ id }) => id === selectedStageId) ? (
        <ProjectTaskBoard projectId={projectId} stage={stages.find(({ id }) => id === selectedStageId)!} />
      ) : null}

      <Modal title={editing ? '编辑阶段' : '新增阶段'} open={open} confirmLoading={saving}
        onCancel={() => setOpen(false)} onOk={() => void submit()} okText="保存" cancelText="取消" destroyOnHidden>
        <Form form={form} layout="vertical" preserve={false}>
          <div className="pm-stage-form-grid">
            <Form.Item name="name" label="阶段名称" rules={[{ required: true, message: '请输入阶段名称' }, { max: 120 }]}><Input /></Form.Item>
            <Form.Item name="stageCode" label="阶段编号" tooltip="留空时系统自动按项目编码生成"><Input maxLength={40} placeholder="如 STG-REQ-001" /></Form.Item>
            <Form.Item name="status" label="阶段状态" rules={[{ required: true }]}><Select options={stageStatuses} /></Form.Item>
            <Form.Item name="owner" label="负责人"><Input maxLength={100} /></Form.Item>
            <Form.Item name="dates" label="计划日期"><DatePicker.RangePicker style={{ width: '100%' }} /></Form.Item>
          </div>
          {editing?.status === 'COMPLETED' ? <Form.Item name="transitionReason" label="重新打开原因"><Input.TextArea rows={2} /></Form.Item> : null}
          <Form.Item name="description" label="阶段说明"><Input.TextArea rows={4} maxLength={5000} showCount /></Form.Item>
        </Form>
      </Modal>
    </section>
  );
}
