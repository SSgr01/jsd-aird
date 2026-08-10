import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  DownOutlined,
  LoadingOutlined,
  PlusOutlined,
  SaveOutlined,
  SendOutlined,
} from '@ant-design/icons';
import {
  Alert,
  App,
  Button,
  Descriptions,
  Dropdown,
  Empty,
  Form,
  Input,
  Result,
  Modal,
  Select,
  Skeleton,
  Space,
  Spin,
  Steps,
  Table,
  Tag,
  Typography,
} from 'antd';
import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';

import { readFieldModel, writeFieldModel } from '@/features/template-workspace/field-model';
import { createCustomFieldWorkspace } from '@/features/template-workspace/custom-field-operations';
import { getAtPath, setAtPath } from '@/features/template-workspace/path-utils';
import {
  buildRepeatDisplay,
  displayCellValue,
  isMeaningfulValue,
  type RepeatDisplayColumn,
} from '@/features/template-workspace/repeat-data';
import { synchronizeStructuredData } from '@/features/template-workspace/structured-data';
import { migrateWorkspaceStructure } from '@/features/template-workspace/structure-migration';
import {
  resolveBindingSelection,
  selectionCycleKey,
} from '@/features/template-workspace/selection-resolver';
import type {
  EditorHandle,
  EditorSelection,
  BusinessField,
  TemplateBinding,
  WorkbookStructureOperation,
} from '@/features/template-workspace/types';
import type { ProductionWorkspace } from '@/features/production-orders/types';
import {
  productionOrderApi,
  type ProductionIngestJob,
} from '@/services/production-orders/production-order-api';

const DocsEditor = lazy(async () => ({
  default: (await import('@/features/template-workspace/UniverDocsEditor')).UniverDocsEditor,
}));

const SheetsEditor = lazy(async () => ({
  default: (await import('@/features/template-workspace/UniverSheetsEditor')).UniverSheetsEditor,
}));

export function ProductionWorkspacePage() {
  const { orderId } = useParams<{ orderId: string }>();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
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
  const [lastSelection, setLastSelection] = useState<EditorSelection>();
  const [customFieldOpen, setCustomFieldOpen] = useState(false);
  const [ingestJob, setIngestJob] = useState<ProductionIngestJob>();
  const [ingestPreview, setIngestPreview] = useState('');
  const [revisions, setRevisions] = useState<Array<{ revisionId: string; revisionNo: number; status: string; createdAt: string; dataHash: string }>>([]);
  const [confirmingIngest, setConfirmingIngest] = useState(false);
  const [expandedRepeatIds, setExpandedRepeatIds] = useState<Record<string, boolean>>({});
  const [customForm] = Form.useForm<{
    kind: 'SCALAR' | 'REPEAT_FIELD' | 'MATRIX_FIELD';
    parentFieldId?: string;
    name: string;
    valueType: string;
    labelRange?: string;
    valueRange: string;
  }>();

  const loadWorkspace = useCallback(async () => {
    if (!orderId) return;
    const model = await productionOrderApi.getEditModel(orderId);
    const loaded = model.snapshotFileId
      ? await productionOrderApi.downloadSnapshot(model.snapshotFileId)
      : {};
    setWorkspace(model);
    setRevisions(await productionOrderApi.listRevisions(orderId).catch(() => []));
    setData(model.data || {});
    setSnapshot(loaded);
  }, [orderId]);

  useEffect(() => {
    void loadWorkspace().catch((reason) =>
      setError(reason instanceof Error ? reason.message : '生产单加载失败'));
  }, [loadWorkspace]);

  useEffect(() => {
    const jobId = searchParams.get('ingestJobId');
    if (!orderId || !jobId) return;
    void productionOrderApi.getIngestJob(orderId, jobId).then((job) => {
      setIngestJob(job);
      setIngestPreview(JSON.stringify(job.result?.data ?? {}, null, 2));
    }).catch((reason) => void message.error(
      reason instanceof Error ? reason.message : '导入预览加载失败'));
  }, [message, orderId, searchParams]);

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
  const structuralParentIds = useMemo(
    () => new Set(
      (workspace?.mapping || [])
        .map((binding) => binding.parentBindingId)
        .filter((value): value is string => Boolean(value)),
    ),
    [workspace?.mapping],
  );
  const selectedRepeat = useMemo(() => {
    if (!workspace || !fieldModel || !selected || !selectedField) return undefined;
    const parentField = findRepeatParentField(selectedField, selected, fieldModel.fields);
    if (!parentField) return undefined;
    const parentBinding = workspace.mapping.find((binding) => binding.bindingId === parentField.bindingId);
    if (!parentBinding) return undefined;
    const columns = repeatColumns(parentField, fieldModel.fields, workspace.mapping, data);
    const maxRows = Number(parentBinding.termination?.maxRecords);
    return {
      parentField,
      parentBinding,
      columns,
      model: buildRepeatDisplay(columns, {
        maxRows: Number.isFinite(maxRows) && maxRows > 0 ? maxRows : undefined,
        expanded: Boolean(expandedRepeatIds[parentField.id]),
      }),
    };
  }, [data, expandedRepeatIds, fieldModel, selected, selectedField, workspace]);
  const selectedMatrix = useMemo(() => {
    if (!workspace || !fieldModel || !selected || !selectedField) return undefined;
    if (selectedField.kind !== 'MATRIX' && !['MATRIX_REGION', 'MATRIX_FIELD'].includes(selected.mappingKind || '')) {
      return undefined;
    }
    const parentField = findMatrixParentField(selectedField, selected, fieldModel.fields);
    if (!parentField) return undefined;
    const parentBinding = workspace.mapping.find((binding) => binding.bindingId === parentField.bindingId);
    if (!parentBinding) return undefined;
    return { parentField, parentBinding };
  }, [fieldModel, selected, selectedField, workspace]);

  const onEditorValue = useCallback((binding: TemplateBinding, value: unknown) => {
    if (value === undefined || structuralParentIds.has(binding.bindingId)) return;
    setData((current) => {
      const previous = getAtPath(current, binding.dataPath);
      return sameValue(previous, value) ? current : setAtPath(current, binding.dataPath, value);
    });
  }, [structuralParentIds]);

  const onSelectionChange = useCallback(
    (selection: EditorSelection) => {
      if (!workspace) return;
      setLastSelection(selection);
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

  const addOrderField = async () => {
    if (!workspace || !fieldModel || !orderId) return;
    const values = await customForm.validateFields();
    const parentField = values.parentFieldId
      ? fieldModel.fields.find((field) => field.id === values.parentFieldId)
      : undefined;
    const parentBinding = parentField?.bindingId
      ? workspace.mapping.find((binding) => binding.bindingId === parentField.bindingId)
      : undefined;
    try {
      const created = createCustomFieldWorkspace(
        workspace.schema,
        fieldModel,
        workspace.mapping,
        {
          ownerId: orderId,
          origin: 'ORDER_LOCAL',
          kind: values.kind,
          name: values.name,
          valueType: values.valueType,
          parentField,
          parentBinding,
          sheet: lastSelection,
          labelRange: values.labelRange,
          valueRange: values.valueRange,
        },
      );
      setWorkspace({ ...workspace, schema: created.schema, mapping: created.mapping });
      setSelectedId(created.binding.bindingId);
      if (values.labelRange) {
        await editorRef.current?.writeLabel?.(created.binding, created.field.name);
      }
      setCustomFieldOpen(false);
      customForm.resetFields();
      setDirty(true);
      void message.success('订单自定义字段已添加');
    } catch (reason) {
      void message.error(reason instanceof Error ? reason.message : '字段添加失败');
    }
  };

  const save = async () => {
    if (!workspace || !orderId || !editorRef.current) return false;
    setSaving(true);
    try {
      const currentSnapshot = editorRef.current.getSnapshot();
      const synchronized = synchronizeStructuredData(
        data,
        workspace.mapping,
        (binding) => editorRef.current?.readBinding(binding),
      );
      const synchronizedData = synchronized.data;
      const bindingValues = synchronized.bindingValues;
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

  const handleStructureChange = useCallback((operation: WorkbookStructureOperation) => {
    if (!workspace || !fieldModel) return;
    const migrated = migrateWorkspaceStructure(workspace.mapping, fieldModel, operation);
    setWorkspace({
      ...workspace,
      mapping: migrated.mapping,
      schema: writeFieldModel(workspace.schema, migrated.model),
    });
    setDirty(true);
  }, [fieldModel, workspace]);

  const appendMatrixMember = async () => {
    if (!selected || selected.mappingKind !== 'MATRIX_REGION') return;
    try {
      await editorRef.current?.appendRepeatRecord?.(selected);
      setDirty(true);
      void message.success('已新增矩阵成员位置，请填写成员名称和数据');
    } catch (reason) {
      void message.error(reason instanceof Error ? reason.message : '矩阵成员新增失败');
    }
  };

  const confirmIngest = async () => {
    if (!workspace || !orderId || !ingestJob) return;
    let correctedData: Record<string, unknown>;
    try {
      const parsed: unknown = JSON.parse(ingestPreview);
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error();
      correctedData = parsed as Record<string, unknown>;
    } catch {
      void message.error('修正后的实例数据不是有效的 JSON 对象');
      return;
    }
    setConfirmingIngest(true);
    try {
      await productionOrderApi.confirmIngestJob(orderId, ingestJob.id, {
        baseWorkspaceHash: workspace.workspaceHash,
        lockVersion: workspace.lockVersion,
        resultVersion: ingestJob.resultVersion,
        selectedTemplateVersionId: ingestJob.selectedTemplateVersionId,
        correctedData,
      });
      setIngestJob(undefined);
      setSearchParams({}, { replace: true });
      await loadWorkspace();
      void message.success('导入结果已写入生产单草稿，请继续检查后再提交');
    } catch (reason) {
      void message.error(reason instanceof Error ? reason.message : '导入确认失败');
    } finally {
      setConfirmingIngest(false);
    }
  };

  const reselectIngestTemplate = async (templateVersionId: string) => {
    if (!orderId || !ingestJob) return;
    setConfirmingIngest(true);
    try {
      const next = await productionOrderApi.createIngestJob(orderId, {
        sourceType: ingestJob.sourceType,
        sourceFileIds: ingestJob.sourceFileIds,
        requestedTemplateVersionId: templateVersionId,
      });
      setIngestJob(next);
      setIngestPreview(JSON.stringify(next.result?.data ?? {}, null, 2));
      setSearchParams({ ingestJobId: next.id }, { replace: true });
    } catch (reason) {
      void message.error(reason instanceof Error ? reason.message : '候选模板重新抽取失败');
    } finally {
      setConfirmingIngest(false);
    }
  };

  const exportProduction = async (revisionId?: string) => {
    if (!workspace || !orderId) return;
    if (dirty && !(await save())) return;
    try {
      const checked = await productionOrderApi.checkExport(orderId, workspace.format, revisionId);
      const download = async () => {
        await productionOrderApi.exportOffice(orderId, workspace.format, revisionId);
        void message.success('生产单文件已开始下载');
      };
      if (checked.warnings.length) {
        modal.confirm({
          title: `导出包含 ${checked.warnings.length} 个提示`,
          content: checked.warnings.slice(0, 5).map((item) => item.message).join('；'),
          okText: '继续导出', cancelText: '取消',
          onOk: () => download().catch((error) => void message.error(error instanceof Error ? error.message : '生产单导出失败')),
        });
      } else await download();
    } catch (reason) {
      void message.error(reason instanceof Error ? reason.message : '生产单导出失败');
    }
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
          <Button
            icon={<PlusOutlined />}
            disabled={!editable || workspace.format !== 'XLSX'}
            onClick={() => {
              customForm.setFieldsValue({
                kind: 'SCALAR',
                valueType: 'string',
                valueRange: lastSelection?.address || '',
              });
              setCustomFieldOpen(true);
            }}
          >
            新增自定义字段
          </Button>
          {selected?.mappingKind === 'MATRIX_REGION' && <Button
            icon={<PlusOutlined />}
            disabled={!editable}
            onClick={() => void appendMatrixMember()}
          >新增矩阵成员</Button>}
          <Dropdown
            menu={{
              items: [
                ...(editable ? [{ key: 'draft', label: '导出当前草稿' }] : []),
                ...(!editable && revisions.length ? [{ key: 'latest', label: `导出最新提交（修订 ${revisions.at(0)?.revisionNo ?? ''}）` }] : []),
                ...(revisions.length > 1 ? revisions.map((revision) => ({ key: revision.revisionId, label: `导出历史修订 ${revision.revisionNo}` })) : []),
              ],
              onClick: ({ key }) => void exportProduction(key === 'draft' || key === 'latest' ? undefined : key),
            }}
          >
            <Button icon={<DownOutlined />}>导出 {workspace.format === 'XLSX' ? 'Excel' : 'Word'}</Button>
          </Dropdown>
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
            const displayFields = fields.filter((field) => !field.parentFieldId);
            if (!displayFields.length) return null;
            return (
              <section className="production-field-group" key={group.id}>
                <strong>{group.name}</strong>
                {displayFields.map((field) => {
                  const binding = workspace.mapping.find((item) => item.bindingId === field.bindingId);
                  if (!binding) return null;
                  return (
                    <button
                      key={field.id}
                      type="button"
                      className="binding-item"
                      aria-current={selectedId === binding.bindingId || selectedField?.parentFieldId === field.id}
                      onClick={() => {
                        setSelectedId(binding.bindingId);
                        editorRef.current?.focusBinding(binding);
                      }}
                    >
                      <span>{field.name}</span>
                      <small className="binding-path">
                        {fieldSummary(field, binding, fieldModel.fields, workspace.mapping, data)}
                      </small>
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
                onStructureChange={handleStructureChange}
              />
            ) : (
              <div className={editable ? undefined : 'document-readonly'}>
                <DocsEditor
                  ref={editorRef}
                  snapshot={snapshot}
                  bindings={workspace.mapping}
                  editable={editable}
                  onDirty={() => setDirty(true)}
                  onEditorValue={onEditorValue}
                />
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
              <label htmlFor="production-field-value">
                {selectedRepeat?.parentField.name || selectedMatrix?.parentField.name || selectedField.name}
              </label>
              {selectedRepeat ? (
                <RepeatPreview
                  model={selectedRepeat.model}
                  columns={selectedRepeat.columns}
                  expanded={Boolean(expandedRepeatIds[selectedRepeat.parentField.id])}
                  onToggle={() => setExpandedRepeatIds((current) => ({
                    ...current,
                    [selectedRepeat.parentField.id]: !current[selectedRepeat.parentField.id],
                  }))}
                  onFocus={() => editorRef.current?.focusBinding(selectedRepeat.parentBinding)}
                />
              ) : selectedField.kind === 'SCALAR' ? (
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
              ) : selectedMatrix ? (
                <MatrixPreview
                  value={getAtPath(data, selectedMatrix.parentBinding.dataPath)}
                  binding={selectedMatrix.parentBinding}
                  onFocus={() => editorRef.current?.focusBinding(selectedMatrix.parentBinding)}
                />
              ) : (
                <Alert
                  type="info"
                  showIcon
                  message="请直接在表格中填写结果"
                  description="右侧展示会随 Excel 内容同步，保存时按结构化业务数据保存。"
                />
              )}
              <div className="sync-ok"><CheckCircleOutlined /> 已与模板位置关联</div>
            </div>
          ) : (
            <Empty description="请从左侧选择要填写的内容" />
          )}
        </aside>
      </div>

      <Modal
        title="新增订单自定义字段"
        open={customFieldOpen}
        okText="添加字段"
        cancelText="取消"
        onOk={() => void addOrderField()}
        onCancel={() => setCustomFieldOpen(false)}
      >
        <Form form={customForm} layout="vertical" preserve={false}>
          <Form.Item name="kind" label="字段位置类型" rules={[{ required: true }]}>
            <Select options={[
              { value: 'SCALAR', label: '普通单元格字段' },
              { value: 'REPEAT_FIELD', label: '明细表字段' },
            ]} />
          </Form.Item>
          <Form.Item noStyle shouldUpdate>
            {({ getFieldValue }) => getFieldValue('kind') !== 'SCALAR' ? (
              <Form.Item name="parentFieldId" label="所属结构区域" rules={[{ required: true }]}>
                <Select options={fieldModel.fields
                  .filter((field) => getFieldValue('kind') === 'REPEAT_FIELD'
                    ? ['ROW_TABLE', 'COLUMN_TABLE'].includes(field.kind)
                    : field.kind === 'MATRIX')
                  .map((field) => ({ value: field.id, label: field.name }))} />
              </Form.Item>
            ) : null}
          </Form.Item>
          <Form.Item name="name" label="字段名称" rules={[{ required: true, whitespace: true }]}>
            <Input placeholder="例如：客户备注" />
          </Form.Item>
          <Form.Item name="valueType" label="数据类型" rules={[{ required: true }]}>
            <Select options={[
              { value: 'string', label: '文本' },
              { value: 'number', label: '数值' },
              { value: 'integer', label: '整数' },
              { value: 'date', label: '日期' },
              { value: 'boolean', label: '是/否' },
            ]} />
          </Form.Item>
          <Form.Item name="labelRange" label="标签位置">
            <Input placeholder="例如 A2" />
          </Form.Item>
          <Form.Item
            name="valueRange"
            label="填写范围"
            rules={[{ required: true, message: '请选择 Excel 区域或输入坐标' }]}
            extra={lastSelection ? `当前选择：${lastSelection.sheetName} ${lastSelection.address}` : undefined}
          >
            <Input placeholder="例如 B2、E8:E20 或 E5:N5" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={ingestJob?.sourceType === 'PHOTO' ? '照片识别结果复核' : 'Excel 实例导入预览'}
        open={Boolean(ingestJob)}
        width={960}
        okText="确认写入生产单草稿"
        cancelText="稍后处理"
        okButtonProps={{
          disabled: ingestJob?.status !== 'REVIEW_REQUIRED',
          loading: confirmingIngest,
        }}
        onOk={() => void confirmIngest()}
        onCancel={() => setIngestJob(undefined)}
      >
        {ingestJob?.status === 'FAILED' ? (
          <Alert type="error" showIcon message="实例导入失败" description={ingestJob.errorMessage} />
        ) : ingestJob ? (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Alert
              type={ingestJob.items.some((item) => item.reviewStatus === 'NEEDS_REVIEW') ? 'warning' : 'success'}
              showIcon
              message={`${ingestJob.sourceType === 'XLSX' ? 'Excel' : '照片'}已抽取 ${ingestJob.items.length} 个值`}
              description={ingestJob.matchMode === 'EXACT_MANIFEST'
                ? '已通过隐藏模板清单精确匹配，全程未调用 AI。'
                : '请核对低置信度内容和明细、矩阵记录，确认后只更新当前生产单。'}
            />
            {ingestJob.result?.requiresTemplateSelection
              && (ingestJob.result.templateCandidates?.length ?? 0) > 0 && <div>
                <Typography.Text strong>相似模板候选</Typography.Text>
                <Select
                  style={{ width: '100%', marginTop: 6 }}
                  value={ingestJob.selectedTemplateVersionId}
                  loading={confirmingIngest}
                  onChange={(value: string) => void reselectIngestTemplate(value)}
                  options={ingestJob.result.templateCandidates?.map((candidate) => ({
                    value: candidate.templateVersionId,
                    label: `${candidate.templateName}（${candidate.templateCode}，匹配 ${Math.round(candidate.score * 100)}%）`,
                  }))}
                />
              </div>}
            <Table
              size="small"
              rowKey="id"
              pagination={{ pageSize: 8 }}
              dataSource={ingestJob.items}
              columns={[
                { title: '类型', dataIndex: 'itemKind', width: 90 },
                { title: '字段', dataIndex: 'fieldCode', width: 180 },
                { title: '数据路径', dataIndex: 'dataPath' },
                { title: '值', dataIndex: 'normalizedValue', render: (value: unknown) => formatValue(value) || '—' },
                { title: '置信度', dataIndex: 'confidence', width: 90, render: (value: number) => `${Math.round(value * 100)}%` },
                { title: '状态', dataIndex: 'reviewStatus', width: 110, render: (value: string) => <Tag color={value === 'NEEDS_REVIEW' ? 'orange' : 'green'}>{value === 'NEEDS_REVIEW' ? '需复核' : '已抽取'}</Tag> },
              ]}
            />
            <div>
              <Typography.Text strong>结构化实例数据（可在确认前修正）</Typography.Text>
              <Input.TextArea
                aria-label="结构化实例数据"
                value={ingestPreview}
                autoSize={{ minRows: 8, maxRows: 18 }}
                onChange={(event) => setIngestPreview(event.target.value)}
              />
            </div>
          </Space>
        ) : null}
      </Modal>
    </section>
  );
}

function formatValue(value: unknown) {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(value);
}

function sameValue(left: unknown, right: unknown) {
  return Object.is(left, right) || JSON.stringify(left) === JSON.stringify(right);
}

function findRepeatParentField(
  field: BusinessField,
  binding: TemplateBinding,
  fields: BusinessField[],
) {
  if (field.parentFieldId) {
    return fields.find((item) => item.id === field.parentFieldId);
  }
  if (['ROW_TABLE', 'COLUMN_TABLE'].includes(field.kind)) return field;
  if (binding.mappingKind === 'REPEAT_REGION') return field;
  if (binding.parentBindingId) {
    return fields.find((item) => item.bindingId === binding.parentBindingId);
  }
  return undefined;
}

function findMatrixParentField(
  field: BusinessField,
  binding: TemplateBinding,
  fields: BusinessField[],
) {
  if (field.parentFieldId) return fields.find((item) => item.id === field.parentFieldId);
  if (field.kind === 'MATRIX' || binding.mappingKind === 'MATRIX_REGION') return field;
  if (binding.parentBindingId) {
    return fields.find((item) => item.bindingId === binding.parentBindingId);
  }
  return undefined;
}

function repeatColumns(
  parentField: BusinessField,
  fields: BusinessField[],
  mapping: TemplateBinding[],
  data: Record<string, unknown>,
): RepeatDisplayColumn[] {
  const childFields = fields.filter(
    (field) => field.parentFieldId === parentField.id && field.bindingId,
  );
  if (childFields.length) {
    return childFields.map((field) => {
      const binding = mapping.find((item) => item.bindingId === field.bindingId);
      return {
        key: field.id,
        name: field.name,
        values: binding ? unknownArray(getAtPath(data, binding.dataPath)) : [],
        sequence: isSequenceField(field.name, field.fieldCode),
      };
    });
  }

  const parentBinding = mapping.find((item) => item.bindingId === parentField.bindingId);
  return mapping
    .filter((binding) => binding.parentBindingId === parentBinding?.bindingId)
    .map((binding, index) => ({
      key: binding.bindingId,
      name: stringValue(binding.diagnostic?.displayName)
        || binding.fieldCode
        || `字段 ${index + 1}`,
      values: unknownArray(getAtPath(data, binding.dataPath)),
      sequence: isSequenceField(
        stringValue(binding.diagnostic?.displayName),
        binding.fieldCode,
      ),
    }));
}

function fieldSummary(
  field: BusinessField,
  binding: TemplateBinding,
  fields: BusinessField[],
  mapping: TemplateBinding[],
  data: Record<string, unknown>,
) {
  const parent = findRepeatParentField(field, binding, fields);
  if (parent && (parent.id === field.id || field.parentFieldId)) {
    const columns = repeatColumns(parent, fields, mapping, data);
    const maxRows = Number(mapping.find((item) => item.bindingId === parent.bindingId)?.termination?.maxRecords);
    const model = buildRepeatDisplay(columns, {
      maxRows: Number.isFinite(maxRows) && maxRows > 0 ? maxRows : undefined,
    });
    return model.totalRows ? `已填 ${model.filledRows}/${model.totalRows} 行` : '待填写';
  }
  if (field.kind === 'MATRIX' || binding.mappingKind === 'MATRIX_REGION') {
    const values = matrixValues(getAtPath(data, binding.dataPath));
    const filled = values.flat().filter(isMeaningfulValue).length;
    const total = values.reduce((count, row) => count + row.length, 0);
    return total ? `已填 ${filled}/${total} 格` : '待填写';
  }
  return formatValue(getAtPath(data, binding.dataPath)) || '待填写';
}

function isSequenceField(name?: string, fieldCode?: string) {
  const value = `${name || ''} ${fieldCode || ''}`;
  return value.includes('序号') || /(?:sequence|serial|row[_ .-]?no)/i.test(value);
}

function unknownArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function stringValue(value: unknown) {
  return typeof value === 'string' ? value : '';
}

function RepeatPreview({
  model,
  columns,
  expanded,
  onToggle,
  onFocus,
}: {
  model: ReturnType<typeof buildRepeatDisplay>;
  columns: RepeatDisplayColumn[];
  expanded: boolean;
  onToggle: () => void;
  onFocus: () => void;
}) {
  const valueColumns = columns.filter((column) => !column.sequence);
  return (
    <div className="structured-field-preview">
      <div className="structured-field-toolbar">
        <Typography.Text type="secondary">
          已填 {model.filledRows} 行{model.totalRows ? ` / 共 ${model.totalRows} 行` : ''}
        </Typography.Text>
        <Space size={4}>
          {model.totalRows > model.rows.length && (
            <Button type="link" size="small" onClick={onToggle}>
              {expanded ? '收起空行' : `展开全部 ${model.totalRows} 行`}
            </Button>
          )}
          <Button type="link" size="small" onClick={onFocus}>定位 Excel</Button>
        </Space>
      </div>
      <Table
        size="small"
        rowKey="key"
        pagination={false}
        dataSource={model.rows}
        locale={{ emptyText: '暂无填写内容' }}
        scroll={{ x: 'max-content', y: 300 }}
        columns={[
          {
            title: '序号',
            dataIndex: 'index',
            width: 58,
            render: (value: number) => value,
          },
          ...valueColumns.map((column) => ({
            title: column.name,
            dataIndex: ['values', column.key],
            render: (_value: unknown, row: { index: number; values: Record<string, unknown> }) => {
              const value = row.values[column.key];
              return displayCellValue(value, column.sequence ? String(row.index) : '—');
            },
          })),
        ]}
      />
    </div>
  );
}

function MatrixPreview({
  value,
  binding,
  onFocus,
}: {
  value: unknown;
  binding: TemplateBinding;
  onFocus: () => void;
}) {
  const rows = matrixValues(value);
  const columnSlots = slotLabels(binding.locator.columnSlots);
  const rowSlots = slotLabels(binding.locator.rowSlots);
  const width = Math.max(rows.reduce((max, row) => Math.max(max, row.length), 0), columnSlots.length);
  const filled = rows.flat().filter(isMeaningfulValue).length;
  const total = rows.reduce((count, row) => count + row.length, 0);
  const columns = [
    {
      title: '行',
      dataIndex: 'rowIndex',
      width: 52,
      render: (index: number) => rowSlots[index - 1] || index,
    },
    ...Array.from({ length: width }, (_, index) => ({
      title: columnSlots[index] || `列 ${index + 1}`,
      dataIndex: `column-${index}`,
      render: (_value: unknown, row: { values: unknown[] }) => displayCellValue(row.values[index]),
    })),
  ];
  const dataSource = rows.map((row, index) => ({
    key: String(index),
    rowIndex: index + 1,
    values: row,
  }));

  return (
    <div className="structured-field-preview">
      <div className="structured-field-toolbar">
        <Typography.Text type="secondary">已填 {filled}/{total || 0} 格</Typography.Text>
        <Button type="link" size="small" onClick={onFocus}>定位 Excel</Button>
      </div>
      <Table
        size="small"
        rowKey="key"
        pagination={false}
        dataSource={dataSource}
        columns={columns}
        locale={{ emptyText: '暂无矩阵数据' }}
        scroll={{ x: 'max-content', y: 300 }}
      />
    </div>
  );
}

function matrixValues(value: unknown): unknown[][] {
  if (!Array.isArray(value)) return [];
  return (value as unknown[]).map((row: unknown): unknown[] => {
    if (Array.isArray(row)) return row.map((item: unknown) => item);
    if (isRecord(row) && Array.isArray(row.value)) {
      return row.value.map((item: unknown) => item);
    }
    return [row];
  });
}

function slotLabels(value: unknown) {
  if (!Array.isArray(value)) return [];
  return value.map((slot) => {
    if (!isRecord(slot)) return '';
    return stringValue(slot.label) || stringValue(slot.name) || stringValue(slot.code);
  });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function snapshotVersion(snapshot: Record<string, unknown>, fallback: number) {
  const value = snapshot.snapshotFormatVersion;
  return typeof value === 'number' && Number.isInteger(value) && value > 0 ? value : fallback;
}
