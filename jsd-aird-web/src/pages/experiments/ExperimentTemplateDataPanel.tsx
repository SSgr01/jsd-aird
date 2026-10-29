import { Alert, Input, Tabs, Tag, Typography } from 'antd';
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import {
  DataFieldDataBrowser,
  DataFieldStructureBrowser,
  DataWorkbenchPanel,
} from '@/features/data-workbench/DataWorkbench';
import { getAtPath } from '@/features/template-workspace/path-utils';
import type { DataFieldValueView, DataWorkbookSnapshot } from '@/services/data/data-api';
import type { ExperimentTemplateDataReview } from '@/services/experiments/experiment-api';
import type { EditorSelection } from '@/features/template-workspace/types';
import type { FieldPanelView } from '@/features/template-workspace/field-definition-selection';
import { findFieldForCell } from '@/features/data-workbench/field-cell-selection';
import { AutoSaveFieldInput } from '@/features/data-workbench/AutoSaveFieldInput';
import { DataReviewFooter } from '@/features/data-workbench/DataReviewFooter';
import { FieldDefinitionReview } from '@/features/data-workbench/FieldDefinitionReview';
import type { FieldDefinitionReviewItem } from '@/features/data-workbench/field-definition-review';

export type { FieldDefinitionReviewItem } from '@/features/data-workbench/field-definition-review';

interface Props {
  review?: ExperimentTemplateDataReview;
  values: Record<string, unknown>;
  editable: boolean;
  confirmed: boolean;
  busy: boolean;
  onChange: (path: string, value: string) => void;
  onFocus: (bindingId: string) => void;
  onConfirm: () => void;
  importedSnapshot?: Record<string, unknown>;
  workbook?: DataWorkbookSnapshot;
  embedded?: boolean;
  selectedCell?: EditorSelection;
  onSelectField?: (field: DataFieldValueView) => void;
  onWriteValue?: (field: DataFieldValueView, value: string) => Promise<void>;
  notice?: ReactNode;
  confirmationBlocked?: boolean;
  definitionReviews?: FieldDefinitionReviewItem[];
  onEditDefinition?: (item: FieldDefinitionReviewItem) => void;
  onConfirmDefinition?: (item: FieldDefinitionReviewItem) => Promise<void>;
  structureEditor?: (view: FieldPanelView, onViewChange: (view: 'structure' | 'properties') => void) => ReactNode;
}

const EMPTY_REVIEW = {
  fields: [],
  bindings: [],
  values: {},
  confirmed: false,
} as unknown as ExperimentTemplateDataReview;
const fieldKey = (field: DataFieldValueView) =>
  [field.recordId, field.bindingId, field.valuePath, field.sheetId, field.address].join('|');

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

function importedValue(
  snapshot: Record<string, unknown>,
  locator: Record<string, unknown>,
): string {
  const sheets = snapshot.sheets as Record<string, Record<string, unknown>> | undefined;
  if (!sheets) return '';
  const sheet =
    sheets[String(locator.sheetId || '')] ??
    Object.values(sheets).find((item) => item.name === locator.sheetName);
  if (!sheet) return '';
  const rawRange = String(
    locator.valueRange ||
      locator.address ||
      locator.logicalInputRange ||
      locator.anchorAddress ||
      locator.dataRange ||
      '',
  );
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
      if (
        !inline &&
        labelFirst &&
        labelLast &&
        row >= labelFirst[0] &&
        row <= labelLast[0] &&
        column >= labelFirst[1] &&
        column <= labelLast[1]
      )
        continue;
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
  review = EMPTY_REVIEW,
  values,
  editable,
  confirmed,
  busy,
  onChange,
  onFocus,
  onConfirm,
  importedSnapshot,
  workbook: instanceWorkbook,
  embedded,
  selectedCell,
  onSelectField,
  onWriteValue,
  notice,
  confirmationBlocked,
  definitionReviews = [],
  onEditDefinition,
  onConfirmDefinition,
  structureEditor,
}: Props) {
  const [activeTab, setActiveTab] = useState<FieldPanelView | 'mapping'>('data');
  const [selectedBindingId, setSelectedBindingId] = useState<string>();
  const [selectedFieldKey, setSelectedFieldKey] = useState<string>();
  const [corrections, setCorrections] = useState<Record<string, string>>({});
  const [savingField, setSavingField] = useState<string>();
  const correctionWrites = useRef(new Set<string>());
  const workbook = useMemo<DataWorkbookSnapshot>(() => {
    if (instanceWorkbook) return instanceWorkbook;
    const candidateFields = review.fields.flatMap((field): DataFieldValueView[] => {
      const binding =
        review.bindings.find((item) => item.dataPath === field.dataPath) ??
        review.bindings.find((item) => item.fieldCode === field.fieldCode);
      if (binding?.mappingKind === 'REPEAT_REGION') return [];
      const dataPath = binding?.dataPath || field.dataPath || field.fieldCode;
      const locator = binding?.locator || {};
      const displayValue = importedSnapshot
        ? importedValue(importedSnapshot, locator)
        : asText(getAtPath(values, dataPath));
      return [
        {
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
        },
      ];
    });
    const fields = [
      ...new Map(
        candidateFields.map((field) => [
          [field.bindingId, field.valuePath, field.sheetId, field.address].join('|'),
          field,
        ]),
      ).values(),
    ];
    return {
      fileName: '在线实验记录',
      format: 'XLSX',
      snapshot: {},
      sheets: [],
      editable,
      regions: [
        {
          regionId: 'experiment',
          name: '本次实验',
          structureType: 'FORM',
          fieldCount: fields.length,
          recordCount: 1,
          fieldGroups: [],
        },
      ],
      records: [
        {
          recordId: 'current',
          regionId: 'experiment',
          label: '本次实验',
          sequence: 1,
          excluded: false,
        },
      ],
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
  }, [review, values, editable, importedSnapshot, instanceWorkbook]);
  const missingCount = workbook.fields.filter((field) =>
    ['MISSING', 'BLOCKED'].includes(field.valueStatus),
  ).length;
  useEffect(() => {
    if (!selectedCell) return;
    const field = findFieldForCell(workbook.fields, selectedCell);
    if (field) {
      setSelectedBindingId(field.bindingId);
      setSelectedFieldKey(fieldKey(field));
    }
  }, [selectedCell, workbook.fields]);
  const select = (field: DataFieldValueView) => {
    setSelectedBindingId(field.bindingId);
    setSelectedFieldKey(fieldKey(field));
    if (onSelectField) onSelectField(field);
    else onFocus(field.bindingId);
  };
  const saveCorrection = (field: DataFieldValueView, value: string) => {
    const key = fieldKey(field);
    if (!onWriteValue || correctionWrites.current.has(key)) return;
    correctionWrites.current.add(key);
    setSavingField(key);
    void onWriteValue(field, value)
      .then(() => {
        setCorrections((current) => {
          if (current[key] !== value) return current;
          const next = { ...current };
          delete next[key];
          return next;
        });
      })
      .catch(() => undefined)
      .finally(() => {
        correctionWrites.current.delete(key);
        setSavingField((current) => current === key ? undefined : current);
      });
  };
  const changeCorrection = (field: DataFieldValueView, value: string) => {
    const key = fieldKey(field);
    const original = asText(field.effectiveValue);
    setCorrections((current) => {
      const next = { ...current };
      if (value === original) delete next[key];
      else next[key] = value;
      return next;
    });
  };
  const content = (
    <>
      {missingCount || notice || definitionReviews.length ? <div className="data-workbench-panel-notice">
        {missingCount ? (
          <Alert
            type="warning"
            showIcon
            message={
              instanceWorkbook
                ? `有 ${missingCount} 项数据待核对，请修正后确认`
                : `有 ${missingCount} 个必填字段未填写，请修正后确认`
            }
          />
        ) : null}
        <FieldDefinitionReview
          items={definitionReviews}
          busy={busy}
          onEdit={(item) => {
            setActiveTab('properties');
            setSelectedBindingId(item.bindingId);
            onEditDefinition?.(item);
          }}
          onConfirm={onConfirmDefinition}
        />
        {notice}
      </div> : null}
      <Tabs
        className="data-workbench-tabs"
        activeKey={activeTab}
        onChange={(key) => setActiveTab(key as FieldPanelView | 'mapping')}
        items={[
          { key: 'data', label: '字段数据' },
          { key: 'structure', label: '字段结构' },
          ...(structureEditor ? [{ key: 'properties', label: '字段属性' }] : []),
          ...(!instanceWorkbook ? [{ key: 'mapping', label: '字段映射' }] : []),
        ]}
      />
      {activeTab === 'data' ? (
        <DataFieldDataBrowser
          workbook={workbook}
          definitionIssueCount={definitionReviews.length}
          selectedFieldKey={selectedFieldKey}
          selectedCell={selectedCell}
          onSelectField={select}
          renderFieldExtra={(field) =>
            onWriteValue && editable && field.editable ? (
              <AutoSaveFieldInput
                label={field.fieldName}
                disabled={busy}
                saving={savingField === fieldKey(field)}
                value={corrections[fieldKey(field)] ?? asText(field.effectiveValue)}
                savedValue={asText(field.effectiveValue)}
                onChange={(value) => changeCorrection(field, value)}
                onSave={(value) => saveCorrection(field, value)}
              />
            ) : field.bindingId === selectedBindingId &&
              editable &&
              !importedSnapshot &&
              !instanceWorkbook ? (
              <Input
                aria-label={`修改${field.fieldName}`}
                value={asText(getAtPath(values, field.valuePath))}
                onChange={(event) => onChange(field.valuePath, event.target.value)}
              />
            ) : null
          }
          renderFieldMeta={(field) =>
            instanceWorkbook && field.valueSource === 'FORMULA' ? (
              <Typography.Text type="secondary">公式结果由系统计算</Typography.Text>
            ) : null
          }
        />
      ) : null}
      {structureEditor?.(activeTab === 'mapping' ? 'data' : activeTab, setActiveTab)}
      {activeTab === 'structure' && !structureEditor ? (
        <DataFieldStructureBrowser
          workbook={workbook}
          selectedBindingId={selectedBindingId}
          onSelectField={(field) => {
            setSelectedBindingId(field.bindingId);
            const selectedRecord = workbook.fields.find(
              (item) => fieldKey(item) === selectedFieldKey,
            )?.recordId;
            const value =
              workbook.fields.find(
                (item) => item.bindingId === field.bindingId && item.recordId === selectedRecord,
              ) ?? workbook.fields.find((item) => item.bindingId === field.bindingId);
            if (value) select(value);
            else onFocus(field.bindingId);
          }}
        />
      ) : null}
      {activeTab === 'mapping' ? (
        <div className="data-panel-body">
          <Typography.Paragraph type="secondary">
            模板字段和填写位置已锁定。请在左侧表格或“字段数据”中修改本次实验值。
          </Typography.Paragraph>
          {workbook.fields.map((field) => (
            <div className="data-field-card" key={field.bindingId}>
              <button
                type="button"
                className="data-field-card-summary"
                onClick={() => select(field)}
              >
                <strong>{field.fieldName}</strong>
                <span>
                  {field.sheetName || '工作表'} · {field.address || '模板字段'}
                </span>
              </button>
            </div>
          ))}
        </div>
      ) : null}
      <DataReviewFooter
        editable={editable}
        confirmed={confirmed}
        busy={busy}
        blocked={missingCount > 0 || confirmationBlocked}
        saving={Boolean(savingField) || Object.keys(corrections).length > 0}
        onConfirm={onConfirm}
      />
    </>
  );
  return embedded ? (
    content
  ) : (
    <DataWorkbenchPanel title="本次实验数据" className="experiment-template-data-panel"
      extra={<Tag color={confirmed ? 'success' : 'warning'}>{confirmed ? '已核对' : '待核对'}</Tag>}>
      {content}
    </DataWorkbenchPanel>
  );
}
