import { Alert } from 'antd';
import { useEffect, useState } from 'react';
import { FieldProperties, FieldStructure } from '@/pages/template-workspace/TemplateFieldManager';
import { ExperimentSemanticsPanel } from '@/pages/template-workspace/ExperimentSemanticsPanel';
import { projectionLocator, projectionFieldViews, updateProjectionField, type ProjectionFieldTarget } from './list-projection';
import { resolveBindingSelection } from './selection-resolver';
import { normalizeAddress, validateAddress } from '@/pages/template-workspace/coordinates';
import { createCustomFieldWorkspace, expandAdjacentRepeatRegion } from './custom-field-operations';
import type { BusinessField, EditorSelection, TemplateBinding } from './types';
import type { WorkbookTemplateInstance } from './workbook-instance';
import { instanceLocalSemantic } from './workbook-instance';
import { synchronizeLocatorCoordinates, locatorValueRange } from './locator';
import { parseRange } from './structure-migration';

/** Adapter around the existing field manager, shared by instance workbenches. */
export function InstanceFieldEditor({
  instance,
  ownerId,
  editable,
  selection,
  onChange,
  onFocus,
  onFocusBinding,
  hiddenFieldIds,
  displayBindings,
}: {
  instance: WorkbookTemplateInstance;
  ownerId: string;
  editable: boolean;
  selection?: EditorSelection;
  onChange: (next: WorkbookTemplateInstance) => void;
  onFocus: (field: BusinessField) => void;
  onFocusBinding?: (binding: TemplateBinding) => void;
  onWriteLabel?: (field: BusinessField, value: string) => void;
  hiddenFieldIds?: ReadonlySet<string>;
  displayBindings?: TemplateBinding[];
}) {
  const [selectedId, setSelectedId] = useState<string | undefined>(()=>instance.model.fields.find(f=>f.reviewStatus==='NEEDS_CONFIRMATION'||f.reviewStatus==='ISSUE'||f.experimentSemanticStatus==='NEEDS_REVIEW')?.id);
  const [projectionSelection, setProjectionSelection] = useState<{ id: string; target: ProjectionFieldTarget }>();
  const [contextFieldId, setContextFieldId] = useState<string>();
  const [activeView, setActiveView] = useState<'structure' | 'properties'>('structure');
  const selectedProjection = instance.execution?.listProjections.find(
    (projection) => projection.listProjectionId === projectionSelection?.id,
  );
  const projectionViews = projectionFieldViews(instance.execution?.listProjections ?? [], instance.model, instance.mapping);
  const selectedProjectionView = projectionViews.find((view) => view.projection.listProjectionId === projectionSelection?.id && view.target === projectionSelection.target);
  const selectionMatch = selection ? resolveBindingSelection(displayBindings ?? [...projectionViews.map((view) => view.binding), ...instance.mapping], selection) : undefined;
  const selectionView = projectionViews.find((view) => view.binding.bindingId === selectionMatch?.binding.bindingId);
  const selectionKey = selection ? `${selection.sheetId}:${selection.address}` : '';
  const selectionProjectionId = selectionView?.projection.listProjectionId;
  const selectionProjectionTarget = selectionView?.target;
  const selectionFieldId = instance.model.fields.find((field) => field.bindingId === selectionMatch?.binding.bindingId
    && field.displayRole !== 'REGION' && field.mappingKind !== 'REPEAT_REGION')?.id;
  useEffect(() => {
    if (!selectionKey) return;
    if (selectionProjectionId && selectionProjectionTarget) {
      setActiveView('properties');
      setSelectedId(undefined);
      setProjectionSelection((current) => current?.id === selectionProjectionId && current.target === selectionProjectionTarget
        ? current : { id: selectionProjectionId, target: selectionProjectionTarget });
    } else {
      setProjectionSelection(undefined);
      if (selectionFieldId) { setSelectedId(selectionFieldId); setContextFieldId(selectionFieldId); setActiveView('properties'); }
    }
  }, [selectionKey, selectionProjectionId, selectionProjectionTarget, selectionFieldId]);
  const selected = instance.model.fields.find((f) => f.id === selectedId);
  const update = (id: string, changes: Partial<BusinessField>) => {
    const next = structuredClone(instance);
    next.model.fields = next.model.fields.map((f) =>
      f.id === id ? { ...f, ...changes, reviewStatus: 'CONFIRMED' } : f,
    );
    const field = next.model.fields.find((f) => f.id === id);
    if (field)
      next.mapping = next.mapping.map((b) =>
        b.bindingId === field.bindingId
          ? {
              ...b,
              fieldCode: field.fieldCode,
              labelPath: field.pathSegments?.join(' > ') || field.name,
              experimentField: field.experimentField,
              locator: {
                ...b.locator,
                unit: field.unit,
                valueType: field.valueType,
                displayName: field.name,
              },
            }
          : b,
      );
    onChange(next);
  };
  const coordinates = (
    id: string,
    changes: { labelAddress?: string; address?: string; sheetId?: string; sheetName?: string },
  ) => {
    const next = structuredClone(instance),
      field = next.model.fields.find((f) => f.id === id);
    if (!field) return;
    next.mapping = next.mapping.map((b) => {
      if (b.bindingId !== field.bindingId) return b;
      const locator = synchronizeLocatorCoordinates(b.locator, {
        ...changes,
        ...(changes.labelAddress !== undefined ? { labelRange: changes.labelAddress } : {}),
        ...(changes.address !== undefined
          ? { valueRange: changes.address, logicalInputRange: changes.address }
          : {}),
      });
      field.locator = locator;
      field.labelRange = typeof locator.labelRange === 'string' ? locator.labelRange : '';
      field.labelStatus = field.labelRange ? 'RESOLVED' : 'UNRESOLVED';
      field.valueRange = typeof locator.valueRange === 'string' ? locator.valueRange : '';
      field.reviewStatus =
        locator.address || locator.valueRange ? 'CONFIRMED' : 'NEEDS_CONFIRMATION';
      return {
        ...b,
        locator,
        bindingStatus:
          field.reviewStatus === 'CONFIRMED' ? ('VALID' as const) : ('MISSING' as const),
      };
    });
    const binding = next.mapping.find((b) => b.bindingId === field.bindingId);
    if (binding) {
      const expanded = expandAdjacentRepeatRegion(next.mapping, next.model, binding);
      next.mapping = expanded.mapping;
      next.model = expanded.model;
    }
    onChange(next);
  };
  const add = (parent?: BusinessField) => {
    const selectedRow = selection ? parseRange(selection.address)?.startRow : undefined;
    const nearest = instance.model.fields.filter(f => f.displayRole !== 'REGION' && f.mappingKind !== 'REPEAT_REGION'
      && f.locator?.sheetId === selection?.sheetId).map(field => ({ field, row: parseRange(locatorValueRange(field.locator))?.startRow }))
      .filter(item => item.row !== undefined && selectedRow !== undefined && item.row <= selectedRow)
      .sort((a, b) => b.row! - a.row!)[0]?.field;
    const context = parent ?? selected ?? instance.model.fields.find(f => f.id === contextFieldId) ?? nearest;
    if (!parent && context?.parentFieldId) parent = instance.model.fields.find(f => f.id === context.parentFieldId);
    if (!parent && context?.bindingId) {
      const parentId = instance.mapping.find(b => b.bindingId === context.bindingId)?.parentBindingId;
      if (parentId) parent = instance.model.fields.find(f => f.bindingId === parentId);
    }
    const created = createCustomFieldWorkspace({}, instance.model, instance.mapping, {
      ownerId,
      origin: 'INSTANCE_LOCAL',
      kind: parent ? 'REPEAT_FIELD' : 'SCALAR',
      name: '新字段',
      groupId: context?.groupId,
      parentField: parent,
      parentBinding: parent && instance.mapping.find((b) => b.bindingId === parent.bindingId),
      sheet: selection ? { sheetId: selection.sheetId, sheetName: selection.sheetName } : {
        sheetId: typeof instance.mapping[0]?.locator.sheetId === 'string' ? instance.mapping[0].locator.sheetId : '',
        sheetName: typeof instance.mapping[0]?.locator.sheetName === 'string' ? instance.mapping[0].locator.sheetName : '',
      },
    });
    const semantic = instanceLocalSemantic(context);
    created.model.fields = created.model.fields.map((f) =>
      f.id === created.field.id
        ? {
            ...f,
            experimentField: semantic,
            experimentSemanticStatus:
              instance.execution?.templateUsage === 'EXPERIMENT_DATA' ? 'AUTO_CONFIRMED' : undefined,
          }
        : f,
    );
    created.mapping = created.mapping.map((b) =>
      b.bindingId === created.binding.bindingId ? { ...b, experimentField: semantic } : b,
    );
    if (!parent) {
      const componentIds = [...new Set(instance.mapping.filter(b => b.mappingKind === 'SCALAR' && b.locator.sheetId === created.binding.locator.sheetId).map(b => b.componentId || b.locator.componentId).filter((id): id is string => typeof id === 'string' && Boolean(id)))];
      if (componentIds.length === 1) {
        created.mapping = created.mapping.map(b => b.bindingId === created.binding.bindingId ? { ...b, componentId: componentIds[0], locator: { ...b.locator, componentId: componentIds[0] } } : b);
      }
    }
    onChange({ ...instance, model: created.model, mapping: created.mapping });
    setSelectedId(created.field.id);
    setProjectionSelection(undefined);
    setActiveView('properties');
  };
  const renderProperties = () => {
  if (selectedProjection && projectionSelection && selectedProjectionView && instance.execution) {
    const execution = instance.execution;
    return <div>
      {hiddenFieldIds?.has(selectedProjectionView.field.id) && <Alert type="info" message="本次结果未展示" description="切回填写页可查看完整表格。" />}
      <FieldProperties
        key={selectedProjectionView.field.id}
        editable={editable} format="XLSX" groups={instance.model.groups}
        field={selectedProjectionView.field} binding={selectedProjectionView.binding}
        onUpdateField={(_id, update) => onChange({ ...instance, execution: {
          ...execution,
          listProjections: execution.listProjections.map((current) =>
            current.listProjectionId === selectedProjection.listProjectionId
              ? updateProjectionField(current, projectionSelection.target, update) : current),
        } })}
        onUpdateCoordinates={(_id, update) => {
          if (update.address === undefined || validateAddress(normalizeAddress(update.address), false)) return;
          onChange({ ...instance, execution: { ...execution,
            listProjections: execution.listProjections.map((current) => current.listProjectionId === selectedProjection.listProjectionId
              ? { ...current, [projectionSelection.target]: normalizeAddress(update.address ?? '') } : current),
          } });
        }}
        onPickCoordinate={() => {
          if (selection && selection.sheetId === projectionLocator(selectedProjection, instance.mapping)?.sheetId)
            onChange({ ...instance, execution: {
            ...execution,
            listProjections: execution.listProjections.map((current) =>
              current.listProjectionId === selectedProjection.listProjectionId
                ? { ...current, [projectionSelection.target]: selection.address } : current),
          } });
        }}
        onDeleteField={() => undefined} onPlaceWordField={() => undefined}
      />
    </div>;
  }
  if (selected)
    return (
      <div>
        {hiddenFieldIds?.has(selected.id) && <Alert type="info" message="本次结果未展示" description="切回填写页可查看完整表格。" />}
        <FieldProperties
          key={selected.id}
          editable={editable}
          format="XLSX"
          groups={instance.model.groups}
          field={selected}
          binding={instance.mapping.find((b) => b.bindingId === selected.bindingId)}
          onUpdateField={update}
          onUpdateCoordinates={coordinates}
          onPickCoordinate={(id, target) => {
            if (selection)
              coordinates(id, {
                [target]: selection.address,
                sheetId: selection.sheetId,
                sheetName: selection.sheetName,
              });
          }}
          onDeleteField={(field) => {
            onChange({
              ...instance,
              model: {
                ...instance.model,
                fields: instance.model.fields.filter((f) => f.id !== field.id),
              },
              mapping: instance.mapping.filter((b) => b.bindingId !== field.bindingId),
            });
            setSelectedId(undefined);
            setActiveView('structure');
          }}
          onPlaceWordField={() => undefined}
        />
        {instance.execution?.templateUsage === 'EXPERIMENT_DATA' && (
          <ExperimentSemanticsPanel
            editable={editable}
            configuration={instance.execution}
            fieldModel={{ ...instance.model, fields: [selected] }}
            mapping={instance.mapping}
            onChange={(execution) => onChange({ ...instance, execution })}
            onUpdateField={update}
          />
        )}
      </div>
    );
  return null;
  };
  return (
    <div className="instance-field-editor">
      <div className="field-manager-tabs" role="tablist" aria-label="字段定义">
        <button type="button" role="tab" aria-selected={activeView === 'structure'} onClick={() => setActiveView('structure')}>字段列表</button>
        <button type="button" role="tab" aria-selected={activeView === 'properties'} disabled={!selected && !selectedProjectionView} onClick={() => setActiveView('properties')}>字段属性</button>
      </div>
      <div className="instance-field-editor-content">
      {activeView === 'properties' ? renderProperties() : <FieldStructure
        allowGroupManagement={false}
        hiddenFieldIds={hiddenFieldIds}
        editable={editable}
        fieldModel={instance.model}
        experimentConfiguration={instance.execution}
        onSelectProjection={(projection, target) => {
          setSelectedId(undefined);
          setProjectionSelection({ id: projection.listProjectionId, target });
          setActiveView('properties');
          const view = projectionViews.find((item) => item.projection.listProjectionId === projection.listProjectionId && item.target === target);
          if (view && !hiddenFieldIds?.has(view.field.id)) onFocusBinding?.(view.binding);
        }}
        selectedFieldId={selectedProjectionView?.field.id ?? selectedId}
        onSelectField={(f) => {
          setSelectedId(f.id);
          setProjectionSelection(undefined);
          setContextFieldId(f.id);
          setActiveView('properties');
          if (!hiddenFieldIds?.has(f.id)) onFocus(f);
        }}
        onAddField={() => add()}
        onAddStructuredField={(parent) => add(parent)}
        onManageGroups={() => undefined}
      />}
      </div>
    </div>
  );
}
