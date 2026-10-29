import { useEffect, useMemo, useState } from 'react';

import type { ExperimentModel } from '@/services/experiments/experiment-api';
import type { RecognitionReview, RecognitionReviewItem, RecognitionSuggestionPayload } from '@/services/templates/template-api';
import { RecognitionReviewPanel } from '@/pages/template-workspace/RecognitionReviewPanel';
import { FieldProperties, FieldStructure } from '@/pages/template-workspace/TemplateFieldManager';
import { CollapsibleFieldPanel } from '@/components/field-panel/CollapsibleFieldPanel';
import type { BusinessField, FieldModel } from '@/features/template-workspace/types';

type Field = NonNullable<ExperimentModel['recognizedFields']>[number];
type Region = NonNullable<ExperimentModel['recognizedRegions']>[number];
type Tab = 'recognition' | 'structure' | 'properties';

function sheetLabel(locator: Field['locator']): string {
  return String(locator.sheetName || locator.sheetId || '当前工作表');
}

function canConfirm(field: Field) {
  return Boolean(field.name.trim() && field.fieldCode.trim() && field.dataPath.trim()
    && (field.locator.address || field.locator.anchorAddress || field.locator.valueRange
      || field.locator.dataRange || field.locator.range));
}

interface Props {
  fields: Field[];
  regions: Region[];
  editable: boolean;
  onUpdateField: (fieldId: string, changes: Partial<Field>) => void;
  onConfirmRegion: (regionId: string, confirmed: boolean) => void;
  onFocusField: (fieldId: string) => void;
  onFocusRegion: (regionId: string) => void;
  onAddField: (regionId?: string) => void;
  onDeleteField: (fieldId: string) => void;
  picking?: { fieldId: string; target: 'labelAddress' | 'address' };
  onPickCoordinate: (fieldId: string, target: 'labelAddress' | 'address') => void;
  reviewTrigger: number;
}

function reviewItem(field: Field): RecognitionReviewItem {
  const locator = field.locator;
  const sheetId = String(locator.sheetId || 'sheet-1');
  const sheetName = sheetLabel(locator);
  const address = String(locator.address || locator.anchorAddress || locator.valueRange || locator.dataRange || locator.range || '');
  return {
    id: field.id, suggestionIds: [field.id], fieldId: field.id,
    fieldName: field.name, description: '', groupName: field.groupName || '基本信息',
    kind: 'SCALAR', valueType: 'string', sheetId, sheetName,
    labelAddress: String(locator.labelAddress || ''), address,
    confidence: field.confidence ?? 1,
    confidenceLevel: (field.confidence ?? 1) >= 0.8 ? 'HIGH' : (field.confidence ?? 1) >= 0.65 ? 'MEDIUM' : 'LOW',
    status: field.confirmed ? 'CONFIRMED' : 'PENDING',
    payload: {
      fieldCode: field.fieldCode, fieldId: field.id, bindingId: field.bindingId,
      fieldName: field.name, dataPath: field.dataPath, valueType: 'string', required: false,
      role: 'FIELD', locator, groupName: field.groupName,
      mappingKind: field.mappingKind as RecognitionSuggestionPayload['mappingKind'],
    },
  };
}

function regionReviewItem(region: Region): RecognitionReviewItem {
  const locator = region.locator;
  return {
    id: region.id, suggestionIds: [region.id], fieldName: region.name,
    description: '', groupName: region.name,
    kind: region.kind as RecognitionReviewItem['kind'], valueType: 'array',
    sheetId: String(locator.sheetId || 'sheet-1'),
    sheetName: sheetLabel(locator),
    labelAddress: '', address: String(locator.range || locator.address || ''),
    confidence: 1, confidenceLevel: 'HIGH', status: region.confirmed ? 'CONFIRMED' : 'PENDING',
    payload: {
      fieldCode: region.bindingId || region.id, bindingId: region.bindingId,
      fieldName: region.name, dataPath: region.dataPath, valueType: 'array', required: false,
      role: 'REPEAT_REGION', locator,
      kind: region.kind as RecognitionSuggestionPayload['kind'],
    },
  };
}

function fieldPosition(field: Field): [number, number] {
  const locator = field.locator;
  const address = String(locator.labelRange || locator.labelAddress || locator.address
    || locator.valueRange || locator.range || '').toUpperCase();
  const match = /^([A-Z]+)([1-9]\d*)/.exec(address);
  if (!match) return [Number.MAX_SAFE_INTEGER, Number.MAX_SAFE_INTEGER];
  const column = [...(match[1] ?? '')].reduce((value, letter) => value * 26 + letter.charCodeAt(0) - 64, 0);
  return [Number(match[2]), column];
}

function buildReview(fields: Field[], regions: Region[]): RecognitionReview {
  const orderedFields = [...fields].sort((left, right) => {
    const a = fieldPosition(left);
    const b = fieldPosition(right);
    return a[0] - b[0] || a[1] - b[1] || left.name.localeCompare(right.name, 'zh-CN');
  });
  const fieldItems = orderedFields.map(reviewItem);
  const rootItems = regions.map(regionReviewItem);
  const pending = fields.filter((field) => !field.confirmed).length;
  return {
    recognitionRunId: 'experiment-recognition', runStatus: 'PARSED',
    summary: {
      total: fields.length, confirmed: fields.length - pending, pending,
      lowConfidence: fields.filter((field) => field.confidence < 0.65).length,
      conflict: 0, ignored: 0, scalar: fields.length, rowTable: 0, columnTable: 0,
      qualityIssueCount: 0, autoFixedCount: 0, blockingIssueCount: 0,
    },
    groups: [...new Set(fields.map((field) => field.groupName || '基本信息'))],
    items: [...rootItems, ...fieldItems], qualityIssues: [],
    recognitionCoverage: {
      status: regions.length ? 'COMPLETE' : 'REVIEW_REQUIRED',
      physicalRegionCount: regions.length,
      expectedRegionCount: regions.length,
      coveredRegionCount: regions.length,
      unresolvedRegionCount: 0,
      coverageRatio: regions.length ? 1 : 0,
    },
    regions: regions.map((region, index) => {
      const locator = region.locator;
      const relatedFields = orderedFields.filter((field) => field.regionId === region.id
        || (index === 0 && !regions.some((candidate) => candidate.id === field.regionId)));
      return {
        regionId: region.id, kind: region.kind,
        sheetId: String(locator.sheetId || 'sheet-1'),
        sheetName: sheetLabel(locator),
        range: String(locator.range || locator.address || ''),
        fieldName: region.name, status: region.confirmed ? 'CONFIRMED' : 'PENDING',
        canonicalStatus: region.confirmed ? 'CONFIRMED' : 'PROVISIONAL',
        structureStatus: region.confirmed ? 'CONFIRMED' : 'PROVISIONAL',
        alternatives: [{ alternativeId: region.id, regions: [{ suggestionId: region.id, kind: region.kind, range: String(locator.range || locator.address || '') }] }],
        fields: relatedFields.map(reviewItem),
        auditSuggestions: [],
      };
    }),
    statistics: {
      regionCount: regions.length, structureAlternativeCount: regions.length,
      structureConflictGroups: 0, fieldCount: fields.length,
      pendingFieldCount: pending, auditSuggestionCount: 0,
    },
  };
}

export function ExperimentFieldReviewPanel({ fields, regions, editable, onUpdateField, onConfirmRegion, onFocusField, onFocusRegion, onAddField, onDeleteField, picking, onPickCoordinate, reviewTrigger }: Props) {
  const [tab, setTab] = useState<Tab>('recognition');
  const [selectedFieldId, setSelectedFieldId] = useState<string>();
  useEffect(() => {
    if (reviewTrigger > 0) setTab('recognition');
  }, [reviewTrigger]);
  const selectedField = fields.find((field) => field.id === selectedFieldId);
  const review = useMemo(() => buildReview(fields, regions), [fields, regions]);
  const fieldModel = useMemo<FieldModel>(() => {
    const names = [...new Set(fields.map((field) => field.groupName || '基本信息'))];
    const groups = names.map((name, order) => ({ id: `experiment-group-${order}`, name, order }));
    const groupId = (name: string) => groups.find((group) => group.name === name)?.id ?? groups[0]?.id ?? 'experiment-group-0';
    const roots: BusinessField[] = regions.map((region) => ({
      id: region.id, fieldId: region.id, bindingId: region.bindingId,
      dataPath: region.dataPath, groupId: groupId('基本信息'), name: region.name,
      displayRole: 'REGION', kind: region.kind === 'FORM_REGION' || region.kind === 'COLUMN_TABLE' ? region.kind : 'ROW_TABLE',
      valueType: 'array', required: false, reviewStatus: region.confirmed ? 'CONFIRMED' : 'NEEDS_CONFIRMATION',
      regionId: region.id, mappingKind: 'REPEAT_REGION',
      locator: { ...region.locator, sheetName: sheetLabel(region.locator) },
    }));
    const children: BusinessField[] = [...fields].sort((left, right) => {
      const a = fieldPosition(left);
      const b = fieldPosition(right);
      return a[0] - b[0] || a[1] - b[1] || left.name.localeCompare(right.name, 'zh-CN');
    }).map((field) => ({
      id: field.id, fieldId: field.id, bindingId: field.bindingId,
      dataPath: field.dataPath, fieldCode: field.fieldCode,
      groupId: groupId(field.groupName || '基本信息'), name: field.name,
      displayRole: 'FIELD', kind: 'SCALAR', valueType: field.valueType || 'string',
      required: field.required ?? false, unit: field.unit, description: field.description,
      standardMatchStatus: field.standardMatchStatus === 'MATCHED' || field.standardMatchStatus === 'CONFIRMED' ? field.standardMatchStatus : 'UNMATCHED',
      fieldType: field.mappingKind === 'REPEAT_FIELD' ? 'TABLE_COLUMN' : undefined,
      reviewStatus: field.confirmed ? 'CONFIRMED' : 'NEEDS_CONFIRMATION',
      confidence: field.confidence, regionId: field.regionId,
      parentFieldId: field.regionId || undefined,
      mappingKind: field.mappingKind === 'REPEAT_FIELD' ? 'REPEAT_FIELD' : 'SCALAR',
      locator: { ...field.locator, sheetName: sheetLabel(field.locator) },
    }));
    return { modelVersion: 1, groups, fields: [...roots, ...children], blocks: [], semanticAnnotations: [] };
  }, [fields, regions]);
  const selectField = (field: Field, nextTab: Tab = 'properties') => {
    setSelectedFieldId(field.id);
    setTab(nextTab);
    onFocusField(field.id);
  };

  return <CollapsibleFieldPanel className="experiment-field-manager" tabs={
    <div className="field-manager-tabs" role="tablist" aria-label="字段管理页面">
      <button type="button" role="tab" data-tab="recognition" aria-selected={tab === 'recognition'} onClick={() => setTab('recognition')}>识别确认</button>
      <button type="button" role="tab" aria-selected={tab === 'structure'} onClick={() => setTab('structure')}>字段结构</button>
      <button type="button" role="tab" aria-selected={tab === 'properties'} disabled={!selectedField} onClick={() => setTab('properties')}>字段属性</button>
    </div>
    }>
    {tab === 'recognition' ? <RecognitionReviewPanel
      review={review}
      editable={editable}
      allowIgnore={false}
      selectedRecognitionItemId={selectedFieldId}
      onSelect={(item) => {
        const field = fields.find((candidate) => candidate.id === item.id);
        if (field) selectField(field, 'recognition');
        else if (regions.some((region) => region.id === item.id)) {
          setSelectedFieldId(item.id);
          onFocusRegion(item.id);
        }
      }}
      onConfirm={(item) => {
        if (regions.some((region) => region.id === item.id)) onConfirmRegion(item.id, true);
        else onUpdateField(item.id, { confirmed: true });
      }}
      onModify={(item) => {
        const field = fields.find((candidate) => candidate.id === item.id);
        if (field) selectField(field);
      }}
      onIgnore={() => {}}
      onRestore={() => {}}
      onSelectQualityIssue={() => {}}
      onApplyQualityIssue={() => {}}
      onIgnoreQualityIssue={() => {}}
      onRollbackQualityIssue={() => {}}
    /> : null}

    {tab === 'structure' ? <FieldStructure
      editable={editable}
      fieldModel={fieldModel}
      recognitionReview={review}
      selectedFieldId={selectedFieldId}
      onSelectField={(item) => {
        const field = fields.find((candidate) => candidate.id === item.id);
        if (field) selectField(field);
        else onFocusRegion(item.id);
      }}
      onAddField={() => onAddField()}
      onAddStructuredField={(parent) => onAddField(parent.id)}
      onManageGroups={() => {}}
      allowGroupManagement={false}
    /> : null}

    {tab === 'properties' && selectedField ? <FieldProperties
      key={selectedField.id}
      editable={editable}
      format="XLSX"
      groups={fieldModel.groups}
      field={fieldModel.fields.find((item) => item.id === selectedField.id)!}
      picking={picking}
      onUpdateField={(fieldId, changes) => {
        const groupName = changes.groupId
          ? fieldModel.groups.find((group) => group.id === changes.groupId)?.name
          : undefined;
        const update: Partial<Field> = {
          ...(changes.name !== undefined ? { name: changes.name } : {}),
          ...(groupName ? { groupName } : {}),
          ...(changes.valueType !== undefined ? { valueType: changes.valueType } : {}),
          ...(changes.unit !== undefined ? { unit: changes.unit } : {}),
          ...(changes.required !== undefined ? { required: changes.required } : {}),
          ...(changes.description !== undefined ? { description: changes.description } : {}),
        };
        onUpdateField(fieldId, { ...update, confirmed: canConfirm({ ...selectedField, ...update }) });
      }}
      onUpdateCoordinates={(fieldId, changes) => {
        const field = fields.find((item) => item.id === fieldId);
        if (!field) return;
        const locator = {
            ...field.locator,
            ...(changes.labelAddress !== undefined ? { labelAddress: changes.labelAddress, labelRange: changes.labelAddress } : {}),
            ...(changes.address !== undefined ? { address: changes.address, valueRange: changes.address } : {}),
            source: 'MANUAL',
          };
        onUpdateField(fieldId, { locator, confirmed: canConfirm({ ...field, locator }) });
      }}
      onPickCoordinate={onPickCoordinate}
      onDeleteField={(field) => onDeleteField(field.id)}
      onPlaceWordField={() => {}}
    /> : null}
  </CollapsibleFieldPanel>;
}
