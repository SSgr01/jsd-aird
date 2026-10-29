import { useEffect, useRef, useState } from 'react';
import type { EditorSelection, FieldModel, TemplateBinding, ExperimentListProjection } from './types';
import { projectionFieldViews, type ProjectionFieldTarget } from './list-projection';
import { resolveBindingSelection } from './selection-resolver';

export type FieldPanelView = 'data' | 'structure' | 'properties';
export type FieldDefinitionTarget = { fieldId?: string; projection?: { id: string; target: ProjectionFieldTarget } };

/** Shared selection rules; data browsing never changes into definition editing. */
export function useFieldDefinitionSelection(options: {
  model: FieldModel; mapping: TemplateBinding[]; projections: ExperimentListProjection[];
  selection?: EditorSelection; displayBindings?: TemplateBinding[];
  view: string; onViewChange: (view: 'properties') => void;
  selectedFieldId?: string;
}) {
  const [target, setTarget] = useState<FieldDefinitionTarget>({ fieldId: options.selectedFieldId });
  const latest = useRef(options); latest.current = options;
  useEffect(() => {
    if (options.selectedFieldId) setTarget({ fieldId: options.selectedFieldId });
  }, [options.selectedFieldId]);
  const selectionKey = options.selection ? `${options.selection.sheetId}:${options.selection.address}` : '';
  useEffect(() => {
    const current = latest.current;
    if (!current.selection) return;
    const views = projectionFieldViews(current.projections, current.model, current.mapping);
    const match = resolveBindingSelection(current.displayBindings ?? [...views.map(view => view.binding), ...current.mapping], current.selection);
    const projection = views.find(view => view.binding.bindingId === match?.binding.bindingId);
    const field = current.model.fields.find(field => field.bindingId === match?.binding.bindingId && field.displayRole !== 'REGION' && field.mappingKind !== 'REPEAT_REGION');
    if (projection) setTarget({ projection: { id: projection.projection.listProjectionId, target: projection.target } });
    else if (field) setTarget({ fieldId: field.id });
    else return;
    if (current.view !== 'data' && current.view !== 'recognition') current.onViewChange('properties');
  }, [selectionKey]);
  return { target, setTarget };
}
