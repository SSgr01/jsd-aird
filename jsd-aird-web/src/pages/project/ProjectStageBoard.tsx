import { StatusTag } from '@/components/status/StatusTag';
import { FormModal } from '@/components/form-dialog/FormModal';
import { ArrowLeftOutlined, ArrowRightOutlined, PlusOutlined } from '@ant-design/icons';
import { Alert, Button, DatePicker, Empty, Form, Input, Popconfirm, Select, Skeleton, Space, Tag, Tooltip, message } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import dayjs from '@/utils/dayjs';
import { Can } from '@/components/auth/Can';
import { usePermission } from '@/components/auth/usePermission';
import { ProjectTaskBoard } from './ProjectTaskBoard';
import { useProjectOwnerOptions } from './useProjectOwnerOptions';

import {
  createStage,
  applyStandardStageTemplate,
  deleteStage,
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
  creationMode?: 'manual' | 'template';
  name: string;
  stageCode?: string;
  status: StageStatus;
  owner?: string;
  dates?: [dayjs.Dayjs, dayjs.Dayjs];
  description?: string;
  transitionReason?: string;
}

export function ProjectStageBoard({ projectId }: { projectId: string }) {
  const canUpdateStage = usePermission('project.stage.update');
  const [stages, setStages] = useState<ProjectStage[]>([]);
  const [selectedStageId, setSelectedStageId] = useState<string>();
  const [collapsedStageId, setCollapsedStageId] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [deletingStageId, setDeletingStageId] = useState<string>();
  const [error, setError] = useState<string>();
  const [editing, setEditing] = useState<ProjectStage>();
  const [open, setOpen] = useState(false);
  const [dragId, setDragId] = useState<string>();
  const [searchParams] = useSearchParams();
  const focusedId = searchParams.get('stageId');
  const focusedRef = useRef<HTMLDivElement>(null);
  const [form] = Form.useForm<StageFormValues>();
  const [messageApi, holder] = message.useMessage();
  const ownerOptions = useProjectOwnerOptions(open);

  const load = useCallback(async (): Promise<ProjectStage[] | undefined> => {
    setLoading(true);
    setError(undefined);
    try {
      const data = await getProjectStages(projectId);
      setStages(data);
      setSelectedStageId((value) => value && data.some(({ id }) => id === value) ? value : (focusedId && data.some(({ id }) => id === focusedId) ? focusedId : data[0]?.id));
      return data;
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '阶段加载失败');
      return undefined;
    } finally {
      setLoading(false);
    }
  }, [focusedId, projectId]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (focusedId && typeof focusedRef.current?.scrollIntoView === 'function') {
      focusedRef.current.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  }, [focusedId, stages]);

  const openCreate = () => {
    setEditing(undefined);
    form.resetFields();
    form.setFieldsValue({ status: 'PENDING', creationMode: 'manual' });
    setOpen(true);
  };

  const openEdit = (stage: ProjectStage) => {
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

  const deleteStageCard = async (stage: ProjectStage) => {
    setDeletingStageId(stage.id);
    try {
      await deleteStage(stage.id, stage.version);
      if (selectedStageId === stage.id) setSelectedStageId(undefined);
      if (collapsedStageId === stage.id) setCollapsedStageId(undefined);
      messageApi.success('阶段已删除');
      await load();
    } catch (reason) {
      messageApi.error(reason instanceof Error ? reason.message : '阶段删除失败');
    } finally {
      setDeletingStageId(undefined);
    }
  };

  const submit = async () => {
    if (!editing && form.getFieldValue('creationMode') === 'template') {
      setSaving(true);
      try {
        const created = await applyStandardStageTemplate(projectId);
        setOpen(false);
        messageApi.info(created.length ? `已创建 ${created.length} 个阶段` : '模板阶段已全部存在');
        if (created.length) await load();
      } catch (reason) {
        messageApi.error(reason instanceof Error ? reason.message : '阶段模板应用失败');
      } finally { setSaving(false); }
      return;
    }
    let values: StageFormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
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

  // 任务保存后只刷新阶段统计（任务数/实验数等），不切换当前阶段：
  // 编辑任务换了阶段也停留在用户当前查看的阶段，避免视图被自动带走。
  const handleTaskSaved = useCallback(async () => {
    await load();
  }, [load]);

  return (
    <section className="pm-stage-overview" aria-label="项目阶段预览">
      {holder}
      <div className="pm-stage-overview-head">
        <div className="pm-stage-overview-title"><strong>项目预览图</strong><span>拖拽或使用左右按钮调整顺序</span></div>
        <Space.Compact className="pm-stage-overview-actions">
          <Can permission="project.stage.update"><Button
            icon={<ArrowLeftOutlined />}
            disabled={!selectedStageId || stages.findIndex(({ id }) => id === selectedStageId) <= 0}
            onClick={() => moveSelected(-1)}
          >
            左移
          </Button></Can>
          <Can permission="project.stage.update"><Button
            icon={<ArrowRightOutlined />}
            disabled={!selectedStageId || stages.findIndex(({ id }) => id === selectedStageId) < 0 || stages.findIndex(({ id }) => id === selectedStageId) >= stages.length - 1}
            onClick={() => moveSelected(1)}
          >
            右移
          </Button></Can>
          <Can permission="project.stage.create"><Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增阶段</Button></Can>
        </Space.Compact>
      </div>
      {loading ? <div className="pm-stage-loading"><Skeleton active paragraph={{ rows: 2 }} /></div> : null}
      {!loading && error ? <Alert type="error" showIcon message="阶段加载失败" description={error} action={<Button onClick={() => void load()}>重试</Button>} /> : null}
      {!loading && !error && !stages.length ? <Empty className="pm-stage-empty" description="暂无阶段" /> : null}
      {!loading && !error && stages.length ? (
        <div className="pm-stage-flow">
          {stages.map((stage, index) => (
            <div className="pm-stage-wrap" key={stage.id}>
              <Tooltip title={stage.description?.trim() ? <span style={{ whiteSpace: 'pre-wrap' }}>{stage.description}</span> : null}>
              <div
                ref={stage.id === focusedId ? focusedRef : undefined}
                className={`pm-stage-card${stage.id === focusedId ? ' pm-stage-focused' : ''}${stage.id === selectedStageId ? ' pm-stage-selected' : ''}`}
                draggable={canUpdateStage} onDragStart={() => setDragId(stage.id)} onDragOver={(event) => { if (canUpdateStage) event.preventDefault(); }}
                onDrop={() => { if (canUpdateStage) dropOn(stage.id); }}
                onClick={() => {
                  if (stage.id === selectedStageId) {
                    setCollapsedStageId((value) => (value === stage.id ? undefined : stage.id));
                  } else {
                    setSelectedStageId(stage.id);
                    setCollapsedStageId(undefined);
                  }
                }}
              >
                <div className="pm-stage-card-head">
                  <strong>{stage.name}</strong>
                  <Tag>阶段{stage.orderNo}</Tag>
                </div>
                <div className="pm-stage-card-stats">
                  <span title={`${stage.taskCount}任务`}>{stage.taskCount}任务</span>
                  <span title={`${stage.experimentCount ?? 0}实验`}>{stage.experimentCount ?? 0}实验</span>
                  <span title={`${stage.materialCount ?? 0}资料`}>{stage.materialCount ?? 0}资料</span>
                </div>
                <div className="pm-stage-card-actions" onClick={(event) => event.stopPropagation()} onDragStart={(event) => event.stopPropagation()}>
                  <span className="pm-stage-card-status">
                    <span className={`pm-dot pm-dot-${stage.status.toLowerCase()}`} />
                    <StatusTag status={stage.status} label={formatStageStatus(stage.status)} />
                  </span>
                  <Can permission="project.stage.update">
                    <Button type="link" size="small" onClick={() => openEdit(stage)}>编辑</Button>
                  </Can>
                  <Can permission="project.stage.delete">
                    <Popconfirm
                      title="确认删除当前阶段？"
                      description="阶段下存在任务时不能删除。"
                      okText="删除"
                      cancelText="取消"
                      okButtonProps={{ danger: true, loading: deletingStageId === stage.id }}
                      onConfirm={() => void deleteStageCard(stage)}
                    >
                      <Button type="link" size="small" danger loading={deletingStageId === stage.id}>删除</Button>
                    </Popconfirm>
                  </Can>
                </div>
              </div>
              </Tooltip>
              {index < stages.length - 1 ? <span className="pm-stage-arrow">→</span> : null}
            </div>
          ))}
        </div>
      ) : null}
      {stages.find(({ id }) => id === selectedStageId) && collapsedStageId !== selectedStageId ? (
        <Can permission="project.task.view"><ProjectTaskBoard
          projectId={projectId}
          stage={stages.find(({ id }) => id === selectedStageId)!}
          stages={stages}
          onTaskSaved={handleTaskSaved}
        /></Can>
      ) : null}

      <FormModal form={form} title={editing ? '编辑阶段' : '新增阶段'} open={open} confirmLoading={saving}
        onCancel={() => setOpen(false)} onOk={() => submit()} okText="保存" cancelText="取消" destroyOnHidden>
        <Form form={form} layout="vertical" preserve={false}>
          {!editing ? <Form.Item name="creationMode" label="创建方式">
            <Select options={[{ value: 'manual', label: '手动创建' }, { value: 'template', label: '四阶段模板' }]} />
          </Form.Item> : null}
          <Form.Item noStyle shouldUpdate={(previous, current) => previous.creationMode !== current.creationMode}>
            {() => !editing && form.getFieldValue('creationMode') === 'template' ? (
              <div>实验阶段 → 发样沟通 → 中试放大 → 量产导入</div>
            ) : <>
              <div className="pm-stage-form-grid">
                <Form.Item name="name" label="阶段名称" rules={[{ required: true, message: '请输入阶段名称' }, { max: 120 }]}><Input /></Form.Item>
                <Form.Item name="stageCode" label="阶段编号" tooltip="留空时系统自动按项目编码生成"><Input maxLength={40} placeholder="如 STG-REQ-001" /></Form.Item>
                <Form.Item name="status" label="阶段状态" rules={[{ required: true }]}><Select options={stageStatuses} /></Form.Item>
                <Form.Item name="owner" label="负责人">
                  <Select showSearch allowClear loading={ownerOptions.loading} options={ownerOptions.options}
                    optionFilterProp="label" placeholder="请选择负责人"
                    notFoundContent={ownerOptions.error ? '负责人列表加载失败' : '暂无可选用户'} />
                </Form.Item>
                <Form.Item name="dates" label="计划日期"><DatePicker.RangePicker style={{ width: '100%' }} /></Form.Item>
              </div>
              {editing?.status === 'COMPLETED' ? <Form.Item name="transitionReason" label="重新打开原因"><Input.TextArea rows={2} /></Form.Item> : null}
              <Form.Item name="description" label="阶段说明"><Input.TextArea rows={4} maxLength={5000} showCount /></Form.Item>
            </>}
          </Form.Item>
        </Form>
      </FormModal>
    </section>
  );
}
