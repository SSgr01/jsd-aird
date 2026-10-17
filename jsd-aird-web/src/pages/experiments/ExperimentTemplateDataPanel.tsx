import { Alert, Button, Input, Tabs, Tag, Typography } from 'antd';
import { useMemo, useState } from 'react';

import { DataFieldDataBrowser, DataFieldStructureBrowser } from '@/features/data-workbench/DataWorkbench';
import { getAtPath } from '@/features/template-workspace/path-utils';
import type { DataFieldValueView, DataWorkbookSnapshot } from '@/services/data/data-api';
import type { ExperimentTemplateDataReview } from '@/services/experiments/experiment-api';

interface Props {
  review: ExperimentTemplateDataReview;
  values: Record<string, unknown>;
  editable: boolean;
  confirmed: boolean;
  busy: boolean;
  onChange: (path: string, value: string) => void;
  onFocus: (bindingId: string) => void;
  onConfirm: () => void;
  importedSnapshot?: Record<string, unknown>;
}

function asText(value: unknown): string {
  if (value == null) return '';
  if (Array.isArray(value)) return value.map(asText).filter(Boolean).join('、');
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

function coordinate(address: string): [number, number] | undefined {
  const match = /^([A-Z]+)([1-9]\d*)$/i.exec(address.replaceAll('$', ''));
  if (!match) return undefined;
  let column = 0;
  for (const letter of match[1]!.toUpperCase()) column = column * 26 + letter.charCodeAt(0) - 64;
  return [Number(match[2]) - 1, column - 1];
}

function importedValue(snapshot: Record<string, unknown>, locator: Record<string, unknown>): string {
  const sheets = snapshot.sheets as Record<string, Record<string, unknown>> | undefined;
  if (!sheets) return '';
  const sheet = sheets[String(locator.sheetId || '')]
    ?? Object.values(sheets).find((item) => item.name === locator.sheetName);
  if (!sheet) return '';
  const rawRange = String(locator.valueRange || locator.address || locator.logicalInputRange
    || locator.anchorAddress || locator.dataRange || '');
  const [start = '', end = start] = rawRange.split(':');
  const first = coordinate(start);
  const last = coordinate(end);
  if (!first || !last || (last[0] - first[0] + 1) * (last[1] - first[1] + 1) > 10000) return '';
  const labelRange = String(locator.labelRange || '').split(':');
  const labelFirst = coordinate(labelRange[0] || '');
  const labelLast = coordinate(labelRange[1] || labelRange[0] || '');
  const cells = (sheet.cellData ?? {}) as Record<string, Record<string, { v?: unknown }>>;
  const values: string[] = [];
  for (let row = first[0]; row <= last[0]; row += 1) {
    for (let column = first[1]; column <= last[1]; column += 1) {
      const inline = locator.valueMode === 'INLINE_TEXT' || locator.valuePart === 'AFTER_DELIMITER';
      if (!inline && labelFirst && labelLast && row >= labelFirst[0] && row <= labelLast[0]
        && column >= labelFirst[1] && column <= labelLast[1]) continue;
      let value = asText(cells[String(row)]?.[String(column)]?.v);
      if (inline) {
        value = value.split(/[：:]/).slice(1).join(':').trim();
      }
      if (value.trim()) values.push(value);
    }
  }
  return values.join('、');
}

export function ExperimentTemplateDataPanel({
  review, values, editable, confirmed, busy, onChange, onFocus, onConfirm, importedSnapshot,
}: Props) {
  const [activeTab, setActiveTab] = useState<'data' | 'structure' | 'mapping'>('data');
  const [selectedBindingId, setSelectedBindingId] = useState<string>();
  const [selectedFieldKey, setSelectedFieldKey] = useState<string>();
  const workbook = useMemo<DataWorkbookSnapshot>(() => {
    const candidateFields = review.fields.flatMap((field): DataFieldValueView[] => {
      const binding = review.bindings.find((item) => item.dataPath === field.dataPath)
        ?? review.bindings.find((item) => item.fieldCode === field.fieldCode);
      if (binding?.mappingKind === 'REPEAT_REGION') return [];
      const dataPath = binding?.dataPath || field.dataPath || field.fieldCode;
      const locator = binding?.locator || {};
      const displayValue = importedSnapshot ? importedValue(importedSnapshot, locator)
        : asText(getAtPath(values, dataPath));
      return [{
        recordId: 'current',
        componentId: 'experiment',
        fieldCode: field.fieldCode,
        fieldName: field.displayName,
        bindingId: binding?.bindingId || `unbound:${field.fieldCode}`,
        valuePath: dataPath,
        valueSource: 'MANUAL',
        valueStatus: field.required && displayValue.trim() === '' ? 'MISSING' : 'VALID',
        valueType: field.dataType,
        unit: field.defaultUnit,
        required: field.required,
        identity: field.identity,
        trainingEligible: binding?.trainingEligible ?? false,
        ragEligible: binding?.ragEligible ?? false,
        sheetId: String(locator.sheetId || ''),
        sheetName: String(locator.sheetName || ''),
        address: String(locator.address || locator.valueRange || ''),
        rawValue: displayValue,
        normalizedValue: displayValue,
        effectiveValue: displayValue,
        editable,
        excluded: false,
        mappingKind: binding?.mappingKind || 'SCALAR',
      }];
    });
    const fields = [...new Map(candidateFields.map((field) => [
      [field.bindingId, field.valuePath, field.sheetId, field.address].join('|'), field,
    ])).values()];
    return {
      fileName: '在线实验记录',
      format: 'XLSX',
      snapshot: {},
      sheets: [],
      editable,
      regions: [{
        regionId: 'experiment',
        name: '本次实验',
        structureType: 'FORM',
        fieldCount: fields.length,
        recordCount: 1,
        fieldGroups: [],
      }],
      records: [{
        recordId: 'current', regionId: 'experiment', label: '本次实验', sequence: 1, excluded: false,
      }],
      fieldDefinitions: fields.map((field) => ({
        componentId: 'experiment',
        bindingId: field.bindingId,
        fieldCode: field.fieldCode,
        displayName: field.fieldName,
        valueType: field.valueType,
        unit: field.unit,
        required: field.required,
        identity: field.identity,
        sheetId: field.sheetId,
        sheetName: field.sheetName,
        sourceRange: field.address,
      })),
      fields,
    };
  }, [review, values, editable, importedSnapshot]);
  const missingCount = workbook.fields.filter((field) => field.valueStatus === 'MISSING').length;
  const select = (field: DataFieldValueView) => {
    setSelectedBindingId(field.bindingId);
    setSelectedFieldKey([field.recordId, field.bindingId, field.valuePath, field.sheetId, field.address].join('|'));
    onFocus(field.bindingId);
  };
  return <aside className="data-workbench-panel experiment-template-data-panel">
    <div className="data-workbench-panel-header">
      <div className="data-workbench-panel-title">
        <Typography.Title level={5}>本次实验数据</Typography.Title>
        <Tag color={confirmed ? 'success' : 'warning'}>{confirmed ? '已确认' : '待确认'}</Tag>
      </div>
      {missingCount ? <Alert type="warning" showIcon message={`有 ${missingCount} 个必填字段未填写，请修正后确认`} /> : null}
    </div>
    <Tabs className="data-workbench-tabs" activeKey={activeTab}
      onChange={(key) => setActiveTab(key as 'data' | 'structure' | 'mapping')}
      items={[
        { key: 'data', label: '字段数据' },
        { key: 'structure', label: '字段结构' },
        { key: 'mapping', label: '字段映射' },
      ]} />
    {activeTab === 'data' ? <DataFieldDataBrowser workbook={workbook} selectedFieldKey={selectedFieldKey}
      onSelectField={select}
      renderFieldExtra={(field) => field.bindingId === selectedBindingId && editable && !importedSnapshot
        ? <Input aria-label={`修改${field.fieldName}`} value={asText(getAtPath(values, field.valuePath))}
            onChange={(event) => onChange(field.valuePath, event.target.value)} />
        : null} /> : null}
    {activeTab === 'structure' ? <DataFieldStructureBrowser workbook={workbook} selectedBindingId={selectedBindingId}
      onSelectField={(field) => {
        setSelectedBindingId(field.bindingId);
        const value = workbook.fields.find((item) => item.bindingId === field.bindingId);
        if (value) setSelectedFieldKey([value.recordId, value.bindingId, value.valuePath, value.sheetId, value.address].join('|'));
        onFocus(field.bindingId);
      }} /> : null}
    {activeTab === 'mapping' ? <div className="data-panel-body">
      <Typography.Paragraph type="secondary">模板字段和填写位置已锁定。请在左侧表格或“字段数据”中修改本次实验值。</Typography.Paragraph>
      {workbook.fields.map((field) => <div className="data-field-card" key={field.bindingId}>
        <button type="button" className="data-field-card-summary" onClick={() => select(field)}>
          <strong>{field.fieldName}</strong><span>{field.sheetName || '工作表'} · {field.address || '模板字段'}</span>
        </button>
      </div>)}
    </div> : null}
    <div className="data-workbench-footer">
      <Button type="primary" disabled={!editable || missingCount > 0 || confirmed} loading={busy}
        onClick={onConfirm}>确认并提交本次数据</Button>
    </div>
  </aside>;
}
