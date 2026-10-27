import { generateUUID } from '@/utils/uuid';
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
  if (!insert) {
    const removed = new Set(
      source.mapping
        .filter(
          (b) =>
            b.mappingKind === 'REPEAT_FIELD' &&
            b.locator.sheetId === operation.sheetId &&
            instance.mapping.find((n) => n.bindingId === b.bindingId)?.bindingStatus ===
              'MISSING' &&
            (source.mapping.find((p) => p.bindingId === b.parentBindingId)?.repeatAxis === 'COLUMN'
              ? 'COLUMN'
              : 'ROW') !== axis,
        )
        .map((b) => b.bindingId),
    );
    instance.mapping = instance.mapping.filter((b) => !removed.has(b.bindingId));
    instance.model.fields = instance.model.fields.filter(
      (f) => !f.bindingId || !removed.has(f.bindingId),
    );
  }
  const move = (list: InstanceIdentity[]) =>
    list.flatMap((identity) => {
      if (identity.sheetId !== operation.sheetId || identity.axis !== axis) return [identity];
      if (
        !insert &&
        identity.index <= index + count - 1 &&
        identity.index + identity.size - 1 >= index
      )
        return [];
      return [
        {
          ...identity,
          index:
            identity.index >= index ? identity.index + (insert ? count : -count) : identity.index,
        },
      ];
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
      instance.issues.push({
        code: 'PARTIAL_RECORD',
        sheetId: operation.sheetId,
        message: '插入位置或数量拆分了重复记录，请核对区域边界',
      });
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
  instance.model.fields = instance.model.fields.map((f) => ({
    ...f,
    locator: instance.mapping.find((b) => b.bindingId === f.bindingId)?.locator ?? f.locator,
  }));
  instance.records.sort((a, b) => a.index - b.index);
  instance.items.sort((a, b) => a.index - b.index);
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
  return formula
    .split(/("(?:[^"]|"")*")/)
    .map((part, index) =>
      index % 2
        ? part
        : part.replace(
            /(?<![A-Za-z0-9_!$])(\$?)([A-Z]{1,3})(\$?)([1-9][0-9]*):(\$?)([A-Z]{1,3})(\$?)([1-9][0-9]*)/g,
            (
              match: string,
              ac: string,
              c1: string,
              ar: string,
              r1: string,
              bc: string,
              c2: string,
              br: string,
              r2: string,
            ) => {
              const range = parseRange(`${c1}${r1}:${c2}${r2}`);
              if (!range) return match;
              for (const change of changes) {
                const before = parseRange(change.before),
                  after = parseRange(change.after);
                if (!before || !after) continue;
                if (
                  change.axis === 'ROW' &&
                  range.startRow === before.startRow &&
                  range.endRow === before.endRow &&
                  range.startColumn >= before.startColumn &&
                  range.endColumn <= before.endColumn
                )
                  return `${ac}${c1}${ar}${after.startRow}:${bc}${c2}${br}${after.endRow}`;
                if (
                  change.axis === 'COLUMN' &&
                  range.startColumn === before.startColumn &&
                  range.endColumn === before.endColumn &&
                  range.startRow >= before.startRow &&
                  range.endRow <= before.endRow
                ) {
                  const left = workbookAddress(range.startRow - 1, after.startColumn - 1).replace(
                      /[0-9]+$/,
                      '',
                    ),
                    right = workbookAddress(range.endRow - 1, after.endColumn - 1).replace(
                      /[0-9]+$/,
                      '',
                    );
                  return `${ac}${left}${ar}${r1}:${bc}${right}${br}${r2}`;
                }
              }
              return match;
            },
          ),
    )
    .join('');
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
  const known =
    source.mapping.some(
      (b) =>
        b.mappingKind !== 'REPEAT_REGION' &&
        b.locator.sheetId === selection.sheetId &&
        ['valueRange', 'address', 'labelRange', 'labelAddress'].some((k) => includes(b.locator[k])),
    ) ||
    (source.execution?.listProjections ?? []).some(
      (p) => includes(p.labelRange) || includes(p.valueRange),
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
  if (typeof value !== 'string' || !value.trim() || /^[\d\s.,+\-/]+$/.test(value)) return undefined;
  const at = parseRange(selection.address);
  if (!at || at.startRow !== at.endRow || at.startColumn !== at.endColumn) return undefined;
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
    groupId: neighbor?.groupId ?? source.model.groups[0]?.id ?? 'other',
    name: value.trim(),
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
    pathSegments: [source.model.groups.find(group => group.id === neighbor?.groupId)?.name ?? '', value.trim()].filter(Boolean),
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
      if (cell.f)
        cell.f = cell.f
          .split(/("(?:[^"]|"")*")/)
          .map((part, index) =>
            index % 2
              ? part
              : part.replace(
                  /(\$?)([A-Z]{1,3})(\$?)([1-9][0-9]*)/g,
                  (
                    _all: string,
                    absoluteColumn: string,
                    letters: string,
                    absoluteRow: string,
                    number: string,
                  ) => {
                    let col = 0;
                    for (const letter of letters) col = col * 26 + letter.charCodeAt(0) - 64;
                    const address = workbookAddress(
                      Number(number) - 1 + (absoluteRow ? 0 : rowOffset),
                      col - 1 + (absoluteColumn ? 0 : columnOffset),
                    );
                    return address.replace(
                      /^([A-Z]+)(\d+)$/,
                      (_match, column: string, row: string) =>
                        `${absoluteColumn}${column}${absoluteRow}${row}`,
                    );
                  },
                ),
          )
          .join('');
      else {
        cell.v = '';
        delete cell.p;
      }
      return cell;
    }),
  );
}

function locatorText(value: unknown): string {
  return typeof value === 'string' ? value : '';
}
