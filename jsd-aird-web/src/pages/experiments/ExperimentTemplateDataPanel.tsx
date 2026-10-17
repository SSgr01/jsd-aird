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
}

function asText(value: unknown): string {
  if (value == null) return '';
  if (Array.isArray(value)) return value.map(asText).filter(Boolean).join('、');
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

export function ExperimentTemplateDataPanel({
  review, values, editable, confirmed, busy, onChange, onFocus, onConfirm,
}: Props) {
  const [selectedBindingId, setSelectedBindingId] = useState<string>();
  const [selectedFieldKey, setSelectedFieldKey] = useState<string>();
  const workbook = useMemo<DataWorkbookSnapshot>(() => {
    const candidateFields = review.fields.flatMap((field): DataFieldValueView[] => {
      const binding = review.bindings.find((item) => item.dataPath === field.dataPath)
        ?? review.bindings.find((item) => item.fieldCode === field.fieldCode);
      if (binding?.mappingKind === 'REPEAT_REGION') return [];
      const dataPath = binding?.dataPath || field.dataPath || field.fieldCode;
      const currentValue = getAtPath(values, dataPath);
      const displayValue = asText(currentValue);
      const locator = binding?.locator || {};
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
  }, [review, values, editable]);
  const missingCount = workbook.fields.filter((field) => field.valueStatus === 'MISSING').length;
  const select = (field: DataFieldValueView) => {
    setSelectedBindingId(field.bindingId);
    setSelectedFieldKey([field.recordId, field.bindingId, field.valuePath, field.sheetId, field.address].join('|'));
    onFocus(field.bindingId);
  };
  return <aside className="data-workbench-panel experiment-template-data-panel">
    <div className="data-workbench-panel-header">
      <Typography.Title level={5}>本次实验数据</Typography.Title>
      <Tag color={confirmed ? 'success' : 'warning'}>{confirmed ? '已确认' : '待确认'}</Tag>
    </div>
    {missingCount ? <Alert type="warning" showIcon message={`有 ${missingCount} 个必填字段未填写，请修正后确认`} /> : null}
    <Tabs size="small" items={[
      {
        key: 'data', label: '字段数据',
        children: <DataFieldDataBrowser workbook={workbook} selectedFieldKey={selectedFieldKey}
          onSelectField={select}
          renderFieldExtra={(field) => field.bindingId === selectedBindingId && editable
            ? <Input aria-label={`修改${field.fieldName}`} value={asText(getAtPath(values, field.valuePath))}
                onChange={(event) => onChange(field.valuePath, event.target.value)} />
            : null} />,
      },
      {
        key: 'structure', label: '字段结构',
        children: <DataFieldStructureBrowser workbook={workbook} selectedBindingId={selectedBindingId}
          onSelectField={(field) => {
            setSelectedBindingId(field.bindingId);
            const value = workbook.fields.find((item) => item.bindingId === field.bindingId);
            if (value) setSelectedFieldKey([value.recordId, value.bindingId, value.valuePath, value.sheetId, value.address].join('|'));
            onFocus(field.bindingId);
          }} />,
      },
      {
        key: 'mapping', label: '字段映射',
        children: <div className="data-panel-body">
          <Typography.Paragraph type="secondary">模板字段和填写位置已锁定。请在左侧表格或“字段数据”中修改本次实验值。</Typography.Paragraph>
          {workbook.fields.map((field) => <div className="data-field-card" key={field.bindingId}>
            <button type="button" className="data-field-card-summary" onClick={() => select(field)}>
              <strong>{field.fieldName}</strong><span>{field.sheetName || '工作表'} · {field.address || '模板字段'}</span>
            </button>
          </div>)}
        </div>,
      },
    ]} />
    <div className="data-workbench-footer">
      <Button type="primary" disabled={!editable || missingCount > 0 || confirmed} loading={busy}
        onClick={onConfirm}>确认并提交本次数据</Button>
    </div>
  </aside>;
}
