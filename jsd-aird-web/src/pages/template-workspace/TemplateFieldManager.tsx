import {
  AimOutlined,
  CheckCircleOutlined,
  DeleteOutlined,
  EditOutlined,
  ExclamationCircleOutlined,
  FolderOpenOutlined,
  PlusOutlined,
  SettingOutlined,
  TableOutlined,
} from '@ant-design/icons';
import { Button, Empty, Input, Select, Switch, Tooltip } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { MutableRefObject } from 'react';
import { CollapsibleFieldPanel } from '@/components/field-panel/CollapsibleFieldPanel';

import type {
  BusinessField,
  EditorSelection,
  ExperimentImportConfiguration,
  ExperimentListProjection,
  FieldModel,
  TemplateBinding,
  TemplateFormat,
} from '@/features/template-workspace/types';
import { bindingForField } from '@/features/template-workspace/field-model';
import {
  projectionFieldName, projectionGroup, projectionName, projectionUnit,
  projectionFieldViews, updateProjectionField,
  type ProjectionFieldTarget,
} from '@/features/template-workspace/list-projection';
import { isExperimentTemplate } from '@/features/template-workspace/experiment-semantics';
import type {
  RecognitionReview,
  RecognitionReviewItem,
  TemplateQualityIssue,
} from '@/services/templates/template-api';

import { normalizeAddress, validateAddress } from './coordinates';
import { RecognitionReviewPanel } from './RecognitionReviewPanel';
import { ExperimentSemanticsPanel } from './ExperimentSemanticsPanel';
import { resolveBindingSelection } from '@/features/template-workspace/selection-resolver';
import type { ProjectionRangeTarget } from './ExperimentSemanticsPanel';
import {
  locatorLabelRange,
  locatorValueRange,
  mergeLocators,
} from '@/features/template-workspace/locator';

export type CoordinateTarget = 'labelAddress' | 'address';
export type FieldManagerTab = 'recognition' | 'structure' | 'properties' | 'experiment';

interface Props {
  selection?: EditorSelection;
  onFocusBinding?: (binding: TemplateBinding) => void;
  editable: boolean;
  /** Allows production-order review to keep recognition actions editable while
   * keeping the template's field definition read-only. */
  fieldDefinitionEditable?: boolean;
  format: TemplateFormat;
  fieldModel: FieldModel;
  mapping: TemplateBinding[];
  selectedFieldId?: string;
  selectedRecognitionItemId?: string;
  selectedQualityIssueId?: string;
  picking?: { fieldId: string; target: CoordinateTarget };
  activeTab: FieldManagerTab;
  recognitionReview?: RecognitionReview;
  recognitionBusy?: boolean;
  experimentConfiguration: ExperimentImportConfiguration;
  onExperimentConfigurationChange: (configuration: ExperimentImportConfiguration) => void;
  onActiveTabChange: (tab: FieldManagerTab) => void;
  onSelectRecognitionItem: (item: RecognitionReviewItem) => void;
  onConfirmRecognitionItem: (item: RecognitionReviewItem, selectedAlternativeId?: string) => void;
  onModifyRecognitionItem: (item: RecognitionReviewItem) => void;
  onIgnoreRecognitionItem: (item: RecognitionReviewItem) => void;
  onRestoreRecognitionItem: (item: RecognitionReviewItem) => void;
  onSelectQualityIssue: (issue: TemplateQualityIssue) => void;
  onApplyQualityIssue: (issue: TemplateQualityIssue) => void;
  onIgnoreQualityIssue: (issue: TemplateQualityIssue) => void;
  onRollbackQualityIssue: (issue: TemplateQualityIssue) => void;
  onSelectField: (field: BusinessField) => void;
  onUpdateField: (fieldId: string, update: Partial<BusinessField>) => void;
  onUpdateCoordinates: (fieldId: string, update: Partial<Record<CoordinateTarget, string>>) => void;
  onPickCoordinate: (fieldId: string, target: CoordinateTarget) => void;
  onPickProjectionRange: (projectionId: string, target: ProjectionRangeTarget) => void;
  onAddField: (groupId?: string) => void;
  onAddStructuredField: (parent: BusinessField, kind: 'REPEAT_FIELD') => void;
  onDeleteField: (field: BusinessField) => void;
  onManageGroups: () => void;
  onPlaceWordField: (field: BusinessField) => void;
}

const VALUE_TYPES = [
  { value: 'string', label: '文本' },
  { value: 'number', label: '数值' },
  { value: 'integer', label: '整数' },
  { value: 'date', label: '日期' },
  { value: 'datetime', label: '日期时间' },
  { value: 'time', label: '时间' },
  { value: 'duration', label: '时长' },
  { value: 'boolean', label: '是 / 否' },
];

export function TemplateFieldManager({
  editable,
  fieldDefinitionEditable = editable,
  format,
  fieldModel,
  mapping,
  selectedFieldId,
  selectedRecognitionItemId,
  selectedQualityIssueId,
  picking,
  activeTab,
  recognitionReview,
  recognitionBusy,
  experimentConfiguration,
  onExperimentConfigurationChange,
  onActiveTabChange,
  onSelectRecognitionItem,
  onConfirmRecognitionItem,
  onModifyRecognitionItem,
  onIgnoreRecognitionItem,
  onRestoreRecognitionItem,
  onSelectQualityIssue,
  onApplyQualityIssue,
  onIgnoreQualityIssue,
  onRollbackQualityIssue,
  onSelectField,
  onUpdateField,
  onUpdateCoordinates,
  onPickCoordinate,
  onPickProjectionRange,
  onAddField,
  onAddStructuredField,
  onDeleteField,
  onManageGroups,
  onPlaceWordField,
  selection,
  onFocusBinding,
}: Props) {
  const [projectionSelection, setProjectionSelection] = useState<{ id: string; target: ProjectionFieldTarget }>();
  const selectedProjection = experimentConfiguration.listProjections.find(
    (projection) => projection.listProjectionId === projectionSelection?.id,
  );
  const projectionViews = projectionFieldViews(experimentConfiguration.listProjections, fieldModel, mapping);
  const selectedProjectionView = projectionViews.find((view) => view.projection.listProjectionId === projectionSelection?.id
    && view.target === projectionSelection.target);
  const selectionMatch = selection ? resolveBindingSelection([...projectionViews.map((view) => view.binding), ...mapping], selection) : undefined;
  const selectionView = projectionViews.find((view) => view.binding.bindingId === selectionMatch?.binding.bindingId);
  const selectionKey = selection ? `${selection.sheetId}:${selection.address}` : '';
  const selectionProjectionId = selectionView?.projection.listProjectionId;
  const selectionProjectionTarget = selectionView?.target;
  useEffect(() => {
    if (!selectionKey) return;
    setProjectionSelection((current) => selectionProjectionId && selectionProjectionTarget
      ? current?.id === selectionProjectionId && current.target === selectionProjectionTarget
        ? current : { id: selectionProjectionId, target: selectionProjectionTarget }
      : undefined);
  }, [selectionKey, selectionProjectionId, selectionProjectionTarget]);
  const selectedField = fieldModel.fields.find(
    (field) => field.id === selectedFieldId && !isRegionField(field),
  );
  const selectedBinding = selectedField ? bindingForField(selectedField, mapping) : undefined;
  const experimentTemplate = format === 'XLSX' && isExperimentTemplate(experimentConfiguration);

  const selectField = (field: BusinessField) => {
    setProjectionSelection(undefined);
    onSelectField(field);
    onActiveTabChange('properties');
  };

  return (
    <CollapsibleFieldPanel tabs={
      <div className="field-manager-tabs" role="tablist" aria-label="字段管理页面">
        {recognitionReview?.recognitionRunId && editable && (
          <button
            type="button"
            role="tab"
            aria-selected={activeTab === 'recognition'}
            onClick={() => onActiveTabChange('recognition')}
          >
            识别确认
          </button>
        )}
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'structure'}
          onClick={() => onActiveTabChange('structure')}
        >
          字段结构
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'properties'}
          disabled={!selectedField && !selectedProjection}
          onClick={() => onActiveTabChange('properties')}
        >
          字段属性
        </button>
        {experimentTemplate && (
          <button
            type="button"
            role="tab"
            aria-selected={activeTab === 'experiment'}
            onClick={() => onActiveTabChange('experiment')}
          >
            实验数据设置
          </button>
        )}
      </div>
      }>
      {activeTab === 'recognition' && recognitionReview?.recognitionRunId && editable ? (
        <RecognitionReviewPanel
          review={recognitionReview}
          editable={editable}
          selectedRecognitionItemId={selectedRecognitionItemId ?? selectedField?.recognitionItemId}
          selectedQualityIssueId={selectedQualityIssueId}
          busy={recognitionBusy}
          onSelect={onSelectRecognitionItem}
          onConfirm={onConfirmRecognitionItem}
          onModify={onModifyRecognitionItem}
          onIgnore={onIgnoreRecognitionItem}
          onRestore={onRestoreRecognitionItem}
          onSelectQualityIssue={onSelectQualityIssue}
          onApplyQualityIssue={onApplyQualityIssue}
          onIgnoreQualityIssue={onIgnoreQualityIssue}
          onRollbackQualityIssue={onRollbackQualityIssue}
        />
      ) : activeTab === 'experiment' && experimentTemplate ? (
        <ExperimentSemanticsPanel
          editable={editable}
          configuration={experimentConfiguration}
          fieldModel={fieldModel}
          mapping={mapping}
          onChange={onExperimentConfigurationChange}
          onUpdateField={onUpdateField}
          onPickProjectionRange={onPickProjectionRange}
        />
      ) : activeTab === 'structure' ? (
        <FieldStructure
          editable={fieldDefinitionEditable}
          fieldModel={fieldModel}
          recognitionReview={recognitionReview}
          selectedFieldId={selectedProjectionView?.field.id ?? selectedFieldId}
          onSelectField={selectField}
          onAddField={onAddField}
          onAddStructuredField={onAddStructuredField}
          onManageGroups={onManageGroups}
          experimentConfiguration={experimentConfiguration}
          onSelectProjection={(projection, target) => {
            setProjectionSelection({ id: projection.listProjectionId, target });
            const view = projectionViews.find((item) => item.projection.listProjectionId === projection.listProjectionId && item.target === target);
            if (view) onFocusBinding?.(view.binding);
            onActiveTabChange('properties');
          }}
        />
      ) : selectedProjection && projectionSelection && selectedProjectionView ? (
        <FieldProperties
          key={selectedProjectionView.field.id}
          editable={fieldDefinitionEditable}
          format={format} groups={fieldModel.groups}
          field={selectedProjectionView.field} binding={selectedProjectionView.binding}
          onUpdateField={(_id, update) => onExperimentConfigurationChange({
            ...experimentConfiguration,
            listProjections: experimentConfiguration.listProjections.map((projection) =>
              projection.listProjectionId === selectedProjection.listProjectionId
                ? updateProjectionField(projection, projectionSelection.target, update) : projection),
          })}
          onUpdateCoordinates={(_id, update) => {
            if (update.address === undefined || validateAddress(normalizeAddress(update.address), false)) return;
            onExperimentConfigurationChange({ ...experimentConfiguration,
              listProjections: experimentConfiguration.listProjections.map((projection) =>
                projection.listProjectionId === selectedProjection.listProjectionId
                  ? { ...projection, [projectionSelection.target]: normalizeAddress(update.address) } : projection),
            });
          }}
          onPickCoordinate={() => onPickProjectionRange(selectedProjection.listProjectionId, projectionSelection.target)}
          onDeleteField={() => undefined} onPlaceWordField={() => undefined}
        />
      ) : selectedField ? (
        <FieldProperties
          key={selectedField.id}
          editable={fieldDefinitionEditable}
          format={format}
          groups={fieldModel.groups}
          field={selectedField}
          binding={selectedBinding}
          picking={picking}
          onUpdateField={onUpdateField}
          onUpdateCoordinates={onUpdateCoordinates}
          onPickCoordinate={onPickCoordinate}
          onDeleteField={onDeleteField}
          onPlaceWordField={onPlaceWordField}
        />
      ) : (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请先选择字段" />
      )}
    </CollapsibleFieldPanel>
  );
}

export function FieldStructure({
  editable,
  fieldModel,
  recognitionReview,
  selectedFieldId,
  onSelectField,
  onAddField,
  onAddStructuredField,
  onManageGroups,
  allowGroupManagement = true,
  experimentConfiguration,
  onSelectProjection,
  hiddenFieldIds,
}: Pick<
  Props,
  | 'editable'
  | 'fieldModel'
  | 'recognitionReview'
  | 'selectedFieldId'
  | 'onSelectField'
  | 'onAddField'
  | 'onAddStructuredField'
  | 'onManageGroups'
> & {
  allowGroupManagement?: boolean;
  experimentConfiguration?: ExperimentImportConfiguration;
  onSelectProjection?: (projection: ExperimentListProjection, target: ProjectionFieldTarget) => void;
  hiddenFieldIds?: ReadonlySet<string>;
}) {
  const fieldRefs = useRef(new Map<string, HTMLButtonElement>());
  const projections = experimentConfiguration?.listProjections ?? [];
  const regions = buildFieldRegionViews(fieldModel, recognitionReview, projections);
  const realFieldCount = fieldModel.fields.filter((field) => !isRegionField(field)).length + projections.length * 2;
  const pendingFieldCount = fieldModel.fields.filter(
    (field) => !isRegionField(field) && (field.candidate || field.reviewStatus !== 'CONFIRMED'),
  ).length + projections.reduce((count, projection) => count + Number(!projection.labelRange) + Number(!projection.valueRange), 0);

  useEffect(() => {
    if (!selectedFieldId) return;
    const field = fieldRefs.current.get(selectedFieldId);
    let group = field?.closest('details');
    while (group) {
      group.open = true;
      group = group.parentElement?.closest('details');
    }
    field?.scrollIntoView?.({ block: 'nearest' });
  }, [selectedFieldId]);

  return (
    <section className="field-structure-pane" role="tabpanel">
      <div className="field-manager-toolbar">
        <span>
          区域 {regions.length} · 字段 {realFieldCount} · 待确认 {pendingFieldCount}
        </span>
        <div>
          {allowGroupManagement && <Tooltip title="管理业务分组">
            <Button
              size="small"
              icon={<SettingOutlined />}
              disabled={!editable}
              aria-label="管理业务分组"
              onClick={onManageGroups}
            />
          </Tooltip>}
          <Button
            size="small"
            type="primary"
            icon={<PlusOutlined />}
            disabled={!editable}
            onClick={() => onAddField()}
          >
            新增字段
          </Button>
        </div>
      </div>
      <div className="field-manager-help">
        普通字段：在空白位置输入名称后自动发现；明细字段请点击对应区域右侧“＋”。在数据区填写业务值不会创建字段。
      </div>

      <div className="field-region-tree">
        {regions.map((region) => (
          <details className="field-tree-group field-region-group" key={region.id} open>
            <summary>
              <span>
                <FolderOpenOutlined /> {region.name}
                <small className="field-region-meta">
                  {kindLabel(region.kind)}
                  {region.sheet ? ` · ${region.sheet}` : ''}
                  {region.range ? ` · ${region.range}` : ''}
                </small>
              </span>
              <span className="field-tree-group-meta">
                <span>{region.fields.length + (region.projections?.length ?? 0) * 2}</span>
                {editable && ['ROW_TABLE', 'COLUMN_TABLE'].includes(region.kind) && (
                  <Tooltip title={!region.root ? '结构区域尚未确认，请先确认结构' : '新增明细字段'}>
                    <button
                      type="button"
                      className="field-tree-group-add"
                      aria-label={`在${region.name}中新增明细字段`}
                      disabled={!region.root}
                      onClick={(event) => {
                        event.preventDefault();
                        event.stopPropagation();
                        if (!region.root) return;
                        onAddStructuredField(region.root, 'REPEAT_FIELD');
                      }}
                    >
                      <PlusOutlined />
                    </button>
                  </Tooltip>
                )}
              </span>
            </summary>
            <div className="field-tree-items">
              {region.projections?.flatMap((projection) => (['labelRange', 'valueRange'] as const).map((target) => (
                <button type="button" key={`${projection.listProjectionId}:${target}`}
                  aria-current={selectedFieldId === `list:${projection.listProjectionId}:${target}`}
                  ref={(node) => { if (node) fieldRefs.current.set(`list:${projection.listProjectionId}:${target}`, node); else fieldRefs.current.delete(`list:${projection.listProjectionId}:${target}`); }}
                  disabled={!onSelectProjection}
                  onClick={() => onSelectProjection?.(projection, target)}>
                  <span className="field-tree-icon kind-scalar"><TableOutlined /></span>
                  <span className="field-tree-name">
                    <strong>{projectionFieldName(projection, target)}</strong>
                    {hiddenFieldIds?.has(`list:${projection.listProjectionId}:${target}`) && <small>结果未展示</small>}
                    <small>明细字段{target === 'valueRange' && projectionUnit(projection, fieldModel)
                      ? ` · ${projectionUnit(projection, fieldModel)}` : ''} · {projection[target] || '位置待确认'}</small>
                  </span>
                  {projection[target]
                    ? <span className="field-tree-status confirmed" aria-label="已配置"><CheckCircleOutlined /></span>
                    : <span className="field-tree-status needs-review" aria-label="位置待确认"><ExclamationCircleOutlined /></span>}
                </button>
              )))}
              {buildFieldTree(region.fields).map((node) => (
                <FieldTreeNode
                  key={node.field.id}
                  node={node}
                  selectedFieldId={selectedFieldId}
                  fieldRefs={fieldRefs}
                  onSelectField={onSelectField}
                  hiddenFieldIds={hiddenFieldIds}
                />
              ))}
              {!region.fields.length && !region.projections?.length && (
                <span className="field-tree-empty">
                  该区域暂未生成字段。请在“识别确认”中确认结构并识别字段。
                </span>
              )}
            </div>
          </details>
        ))}
      </div>
    </section>
  );
}

type FieldRegionView = {
  id: string;
  name: string;
  kind: string;
  sheet?: string;
  range?: string;
  root?: BusinessField;
  fields: BusinessField[];
  projections?: ExperimentListProjection[];
};

type FieldTreeNode = {
  field: BusinessField;
  children: FieldTreeNode[];
};

function buildFieldTree(fields: BusinessField[]): FieldTreeNode[] {
  const nodes = new Map(
    fields.map((field) => [field.id, { field, children: [] as FieldTreeNode[] }]),
  );
  const roots: FieldTreeNode[] = [];
  for (const node of nodes.values()) {
    const parent = node.field.parentFieldId ? nodes.get(node.field.parentFieldId) : undefined;
    if (parent) parent.children.push(node);
    else roots.push(node);
  }
  return roots;
}

function FieldTreeNode({
  node,
  selectedFieldId,
  fieldRefs,
  onSelectField,
  depth = 0,
  hiddenFieldIds,
}: {
  node: FieldTreeNode;
  selectedFieldId?: string;
  fieldRefs: MutableRefObject<Map<string, HTMLButtonElement>>;
  onSelectField: (field: BusinessField) => void;
  depth?: number;
  hiddenFieldIds?: ReadonlySet<string>;
}) {
  return (
    <div className="field-tree-node">
      <FieldTreeButton
        field={node.field}
        selectedFieldId={selectedFieldId}
        fieldRefs={fieldRefs}
        onSelectField={onSelectField}
        depth={depth}
        hiddenFieldIds={hiddenFieldIds}
      />
      {node.children.map((child) => (
        <FieldTreeNode
          key={child.field.id}
          node={child}
          selectedFieldId={selectedFieldId}
          fieldRefs={fieldRefs}
          onSelectField={onSelectField}
          depth={depth + 1}
          hiddenFieldIds={hiddenFieldIds}
        />
      ))}
    </div>
  );
}

function isRegionField(field: BusinessField) {
  return (
    field.displayRole === 'REGION' ||
    ['FORM_REGION', 'ROW_TABLE', 'COLUMN_TABLE'].includes(field.kind) ||
    field.mappingKind === 'REPEAT_REGION'
  );
}

function buildFieldRegionViews(
  fieldModel: FieldModel,
  review?: RecognitionReview,
  projections: ExperimentListProjection[] = [],
): FieldRegionView[] {
  const regionNodes = review?.regions ?? [];
  const roots = fieldModel.fields.filter((field) => isRegionField(field) && !field.structuralOnly);
  const used = new Set<string>();
  const result: FieldRegionView[] = [];

  for (const region of regionNodes) {
    const root = roots.find((field) => regionRootMatches(field, region));
    const fields = fieldModel.fields.filter((field) => {
      if (isRegionField(field) || used.has(field.id)) return false;
      const matched =
        (root && field.parentFieldId === root.id) ||
        (region.regionId && field.regionId === region.regionId) ||
        (region.blockId && field.blockId === region.blockId) ||
        (region.fields ?? []).some((item) => reviewFieldMatches(field, item));
      if (matched) used.add(field.id);
      return matched;
    });
    result.push({
      id: region.regionId,
      name: region.fieldName || '未命名区域',
      kind: region.kind,
      sheet: displaySheetName(region.sheetName, region.sheetId, root, fields, region.fields ?? []),
      range: root ? locatorValueRange(root.locator) || region.range : region.range,
      root,
      fields,
    });
  }

  for (const root of roots) {
    if (result.some((region) => region.root?.id === root.id || region.id === root.blockId))
      continue;
    const fields = fieldModel.fields.filter((field) => {
      if (isRegionField(field) || used.has(field.id)) return false;
      const matched = field.parentFieldId === root.id
        || (Boolean(root.blockId) && field.blockId === root.blockId);
      if (matched) used.add(field.id);
      return matched;
    });
    result.push({
      id: root.blockId || root.id,
      name: root.name,
      kind: root.kind,
      range: locatorValueRange(root.locator),
      root,
      fields,
    });
  }

  // Published/manual templates can have business groups without recognition
  // regions. Use those definitions instead of discarding their grouping.
  for (const group of [...fieldModel.groups].sort((left, right) => left.order - right.order)) {
    const fields = fieldModel.fields.filter((field) =>
      !isRegionField(field) && !used.has(field.id) && field.groupId === group.id,
    );
    if (!fields.length) continue;
    fields.forEach((field) => used.add(field.id));
    result.push({ id: `group:${group.id}`, name: group.name, kind: 'BUSINESS_GROUP', fields });
  }
  for (const projection of projections) {
    const group = projectionGroup(projection, fieldModel);
    const region = result.find((item) => item.id === `group:${group?.id}`)
      ?? result.find((item) => group && item.fields.some((field) => field.groupId === group.id));
    if (region) region.projections = [...(region.projections ?? []), projection];
    else result.push({
      id: `projection:${projection.listProjectionId}`, name: projectionName(projection, fieldModel),
      kind: 'LIST_REGION', fields: [], projections: [projection],
    });
  }
  const unassigned = fieldModel.fields.filter(
    (field) => !isRegionField(field) && !used.has(field.id),
  );
  if (unassigned.length)
    result.push({ id: 'unassigned', name: '未分区字段', kind: 'UNASSIGNED', fields: unassigned });
  return result;
}

function displaySheetName(
  regionSheetName: string | undefined,
  regionSheetId: string | undefined,
  root: BusinessField | undefined,
  fields: BusinessField[],
  reviewFields: RecognitionReviewItem[],
) {
  const reviewName = reviewFields
    .map((field) => field.sheetName)
    .find((name) => name && !/^sheet-\d+$/i.test(name));
  const recognizedName = fields
    .map((field) => textValue(field.locator?.sheetName))
    .find((name) => name && !/^sheet-\d+$/i.test(name));
  const rootName = textValue(root?.locator?.sheetName);
  const regionNameIsTechnical = !regionSheetName || /^sheet-\d+$/i.test(regionSheetName);
  if (!regionNameIsTechnical) return regionSheetName;
  return reviewName || recognizedName || rootName || regionSheetName || regionSheetId;
}

function regionRootMatches(
  field: BusinessField,
  region: NonNullable<RecognitionReview['regions']>[number],
) {
  return (
    (region.regionId && field.regionId === region.regionId) ||
    (region.blockId && field.blockId === region.blockId) ||
    field.id === region.regionId ||
    field.fieldId === region.regionId
  );
}

function reviewFieldMatches(field: BusinessField, item: RecognitionReviewItem) {
  return (
    field.recognitionItemId === item.id ||
    (item.payload.fieldId && field.fieldId === item.payload.fieldId) ||
    (item.payload.relationId && field.relationId === item.payload.relationId) ||
    (item.payload.bindingId && field.bindingId === item.payload.bindingId)
  );
}

function textValue(value: unknown) {
  return typeof value === 'string' ? value : '';
}

function FieldTreeButton({
  field,
  selectedFieldId,
  fieldRefs,
  onSelectField,
  depth = 0,
  hiddenFieldIds,
}: {
  field: BusinessField;
  selectedFieldId?: string;
  fieldRefs: MutableRefObject<Map<string, HTMLButtonElement>>;
  onSelectField: (field: BusinessField) => void;
  depth?: number;
  hiddenFieldIds?: ReadonlySet<string>;
}) {
  return (
    <button
      type="button"
      className={depth > 0 ? 'field-tree-child' : undefined}
      style={depth > 0 ? { paddingLeft: `${12 + depth * 18}px` } : undefined}
      ref={(node) => {
        if (node) fieldRefs.current.set(field.id, node);
        else fieldRefs.current.delete(field.id);
      }}
      aria-current={field.id === selectedFieldId}
      onClick={() => onSelectField(field)}
    >
      <span className={`field-tree-icon kind-${field.kind.toLowerCase()}`}>
        {field.kind === 'SCALAR' ? <EditOutlined /> : <TableOutlined />}
      </span>
      <span className="field-tree-name">
        <strong title={fieldDisplayPath(field)}>{fieldDisplayPath(field)}</strong>
        {hiddenFieldIds?.has(field.id) && <small>结果未展示</small>}
        <small>
          {field.standardRequired &&
          (field.requiresStandardConfirmation || field.fieldOrigin === 'PENDING_STANDARD')
            ? '待选择业务字段'
            : field.candidate
              ? '待确认候选'
              : field.fieldType === 'TABLE_COLUMN'
                ? '明细列'
                : kindLabel(field.kind)}
          {field.unit ? ` · ${field.unit}` : ''}
        </small>
      </span>
      {field.labelStatus === 'UNRESOLVED' ||
      field.reviewStatus === 'NEEDS_CONFIRMATION' ||
      (field.confidence ?? 1) < 0.85 ? (
        <span
          className="field-tree-status needs-review"
          aria-label={field.labelStatus === 'UNRESOLVED' ? '标签位置待确认' : '建议核对'}
          title={field.labelStatus === 'UNRESOLVED' ? '标签位置待确认' : '建议核对'}
        >
          <ExclamationCircleOutlined />
        </span>
      ) : (
        <span className="field-tree-status confirmed" aria-label="已识别" title="已识别">
          <CheckCircleOutlined />
        </span>
      )}
    </button>
  );
}

function fieldDisplayPath(field: BusinessField) {
  const segments = (field.pathSegments ?? []).map((segment) => segment.trim()).filter(Boolean);
  return segments.length ? segments.join(' > ') : field.name;
}

function fieldNameInputValue(field: BusinessField) {
  return fieldDisplayPath(field);
}

function fieldNameInputUpdate(field: BusinessField, value: string) {
  const entered = value.trim();
  const enteredPath = entered
    .split(/\s*>\s*/)
    .map((segment) => segment.trim())
    .filter(Boolean);
  const currentPath = (field.pathSegments ?? []).map((segment) => segment.trim()).filter(Boolean);
  const nextPath =
    enteredPath.length > 1
      ? enteredPath
      : currentPath.length > 1
        ? [...currentPath.slice(0, -1), entered]
        : enteredPath;
  return {
    name: nextPath.at(-1) ?? entered,
    pathSegments: nextPath.length ? nextPath : [entered],
  };
}

export function FieldProperties({
  editable,
  format,
  groups,
  field,
  binding,
  picking,
  onUpdateField,
  onUpdateCoordinates,
  onPickCoordinate,
  onDeleteField,
  onPlaceWordField,
}: {
  editable: boolean;
  format: TemplateFormat;
  groups: FieldModel['groups'];
  field: BusinessField;
  binding?: TemplateBinding;
  picking?: { fieldId: string; target: CoordinateTarget };
  onUpdateField: Props['onUpdateField'];
  onUpdateCoordinates: Props['onUpdateCoordinates'];
  onPickCoordinate: Props['onPickCoordinate'];
  onDeleteField: Props['onDeleteField'];
  onPlaceWordField: Props['onPlaceWordField'];
}) {
  const locator = mergeLocators(field.locator, binding?.locator);
  const sourceLabelAddress = locatorLabelRange(locator);
  const sourceAddress = locatorValueRange(locator);
  const canDeleteField = editable && !field.candidate && !field.projectionField && field.reviewStatus === 'CONFIRMED';
  const [labelAddress, setLabelAddress] = useState(sourceLabelAddress);
  const [address, setAddress] = useState(sourceAddress);
  const [labelError, setLabelError] = useState<string>();
  const [addressError, setAddressError] = useState<string>();
  const sheetName = text(locator.sheetName) || text(locator.sheetCode) || '当前工作表';

  useEffect(() => setLabelAddress(sourceLabelAddress), [sourceLabelAddress]);
  useEffect(() => setAddress(sourceAddress), [sourceAddress]);

  const reviewText =
    field.reviewStatus === 'ISSUE'
      ? '该字段存在识别冲突，请核对字段属性或 Excel 位置。'
      : field.standardRequired &&
          (field.requiresStandardConfirmation || field.fieldOrigin === 'PENDING_STANDARD')
        ? '该字段被要求绑定标准字段，请选择标准字段，或转为模板自定义字段。'
        : field.standardMatchStatus === 'UNMATCHED'
          ? '该字段将作为模板自定义字段保存，尚未匹配标准字段。'
          : field.reviewStatus === 'NEEDS_CONFIRMATION' || (field.confidence ?? 1) < 0.85
            ? '系统建议核对名称或位置，修改后会自动记为已确认。'
            : undefined;

  const commitCoordinate = (target: CoordinateTarget, rawValue: string) => {
    const value = normalizeAddress(rawValue);
    const error = validateAddress(
      value,
      target === 'labelAddress' && field.fieldType !== 'TABLE_COLUMN',
    );
    if (target === 'labelAddress') {
      setLabelAddress(value);
      setLabelError(error);
    } else {
      setAddress(value);
      setAddressError(error);
    }
    if (!error && value === normalizeAddress(target === 'labelAddress' ? sourceLabelAddress : sourceAddress)) return;
    onUpdateCoordinates(field.id, { [target]: value });
  };

  if (format === 'DOCX') {
    return (
      <section className="field-properties-pane" role="tabpanel">
        <div className="field-properties-scroll">
          {reviewText && <FieldReviewNotice field={field} text={reviewText} />}
          {(field.candidate || field.reviewStatus === 'NEEDS_CONFIRMATION') && (
            <div className="field-property-alert full" role="status">
              待确认字段请在“识别确认”中选择忽略；确认后可在此删除。
            </div>
          )}
          <BusinessPropertyFields
            editable={editable}
            field={field}
            groups={groups}
            onUpdateField={onUpdateField}
          />
          <div className="word-position-note">
            Word 字段位置跟随正文中的内容控件保存，不使用 Excel 单元格坐标。
          </div>
          {editable && (
            <Button block icon={<AimOutlined />} onClick={() => onPlaceWordField(field)}>
              在选中文本/位置插入字段
            </Button>
          )}
        </div>
        {canDeleteField && <DeleteFieldButton field={field} onDeleteField={onDeleteField} />}
      </section>
    );
  }

  return (
    <section className="field-properties-pane" role="tabpanel">
      <div className="field-properties-scroll">
        {reviewText && <FieldReviewNotice field={field} text={reviewText} />}
        {(field.candidate || field.reviewStatus === 'NEEDS_CONFIRMATION') && (
          <div className="field-property-alert full" role="status">
            待确认字段请在“识别确认”中选择忽略；确认后可在此删除。
          </div>
        )}
        <BusinessPropertyFields
          editable={editable}
          field={field}
          groups={groups}
          onUpdateField={onUpdateField}
        />

        <div className="field-property-section">
          <div className="field-property-section-title">Excel 位置</div>
          <span className="field-sheet-name">工作表：{sheetName}</span>
          <div className="field-position-meta">
            <span>
              定位来源：
              {locator.source === 'INFERRED'
                ? '系统推断'
                : locator.source === 'MANUAL'
                  ? '人工配置'
                  : locator.source === 'UNRESOLVED'
                    ? '待确认'
                    : '识别结果'}
            </span>
            {typeof locator.confidence === 'number' && (
              <span>置信度：{Math.round(locator.confidence * 100)}%</span>
            )}
          </div>
          <CoordinateInput
            label="标签位置"
            value={labelAddress}
            error={labelError}
            placeholder={field.fieldType === 'TABLE_COLUMN' ? '例如 A5:C5' : '例如 A2'}
            editable={editable && field.labelStatus !== 'NOT_APPLICABLE'}
            picking={picking?.fieldId === field.id && picking.target === 'labelAddress'}
            onChange={setLabelAddress}
            onCommit={(value) => commitCoordinate('labelAddress', value)}
            onPick={() => onPickCoordinate(field.id, 'labelAddress')}
          />
          {!labelAddress && field.fieldType !== 'MANUAL_VALUE' && field.labelStatus !== 'NOT_APPLICABLE' && (
            <span className="coordinate-help">
              尚未确认标签位置，请从工作表选择标签单元格后再发布。
            </span>
          )}
          <CoordinateInput
            label="填写位置"
            value={address}
            error={addressError}
            placeholder="例如 B2 或 B7:D10"
            editable={editable}
            picking={picking?.fieldId === field.id && picking.target === 'address'}
            onChange={setAddress}
            onCommit={(value) => commitCoordinate('address', value)}
            onPick={() => onPickCoordinate(field.id, 'address')}
          />
          {!address && (
            <span className="coordinate-help">尚未设置填写位置，生产单数据不能写入 Excel。</span>
          )}
          <span className="coordinate-help">
            可直接输入坐标，也可以切换工作表后从左侧 Excel 选择。
          </span>
        </div>
      </div>

      {canDeleteField && <DeleteFieldButton field={field} onDeleteField={onDeleteField} />}
    </section>
  );
}

function FieldReviewNotice({ field, text: notice }: { field: BusinessField; text: string }) {
  return (
    <div
      className="field-review-notice"
      data-tone={field.reviewStatus === 'ISSUE' ? 'conflict' : 'pending'}
    >
      <ExclamationCircleOutlined />
      <span>{notice}</span>
    </div>
  );
}

function BusinessPropertyFields({
  editable,
  field,
  groups,
  onUpdateField,
}: Pick<Props, 'editable' | 'onUpdateField'> & {
  field: BusinessField;
  groups: FieldModel['groups'];
}) {
  return (
    <>
      <div className="field-property-section field-property-grid">
        <div className="field-property-section-title full">基本属性</div>
        <label className="field-property full">
          <span>字段名称</span>
          <Input
            value={fieldNameInputValue(field)}
            disabled={!editable}
            maxLength={100}
            status={(field.name ?? '').trim() ? undefined : 'error'}
            onChange={(event) =>
              onUpdateField(field.id, fieldNameInputUpdate(field, event.target.value))
            }
          />
          {!(field.name ?? '').trim() && <small role="alert">字段名称不能为空</small>}
        </label>
        {field.semanticConflict && (
          <div className="field-property-alert full" role="alert">
            {field.conflictMessage || '这个字段存在识别冲突，请核对后再保存。'}
          </div>
        )}
        {field.mappingKind === 'REPEAT_REGION' && (
          <div className="field-property-layout full">
            <span className="field-property-section-title">重复区域规则</span>
            <small>填写方向：{field.repeatAxis === 'COLUMN' ? '按列' : '按行'}</small>
            <small>
              每条记录：{field.recordHeight || 1} 行 × {field.recordWidth || 1} 列；步长{' '}
              {field.recordStride || 1}
            </small>
            <small>
              数据范围：
              {locationDisplay(field.locator?.dataRange, field.valueRange, field.locator?.address)}
            </small>
            <small>用户可以手动插入行/列，自动新增只在填写末条记录后提供便捷扩展。</small>
          </div>
        )}
        <label className="field-property">
          <span>字段分组</span>
          <Select
            value={field.groupId}
            disabled={!editable || Boolean(field.projectionField)}
            options={groups.map((group) => ({ value: group.id, label: group.name }))}
            onChange={(value) => onUpdateField(field.id, { groupId: value })}
          />
        </label>
        <label className="field-property">
          <span>字段类型</span>
          <Input
            value={
              field.fieldType === 'TABLE_COLUMN'
                ? '表格列字段'
                : field.fieldType === 'MANUAL_VALUE'
                  ? '手工录入字段'
                  : '普通字段'
            }
            disabled
          />
        </label>
        <label className="field-property">
          <span>值类型</span>
          <Select
            value={field.valueType}
            disabled={!editable || field.kind !== 'SCALAR' || Boolean(field.projectionField)}
            options={VALUE_TYPES}
            onChange={(value) => onUpdateField(field.id, { valueType: value, ...(['date', 'datetime', 'time', 'boolean'].includes(value) ? { unit: '' } : {}) })}
          />
        </label>
        <label className="field-property">
          <span>单位</span>
          <Input
            value={field.unit}
            disabled={!editable || Boolean(field.projectionField) || ['date', 'datetime', 'time', 'boolean'].includes(field.valueType)}
            maxLength={40}
            placeholder="可留空"
            onChange={(event) => onUpdateField(field.id, { unit: event.target.value })}
          />
        </label>
        <label className="field-property">
          <span>是否必填</span>
          <Switch
            checked={field.required}
            disabled={!editable || isRegionField(field) || Boolean(field.projectionField)}
            checkedChildren="是"
            unCheckedChildren="否"
            onChange={(checked) => onUpdateField(field.id, { required: checked })}
          />
          {isRegionField(field) && <small>结构区域不参与必填校验</small>}
        </label>
        <label className="field-property full">
          <span>填写说明</span>
          <Input.TextArea
            value={field.description}
            disabled={!editable}
            rows={3}
            maxLength={300}
            placeholder="可选，填写给业务人员看的说明"
            onChange={(event) => onUpdateField(field.id, { description: event.target.value })}
          />
        </label>
      </div>
    </>
  );
}

function locationDisplay(...values: unknown[]) {
  return (
    values.find((value): value is string => typeof value === 'string' && value.trim().length > 0) ||
    '未定位'
  );
}

function CoordinateInput({
  label,
  value,
  error,
  placeholder,
  editable,
  picking,
  onChange,
  onCommit,
  onPick,
}: {
  label: string;
  value: string;
  error?: string;
  placeholder: string;
  editable: boolean;
  picking: boolean;
  onChange: (value: string) => void;
  onCommit: (value: string) => void;
  onPick: () => void;
}) {
  return (
    <label className="field-property coordinate-property">
      <span>{label}</span>
      <div className="coordinate-input-row">
        <Input
          value={value}
          disabled={!editable}
          placeholder={placeholder}
          status={error ? 'error' : undefined}
          onChange={(event) => onChange(event.target.value.toUpperCase())}
          onBlur={(event) => onCommit(event.target.value)}
          onPressEnter={(event) => onCommit(event.currentTarget.value)}
        />
        <Button
          icon={<AimOutlined />}
          type={picking ? 'primary' : 'default'}
          disabled={!editable}
          aria-label={`从 Excel 选择${label}`}
          onClick={onPick}
        >
          {picking ? '选取中' : '选取'}
        </Button>
      </div>
      {error && <small role="alert">{error}</small>}
    </label>
  );
}

function DeleteFieldButton({
  field,
  onDeleteField,
}: {
  field: BusinessField;
  onDeleteField: Props['onDeleteField'];
}) {
  return (
    <div className="field-properties-actions">
      <Button danger icon={<DeleteOutlined />} onClick={() => onDeleteField(field)}>
        删除字段
      </Button>
    </div>
  );
}

function kindLabel(kind: string) {
  if (kind === 'BUSINESS_GROUP') return '业务分组';
  if (kind === 'LIST_REGION') return '明细区域';
  if (kind === 'ROW_TABLE') return '明细表';
  if (kind === 'COLUMN_TABLE') return '横向明细表';
  if (kind === 'FREE_TEXT') return '自由文本区';
  return '普通字段';
}

function text(value: unknown) {
  return typeof value === 'string' ? value : '';
}
