import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  LoadingOutlined,
  SaveOutlined,
  SendOutlined,
} from '@ant-design/icons';
import {
  Alert,
  App,
  Button,
  Descriptions,
  Empty,
  Input,
  Result,
  Skeleton,
  Space,
  Spin,
  Steps,
  Tag,
  Typography,
} from 'antd';
import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { readFieldModel } from '@/features/template-workspace/field-model';
import { getAtPath, setAtPath } from '@/features/template-workspace/path-utils';
import {
  resolveBindingSelection,
  selectionCycleKey,
} from '@/features/template-workspace/selection-resolver';
import type {
  EditorHandle,
  EditorSelection,
  TemplateBinding,
} from '@/features/template-workspace/types';
import type { ProductionWorkspace } from '@/features/production-orders/types';
import { productionOrderApi } from '@/services/production-orders/production-order-api';

const SheetsEditor = lazy(async () => ({
  default: (await import('@/features/template-workspace/UniverSheetsEditor')).UniverSheetsEditor,
}));
const DocsEditor = lazy(async () => ({
  default: (await import('@/features/template-workspace/UniverDocsEditor')).UniverDocsEditor,
}));

export function ProductionWorkspacePage() {
  const { orderId } = useParams<{ orderId: string }>();
  const navigate = useNavigate();
  const { message, modal } = App.useApp();
  const editorRef = useRef<EditorHandle>(null);
  const selectionCycleRef = useRef({ key: '', index: 0 });
  const [workspace, setWorkspace] = useState<ProductionWorkspace>();
  const [snapshot, setSnapshot] = useState<Record<string, unknown>>();
  const [data, setData] = useState<Record<string, unknown>>({});
  const [selectedId, setSelectedId] = useState<string>();
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();

  useEffect(() => {
    if (!orderId) return;
    void productionOrderApi
      .getEditModel(orderId)
      .then(async (model) => {
        const loaded = model.snapshotFileId
          ? await productionOrderApi.downloadSnapshot(model.snapshotFileId)
          : {};
        setWorkspace(model);
        setData(model.data || {});
        setSnapshot(loaded);
      })
      .catch((reason) => setError(reason instanceof Error ? reason.message : '生产单加载失败'));
  }, [orderId]);

  const fieldModel = useMemo(
    () => (workspace ? readFieldModel(workspace.schema, workspace.mapping) : undefined),
    [workspace],
  );
  const selected = useMemo(
    () => workspace?.mapping.find((item) => item.bindingId === selectedId),
    [selectedId, workspace],
  );
  const selectedField = fieldModel?.fields.find((field) => field.bindingId === selectedId);
  const editable = workspace?.status === 'DRAFT';

  const onEditorValue = useCallback((binding: TemplateBinding, value: unknown) => {
    if (value !== undefined) setData((current) => setAtPath(current, binding.dataPath, value));
  }, []);

  const onSelectionChange = useCallback(
    (selection: EditorSelection) => {
      if (!workspace) return;
      const key = selectionCycleKey(selection);
      const nextIndex = selectionCycleRef.current.key === key
        ? selectionCycleRef.current.index + 1 : 0;
      const match = resolveBindingSelection(workspace.mapping, selection, nextIndex);
      if (match) {
        selectionCycleRef.current = { key, index: match.candidateIndex };
        setSelectedId(match.binding.bindingId);
      }
    },
    [workspace?.mapping],
  );

  const save = async () => {
    if (!workspace || !orderId || !editorRef.current) return false;
    setSaving(true);
    try {
      const currentSnapshot = editorRef.current.getSnapshot();
      let synchronizedData = data;
      const bindingValues = workspace.mapping.flatMap((binding) => {
        const editorValue = editorRef.current?.readBinding(binding) ?? null;
        if (binding.syncDirection === 'DATA_TO_EDITOR') return [];
        synchronizedData = setAtPath(synchronizedData, binding.dataPath, editorValue);
        return [{ dataPath: binding.dataPath, dataValue: editorValue, editorValue }];
      });
      const staged = await productionOrderApi.stageSnapshot(currentSnapshot, workspace.format);
      const result = await productionOrderApi.saveDraft(orderId, {
        lockVersion: workspace.lockVersion,
        baseWorkspaceHash: workspace.workspaceHash,
        schema: workspace.schema,
        mapping: workspace.mapping,
        data: synchronizedData,
        snapshotFileId: staged.fileId,
        snapshotHash: staged.sha256,
        editorAppVersion: 'univer-0.25.1',
        pluginManifest: 'business-editor-v2',
        snapshotFormatVersion: snapshotVersion(currentSnapshot, workspace.snapshotFormatVersion),
        bindingValues,
      });
      setWorkspace({
        ...workspace,
        lockVersion: result.lockVersion,
        workspaceHash: result.workspaceHash,
        snapshotFileId: staged.fileId,
        snapshotHash: staged.sha256,
        reconciliationRequired: result.reconciliationRequired,
      });
      setData(synchronizedData);
      setSnapshot(currentSnapshot);
      setDirty(false);
      void message.success('生产单已保存');
      return true;
    } catch (reason) {
      void message.error(reason instanceof Error ? reason.message : '保存失败');
      return false;
    } finally {
      setSaving(false);
    }
  };

  const submitConfirmed = async () => {
    if (!workspace || !orderId) return;
    if (dirty && !(await save())) return;
    await productionOrderApi.submit(orderId);
    setWorkspace({ ...workspace, status: 'SUBMITTED' });
    void message.success('生产单已提交');
  };

  const submit = () => {
    if (!workspace || !orderId) return;
    modal.confirm({
      title: '确认提交生产单？',
      content: '提交后内容将锁定，不能继续修改。',
      okText: '确认提交',
      cancelText: '继续检查',
      onOk: () => void submitConfirmed(),
    });
  };

  if (error) {
    return <Result status="error" title="生产单加载失败" subTitle={error} extra={<Button onClick={() => navigate('/production-orders/list')}>返回列表</Button>} />;
  }
  if (!workspace || !snapshot || !fieldModel) return <Skeleton active paragraph={{ rows: 14 }} />;

  return (
    <section className="workspace-shell production-workspace">
      <header className="workspace-header">
        <Space>
          <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/production-orders/list')}>返回</Button>
          <span>
            <Typography.Text strong>{workspace.orderNo}</Typography.Text>
            <small className="binding-path">{workspace.templateName}</small>
          </span>
          <Tag color={workspace.format === 'XLSX' ? 'green' : 'blue'}>{workspace.format === 'XLSX' ? 'Excel' : 'Word'}</Tag>
          <Tag color={editable ? 'processing' : workspace.status === 'SUBMITTED' ? 'success' : 'default'}>
            {editable ? '填写中' : workspace.status === 'SUBMITTED' ? '已提交' : '已取消'}
          </Tag>
        </Space>
        <Space>
          {dirty && <Typography.Text type="warning">有未保存内容</Typography.Text>}
          <Button icon={<SaveOutlined />} disabled={!editable || !dirty} loading={saving} onClick={() => void save()}>保存</Button>
          <Button type="primary" icon={<SendOutlined />} disabled={!editable || workspace.reconciliationRequired} onClick={submit}>提交生产单</Button>
        </Space>
      </header>

      <div className="production-summary">
        <Steps size="small" current={workspace.status === 'SUBMITTED' ? 2 : 1} items={[{ title: '基本信息' }, { title: '填写模板' }, { title: '提交完成' }]} />
        <Descriptions size="small" column={4} items={[
          { key: 'no', label: '生产单号', children: workspace.orderNo },
          { key: 'qty', label: '计划数量', children: workspace.quantity ? `${workspace.quantity} ${workspace.unitCode || ''}` : '—' },
          { key: 'date', label: '计划日期', children: workspace.plannedDate || '—' },
          { key: 'template', label: '模板版本', children: workspace.templateName },
        ]} />
      </div>
      {workspace.reconciliationRequired && <Alert banner type="warning" message="部分填写位置需要检查，完成后才能提交。" />}

      <div className="workspace-grid">
        <aside className="workspace-rail">
          <Typography.Text strong>填写内容</Typography.Text>
          <Typography.Paragraph type="secondary" className="rail-help">按业务分组查找，点击后会定位到模板中的填写位置。</Typography.Paragraph>
          {fieldModel.groups.map((group) => {
            const fields = fieldModel.fields.filter((field) => field.groupId === group.id && field.bindingId);
            if (!fields.length) return null;
            return (
              <section className="production-field-group" key={group.id}>
                <strong>{group.name}</strong>
                {fields.map((field) => {
                  const binding = workspace.mapping.find((item) => item.bindingId === field.bindingId);
                  if (!binding) return null;
                  return (
                    <button
                      key={field.id}
                      type="button"
                      className="binding-item"
                      aria-current={selectedId === binding.bindingId}
                      onClick={() => {
                        setSelectedId(binding.bindingId);
                        editorRef.current?.focusBinding(binding);
                      }}
                    >
                      <span>{field.name}</span>
                      <small className="binding-path">{formatValue(getAtPath(data, binding.dataPath)) || '待填写'}</small>
                    </button>
                  );
                })}
              </section>
            );
          })}
          {!fieldModel.fields.length && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="模板尚未配置填写内容" />}
        </aside>

        <div className="workspace-canvas">
          <Suspense fallback={<Spin indicator={<LoadingOutlined spin />} tip="正在打开模板" fullscreen />}>
            {workspace.format === 'XLSX' ? (
              <SheetsEditor
                ref={editorRef}
                snapshot={snapshot}
                bindings={workspace.mapping}
                editable={editable}
                onDirty={() => setDirty(true)}
                onEditorValue={onEditorValue}
                onSelectionChange={onSelectionChange}
              />
            ) : (
              <div className={editable ? undefined : 'document-readonly'}>
                <DocsEditor ref={editorRef} snapshot={snapshot} bindings={workspace.mapping} onDirty={() => setDirty(true)} onEditorValue={onEditorValue} />
              </div>
            )}
          </Suspense>
        </div>

        <aside className="workspace-panel">
          <div className="side-panel-heading">
            <Typography.Text strong>填写内容</Typography.Text>
            <Typography.Text type="secondary">输入内容会同步到模板对应位置</Typography.Text>
          </div>
          {selected && selectedField ? (
            <div className="field-editor">
              <label htmlFor="production-field-value">{selectedField.name}</label>
              {selectedField.kind === 'SCALAR' ? (
                <Input.TextArea
                  id="production-field-value"
                  value={formatValue(getAtPath(data, selected.dataPath))}
                  readOnly={!editable || selected.syncDirection === 'EDITOR_TO_DATA'}
                  autoSize={{ minRows: 4, maxRows: 12 }}
                  onChange={(event) => {
                    if (selected.syncDirection === 'EDITOR_TO_DATA') return;
                    const value = event.target.value;
                    setData((current) => setAtPath(current, selected.dataPath, value));
                    setDirty(true);
                    void editorRef.current?.writeBinding(selected, value).catch((reason) => {
                      void message.error(reason instanceof Error ? reason.message : '内容未能写入模板');
                    });
                  }}
                />
              ) : (
                <Alert
                  type="info"
                  showIcon
                  message={selectedField.kind === 'MATRIX' ? '请直接在矩阵表中填写结果' : '请直接在明细表中逐行填写'}
                  description="系统会把整片表格作为一个业务区域保存，不会要求逐个配置单元格。"
                />
              )}
              <div className="sync-ok"><CheckCircleOutlined /> 已与模板位置关联</div>
            </div>
          ) : (
            <Empty description="请从左侧选择要填写的内容" />
          )}
        </aside>
      </div>
    </section>
  );
}

function formatValue(value: unknown) {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(value);
}

function snapshotVersion(snapshot: Record<string, unknown>, fallback: number) {
  const value = snapshot.snapshotFormatVersion;
  return typeof value === 'number' && Number.isInteger(value) && value > 0 ? value : fallback;
}
