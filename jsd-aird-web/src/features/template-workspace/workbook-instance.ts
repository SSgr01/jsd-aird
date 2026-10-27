import { generateUUID } from '@/utils/uuid';
import { potentialFieldLabel, isStructuredDataCell } from './live-field-discovery';
import { locatorValueRange, locatorLabelRange } from './locator';
import { updateFieldDefinition, removeFieldDefinitions } from './field-editing';
import { offsetWorkbookFormula, expandAggregateReferences } from './formula-references';
import { createCustomFieldWorkspace } from './custom-field-operations';
import {
  migrateRange,
  migrateWorkspaceStructure,
  parseRange,
  formatRange,
} from './structure-migration';
import type {
  BusinessField,
  ExperimentImportConfiguration,
  FieldModel,
  TemplateBinding,
  WorkbookStructureOperation,
} from './types';

export interface InstanceIdentity {
  id: string;
  regionId: string;
  sheetId: string;
  axis: 'ROW' | 'COLUMN';
  index: number;
  size: number;
}
export interface InstanceIssue {
  code: string;
  message: string;
  sheetId?: string;
  address?: string;
  fieldId?: string;
}
export interface WorkbookTemplateInstance {
  version: 2;
  model: FieldModel;
  mapping: TemplateBinding[];
  execution?: ExperimentImportConfiguration;
  records: InstanceIdentity[];
  items: InstanceIdentity[];
  issues: InstanceIssue[];
}
export type WorkbookCell = {
  v?: unknown;
  f?: string | null;
  p?: unknown;
  custom?: Record<string, unknown>;
  [key: string]: unknown;
};
export interface InstanceSheet {
  id: string;
  name: string;
  custom?: Record<string, unknown>;
  cellData?: Record<string, Record<string, WorkbookCell>>;
  rowData?: Record<string, Record<string, unknown>>;
  columnData?: Record<string, Record<string, unknown>>;
  mergeData?: Array<{ startRow: number; endRow: number; startColumn: number; endColumn: number }>;
  [key: string]: unknown;
}
export function instanceSheets(snapshot: Record<string, unknown>): InstanceSheet[] {
  return Object.values((snapshot.sheets ?? {}) as Record<string, InstanceSheet>);
}

/** Imported custom row heights are explicit; an old auto-height cache must not shrink them on reopen. */
export function workbookWithExplicitRowHeights(snapshot: Record<string, unknown>) {
  const next = structuredClone(snapshot);
  for (const sheet of instanceSheets(next)) for (const row of Object.values(sheet.rowData ?? {})) {
    if (typeof row.h === 'number' && row.h > 0 && row.ia === undefined) row.ia = 0;
    if (row.ia === 0) delete row.ah;
  }
  return next;
}
export function readWorkbookInstance(
  snapshot: Record<string, unknown>,
): WorkbookTemplateInstance | undefined {
  const value = instanceSheets(snapshot)
    .map((s) => s.custom?.templateInstance)
    .find((v) => (v as { version?: number })?.version === 2);
  return value ? structuredClone(value as WorkbookTemplateInstance) : undefined;
}
export function writeWorkbookInstance(
  snapshot: Record<string, unknown>,
  instance: WorkbookTemplateInstance,
): void {
  const sheet = instanceSheets(snapshot)[0];
  if (sheet) sheet.custom = { ...sheet.custom, templateInstance: structuredClone(instance) };
}
export function createWorkbookInstance(
  model: FieldModel,
  mapping: TemplateBinding[],
  execution?: ExperimentImportConfiguration,
): WorkbookTemplateInstance {
  const instance: WorkbookTemplateInstance = {
    version: 2,
    model: structuredClone(model),
    mapping: structuredClone(mapping),
    execution: execution && structuredClone(execution),
    records: [],
    items: [],
    issues: [],
  };
  for (const binding of instance.mapping.filter((b) => b.mappingKind === 'REPEAT_REGION')) {
    let root = instance.model.fields.find((f) => f.bindingId === binding.bindingId);
    if (!root) {
      root = {
        id: `region:${binding.bindingId}`,
        structuralOnly: true,
        bindingId: binding.bindingId,
        dataPath: binding.dataPath,
        groupId: instance.model.groups[0]?.id ?? 'other',
        name:
          locatorText(binding.diagnostic?.businessName) ||
          locatorText(binding.locator.displayName) ||
          '明细记录',
        kind: binding.repeatAxis === 'COLUMN' ? 'COLUMN_TABLE' : 'ROW_TABLE',
        displayRole: 'REGION',
        fieldType: 'REGION',
        valueType: 'array',
        required: false,
        reviewStatus: 'CONFIRMED',
        mappingKind: 'REPEAT_REGION',
        repeatAxis: binding.repeatAxis,
        recordHeight: binding.recordHeight,
        recordWidth: binding.recordWidth,
        recordStride: binding.recordStride,
        locator: binding.locator,
      };
      instance.model.fields.unshift(root);
    }
    for (const field of instance.model.fields)
      if (
        instance.mapping.some(
          (b) => b.bindingId === field.bindingId && b.parentBindingId === binding.bindingId,
        )
      )
        field.parentFieldId = root.id;
  }
  for (const binding of mapping.filter((b) => b.mappingKind === 'REPEAT_REGION')) {
    const range = parseRange(
      locatorText(
        binding.locator.dataRange || binding.locator.address || binding.locator.range || '',
      ),
    );
    if (!range) continue;
    const axis = binding.repeatAxis === 'COLUMN' ? 'COLUMN' : 'ROW';
    const size = Math.max(1, (axis === 'ROW' ? binding.recordHeight : binding.recordWidth) || 1);
    const stride = Math.max(size, binding.recordStride || 1);
    const first = axis === 'ROW' ? range.startRow : range.startColumn;
    const end = axis === 'ROW' ? range.endRow : range.endColumn;
    for (let index = first; index + size - 1 <= end; index += stride)
      instance.records.push({
        id: generateUUID(),
        regionId: binding.bindingId,
        sheetId: locatorText(binding.locator.sheetId || ''),
        axis,
        index,
        size,
      });
  }
  for (const projection of execution?.listProjections ?? []) {
    const range = parseRange(projection.labelRange);
    const parent = mapping.find(
      (b) => b.bindingId === projection.parentBindingId || b.componentId === projection.componentId,
    );
    if (!range || !parent) continue;
    const axis = projection.itemAxis;
    const first = axis === 'ROW' ? range.startRow : range.startColumn,
      end = axis === 'ROW' ? range.endRow : range.endColumn;
    for (let index = first; index <= end; index++)
      instance.items.push({
        id: generateUUID(),
        regionId: projection.listProjectionId,
        sheetId: locatorText(parent.locator.sheetId || ''),
        axis,
        index,
        size: 1,
      });
  }
  return instance;
}

/** Geometry and logical identities move together. This function has no experiment-specific rules. */
export function migrateWorkbookInstance(
  source: WorkbookTemplateInstance,
  operation: WorkbookStructureOperation,
): WorkbookTemplateInstance {
  const instance = structuredClone(source);
  const migrated = migrateWorkspaceStructure(source.mapping, source.model, operation);
  instance.mapping = migrated.mapping;
  instance.model = migrated.model;
  const rows = operation.type.endsWith('ROWS'),
    axis = rows ? 'ROW' : 'COLUMN';
  const index = operation.index,
    count = operation.count;
  if (!index || !count) return instance;
  const insert = operation.type.startsWith('INSERT');
  if (insert) for (const binding of instance.mapping) {
    const old = source.mapping.find(item => item.bindingId === binding.bindingId);
    const region = old?.mappingKind === 'REPEAT_REGION' ? old : source.mapping.find(item => item.bindingId === old?.parentBindingId);
    if (!old || !region || old.locator.sheetId !== operation.sheetId || (region.repeatAxis === 'COLUMN' ? 'COLUMN' : 'ROW') !== axis) continue;
    const bounds = parseRange(locatorText(region.locator.dataRange || region.locator.address));
    if (!bounds || index !== (rows ? bounds.startRow : bounds.startColumn)) continue;
    for (const key of ['dataRange', 'valueRange', 'address', 'range', 'logicalInputRange', 'recordRange', 'anchorAddress', 'anchorRange', 'valueAddress', 'valueAnchor']) {
      const previous = parseRange(locatorText(old.locator[key])), current = parseRange(locatorText(binding.locator[key]));
      if (!previous || !current || index !== (rows ? previous.startRow : previous.startColumn)) continue;
      const anchor = ['anchorAddress', 'valueAddress', 'valueAnchor'].includes(key);
      binding.locator[key] = formatRange({ ...current, ...(rows ? { startRow: index, ...(anchor ? { endRow: index } : {}) } : { startColumn: index, ...(anchor ? { endColumn: index } : {}) }) });
    }
    if (binding.locator.value && typeof binding.locator.value === 'object') binding.locator.value = { ...binding.locator.value,
      range: locatorText(binding.locator.valueRange || binding.locator.logicalInputRange || binding.locator.address),
      address: locatorText(binding.locator.valueRange || binding.locator.address).split(':')[0] };
  }
  const move = (list: InstanceIdentity[]) => list.flatMap(identity => {
    if (identity.sheetId !== operation.sheetId || identity.axis !== axis) return [identity];
    const end = identity.index + identity.size - 1;
    if (insert) {
      if (index > identity.index && index <= end) return [{ ...identity, size: identity.size + count }];
      return [{ ...identity, index: identity.index >= index ? identity.index + count : identity.index }];
    }
    const overlap = Math.max(0, Math.min(end, index + count - 1) - Math.max(identity.index, index) + 1);
    if (overlap === identity.size) return [];
    return [{ ...identity, size: identity.size - overlap,
      index: identity.index >= index ? Math.max(index, identity.index - count) : identity.index }];
  });
  instance.records = move(instance.records);
  instance.items = move(instance.items);
  const add = (
    list: InstanceIdentity[],
    regionId: string,
    first: number,
    end: number,
    size: number,
    stride: number,
  ) => {
    if (!insert || index < first || index > end + 1) return;
    if ((index - first) % stride || count % stride) {
      return;
    }
    for (let n = 0; n < count; n += stride)
      list.push({
        id: generateUUID(),
        regionId,
        sheetId: operation.sheetId,
        axis,
        index: index + n,
        size,
      });
  };
  for (const binding of source.mapping.filter(
    (b) =>
      b.mappingKind === 'REPEAT_REGION' &&
      b.locator.sheetId === operation.sheetId &&
      (b.repeatAxis === 'COLUMN' ? 'COLUMN' : 'ROW') === axis,
  )) {
    const range = parseRange(
      locatorText(
        binding.locator.dataRange || binding.locator.address || binding.locator.range || '',
      ),
    );
    if (!range) continue;
    const size = Math.max(1, (rows ? binding.recordHeight : binding.recordWidth) || 1);
    add(
      instance.records,
      binding.bindingId,
      rows ? range.startRow : range.startColumn,
      rows ? range.endRow : range.endColumn,
      size,
      Math.max(size, binding.recordStride || 1),
    );
  }
  for (const projection of instance.execution?.listProjections ?? []) {
    const original = source.execution?.listProjections.find(
      (p) => p.listProjectionId === projection.listProjectionId,
    );
    const parent = source.mapping.find(
      (b) => b.bindingId === projection.parentBindingId || b.componentId === projection.componentId,
    );
    if (!original || parent?.locator.sheetId !== operation.sheetId) continue;
    const range = parseRange(original.labelRange);
    if (range && projection.itemAxis === axis)
      add(
        instance.items,
        projection.listProjectionId,
        rows ? range.startRow : range.startColumn,
        rows ? range.endRow : range.endColumn,
        1,
        1,
      );
    for (const key of ['labelRange', 'valueRange', 'unitRange', 'totalRange'] as const) {
      const value = original[key];
      if (!value) continue;
      const moved = migrateRange(value, operation);
      projection[key] = moved.value;
      const previous = parseRange(value), current = parseRange(moved.value);
      if (insert && previous && current && index === (rows ? previous.startRow : previous.startColumn)
        && (projection.itemAxis === axis && ['labelRange', 'valueRange', 'unitRange'].includes(key)
          || projection.recordAxis === axis && ['valueRange', 'totalRange'].includes(key))) {
        projection[key] = formatRange({ ...current, ...(rows ? { startRow: index } : { startColumn: index }) });
      }
      // An append at the item's tail belongs to the configured item region.
      if (
        insert &&
        range &&
        projection.itemAxis === axis &&
        index === (rows ? range.endRow : range.endColumn) + 1 &&
        ['labelRange', 'valueRange', 'unitRange'].includes(key)
      ) {
        const parsed = parseRange(moved.value);
        if (parsed)
          projection[key] = formatRange({
            ...parsed,
            ...(rows ? { endRow: parsed.endRow + count } : { endColumn: parsed.endColumn + count }),
          });
      }
      const recordRange = parseRange(original.valueRange);
      if (
        insert &&
        recordRange &&
        projection.recordAxis === axis &&
        index === (rows ? recordRange.endRow : recordRange.endColumn) + 1 &&
        ['valueRange', 'totalRange'].includes(key)
      ) {
        const parsed = parseRange(projection[key] || '');
        if (parsed)
          projection[key] = formatRange({
            ...parsed,
            ...(rows ? { endRow: parsed.endRow + count } : { endColumn: parsed.endColumn + count }),
          });
      }
    }
  }
  // Child fields must grow with records appended at the outer boundary.
  for (const binding of instance.mapping.filter((b) => b.mappingKind === 'REPEAT_FIELD')) {
    const parent = instance.mapping.find((b) => b.bindingId === binding.parentBindingId);
    const previous = source.mapping.find((b) => b.bindingId === binding.bindingId);
    if (!parent || !previous || parent.locator.sheetId !== operation.sheetId || !insert) continue;
    const range = parseRange(
      locatorText(previous.locator.valueRange || previous.locator.address || ''),
    );
    if (
      !range ||
      (parent.repeatAxis === 'COLUMN' ? 'COLUMN' : 'ROW') !== axis ||
      index !== (rows ? range.endRow : range.endColumn) + 1
    )
      continue;
    for (const key of ['address', 'range', 'valueRange', 'logicalInputRange']) {
      const old = parseRange(locatorText(previous.locator[key] || ''));
      if (old)
        binding.locator[key] = formatRange({
          ...old,
          ...(rows ? { endRow: old.endRow + count } : { endColumn: old.endColumn + count }),
        });
    }
  }
  const removedFields: string[] = [];
  for (const field of instance.model.fields) {
    const old = source.mapping.find(binding => binding.bindingId === field.bindingId);
    const binding = instance.mapping.find(item => item.bindingId === field.bindingId);
    if (!old || !binding || old.locator.sheetId !== operation.sheetId) continue;
    const value = locatorValueRange(old.locator);
    const migratedValue = value && migrateRange(value, operation);
    if (!insert && migratedValue && migratedValue.removed) { removedFields.push(field.id); continue; }
    const label = locatorLabelRange(old.locator);
    const lostLabel = !insert && label && migrateRange(label, operation).removed;
    const range = parseRange(value);
    const parent = source.mapping.find(item => item.bindingId === old.parentBindingId);
    const recordAxis = parent?.repeatAxis === 'COLUMN' ? 'COLUMN' : 'ROW';
    const overlaps = !insert && range && index <= (rows ? range.endRow : range.endColumn) && index + count - 1 >= (rows ? range.startRow : range.startColumn);
    if (lostLabel || overlaps && !(old.mappingKind === 'REPEAT_FIELD' && recordAxis === axis)) {
      field.reviewStatus = 'NEEDS_CONFIRMATION';
      if (lostLabel) field.labelStatus = 'UNRESOLVED';
    }
    field.locator = binding.locator;
  }
  for (const id of removedFields) {
    const cleaned = deleteWorkbookField(instance, id);
    instance.model = cleaned.model; instance.mapping = cleaned.mapping; instance.execution = cleaned.execution;
    instance.records = cleaned.records; instance.items = cleaned.items; instance.issues = cleaned.issues;
  }
  instance.model.fields = instance.model.fields.map(field => ({ ...field,
    locator: instance.mapping.find(binding => binding.bindingId === field.bindingId)?.locator ?? field.locator,
    valueRange: locatorValueRange(instance.mapping.find(binding => binding.bindingId === field.bindingId)?.locator) || field.valueRange,
    labelRange: locatorLabelRange(instance.mapping.find(binding => binding.bindingId === field.bindingId)?.locator) || field.labelRange,
    columns: field.columns?.map(column => ({ ...column,
      locator: instance.mapping.find(binding => binding.bindingId === column.bindingId)?.locator ?? column.locator,
    })),
  }));
  instance.records.sort((a, b) => a.index - b.index);
  instance.items.sort((a, b) => a.index - b.index);
  instance.issues = instance.issues.filter(issue => issue.code !== 'PARTIAL_RECORD');
  for (const binding of instance.mapping.filter(item => item.mappingKind === 'REPEAT_REGION')) {
    const regionAxis = binding.repeatAxis === 'COLUMN' ? 'COLUMN' : 'ROW';
    const range = parseRange(locatorText(binding.locator.dataRange || binding.locator.address));
    if (!range) continue;
    const first = regionAxis === 'ROW' ? range.startRow : range.startColumn;
    const last = regionAxis === 'ROW' ? range.endRow : range.endColumn;
    const size = Math.max(1, (regionAxis === 'ROW' ? binding.recordHeight : binding.recordWidth) || 1);
    const stride = Math.max(size, binding.recordStride || 1);
    const records = instance.records.filter(record => record.regionId === binding.bindingId).sort((a, b) => a.index - b.index);
    if (records.some((record, n) => record.size !== size || record.index !== first + n * stride)
      || ((last - first + 1) % stride !== 0 && (last - first + 1 - size) % stride !== 0)) instance.issues.push({ code: 'PARTIAL_RECORD', sheetId: String(binding.locator.sheetId), message: '行列操作拆分了重复记录，请核对区域边界或撤销该操作' });
  }
  return instance;
}

export interface StructureCopy {
  source: string;
  target: string;
  rowOffset: number;
  columnOffset: number;
  axis: 'ROW' | 'COLUMN';
  sourceIndex: number;
  targetIndex: number;
  size: number;
}

/** Update configured aggregate references at an appended edge (which Excel does not always expand). */
export function retargetAggregateFormula(
  formula: string,
  changes: Array<{ before: string; after: string; axis: 'ROW' | 'COLUMN' }>,
): string {
  return expandAggregateReferences(formula, changes);
}
/** Plan copies using the pre-operation geometry; SDK applies these inside the same undo transaction. */
export function structureCopies(
  instance: WorkbookTemplateInstance,
  operation: WorkbookStructureOperation,
): StructureCopy[] {
  if (!operation.type.startsWith('INSERT') || !operation.index || !operation.count) return [];
  const axis = operation.type.endsWith('ROWS') ? 'ROW' : 'COLUMN',
    rows = axis === 'ROW',
    index = operation.index,
    count = operation.count;
  const regions: Array<{ range: string; size: number; stride: number }> = instance.mapping
    .filter(
      (b) =>
        b.mappingKind === 'REPEAT_REGION' &&
        b.locator.sheetId === operation.sheetId &&
        (b.repeatAxis === 'COLUMN' ? 'COLUMN' : 'ROW') === axis,
    )
    .map((b) => ({
      range: locatorText(b.locator.dataRange || b.locator.address || ''),
      size: Math.max(1, (rows ? b.recordHeight : b.recordWidth) || 1),
      stride: Math.max(1, b.recordStride || (rows ? b.recordHeight : b.recordWidth) || 1),
    }));
  for (const projection of instance.execution?.listProjections ?? []) {
    const parent = instance.mapping.find(
      (b) => b.bindingId === projection.parentBindingId || b.componentId === projection.componentId,
    );
    if (projection.itemAxis === axis && parent?.locator.sheetId === operation.sheetId) {
      const labels = parseRange(projection.labelRange),
        values = parseRange(projection.valueRange);
      if (labels && values)
        regions.push({
          range: formatRange({
            startRow: Math.min(labels.startRow, values.startRow),
            endRow: Math.max(labels.endRow, values.endRow),
            startColumn: Math.min(labels.startColumn, values.startColumn),
            endColumn: Math.max(labels.endColumn, values.endColumn),
          }),
          size: 1,
          stride: 1,
        });
    }
  }
  const copies: StructureCopy[] = [];
  for (const region of regions) {
    const r = parseRange(region.range);
    if (!r) continue;
    const first = rows ? r.startRow : r.startColumn,
      last = rows ? r.endRow : r.endColumn;
    if (
      index < first ||
      index > last + 1 ||
      (index - first) % region.stride ||
      count % region.stride
    )
      continue;
    const originalIndex = index === first ? first : index - region.stride;
    const sourceIndex = originalIndex >= index ? originalIndex + count : originalIndex;
    const source = formatRange({
      ...r,
      ...(rows
        ? { startRow: sourceIndex, endRow: sourceIndex + region.size - 1 }
        : { startColumn: sourceIndex, endColumn: sourceIndex + region.size - 1 }),
    });
    for (let n = 0; n < count; n += region.stride) {
      const targetIndex = index + n;
      copies.push({
        source,
        target: formatRange({
          ...r,
          ...(rows
            ? { startRow: targetIndex, endRow: targetIndex + region.size - 1 }
            : { startColumn: targetIndex, endColumn: targetIndex + region.size - 1 }),
        }),
        rowOffset: rows ? targetIndex - sourceIndex : 0,
        columnOffset: rows ? 0 : targetIndex - sourceIndex,
        axis,
        sourceIndex,
        targetIndex,
        size: region.size,
      });
    }
  }
  return copies;
}

export function validateWorkbookInstance(instance: WorkbookTemplateInstance): InstanceIssue[] {
  return [
    ...instance.issues,
    ...instance.model.fields
      .filter(
        (f) =>
          f.displayRole !== 'REGION' &&
          (f.reviewStatus !== 'CONFIRMED' ||
            (instance.execution?.templateUsage === 'EXPERIMENT_DATA' &&
              f.experimentSemanticStatus === 'NEEDS_REVIEW') ||
            !instance.mapping.some(
              (b) =>
                b.bindingId === f.bindingId &&
                b.bindingStatus === 'VALID' &&
                Boolean(b.locator.address || b.locator.valueRange),
            )),
      )
      .map((f) => ({
        code: 'FIELD_UNCONFIRMED',
        fieldId: f.id,
        message: `字段“${f.name}”的名称、含义或填写位置待确认`,
      })),
  ];
}

export function instanceFieldForBinding(
  instance: WorkbookTemplateInstance,
  field: BusinessField,
): TemplateBinding | undefined {
  return instance.mapping.find((b) => b.bindingId === field.bindingId);
}

export function recordUnboundCell(
  source: WorkbookTemplateInstance,
  selection: { sheetId: string; address: string },
  value: unknown,
  snapshot?: Record<string, unknown>,
): WorkbookTemplateInstance | undefined {
  const at = parseRange(selection.address);
  if (!at || at.startRow !== at.endRow || at.startColumn !== at.endColumn) return;
  const includes = (text: unknown) => {
    const r = parseRange(locatorText(text));
    return (
      r &&
      at.startRow >= r.startRow &&
      at.endRow <= r.endRow &&
      at.startColumn >= r.startColumn &&
      at.endColumn <= r.endColumn
    );
  };
  const sheet = snapshot && instanceSheets(snapshot).find(item => item.id === selection.sheetId);
  const heading = typeof value === 'string' && source.model.groups.some(group => group.name.replace(/\s/g, '') === value.replace(/\s/g, ''))
    && sheet?.mergeData?.some(merge => merge.startRow === at.startRow - 1 && merge.startColumn === at.startColumn - 1
      && merge.endRow > merge.startRow);
  const known = heading || source.model.staticRegions?.some(region => region.sheetId === selection.sheetId && includes(region.address)) ||
    source.mapping.some(
      (b) =>
        b.mappingKind !== 'REPEAT_REGION' &&
        b.locator.sheetId === selection.sheetId &&
        ['valueRange', 'address', 'labelRange', 'labelAddress'].some((k) => includes(b.locator[k])),
    ) ||
    (source.execution?.listProjections ?? []).some(
      (p) => projectionSheet(source, p) === selection.sheetId && (includes(p.labelRange) || includes(p.valueRange) || includes(p.unitRange)),
    );
  const existing = source.issues.some(
    (i) =>
      i.code === 'UNBOUND_VALUE' &&
      i.sheetId === selection.sheetId &&
      i.address === selection.address,
  );
  const blank = value == null || value === '';
  if ((known || blank) && !existing) return;
  const next = structuredClone(source);
  next.issues = next.issues.filter(
    (i) =>
      !(
        i.code === 'UNBOUND_VALUE' &&
        i.sheetId === selection.sheetId &&
        i.address === selection.address
      ),
  );
  if (!known && !blank)
    next.issues.push({
      code: 'UNBOUND_VALUE',
      sheetId: selection.sheetId,
      address: selection.address,
      message: '此处有内容但没有对应字段，请使用新增字段确认填写位置',
    });
  return next;
}

/** Local fields retain their business domain without becoming standard training indicators. */
export function instanceLocalSemantic(context?: BusinessField) {
  const domain = context?.experimentField?.domain;
  return domain === 'TEST' ? { domain, field: 'VALUE' } as const
    : domain === 'PROCESS' ? { domain, field: 'OTHER' } as const
      : { domain: 'OTHER', field: 'DYNAMIC_VALUE' } as const;
}

export function discoverWorkbookField(
  source: WorkbookTemplateInstance,
  selection: { sheetId: string; sheetName: string; address: string },
  value: unknown,
  snapshot?: Record<string, unknown>,
): WorkbookTemplateInstance | undefined {
  const name = potentialFieldLabel(value);
  if (!name) return undefined;
  const at = parseRange(selection.address);
  if (!at || at.startRow !== at.endRow || at.startColumn !== at.endColumn) return undefined;
  if (source.model.staticRegions?.some(region => region.sheetId === selection.sheetId && rangeContains(region.address, selection.address))) return undefined;
  if (source.mapping.some(binding => binding.locator.sheetId === selection.sheetId && binding.mappingKind !== 'REPEAT_REGION'
    && [locatorLabelRange(binding.locator), locatorValueRange(binding.locator)].some(range => rangeContains(range, selection.address)))) return undefined;
  const candidates = source.mapping
    .filter((b) => b.mappingKind === 'REPEAT_REGION' && b.locator.sheetId === selection.sheetId)
    .flatMap((parent) => {
      const range = parseRange(
        locatorText(parent.locator.dataRange || parent.locator.address || ''),
      );
      if (!range) return [];
      const column = parent.repeatAxis === 'COLUMN';
      if (
        column
          ? at.startRow < range.startRow || at.endRow > range.endRow
          : at.startColumn < range.startColumn || at.endColumn > range.endColumn
      )
        return [];
      const children = source.mapping.filter((b) => b.parentBindingId === parent.bindingId);
      const label = children
        .map((b) => parseRange(locatorText(b.locator.labelRange || b.locator.labelAddress || '')))
        .find((r) => r && (column ? r.startColumn === at.startColumn : r.startRow === at.startRow));
      if (!label) return [];
      if (
        children.some(
          (b) => locatorText(b.locator.labelRange || b.locator.labelAddress) === selection.address,
        )
      )
        return [];
      if (
        (source.execution?.listProjections ?? []).some((p) => {
          if (projectionSheet(source, p) !== selection.sheetId) return false;
          const r = parseRange(p.labelRange);
          return (
            r &&
            at.startRow >= r.startRow &&
            at.startRow <= r.endRow &&
            at.startColumn >= r.startColumn &&
            at.startColumn <= r.endColumn
          );
        })
      )
        return [];
      return [{ parent, range, column, children }];
    });
  if (!candidates.length) return discoverScalarField(source, selection, name, snapshot);
  if (candidates.length !== 1) return undefined;
  const { parent, range, column, children } = candidates[0]!;
  const nearest = children
    .map((b) => ({ b, r: parseRange(locatorText(b.locator.labelRange || '')) }))
    .filter((x) => x.r)
    .sort(
      (a, b) =>
        Math.abs(
          (column ? a.r!.startRow : a.r!.startColumn) - (column ? at.startRow : at.startColumn),
        ) -
        Math.abs(
          (column ? b.r!.startRow : b.r!.startColumn) - (column ? at.startRow : at.startColumn),
        ),
    )[0]?.b;
  const sheet = snapshot && instanceSheets(snapshot).find(item => item.id === selection.sheetId);
  const section = sheet?.mergeData?.filter(merge => merge.startColumn < at.startColumn - 1
    && merge.startRow <= at.startRow - 1 && merge.endRow >= at.startRow - 1)
    .sort((a, b) => b.startColumn - a.startColumn)
    .map(merge => sheet.cellData?.[String(merge.startRow)]?.[String(merge.startColumn)]?.v)
    .map(title => source.model.groups.find(group => typeof title === 'string'
      && group.name.replace(/\s/g, '') === title.replace(/\s/g, '')))
    .find(Boolean);
  const neighbor = (section && source.model.fields.filter(field => field.groupId === section.id
    && field.mappingKind !== 'REPEAT_REGION').sort((a, b) => {
      const left = parseRange(locatorText(a.locator?.valueRange || a.locator?.address));
      const right = parseRange(locatorText(b.locator?.valueRange || b.locator?.address));
      return Math.abs((left?.startRow ?? 0) - at.startRow) - Math.abs((right?.startRow ?? 0) - at.startRow);
    })[0]) ?? source.model.fields.find((f) => f.bindingId === nearest?.bindingId);
  const instance = structuredClone(source),
    id = generateUUID(),
    code = `INSTANCE_LOCAL.${id.replaceAll('-', '')}`;
  const address = formatRange(
    column
      ? { ...range, startRow: at.startRow, endRow: at.startRow }
      : { ...range, startColumn: at.startColumn, endColumn: at.startColumn },
  );
  const locator = {
    sheetId: selection.sheetId,
    sheetName: selection.sheetName,
    labelRange: selection.address,
    labelAddress: selection.address,
    address,
    valueRange: address,
    componentId: parent.componentId,
    valueMode: column ? 'ARRAY_ROW' : 'ARRAY_COLUMN',
  };
  const semantic = instanceLocalSemantic(neighbor);
  instance.mapping.push({
    bindingId: id,
    fieldCode: code,
    dataPath: `/customFields/${id}`,
    parentBindingId: parent.bindingId,
    componentId: parent.componentId,
    mappingKind: 'REPEAT_FIELD',
    repeatAxis: parent.repeatAxis,
    role: 'FIELD',
    locatorType: 'CELL_RANGE',
    locator,
    syncDirection: 'TWO_WAY',
    primaryBinding: true,
    bindingStatus: 'VALID',
    experimentField: semantic,
  });
  instance.model.fields.push({
    id,
    bindingId: id,
    fieldCode: code,
    dataPath: `/customFields/${id}`,
    parentFieldId: source.model.fields.find((f) => f.bindingId === parent.bindingId)?.id,
    groupId: section?.id ?? neighbor?.groupId ?? source.model.groups[0]?.id ?? 'other',
    name,
    kind: 'SCALAR',
    valueType: 'string',
    fieldType: 'TABLE_COLUMN',
    displayRole: 'FIELD',
    labelStatus: 'RESOLVED',
    editability: 'EDITABLE',
    valueSource: 'USER_INPUT',
    standardSelectionStatus: 'CUSTOM',
    standardMatchStatus: 'CONFIRMED',
    requiresStandardConfirmation: false,
    pathSegments: [section?.name ?? source.model.groups.find(group => group.id === neighbor?.groupId)?.name ?? '', name].filter(Boolean),
    unit: '',
    required: false,
    reviewStatus: 'CONFIRMED',
    experimentSemanticStatus:
      source.execution?.templateUsage === 'EXPERIMENT_DATA'
        ? 'AUTO_CONFIRMED'
        : undefined,
    fieldOrigin: 'INSTANCE_LOCAL',
    mappingKind: 'REPEAT_FIELD',
    repeatAxis: parent.repeatAxis,
    locator,
    experimentField: semantic,
  });
  return instance;
}

export function projectionSheet(instance: WorkbookTemplateInstance, projection: NonNullable<WorkbookTemplateInstance['execution']>['listProjections'][number]) {
  return instance.mapping.find(binding => binding.bindingId === projection.parentBindingId
    || projection.componentId && binding.componentId === projection.componentId)?.locator.sheetId;
}

function rangeContains(range: string, address: string) {
  const outer = parseRange(range), inner = parseRange(address);
  return Boolean(outer && inner && outer.startRow <= inner.startRow && outer.endRow >= inner.endRow
    && outer.startColumn <= inner.startColumn && outer.endColumn >= inner.endColumn);
}

export function workbookFieldContext(instance: WorkbookTemplateInstance, selection: { sheetId: string; address: string }, snapshot?: Record<string, unknown>) {
  const at = parseRange(selection.address);
  const sheet = snapshot && instanceSheets(snapshot).find(item => item.id === selection.sheetId);
  const section = at && sheet?.mergeData?.filter(merge => merge.startColumn < at.startColumn - 1
    && merge.startRow <= at.startRow - 1 && merge.endRow >= at.startRow - 1)
    .sort((a, b) => b.startColumn - a.startColumn)
    .map(merge => sheet.cellData?.[String(merge.startRow)]?.[String(merge.startColumn)]?.v)
    .map(title => instance.model.groups.find(group => typeof title === 'string' && group.name.replace(/\s/g, '') === title.replace(/\s/g, ''))).find(Boolean);
  const fields = instance.model.fields.map(field => {
    const binding = instance.mapping.find(item => item.bindingId === field.bindingId);
    return { ...field, locator: field.locator ?? binding?.locator, mappingKind: field.mappingKind ?? binding?.mappingKind };
  }).filter(field => field.displayRole !== 'REGION' && field.mappingKind !== 'REPEAT_REGION'
    && field.locator?.sheetId === selection.sheetId && (!section || field.groupId === section.id));
  const nearest = fields.map(field => ({ field, at: parseRange(locatorLabelRange(field.locator) || locatorValueRange(field.locator)) }))
    .filter(item => item.at && at).sort((a, b) => Math.abs(a.at!.startRow - at!.startRow) + Math.abs(a.at!.startColumn - at!.startColumn)
      - Math.abs(b.at!.startRow - at!.startRow) - Math.abs(b.at!.startColumn - at!.startColumn))[0]?.field;
  return { groupId: section?.id ?? nearest?.groupId, field: nearest, section };
}

function discoverScalarField(source: WorkbookTemplateInstance, selection: { sheetId: string; sheetName: string; address: string }, name: string, snapshot?: Record<string, unknown>) {
  if (isStructuredDataCell(selection, source.mapping, [])) return;
  if (source.model.staticRegions?.some(region => region.sheetId === selection.sheetId && rangeContains(region.address, selection.address))) return;
  const at = parseRange(selection.address)!;
  const context = workbookFieldContext(source, selection, snapshot);
  const forms = [...source.model.blocks.filter(block => block.type === 'FORM_REGION' && block.sheetId === selection.sheetId)];
  // Older templates have scalar bindings but no explicit FORM_REGION nodes.
  // Their configured label/input envelope supplies the same boundary, without inspecting result text.
  if (!forms.length) {
    const ranges = source.model.fields.filter(field => field.groupId === context.groupId && field.displayRole !== 'REGION').flatMap(field => {
      const binding = source.mapping.find(item => item.bindingId === field.bindingId);
      if (!binding || binding.mappingKind !== 'SCALAR' || binding.locator.sheetId !== selection.sheetId) return [];
      return [locatorLabelRange(binding.locator), locatorValueRange(binding.locator)].flatMap(value => { const range = parseRange(value); return range ? [range] : []; });
    });
    if (ranges.length > 1) forms.push({ blockId: `form:${context.groupId}`, sheetId: selection.sheetId, type: 'FORM_REGION', businessName: '', range: formatRange({
      startRow: Math.min(...ranges.map(range => range.startRow)), endRow: Math.max(...ranges.map(range => range.endRow)),
      startColumn: Math.min(...ranges.map(range => range.startColumn)), endColumn: Math.max(...ranges.map(range => range.endColumn)),
    }) });
  }
  const blocks = forms.filter(block => rangeContains(block.range, selection.address));
  if (!blocks.length && !context.section) return;
  const candidates = source.model.fields.flatMap(field => {
    const binding = source.mapping.find(item => item.bindingId === field.bindingId);
    if (!binding || binding.mappingKind !== 'SCALAR' || binding.role === 'REPEAT_REGION' || field.displayRole === 'REGION' || binding.locator.sheetId !== selection.sheetId) return [];
    if (context.groupId && field.groupId !== context.groupId) return [];
    const label = parseRange(locatorLabelRange(binding.locator)), value = parseRange(locatorValueRange(binding.locator));
    if (!label || !value) return [];
    if (blocks.length ? !blocks.some(block => rangeContains(block.range, locatorLabelRange(binding.locator))) : !field.groupId || !snapshot) return [];
    const dx = value.startColumn - label.startColumn, dy = value.startRow - label.startRow;
    if (!(dy === 0 && dx > 0 && at.startColumn === label.startColumn || dx === 0 && dy > 0 && at.startRow === label.startRow)) return [];
    const range = formatRange({ startRow: at.startRow + dy, endRow: at.startRow + dy + value.endRow - value.startRow,
      startColumn: at.startColumn + dx, endColumn: at.startColumn + dx + value.endColumn - value.startColumn });
    if (blocks.length && !blocks.some(block => rangeContains(block.range, range))) return [];
    return [{ field, range, position: dy === 0 ? label.startRow : label.startColumn, current: dy === 0 ? at.startRow : at.startColumn }];
  });
  if (!candidates.length) return;
  const adjacent = candidates.filter(candidate => !candidates.some(other => Math.sign(other.position - other.current) === Math.sign(candidate.position - candidate.current)
    && Math.abs(other.position - other.current) < Math.abs(candidate.position - candidate.current)));
  const ranges = [...new Set(adjacent.map(item => item.range))];
  const input = ranges.length === 1 && !source.mapping.some(binding => binding.locator.sheetId === selection.sheetId && binding.mappingKind !== 'REPEAT_REGION'
    && [locatorValueRange(binding.locator), locatorLabelRange(binding.locator)].some(range => rangesOverlap(range, ranges[0]!))) ? ranges[0] : undefined;
  const created = createCustomFieldWorkspace({}, source.model, source.mapping, { ownerId: source.mapping[0]?.bindingId ?? 'workbook', origin: 'INSTANCE_LOCAL', kind: 'SCALAR',
    name, groupId: context.groupId ?? candidates[0]!.field.groupId, sheet: selection, labelRange: selection.address, valueRange: input });
  const next = structuredClone(source);
  next.model = created.model; next.mapping = created.mapping;
  const semantic = instanceLocalSemantic(context.field);
  next.model.fields = next.model.fields.map(field => field.id === created.field.id ? { ...field, unit: '', pathSegments: [next.model.groups.find(group => group.id === field.groupId)?.name ?? '', name].filter(Boolean),
    experimentField: semantic, experimentSemanticStatus: next.execution?.templateUsage === 'EXPERIMENT_DATA' ? 'AUTO_CONFIRMED' : undefined } : field);
  next.mapping = next.mapping.map(binding => binding.bindingId === created.binding.bindingId ? { ...binding, experimentField: semantic } : binding);
  return next;
}

function rangesOverlap(left: string, right: string) {
  const a = parseRange(left), b = parseRange(right);
  return Boolean(a && b && a.startRow <= b.endRow && a.endRow >= b.startRow && a.startColumn <= b.endColumn && a.endColumn >= b.startColumn);
}

export function updateWorkbookField(source: WorkbookTemplateInstance, id: string, changes: Partial<BusinessField>) {
  const next = structuredClone(source);
  const updated = updateFieldDefinition(next.model, next.mapping, id, { ...changes, reviewStatus: 'CONFIRMED' });
  next.model = updated.model; next.mapping = updated.mapping;
  return next;
}

export function addWorkbookField(source: WorkbookTemplateInstance, options: {
  ownerId: string; origin: 'TEMPLATE_LOCAL' | 'INSTANCE_LOCAL'; selection?: { sheetId: string; sheetName: string; address: string };
  snapshot?: Record<string, unknown>; parentId?: string; groupId?: string;
}) {
  const context = options.selection ? workbookFieldContext(source, options.selection, options.snapshot) : undefined;
  let parent = source.model.fields.find(field => field.id === options.parentId);
  if (!parent && context?.field?.parentFieldId) parent = source.model.fields.find(field => field.id === context.field!.parentFieldId);
  if (parent && !options.parentId && options.selection) {
    const binding = source.mapping.find(item => item.bindingId === parent!.bindingId);
    const bounds = parseRange(locatorText(binding?.locator.dataRange || binding?.locator.address));
    const at = parseRange(options.selection.address);
    const label = parseRange(locatorLabelRange(context?.field?.locator));
    if (!bounds || !at || !(rangeContains(formatRange(bounds), options.selection.address)
      || binding?.repeatAxis === 'COLUMN' && label?.startColumn === at.startColumn && at.startRow >= bounds.startRow && at.startRow <= bounds.endRow
      || binding?.repeatAxis !== 'COLUMN' && label?.startRow === at.startRow && at.startColumn >= bounds.startColumn && at.startColumn <= bounds.endColumn)) parent = undefined;
  }
  if (!parent && options.selection) {
    const region = source.mapping.find(binding => binding.mappingKind === 'REPEAT_REGION' && binding.locator.sheetId === options.selection!.sheetId
      && (() => {
        const bounds = parseRange(locatorText(binding.locator.dataRange || binding.locator.address)), at = parseRange(options.selection!.address);
        if (!bounds || !at) return false;
        if (rangeContains(formatRange(bounds), options.selection!.address)) return true;
        return source.mapping.some(child => child.parentBindingId === binding.bindingId && (() => {
          const label = parseRange(locatorLabelRange(child.locator));
          return binding.repeatAxis === 'COLUMN'
            ? label?.startColumn === at.startColumn && at.startRow >= bounds.startRow && at.startRow <= bounds.endRow
            : label?.startRow === at.startRow && at.startColumn >= bounds.startColumn && at.startColumn <= bounds.endColumn;
        })());
      })());
    if (region) parent = source.model.fields.find(field => field.bindingId === region.bindingId);
  }
  const created = createCustomFieldWorkspace({}, source.model, source.mapping, { ownerId: options.ownerId, origin: options.origin,
    kind: parent ? 'REPEAT_FIELD' : 'SCALAR', name: '新字段', groupId: options.groupId ?? context?.groupId ?? parent?.groupId,
    parentField: parent, parentBinding: parent && source.mapping.find(binding => binding.bindingId === parent.bindingId),
    sheet: options.selection ?? { sheetId: locatorText(parent?.locator?.sheetId ?? source.mapping[0]?.locator.sheetId), sheetName: locatorText(parent?.locator?.sheetName ?? source.mapping[0]?.locator.sheetName) },
  });
  const semantic = instanceLocalSemantic(context?.field);
  const next = structuredClone(source); next.model = created.model; next.mapping = created.mapping;
  next.model.fields = next.model.fields.map(field => field.id === created.field.id ? { ...field, experimentField: semantic,
    experimentSemanticStatus: next.execution?.templateUsage === 'EXPERIMENT_DATA' ? 'AUTO_CONFIRMED' : undefined } : field);
  next.mapping = next.mapping.map(binding => binding.bindingId === created.binding.bindingId ? { ...binding, experimentField: semantic } : binding);
  if (!parent) {
    const components = [...new Set(source.mapping.filter(binding => binding.mappingKind === 'SCALAR' && binding.locator.sheetId === created.binding.locator.sheetId)
      .map(binding => binding.componentId || binding.locator.componentId).filter((id): id is string => typeof id === 'string' && Boolean(id)))];
    if (components.length === 1) next.mapping = next.mapping.map(binding => binding.bindingId === created.binding.bindingId
      ? { ...binding, componentId: components[0], locator: { ...binding.locator, componentId: components[0] } } : binding);
  }
  return { instance: next, fieldId: created.field.id };
}

export function renameWorkbookField(source: WorkbookTemplateInstance, bindingId: string, value: unknown) {
  const field = source.model.fields.find(item => item.bindingId === bindingId && item.displayRole !== 'REGION');
  if (!field) return;
  const name = typeof value === 'string' ? value.trim().replace(/[：:]$/, '').trim() : typeof value === 'number' || typeof value === 'boolean' ? String(value) : '';
  if (name === field.name && field.labelStatus !== 'UNRESOLVED') return;
  const next = updateWorkbookField(source, field.id, name ? { name, labelStatus: 'RESOLVED' } : { labelStatus: 'UNRESOLVED', labelTextMissing: true, reviewStatus: 'NEEDS_CONFIRMATION' });
  if (!name) next.model.fields.find(item => item.id === field.id)!.reviewStatus = 'NEEDS_CONFIRMATION';
  return next;
}

export function renameWorkbookFieldAt(source: WorkbookTemplateInstance, selection: { sheetId: string; address: string }, value: unknown) {
  const address = selection.address.replaceAll('$', '').toUpperCase();
  const labels = source.mapping.filter(binding => binding.mappingKind !== 'REPEAT_REGION' && binding.locator.sheetId === selection.sheetId
    && locatorLabelRange(binding.locator).split(':')[0] === address);
  let current = source, changed = false;
  for (const binding of labels) {
    const next = renameWorkbookField(current, binding.bindingId, value);
    if (next) { current = next; changed = true; }
  }
  return { matched: labels.length > 0, instance: changed ? current : undefined };
}

export function deleteWorkbookField(source: WorkbookTemplateInstance, id: string) {
  const next = structuredClone(source);
  const removed = removeFieldDefinitions(next.model, next.mapping, [id]);
  next.model = removed.model; next.mapping = removed.mapping;
  const projections = next.execution?.listProjections.filter(projection => !removed.bindingIds.has(projection.parentBindingId ?? '')
    && !source.mapping.some(binding => removed.bindingIds.has(binding.bindingId) && binding.mappingKind === 'REPEAT_REGION'
      && binding.componentId && binding.componentId === projection.componentId));
  const projectionIds = new Set(projections?.map(projection => projection.listProjectionId));
  if (next.execution && projections) next.execution.listProjections = projections;
  next.records = next.records.filter(record => !removed.bindingIds.has(record.regionId));
  next.items = next.items.filter(item => projectionIds.has(item.regionId));
  next.issues = next.issues.filter(issue => !issue.fieldId || !removed.fieldIds.has(issue.fieldId));
  return next;
}

export function workbookAddress(row: number, column: number): string {
  return formatRange({
    startRow: row + 1,
    endRow: row + 1,
    startColumn: column + 1,
    endColumn: column + 1,
  });
}
export function copyWorkbookCells(
  cells: Array<Array<WorkbookCell | null | void>>,
  rowOffset: number,
  columnOffset: number,
): WorkbookCell[][] {
  return cells.map((row) =>
    row.map((source) => {
      const cell: WorkbookCell = source ? structuredClone(source) : {};
      if (cell.f) cell.f = offsetWorkbookFormula(cell.f, columnOffset, rowOffset);
      else {
        // Univer coerces an empty string with a copied NUMBER/BOOLEAN type to 0.
        // Clear both the value and its source type while retaining display formats.
        cell.v = null;
        cell.t = null;
        delete cell.p;
      }
      return cell;
    }),
  );
}

function locatorText(value: unknown): string {
  return typeof value === 'string' ? value : '';
}
