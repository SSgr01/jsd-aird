import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  CheckOutlined,
  DownloadOutlined,
  EyeOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import {
  Alert,
  App,
  Button,
  Checkbox,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Spin,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { FilePreviewModal, downloadPreviewFile, type FilePreviewDescriptor } from '@/components/file-preview';
import {
  DataFieldDataBrowser,
  DataFieldStructureBrowser,
  DataWorkbenchShell,
  DataWorkbookCanvas,
  WorkbenchPanelHeader,
} from '@/features/data-workbench/DataWorkbench';
import type { EditorCellChange, EditorHandle, EditorSelection } from '@/features/template-workspace/types';
import {
  dataApi,
  type ComponentMatch,
  type DataFieldValueView,
  type DataWorkbookFieldDefinition,
  type DataMapping,
  type DataPreview,
  type DataWorkbookSnapshot,
} from '@/services/data/data-api';

type PanelTab = 'data' | 'structure' | 'mapping';

const dataTypeLabels: Record<string, string> = {
  MATERIAL: '原料', FORMULA: '配方', PROCESS: '工艺', EQUIPMENT: '设备/仪器', TEST_STANDARD: '检测标准',
};
const jobStatusLabels: Record<string, string> = {
  PARSING: '解析中', VALIDATING: '校验中', WAITING_MAPPING: '待确认字段', WAITING_CONFIRM: '待确认提交',
  COMMITTING: '提交中', COMPLETED: '已完成', FAILED: '处理失败',
};
const compatibilityLabels: Record<string, string> = {
  EXACT: '结构一致', COMPATIBLE: '结构兼容', REVIEW_REQUIRED: '需要复核', INCOMPATIBLE: '结构不兼容', LEGACY: '旧版兼容',
};
const compatibilityReasonLabels: Record<string, string> = {
  REQUIRED_COMPONENT_MISSING: '必选区域缺失', COMPONENT_NOT_FOUND: '未找到对应区域',
  FORMULA_ROLE_CONFLICT: '公式与录入位置冲突', COMPONENT_RANGE_CHANGED: '区域位置发生变化',
  SHEET_RENAMED_OR_MOVED: '工作表已重命名或移动', LABEL_ANCHORS_MISSING: '字段标签缺失',
  LABEL_ANCHORS_CHANGED: '字段标签发生变化', STRUCTURE_FINGERPRINT_UNAVAILABLE: '结构信息不足',
  MANUALLY_REANCHORED: '已人工重新定位', EXACT_STRUCTURE_MATCH: '结构完全一致',
};
const actionOptions = [
  { value: 'MAP', label: '映射到字段' },
  { value: 'IGNORE', label: '忽略此列' },
  { value: 'REQUEST_FIELD', label: '申请新增字段' },
];

export function DataImportJobPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const { message, modal } = App.useApp();
  const editorRef = useRef<EditorHandle>(null);
  const [preview, setPreview] = useState<DataPreview>();
  const [workbook, setWorkbook] = useState<DataWorkbookSnapshot>();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [savingCell, setSavingCell] = useState<string>();
  const [activeTab, setActiveTab] = useState<PanelTab>('data');
  const [selectedFieldKey, setSelectedFieldKey] = useState<string>();
  const [selectedBindingId, setSelectedBindingId] = useState<string>();
  const [selectedCell, setSelectedCell] = useState<EditorSelection>();
  const [previewFile, setPreviewFile] = useState<FilePreviewDescriptor>();
  const [componentAnchor, setComponentAnchor] = useState<{ componentId: string; sheetId?: string }>();
  const [anchorRange, setAnchorRange] = useState('');
  const [anchorReason, setAnchorReason] = useState('');

  const load = useCallback(async (options: { silent?: boolean; preserveSnapshot?: boolean; skipWorkbook?: boolean } = {}) => {
    if (!options.silent) setLoading(true);
    try {
      const nextPreview = await dataApi.preview(id);
      const stillProcessing = ['PARSING', 'VALIDATING', 'COMMITTING'].includes(nextPreview.job.status);
      const nextWorkbook = options.skipWorkbook && stillProcessing
        ? undefined
        : await dataApi.getImportWorkbookSnapshot(id).catch(() => undefined);
      setPreview(nextPreview);
      if (nextWorkbook) {
        setWorkbook((current) => options.preserveSnapshot && current
          ? { ...nextWorkbook, snapshot: current.snapshot }
          : nextWorkbook);
        setSelectedFieldKey((current) => current || fieldKey(nextWorkbook.fields[0]));
        const firstField = nextWorkbook.fields[0];
        const firstDefinition = firstField ? definitionForValue(nextWorkbook, firstField)
          : nextWorkbook.fieldDefinitions?.[0];
        setSelectedBindingId((current) => current || firstDefinition?.bindingId || firstField?.bindingId);
      }
    } catch (error) {
      if (!options.silent) void message.error(error instanceof Error ? error.message : '导入任务加载失败');
    } finally {
      if (!options.silent) setLoading(false);
    }
  }, [id, message]);

  useEffect(() => { void load(); }, [load]);

  const processing = Boolean(preview && ['PARSING', 'VALIDATING', 'COMMITTING'].includes(preview.job.status));
  useEffect(() => {
    if (!processing) return undefined;
    const timer = window.setInterval(() => void load({ silent: true, skipWorkbook: true }), 1600);
    return () => window.clearInterval(timer);
  }, [load, processing]);

  const fields = workbook?.fields || [];
  const blockers = preview?.issues.filter((issue) => issue.severity === 'BLOCKER'
    && !['RESOLVED', 'IGNORED'].includes(issue.status)) || [];

  if (loading || !preview) return <div className="data-workbench-loading"><Spin /></div>;

  const sourceFile: FilePreviewDescriptor = {
    fileName: preview.job.sourceFileName,
    load: () => dataApi.sourceBlob(preview.job.sourceFileId),
  };

  const selectField = (field: DataFieldValueView) => {
    setSelectedFieldKey(fieldKey(field));
    setSelectedBindingId(definitionForValue(workbook, field)?.bindingId || field.bindingId);
    if (field.sheetId && field.address) editorRef.current?.focusCell?.(field.sheetId, field.address);
  };

  const selectDefinition = (field: DataWorkbookFieldDefinition) => {
    setSelectedBindingId(field.bindingId);
    const value = fields.find((item) => item.componentId === field.componentId
      && (item.bindingId === field.bindingId || item.fieldCode === field.fieldCode));
    if (value) setSelectedFieldKey(fieldKey(value));
    if (value?.sheetId && value.address) editorRef.current?.focusCell?.(value.sheetId, value.address);
    else if (field.sheetId && field.sourceRange) editorRef.current?.focusRange?.(field.sheetId, field.sourceRange);
  };

  const handleSelection = (selection: EditorSelection) => {
    setSelectedCell(selection);
    const exact = fields.find((field) => field.sheetId === selection.sheetId
      && field.address?.toUpperCase() === selection.address.toUpperCase());
    const row = cellRow(selection.address);
    const fallback = fields.find((field) => field.sheetId === selection.sheetId && field.rowNumber === row);
    const next = exact || fallback;
    if (next) {
      setSelectedFieldKey(fieldKey(next));
      setSelectedBindingId(definitionForValue(workbook, next)?.bindingId || next.bindingId);
    }
  };

  const handleCellChange = async (change: EditorCellChange) => {
    const field = fields.find((item) => item.sheetId === change.sheetId
      && item.address?.toUpperCase() === change.address.toUpperCase());
    if (!field?.editable || !field.recordId || !field.bindingId || !field.valuePath) {
      await editorRef.current?.writeCell?.(change.sheetId, change.address, change.previousValue).catch(() => undefined);
      void message.warning({
        key: 'data-workbench-readonly-cell',
        content: field ? '该单元格是公式、派生值或只读内容，不能直接修改' : '该单元格尚未绑定可修正字段',
      });
      return;
    }
    const key = `${change.sheetId}:${change.address}`;
    setSavingCell(key);
    setSelectedFieldKey(fieldKey(field));
    try {
      await dataApi.correctValue(id, field.recordId, {
        bindingId: field.bindingId,
        valuePath: field.valuePath,
        correctedValue: change.value,
        reason: '导入确认页直接修正',
      });
      setWorkbook((current) => current ? {
        ...current,
        fields: current.fields.map((item) => fieldKey(item) === fieldKey(field)
          ? { ...item, correctedValue: change.value, effectiveValue: change.value }
          : item),
      } : current);
      await load({ silent: true, preserveSnapshot: true });
      void message.success('修正值已保存并重新校验');
    } catch (error) {
      await editorRef.current?.writeCell?.(change.sheetId, change.address, change.previousValue).catch(() => undefined);
      void message.error(error instanceof Error ? error.message : '修正值保存失败，已恢复原值');
    } finally {
      setSavingCell(undefined);
    }
  };

  const saveSheets = async () => {
    setSaving(true);
    try {
      await dataApi.saveSheets(id, preview.sheets.map((sheet) => ({ ...sheet, confirmationStatus: sheet.selected ? 'CONFIRMED' : 'IGNORED' })));
      await load();
      void message.success('工作表配置已确认并重新读取数据');
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '工作表配置保存失败');
    } finally { setSaving(false); }
  };

  const updateSheet = (sheetId: string, patch: Partial<DataPreview['sheets'][number]>) => {
    setPreview((current) => current ? {
      ...current,
      sheets: current.sheets.map((item) => item.sheetId === sheetId ? { ...item, ...patch } : item),
    } : current);
  };

  const updateMapping = (index: number, patch: Partial<DataMapping>) => {
    setPreview((current) => current ? {
      ...current,
      mappings: current.mappings.map((item, itemIndex) => itemIndex === index ? { ...item, ...patch } : item),
    } : current);
  };

  const saveMappings = async () => {
    setSaving(true);
    try {
      await dataApi.saveMappings(id, preview.mappings);
      await load();
      void message.success('字段映射已保存并重新校验');
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '字段映射保存失败');
    } finally { setSaving(false); }
  };

  const toggleExclusion = (field: DataFieldValueView) => {
    if (!field.recordId) return;
    let reason = '';
    modal.confirm({
      title: field.excluded ? '恢复当前记录' : '排除当前记录',
      content: field.excluded ? '恢复后系统会重新校验整条记录。' : (
        <Input.TextArea rows={3} placeholder="请输入排除原因" onChange={(event) => { reason = event.target.value; }} />
      ),
      okText: field.excluded ? '恢复记录' : '确认排除', cancelText: '取消',
      onOk: async () => {
        if (!field.excluded && !reason.trim()) throw new Error('请输入排除原因');
        await dataApi.excludeRecord(id, field.recordId!, !field.excluded, reason.trim());
        await load({ preserveSnapshot: true });
      },
    });
  };

  const commit = async () => {
    setSaving(true);
    try {
      await dataApi.commit(id);
      await load();
      void message.success('正式数据提交完成');
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '正式数据提交失败');
    } finally { setSaving(false); }
  };

  const saveComponentAnchor = async () => {
    if (!componentAnchor || !anchorRange.trim() || !anchorReason.trim()) {
      void message.warning('请填写工作表、范围和重新定位原因');
      return;
    }
    setSaving(true);
    try {
      await dataApi.reanchorComponent(id, componentAnchor.componentId, {
        sheetId: componentAnchor.sheetId || preview.sheets[0]?.sheetId || '',
        sourceRange: anchorRange.trim(), reason: anchorReason.trim(),
      });
      setComponentAnchor(undefined);
      await load();
      void message.success('区域位置已更新并重新提取数据');
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '区域重新定位失败');
    } finally { setSaving(false); }
  };

  const compatibility = preview.job.compatibilityStatus || 'LEGACY';
  const meta = (
    <Space wrap size={8}>
      <Tag color="blue">{dataTypeLabels[preview.job.targetDataType] || '数据'}</Tag>
      <Tag color={preview.job.status === 'COMPLETED' ? 'success' : preview.job.status === 'FAILED' ? 'error' : 'processing'}>
        {jobStatusLabels[preview.job.status] || '处理中'}
      </Tag>
      <Tag color={compatibility === 'EXACT' ? 'success' : compatibility === 'COMPATIBLE' ? 'blue' : 'warning'}>
        {compatibilityLabels[compatibility] || '需要确认'}
      </Tag>
      <Typography.Text type="secondary">进度 {preview.job.progress}%</Typography.Text>
    </Space>
  );

  return (
    <DataWorkbenchShell
      breadcrumb="数据中心 / 导入确认"
      title={preview.job.sourceFileName}
      leading={<Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/data/upload')}>返回上传</Button>}
      meta={meta}
      actions={<>
        <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
        <Button icon={<EyeOutlined />} onClick={() => setPreviewFile(sourceFile)}>预览原文件</Button>
        <Button icon={<DownloadOutlined />} onClick={() => void downloadPreviewFile(sourceFile)}>下载原文件</Button>
        {preview.job.status !== 'COMPLETED' ? <Button
          type="primary"
          icon={<CheckOutlined />}
          disabled={blockers.length > 0 || preview.job.status !== 'WAITING_CONFIRM' || ['INCOMPATIBLE', 'REVIEW_REQUIRED'].includes(compatibility)}
          loading={saving}
          onClick={() => void commit()}
        >确认并提交</Button> : null}
      </>}
      notice={<WorkbenchNotice preview={preview} processing={processing} />}
      canvas={<DataWorkbookCanvas
        ref={editorRef}
        workbook={workbook}
        loading={!workbook && processing}
        editable={Boolean(workbook?.editable && workbook.fields.some((field) => field.editable) && !savingCell)}
        onSelectionChange={handleSelection}
        onCellChange={(change) => void handleCellChange(change)}
      />}
      panel={<>
        <Tabs
          className="data-workbench-tabs"
          activeKey={activeTab}
          onChange={(value) => setActiveTab(value as PanelTab)}
          items={[
            { key: 'data', label: '字段数据' },
            { key: 'structure', label: '字段结构' },
            { key: 'mapping', label: '字段映射' },
          ]}
        />
        {activeTab === 'data' ? (
          <DataFieldDataBrowser
            workbook={workbook}
            selectedFieldKey={selectedFieldKey}
            selectedCell={selectedCell}
            headerExtra={savingCell ? <Tag color="processing">正在保存</Tag> : undefined}
            onSelectField={selectField}
            renderFieldExtra={(field) => field.recordId ? <Button size="small" danger={!field.excluded} onClick={() => toggleExclusion(field)}>{field.excluded ? '恢复记录' : '排除记录'}</Button> : null}
            renderFieldMeta={(field) => <Space wrap size={4}><Tag>{fieldTypeLabel(field.valueType)}</Tag>{field.required ? <Tag color="orange">必填</Tag> : null}{field.identity ? <Tag color="blue">记录标识</Tag> : null}</Space>}
          />
        ) : null}
        {activeTab === 'structure' ? (
          <DataFieldStructureBrowser
            workbook={workbook}
            selectedBindingId={selectedBindingId}
            onSelectField={selectDefinition}
          />
        ) : null}
        {activeTab === 'mapping' ? (
          <MappingPanel
            preview={preview}
            saving={saving}
            onUpdateMapping={updateMapping}
            onSaveMappings={() => void saveMappings()}
            onUpdateSheet={updateSheet}
            onSaveSheets={() => void saveSheets()}
            onReExtract={() => void dataApi.reExtract(id)
              .then(() => load())
              .catch((error: unknown) => message.error(error instanceof Error ? error.message : '重新读取失败'))}
            onLocateComponent={(item) => {
              setComponentAnchor({ componentId: item.componentId, sheetId: item.sheetId });
              setAnchorRange(''); setAnchorReason('');
            }}
            onFocus={(sheetId, address) => editorRef.current?.focusRange?.(sheetId, address)}
          />
        ) : null}
      </>}
      footer={preview.job.status === 'COMPLETED' ? (
        <Space><CheckCircleOutlined className="data-success-icon" /><Typography.Text strong>导入已完成，正式数据已生成</Typography.Text><Button type="primary" onClick={() => navigate('/data/view')}>查看数据资产</Button></Space>
      ) : undefined}
    >
      <FilePreviewModal open={Boolean(previewFile)} file={previewFile} onClose={() => setPreviewFile(undefined)} />
      <Modal open={Boolean(componentAnchor)} title="重新定位当前数据区域" okText="保存并重新提取" cancelText="取消" confirmLoading={saving} onOk={() => void saveComponentAnchor()} onCancel={() => setComponentAnchor(undefined)}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <Alert type="info" showIcon message="该位置只作用于本次导入，不修改已发布模板。" />
          <Select value={componentAnchor?.sheetId} onChange={(value) => setComponentAnchor((current) => current ? { ...current, sheetId: value } : current)} options={preview.sheets.map((sheet) => ({ value: sheet.sheetId, label: sheet.sheetName }))} style={{ width: '100%' }} />
          <Input value={anchorRange} onChange={(event) => setAnchorRange(event.target.value)} placeholder="区域范围，例如 E4:N100" />
          <Input.TextArea rows={3} value={anchorReason} onChange={(event) => setAnchorReason(event.target.value)} placeholder="请输入重新定位原因" />
        </Space>
      </Modal>
    </DataWorkbenchShell>
  );
}

function MappingPanel({ preview, saving, onUpdateMapping, onSaveMappings, onUpdateSheet, onSaveSheets, onReExtract, onLocateComponent, onFocus }: {
  preview: DataPreview;
  saving: boolean;
  onUpdateMapping: (index: number, patch: Partial<DataMapping>) => void;
  onSaveMappings: () => void;
  onUpdateSheet: (sheetId: string, patch: Partial<DataPreview['sheets'][number]>) => void;
  onSaveSheets: () => void;
  onReExtract: () => void;
  onLocateComponent: (item: ComponentMatch) => void;
  onFocus: (sheetId: string, address: string) => void;
}) {
  const pending = preview.mappings.map((item, index) => ({ item, index })).filter(({ item }) => item.action !== 'MAP' || !item.fieldCode);
  const templateFields = preview.templateContract?.fields || [];
  return <div className="data-panel-body">
    <WorkbenchPanelHeader title="字段对应关系" description="仅在系统无法确定时需要人工选择" />
    {pending.length ? <section className="data-panel-section"><Alert type="warning" showIcon message={`还有 ${pending.length} 个字段需要确认`} />{pending.map(({ item, index }) => <div className="data-mapping-card" key={`${item.sheetId}-${item.sourceColumn}`}><strong>{item.sourceHeader || `位置 ${item.sourceColumn}`}</strong><Select value={item.action} options={actionOptions} onChange={(value) => onUpdateMapping(index, { action: value })} /><Select showSearch optionFilterProp="label" placeholder="选择对应字段" value={item.fieldCode} disabled={item.action !== 'MAP'} options={templateFields.map((field) => ({ value: field.fieldCode, label: `${field.displayName}${field.required ? ' · 必填' : ''}` }))} onChange={(value) => { const field = templateFields.find((candidate) => candidate.fieldCode === value); onUpdateMapping(index, { fieldCode: value, fieldName: field?.displayName, valueType: field?.dataType, standardUnit: field?.defaultUnit, detail: { ...(item.detail || {}), dataPath: field?.dataPath, identity: field?.identity, required: field?.required } }); }} /></div>)}<Button type="primary" block loading={saving} onClick={onSaveMappings}>保存对应关系并校验</Button></section> : preview.job.status === 'WAITING_MAPPING' ? <section className="data-panel-section"><Alert type="info" showIcon message={`系统已自动对应 ${preview.mappings.length} 个字段`} /><Button type="primary" block loading={saving} onClick={onSaveMappings}>确认自动对应并校验</Button></section> : <Alert type="success" showIcon message="字段对应关系已确认" />}
    <WorkbenchPanelHeader title="工作表与数据范围" description="一般无需调整；文件结构变化时可重新读取" extra={<Button size="small" onClick={onReExtract}>重新读取</Button>} />
    <div className="data-panel-section">{preview.sheets.map((sheet) => <article className="data-sheet-card" key={sheet.sheetId}><div className="data-sheet-card-title"><Checkbox checked={sheet.selected} onChange={(event) => onUpdateSheet(sheet.sheetId, { selected: event.target.checked })}>{sheet.sheetName}</Checkbox><Tag>{sheet.selected ? '使用' : '忽略'}</Tag></div><div className="data-sheet-fields"><label>表头行<InputNumber min={1} value={sheet.headerRows[0]} onChange={(value) => onUpdateSheet(sheet.sheetId, { headerRows: value ? [value] : [] })} /></label><label>数据开始<InputNumber min={1} value={sheet.dataStartRow} onChange={(value) => onUpdateSheet(sheet.sheetId, { dataStartRow: value ?? undefined })} /></label><label>数据结束<InputNumber min={sheet.dataStartRow || 1} value={sheet.dataEndRow} onChange={(value) => onUpdateSheet(sheet.sheetId, { dataEndRow: value ?? undefined })} /></label></div></article>)}<Button type="primary" block loading={saving} onClick={onSaveSheets}>确认 Sheet 配置</Button></div>
    {(preview.compatibilityReport?.componentMatches?.length || 0) > 0 ? <><WorkbenchPanelHeader title="区域兼容性" description="上传文件与模板区域的匹配结果" /><div className="data-panel-section">{preview.compatibilityReport?.componentMatches?.map((item, index) => <article className="data-component-card" key={item.componentId}><div><strong>{item.sheetName || `数据区域 ${index + 1}`}</strong><Tag color={item.status === 'EXACT' ? 'success' : item.status === 'COMPATIBLE' ? 'blue' : 'warning'}>{compatibilityLabels[item.status]}</Tag></div><Typography.Text type="secondary">{(item.resolutionReasonCodes || []).map((code) => compatibilityReasonLabels[code] || '位置需要确认').join('、') || `位置匹配 ${Math.round((item.anchorCoverage || 0) * 100)}%`}</Typography.Text><Space><Button size="small" disabled={!item.sheetId} onClick={() => item.sheetId && onFocus(item.sheetId, componentRange(preview, item))}>查看区域</Button><Button size="small" disabled={preview.job.status === 'COMPLETED'} onClick={() => onLocateComponent(item)}>重新定位</Button></Space></article>)}</div></> : null}
  </div>;
}

function WorkbenchNotice({ preview, processing }: { preview: DataPreview; processing: boolean }) {
  if (processing) return <Alert banner showIcon type="info" message="系统正在读取工作簿并校验字段，完成后自动更新。" />;
  if (preview.job.status === 'FAILED') return <Alert banner showIcon type="error" message={preview.job.errorMessage || '文件处理失败，请检查原文件。'} />;
  if (preview.job.compatibilityStatus === 'INCOMPATIBLE') return <Alert banner showIcon type="error" message="上传文件与模板存在关键结构冲突，请修正结构或选择正确模板。" />;
  if (preview.job.compatibilityStatus === 'REVIEW_REQUIRED') return <Alert banner showIcon type="warning" message="结构存在差异，请确认字段、数据范围和实际值。" />;
  return <Alert banner showIcon type="success" message="工作簿已读取，请确认右侧字段和实际数据。" />;
}

function fieldKey(field?: DataFieldValueView) {
  return field ? [field.recordId, field.bindingId, field.valuePath, field.sheetId, field.address].join('|') : undefined;
}

function definitionForValue(workbook: DataWorkbookSnapshot | undefined, field: DataFieldValueView) {
  return workbook?.fieldDefinitions?.find((item) => item.componentId === field.componentId
    && (item.bindingId === field.bindingId || item.fieldCode === field.fieldCode));
}

function cellRow(address: string) {
  const match = /([1-9][0-9]*)$/.exec(address);
  return match ? Number(match[1]) : undefined;
}

function componentRange(preview: DataPreview, item: ComponentMatch) {
  const override = preview.componentOverrides?.find((candidate) => candidate.componentId === item.componentId);
  if (override?.sourceRange) return override.sourceRange;
  const contract = preview.templateContract?.contract;
  const components = contract && Array.isArray(contract.components) ? contract.components : [];
  const component = components.find((candidate: unknown) => isRecord(candidate)
    && candidate.componentId === item.componentId) as Record<string, unknown> | undefined;
  return typeof component?.range === 'string' ? component.range : 'A1';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function fieldTypeLabel(value?: string) {
  const labels: Record<string, string> = {
    TEXT: '文本', STRING: '文本', NUMBER: '数值', DECIMAL: '小数', INTEGER: '整数',
    DATE: '日期', DATETIME: '日期时间', BOOLEAN: '是/否', ENUM: '选项',
  };
  return labels[(value || 'TEXT').toUpperCase()] || '文本';
}
