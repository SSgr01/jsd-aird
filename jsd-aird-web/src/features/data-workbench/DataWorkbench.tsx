import { EditOutlined, FolderOpenOutlined, LoadingOutlined, TableOutlined } from '@ant-design/icons';
import { Button, Empty, Skeleton, Space, Spin, Tag, Typography } from 'antd';
import { forwardRef, lazy, Suspense, useEffect, useMemo, useState, type ReactNode } from 'react';

import type {
  DataFieldValueView,
  DataWorkbookFieldDefinition,
  DataWorkbookRegion,
  DataWorkbookSnapshot,
} from '@/services/data/data-api';
import type { EditorCellChange, EditorHandle, EditorSelection } from '@/features/template-workspace/types';

import { displayValue } from './value-display';

const SheetsEditor = lazy(async () => {
  const module = await import('@/features/template-workspace/UniverSheetsEditor');
  return { default: module.UniverSheetsEditor };
});

interface ShellProps {
  breadcrumb: string;
  title: string;
  leading?: ReactNode;
  meta?: ReactNode;
  actions?: ReactNode;
  notice?: ReactNode;
  canvas: ReactNode;
  panel: ReactNode;
  footer?: ReactNode;
  children?: ReactNode;
}

export function DataWorkbenchShell({ breadcrumb, title, leading, meta, actions, notice, canvas, panel, footer, children }: ShellProps) {
  return (
    <section className="data-workbench" aria-label={`${title}数据工作台`}>
      <header className="data-workbench-header">
        <div className="data-workbench-header-main">
          {leading ? <div className="data-workbench-leading">{leading}</div> : null}
          <div className="data-workbench-identity">
            <Typography.Text type="secondary" className="data-workbench-breadcrumb">{breadcrumb}</Typography.Text>
            <div className="data-workbench-title-row">
              <Typography.Title level={2}>{title}</Typography.Title>
              {meta}
            </div>
          </div>
        </div>
        <Space wrap>{actions}</Space>
      </header>
      {notice ? <div className="data-workbench-notice">{notice}</div> : null}
      <div className="data-workbench-grid">
        <section className="data-workbook-canvas" aria-label="Excel 工作区">{canvas}</section>
        <aside className="data-workbench-panel">{panel}</aside>
      </div>
      {footer ? <footer className="data-workbench-footer">{footer}</footer> : null}
      {children}
    </section>
  );
}

interface CanvasProps {
  workbook?: DataWorkbookSnapshot;
  loading?: boolean;
  editable?: boolean;
  onSelectionChange?: (selection: EditorSelection) => void;
  onCellChange?: (change: EditorCellChange) => void;
}

export const DataWorkbookCanvas = forwardRef<EditorHandle, CanvasProps>(function DataWorkbookCanvas(
  { workbook, loading, editable, onSelectionChange, onCellChange },
  ref,
) {
  if (loading) return <div className="data-workbook-state"><Skeleton active paragraph={{ rows: 10 }} /></div>;
  if (!workbook || !Object.keys(workbook.snapshot || {}).length) {
    return <div className="data-workbook-state"><Empty description="当前文件暂不支持工作区预览，可使用原文件预览" /></div>;
  }
  return (
    <Suspense fallback={<div className="data-workbook-state"><Spin indicator={<LoadingOutlined spin />} /></div>}>
      <SheetsEditor
        ref={ref}
        snapshot={workbook.snapshot}
        bindings={[]}
        editable={Boolean(editable && workbook.editable)}
        onDirty={() => undefined}
        onEditorValue={() => undefined}
        onSelectionChange={onSelectionChange}
        onCellChange={editable && workbook.editable ? onCellChange : undefined}
      />
    </Suspense>
  );
});

interface FieldCardProps {
  field: DataFieldValueView;
  active?: boolean;
  onSelect?: () => void;
  extra?: ReactNode;
  children?: ReactNode;
}

export function DataFieldCard({ field, active, onSelect, extra, children }: FieldCardProps) {
  const raw = displayValue(field.rawValue);
  const normalized = displayValue(field.normalizedValue);
  const effective = displayValue(field.effectiveValue);
  const corrected = field.correctedValue == null ? '' : displayValue(field.correctedValue);
  const normalizedChanged = normalized !== raw;
  const effectiveChanged = effective !== normalized && effective !== raw;
  return (
    <article className={`data-field-card${active ? ' is-active' : ''}`}>
      <button type="button" className="data-field-card-summary" onClick={onSelect}>
        <span>
          <strong>{field.fieldName}</strong>
          {field.labelPath && field.labelPath !== field.fieldName ? <small>{field.labelPath}</small> : null}
        </span>
        <span className="data-field-card-status">
          <Tag color={field.excluded ? 'default' : field.valueStatus === 'VALID' ? 'success' : 'warning'}>
            {field.excluded ? '已排除' : field.valueStatus === 'VALID' ? '校验通过' : '需要确认'}
          </Tag>
        </span>
      </button>
      {active ? (
        <div className="data-field-card-detail">
          <dl>
            <div><dt>实际值</dt><dd>{raw || '空'}</dd></div>
            {normalizedChanged ? <div><dt>格式转换后</dt><dd>{normalized || '空'}</dd></div> : null}
            {effectiveChanged ? <div><dt>最终采用值</dt><dd>{effective || '空'}</dd></div> : null}
            <div><dt>单位</dt><dd>{field.unit || '无'}</dd></div>
          </dl>
          {corrected ? <Typography.Text type="secondary">已人工修正：{corrected}</Typography.Text> : null}
          {children}
          {extra ? <div className="data-field-card-actions">{extra}</div> : null}
        </div>
      ) : null}
    </article>
  );
}

interface FieldDataBrowserProps {
  workbook?: DataWorkbookSnapshot;
  selectedFieldKey?: string;
  selectedCell?: EditorSelection;
  onSelectField: (field: DataFieldValueView) => void;
  renderFieldExtra?: (field: DataFieldValueView) => ReactNode;
  renderFieldMeta?: (field: DataFieldValueView) => ReactNode;
  headerExtra?: ReactNode;
  emptyDescription?: string;
}

export function DataFieldDataBrowser({
  workbook,
  selectedFieldKey,
  selectedCell,
  onSelectField,
  renderFieldExtra,
  renderFieldMeta,
  headerExtra,
  emptyDescription = '文件解析和字段匹配完成后显示实际数据',
}: FieldDataBrowserProps) {
  const fields = workbook?.fields || [];
  const regions = useMemo(() => normalizeRegions(workbook), [workbook]);
  const records = workbook?.records || [];
  const selectedField = fields.find((field) => dataFieldKey(field) === selectedFieldKey);
  const [selectedRegionId, setSelectedRegionId] = useState<string>();
  const [selectedRecordId, setSelectedRecordId] = useState<string>();
  useEffect(() => {
    if (!selectedField?.componentId) return;
    setSelectedRegionId(selectedField.componentId);
    setSelectedRecordId(selectedField.recordGroupId || selectedField.recordId);
  }, [selectedField?.componentId, selectedField?.recordGroupId, selectedField?.recordId]);
  const activeRegionId = selectedRegionId || selectedField?.componentId || regions[0]?.regionId;
  const region = regions.find((item) => item.regionId === activeRegionId) || regions[0];
  const regionRecords = records.filter((item) => !region || item.regionId === region.regionId);
  const activeRecordId = selectedRecordId && regionRecords.some((item) => item.recordId === selectedRecordId)
    ? selectedRecordId : regionRecords[0]?.recordId;
  const visibleFields = activeRecordId
    ? fields.filter((item) => (item.recordGroupId || item.recordId) === activeRecordId
      && (!region || item.componentId === region.regionId))
    : fields.filter((item) => !region || item.componentId === region.regionId);
  const grouped = groupFields(visibleFields, workbook?.fieldDefinitions);

  return <div className="data-panel-body">
    <WorkbenchPanelHeader
      title="字段数据"
      description={selectedCell ? '已同步左侧选中的单元格' : dataBrowserDescription(region)}
      extra={headerExtra}
    />
    {regions.length > 1 ? <div className="data-region-selector" role="list" aria-label="数据区域">
      {regions.map((item) => <button
        type="button"
        key={item.regionId}
        className={item.regionId === region?.regionId ? 'is-active' : ''}
        onClick={() => {
          setSelectedRegionId(item.regionId);
          const firstRecord = records.find((record) => record.regionId === item.regionId);
          setSelectedRecordId(firstRecord?.recordId);
          const first = fields.find((field) => field.componentId === item.regionId
            && (!firstRecord || (field.recordGroupId || field.recordId) === firstRecord.recordId));
          if (first) onSelectField(first);
        }}
      >
        <span><strong>{item.name}</strong><small>{structureLabel(item.structureType, item.recordAxis)}</small></span>
        <span>{item.recordCount ? `${item.recordCount} 条` : `${item.fieldCount} 个字段`}</span>
      </button>)}
    </div> : null}
    {region ? <section className="data-region-summary">
      <div><strong>{region.name}</strong><Tag>{structureLabel(region.structureType, region.recordAxis)}</Tag></div>
      <small>{visibleFieldDefinitionCount(region, workbook)} 个字段{region.recordCount ? `，${region.recordCount} 条记录` : ''}</small>
    </section> : null}
    {regionRecords.length > 1 || regionRecords.length === 1 && !isSingleFormRegion(region) ? <div className="data-structure-toolbar">
      <div className="data-record-strip" aria-label="选择记录">
        {regionRecords.map((item) => <button
          type="button"
          key={item.recordId}
          className={item.recordId === activeRecordId ? 'is-active' : ''}
          onClick={() => {
            setSelectedRecordId(item.recordId);
            const next = fields.find((field) => (field.recordGroupId || field.recordId) === item.recordId
              && (!region || field.componentId === region.regionId));
            if (next) onSelectField(next);
          }}
        >{item.label}</button>)}
      </div>
    </div> : null}
    {selectedCell && !fields.some((field) => field.sheetId === selectedCell.sheetId
      && field.address?.toUpperCase() === selectedCell.address.toUpperCase())
      ? <div className="data-inline-note">该单元格没有匹配到业务字段</div> : null}
    <div className="data-structure-groups">
      {grouped.length ? grouped.map((group) => <section className="data-field-group" key={group.name}>
        {group.name ? <header><strong>{group.name}</strong><span>{distinctBindings(group.fields)} 个字段</span></header> : null}
        <div className="data-field-list">
          {group.fields.map((field) => <DataFieldCard
            key={dataFieldKey(field)}
            field={field}
            active={dataFieldKey(field) === selectedFieldKey}
            onSelect={() => onSelectField(field)}
            extra={renderFieldExtra?.(field)}
          >
            {renderFieldMeta?.(field)}
          </DataFieldCard>)}
        </div>
      </section>) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={emptyDescription} />}
    </div>
  </div>;
}

interface FieldStructureBrowserProps {
  workbook?: DataWorkbookSnapshot;
  selectedBindingId?: string;
  onSelectField: (field: DataWorkbookFieldDefinition) => void;
}

export function DataFieldStructureBrowser({ workbook, selectedBindingId, onSelectField }: FieldStructureBrowserProps) {
  const definitions = useMemo(() => normalizeDefinitions(workbook), [workbook]);
  const regions = useMemo(() => normalizeRegions(workbook), [workbook]);
  if (!definitions.length) {
    return <div className="data-panel-body"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前模板没有可展示的字段结构" /></div>;
  }
  return <div className="data-panel-body data-field-structure-browser">
    <WorkbenchPanelHeader title="字段结构" description="结构来自已发布模板，每个字段只显示一次" />
    <div className="field-region-tree">
      {regions.map((region) => {
        const regionFields = definitions.filter((field) => field.componentId === region.regionId);
        const regionBindingIds = new Set(regionFields.map((field) => field.bindingId));
        const groups = groupDefinitions(regionFields);
        return <details className="field-tree-group field-region-group" key={region.regionId} open>
          <summary>
            <span>
              <FolderOpenOutlined /> {region.name}
              <small className="field-region-meta">{structureLabel(region.structureType, region.recordAxis)}</small>
            </span>
            <span className="field-tree-group-meta">{regionFields.length}</span>
          </summary>
          <div className="field-tree-items">
            {groups.map((group) => <section className="data-definition-group" key={group.name || 'root'}>
              {group.name ? <header><strong>{group.name}</strong><span>{group.fields.length} 个字段</span></header> : null}
              {group.fields.map((field) => <div className={`field-tree-node${field.parentBindingId && regionBindingIds.has(field.parentBindingId) ? ' field-tree-child' : ''}`} key={field.bindingId}>
                <button type="button" aria-current={field.bindingId === selectedBindingId} onClick={() => onSelectField(field)}>
                  <span className={`field-tree-icon kind-${(field.mappingKind || 'scalar').toLowerCase()}`}>
                    {isDetailField(field.mappingKind) ? <TableOutlined /> : <EditOutlined />}
                  </span>
                  <span className="field-tree-name">
                    <strong>{field.displayName}</strong>
                    <small>{definitionMeta(field)}</small>
                  </span>
                  <span className="data-definition-status">{field.required ? '必填' : ''}</span>
                </button>
              </div>)}
            </section>)}
          </div>
        </details>;
      })}
    </div>
  </div>;
}

function normalizeDefinitions(workbook?: DataWorkbookSnapshot): DataWorkbookFieldDefinition[] {
  if (workbook?.fieldDefinitions?.length) return workbook.fieldDefinitions;
  const result = new Map<string, DataWorkbookFieldDefinition>();
  for (const field of workbook?.fields || []) {
    const key = field.bindingId || `${field.componentId}:${field.fieldCode}`;
    if (result.has(key)) continue;
    result.set(key, {
      componentId: field.componentId || `${field.sheetId || 'sheet'}:${field.mappingKind || 'DATA'}`,
      bindingId: key,
      parentBindingId: field.parentBindingId,
      fieldCode: field.fieldCode,
      displayName: field.fieldName,
      labelPath: field.labelPath,
      mappingKind: field.mappingKind,
      repeatAxis: field.repeatAxis,
      valueType: field.valueType,
      unit: field.unit,
      required: field.required,
      identity: field.identity,
      groupPath: field.groupPath,
      sheetId: field.sheetId,
      sheetName: field.sheetName,
      sourceRange: field.address,
    });
  }
  return [...result.values()];
}

function groupDefinitions(fields: DataWorkbookFieldDefinition[]) {
  const result = new Map<string, DataWorkbookFieldDefinition[]>();
  for (const field of fields) {
    const name = customerGroupLabel(field.groupPath || labelGroup(field.labelPath) || '');
    result.set(name, [...(result.get(name) || []), field]);
  }
  return [...result.entries()].map(([name, items]) => ({ name, fields: items }));
}

function definitionMeta(field: DataWorkbookFieldDefinition) {
  const type = valueTypeLabel(field.valueType);
  const role = isDetailField(field.mappingKind)
    ? (field.mappingKind || '').toUpperCase().includes('MATRIX') ? '矩阵指标' : '明细字段'
    : '普通字段';
  return [role, type, field.unit].filter(Boolean).join('，');
}

function isDetailField(kind?: string) {
  const value = (kind || '').toUpperCase();
  return value.includes('REPEAT') || value.includes('TABLE') || value.includes('MATRIX');
}

function valueTypeLabel(value?: string) {
  const labels: Record<string, string> = {
    TEXT: '文本', STRING: '文本', NUMBER: '数值', DECIMAL: '小数', INTEGER: '整数',
    DATE: '日期', DATETIME: '日期时间', BOOLEAN: '是/否', ENUM: '选项',
  };
  return labels[(value || 'TEXT').toUpperCase()] || '文本';
}

function dataBrowserDescription(region?: DataWorkbookRegion) {
  if (!region) return '文件解析完成后按区域和记录查看实际值';
  if (isSingleFormRegion(region)) return '公共信息区域，直接查看本次填写值';
  if (structureLabel(region.structureType, region.recordAxis) === '按列记录') return '每个数据列是一条记录';
  if (structureLabel(region.structureType, region.recordAxis) === '矩阵/交叉表') return '按行维度和列维度组合浏览';
  return '选择记录查看本次导入的实际值';
}

function isSingleFormRegion(region?: DataWorkbookRegion) {
  return Boolean(region && (region.structureType || '').toUpperCase().includes('FORM') && region.recordCount <= 1);
}

function normalizeRegions(workbook?: DataWorkbookSnapshot): DataWorkbookRegion[] {
  if (workbook?.regions?.length) return workbook.regions;
  const fields = workbook?.fields || [];
  const byComponent = new Map<string, DataFieldValueView[]>();
  for (const field of fields) {
    const id = field.componentId || `${field.sheetId || 'sheet'}:${field.mappingKind || 'DATA'}`;
    byComponent.set(id, [...(byComponent.get(id) || []), field]);
  }
  return [...byComponent.entries()].map(([regionId, items]) => ({
    regionId,
    name: fallbackRegionName(items[0]?.mappingKind, items[0]?.repeatAxis),
    structureType: items[0]?.mappingKind || 'SCALAR',
    sheetId: items[0]?.sheetId,
    sheetName: items[0]?.sheetName,
    recordAxis: items[0]?.repeatAxis,
    fieldCount: distinctBindings(items),
    recordCount: new Set(items.map((item) => item.recordId).filter(Boolean)).size,
    fieldGroups: [],
  }));
}

function fallbackRegionName(type?: string, axis?: string) {
  const label = structureLabel(type, axis);
  if (label === '表单信息') return '基本信息';
  if (label === '按行记录' || label === '按列记录') return '明细数据';
  if (label === '矩阵/交叉表') return '矩阵指标';
  return '其他信息';
}

function groupFields(fields: DataFieldValueView[], definitions?: DataWorkbookFieldDefinition[]) {
  const groupsByBinding = new Map((definitions || []).map((field) => [field.bindingId, field.groupPath || '']));
  const result = new Map<string, DataFieldValueView[]>();
  for (const field of fields) {
    const name = customerGroupLabel(groupsByBinding.get(field.bindingId) || customerFieldGroup(field) || '');
    result.set(name, [...(result.get(name) || []), field]);
  }
  return [...result.entries()].map(([name, items]) => ({ name, fields: items }));
}

function customerFieldGroup(field: DataFieldValueView) {
  const code = (field.fieldCode || '').toUpperCase();
  if (code.includes('ROW_DIMENSION') || code.includes('ROW_ATTRIBUTE')) return '行维度';
  if (code.includes('COLUMN_DIMENSION') || code.includes('COLUMN_MEMBER')) return '列维度';
  if (code.includes('MATRIX.MEASURE')) return '指标值';
  return field.groupPath && !internalContractText(field.groupPath)
    ? field.groupPath : labelGroup(field.labelPath);
}

function internalContractText(value?: string) {
  return Boolean(value && /^(?:AUTO|TABLE|MATRIX|DATA|MATERIAL|PRODUCTION|WORKFLOW|FIELD)\..+$/i.test(value));
}

function visibleFieldDefinitionCount(region: DataWorkbookRegion, workbook?: DataWorkbookSnapshot) {
  const definitions = (workbook?.fieldDefinitions || []).filter((field) => field.componentId === region.regionId);
  return definitions.length || region.fieldCount;
}

function labelGroup(value?: string) {
  if (!value) return '';
  const parts = value.split(/\s*(?:>|\/|›)\s*/).filter(Boolean);
  return parts.length > 1 ? parts.slice(0, -1).join(' / ') : '';
}

function customerGroupLabel(value: string) {
  return value.replace(/_/g, ' ').replace(/\s+/g, ' ').trim();
}

function distinctBindings(fields: DataFieldValueView[]) {
  return new Set(fields.map((field) => field.bindingId || field.fieldCode)).size;
}

function structureLabel(type?: string, axis?: string) {
  const value = (type || '').toUpperCase();
  if (value.includes('MATRIX')) return '矩阵/交叉表';
  if (value.includes('FORM')) return '表单信息';
  if ((axis || '').toUpperCase() === 'COLUMN' || value.includes('COLUMN')) return '按列记录';
  if ((axis || '').toUpperCase() === 'ROW' || value.includes('ROW') || value.includes('REPEAT')) return '按行记录';
  return '其他信息';
}

function dataFieldKey(field?: DataFieldValueView) {
  return field ? [field.recordId, field.bindingId, field.valuePath, field.sheetId, field.address].join('|') : undefined;
}

export function WorkbenchPanelHeader({ title, description, extra }: { title: string; description?: string; extra?: ReactNode }) {
  return (
    <div className="data-panel-heading">
      <div><strong>{title}</strong>{description ? <span>{description}</span> : null}</div>
      {extra}
    </div>
  );
}

export function LocateButton({ disabled, onClick }: { disabled?: boolean; onClick?: () => void }) {
  return <Button size="small" disabled={disabled} onClick={onClick}>定位到单元格</Button>;
}
