import { Alert, App, Empty } from 'antd';
import { FieldProperties, FieldStructure } from '@/pages/template-workspace/TemplateFieldManager';
import { projectionLocator, projectionFieldViews, updateProjectionField } from './list-projection';
import { useFieldDefinitionSelection, type FieldPanelView } from './field-definition-selection';
import { normalizeAddress, validateAddress } from '@/pages/template-workspace/coordinates';
import { expandAdjacentRepeatRegion } from './custom-field-operations';
import type { BusinessField, EditorSelection, TemplateBinding } from './types';
import type { WorkbookTemplateInstance } from './workbook-instance';
import { addWorkbookField, updateWorkbookField, deleteWorkbookField } from './workbook-instance';
import { synchronizeLocatorCoordinates } from './locator';

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
  getSnapshot,
  getSelection,
  view,
  onViewChange,
}: {
  instance: WorkbookTemplateInstance;
  ownerId: string;
  editable: boolean;
  selection?: EditorSelection;
  onChange: (next: WorkbookTemplateInstance) => void;
  onFocus: (field: BusinessField) => void;
  onFocusBinding?: (binding: TemplateBinding) => void;
  view: FieldPanelView;
  onViewChange: (view: 'structure' | 'properties') => void;
  hiddenFieldIds?: ReadonlySet<string>;
  displayBindings?: TemplateBinding[];
  getSnapshot?: () => Record<string, unknown> | undefined;
  getSelection?: () => EditorSelection | undefined;
}) {
  const { modal } = App.useApp();
  const { target, setTarget } = useFieldDefinitionSelection({ model: instance.model, mapping: instance.mapping,
    projections: instance.execution?.listProjections ?? [], selection, displayBindings, view, onViewChange });
  const selectedId = target.fieldId, projectionSelection = target.projection;
  const selectedProjection = instance.execution?.listProjections.find(
    (projection) => projection.listProjectionId === projectionSelection?.id,
  );
  const projectionViews = projectionFieldViews(instance.execution?.listProjections ?? [], instance.model, instance.mapping);
  const selectedProjectionView = projectionViews.find((view) => view.projection.listProjectionId === projectionSelection?.id && view.target === projectionSelection.target);
  const argumentsSelection = selection;
  const selected = instance.model.fields.find((f) => f.id === selectedId);
  const update = (id: string, changes: Partial<BusinessField>) => {
    onChange(updateWorkbookField(instance, id, changes));
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
    const created = addWorkbookField(instance, { ownerId, origin: 'INSTANCE_LOCAL', selection: getSelection?.() ?? selection, snapshot: getSnapshot?.(), parentId: parent?.id });
    onChange(created.instance); setTarget({ fieldId: created.fieldId }); onViewChange('properties');
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
          const selection = getSelection?.() ?? argumentsSelection;
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
            const selection = getSelection?.() ?? argumentsSelection;
            if (selection)
              coordinates(id, {
                [target]: selection.address,
                sheetId: selection.sheetId,
                sheetName: selection.sheetName,
              });
          }}
          onDeleteField={(field) => {
            modal.confirm({ title: `删除字段“${field.name}”？`, content: '删除字段定义和填写位置；区域包含的子字段也会移除。表格文字和格式保留。', okText: '删除字段', cancelText: '取消', okButtonProps: { danger: true }, onOk: () => {
              onChange(deleteWorkbookField(instance, field.id));
              setTarget({}); onViewChange('structure');
            } });
          }}
          onPlaceWordField={() => undefined}
        />
      </div>
    );
  return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请先在字段结构或表格中选择字段" />;
  };
  return (
    <div className="instance-field-editor" hidden={view === 'data'}>
      <div className="field-definition-pane" hidden={view !== 'structure'}>
      <FieldStructure
        allowGroupManagement={false}
        hiddenFieldIds={hiddenFieldIds}
        editable={editable}
        fieldModel={instance.model}
        experimentConfiguration={instance.execution}
        onSelectProjection={(projection, target) => {
          setTarget({ projection: { id: projection.listProjectionId, target } });
          onViewChange('properties');
          const view = projectionViews.find((item) => item.projection.listProjectionId === projection.listProjectionId && item.target === target);
          if (view && !hiddenFieldIds?.has(view.field.id)) onFocusBinding?.(view.binding);
        }}
        selectedFieldId={selectedProjectionView?.field.id ?? selectedId}
        onSelectField={(f) => {
          setTarget({ fieldId: f.id });
          onViewChange('properties');
          if (!hiddenFieldIds?.has(f.id)) onFocus(f);
        }}
        onAddField={() => add()}
        onAddStructuredField={(parent) => add(parent)}
        onManageGroups={() => undefined}
      />
      </div>
      <div className="field-definition-pane" hidden={view !== 'properties'}>{view === 'properties' ? renderProperties() : null}</div>
    </div>
  );
}
