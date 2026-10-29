import type { DataFieldValueView, DataWorkbookSnapshot } from '@/services/data/data-api';
import type { WorkbookTemplateInstance } from '@/features/template-workspace/workbook-instance';
import { instanceSheets, workbookAddress } from '@/features/template-workspace/workbook-instance';
import { parseRange } from '@/features/template-workspace/structure-migration';
import { displayValue } from './value-display';
import { workbookCellValue } from './cell-format';

/** One view adapter for scalar fields, row/column records and list projections. */
export function instanceWorkbookView(
  snapshot: Record<string, unknown>,
  instance: WorkbookTemplateInstance,
  editable: boolean,
): DataWorkbookSnapshot {
  const sheets = instanceSheets(snapshot),
    result: DataWorkbookSnapshot = {
      fileName: '本次记录',
      format: 'XLSX',
      snapshot,
      templateInstance: instance,
      editable,
      sheets: sheets.map((s, i) => ({
        sheetId: s.id,
        sheetName: s.name,
        sheetOrder: i,
        selected: true,
        confirmationStatus: 'CONFIRMED',
      })),
      selectedSheetId: sheets[0]?.id,
      regions: [],
      records: [],
      fieldDefinitions: [],
      fields: [],
    };
  const groups = new Map(instance.model.groups.map((g) => [g.id, g.name]));
  const cell = (sheetId: string, row: number, col: number) =>
    sheets.find((s) => s.id === sheetId)?.cellData?.[String(row - 1)]?.[String(col - 1)];
  const value = (sheetId: string, row: number, col: number) => cell(sheetId, row, col)?.v ?? '';
  const shown = (sheetId: string, row: number, col: number, type: string) => {
    const c = cell(sheetId, row, col),
      raw = c?.v ?? '';
    if (typeof raw !== 'number') return raw;
    const styles = snapshot.styles as Record<string, { n?: { pattern?: string } }> | undefined;
    const style = (typeof c?.s === 'string' ? styles?.[c.s] : c?.s) as
      { n?: { pattern?: string } } | undefined;
    const pattern = style?.n?.pattern ?? '';
    return workbookCellValue(raw, type, pattern);
  };
  for (const identity of instance.records) {
    const parent = instance.mapping.find((b) => b.bindingId === identity.regionId);
    const identifiers = instance.mapping.filter(
      (b) =>
        b.parentBindingId === identity.regionId &&
        (/SOURCE_IDENTITY/.test(b.experimentField?.field ?? '') ||
          /sample.number/.test(b.fieldCode ?? '')),
    );
    const label = identifiers
      .map((b) => {
        const r = parseRange(locatorText(b.locator.valueRange || b.locator.address || ''));
        return r
          ? displayValue(
              value(
                identity.sheetId,
                identity.axis === 'ROW' ? identity.index : r.startRow,
                identity.axis === 'COLUMN' ? identity.index : r.startColumn,
              ),
            )
          : '';
      })
      .find(Boolean);
    result.records.push({
      recordId: identity.id,
      regionId: identity.regionId,
      label:
        label ||
        `记录 ${result.records.filter((r) => r.regionId === identity.regionId).length + 1}`,
      sequence: identity.index,
      sheetId: identity.sheetId,
      sheetName: sheets.find((s) => s.id === identity.sheetId)?.name,
      address: workbookAddress(
        identity.axis === 'ROW' ? identity.index - 1 : 0,
        identity.axis === 'COLUMN' ? identity.index - 1 : 0,
      ),
      excluded: false,
    });
    if (parent && !result.regions.some((r) => r.regionId === identity.regionId))
      result.regions.push({
        regionId: identity.regionId,
        name:
          instance.model.fields.find((f) => f.bindingId === identity.regionId)?.name || '明细记录',
        structureType: identity.axis === 'COLUMN' ? 'COLUMN_TABLE' : 'ROW_TABLE',
        recordAxis: identity.axis,
        sheetId: identity.sheetId,
        range: locatorText(parent.locator.dataRange || parent.locator.range || ''),
        fieldCount: 0,
        recordCount: 0,
        fieldGroups: [],
      });
  }
  const add = (
    fieldCode: string,
    name: string,
    bindingId: string,
    regionId: string,
    recordId: string,
    sheetId: string,
    row: number,
    col: number,
    unit: string,
    type: string,
    groupPath: string,
    source: string,
    required = false,
  ) => {
    const raw = shown(sheetId, row, col, type),
      address = workbookAddress(row - 1, col - 1),
      sheet = sheets.find((s) => s.id === sheetId),
      issue = instance.issues.find(
        (i) => i.address === address && (!i.sheetId || i.sheetId === sheetId),
      );
    const data: DataFieldValueView = {
      componentId: regionId,
      recordId,
      recordGroupId: recordId,
      bindingId,
      fieldCode,
      fieldName: name,
      labelPath: `${groupPath} > ${name}`,
      groupPath,
      valuePath: `/cells/${row - 1}/${col - 1}`,
      rawValue: raw,
      normalizedValue: raw,
      effectiveValue: raw,
      valueSource: source,
      valueStatus: issue ? 'BLOCKED' : 'VALID',
      valueType: type,
      unit: /^[<>≤≥]?\s*[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/.test(displayValue(raw)) ? unit : undefined,
      required,
      identity: false,
      trainingEligible: !fieldCode.startsWith('INSTANCE_LOCAL'),
      ragEligible: true,
      sheetId,
      sheetName: sheet?.name,
      address,
      rowNumber: row,
      editable: editable && source !== 'FORMULA',
      excluded: false,
      mappingKind: regionId === 'common' ? 'SCALAR' : 'REPEAT_FIELD',
    };
    result.fields.push(data);
    if (!result.fieldDefinitions!.some((f) => f.bindingId === bindingId))
      result.fieldDefinitions!.push({
        componentId: regionId,
        bindingId,
        fieldCode,
        displayName: name,
        groupPath,
        labelPath: data.labelPath,
        valueType: type,
        unit,
        required,
        identity: false,
        sheetId,
        sheetName: sheet?.name,
        sourceRange: address,
        mappingKind: data.mappingKind,
      });
  };
  for (const field of instance.model.fields) {
    const b = instance.mapping.find((b) => b.bindingId === field.bindingId);
    if (!b || b.mappingKind === 'REPEAT_REGION') continue;
    const r = parseRange(locatorText(b.locator.valueRange || b.locator.address || ''));
    if (!r) continue;
    const sid = locatorText(b.locator.sheetId || ''),
      region = b.parentBindingId || 'common',
      records = b.parentBindingId
        ? instance.records.filter((i) => i.regionId === b.parentBindingId)
        : [];
    const source =
      field.valueSource === 'FORMULA' || b.locator.valueSource === 'FORMULA' ? 'FORMULA' : 'INPUT';
    if (!b.parentBindingId) {
      if (!result.records.some((x) => x.recordId === 'common'))
        result.records.unshift({
          recordId: 'common',
          regionId: 'common',
          label: '共用信息',
          sequence: 0,
          excluded: false,
        });
      if (!result.regions.some((x) => x.regionId === 'common'))
        result.regions.unshift({
          regionId: 'common',
          name: '共用信息',
          structureType: 'FORM_REGION',
          fieldCount: 0,
          recordCount: 1,
          fieldGroups: [],
        });
      add(
        field.fieldCode || field.id,
        field.name,
        b.bindingId,
        region,
        'common',
        sid,
        r.startRow,
        r.startColumn,
        field.unit || '',
        field.valueType,
        groups.get(field.groupId) || '其他',
        source,
        field.required,
      );
    } else
      for (const record of records) {
        const row = record.axis === 'ROW' ? record.index : r.startRow,
          col = record.axis === 'COLUMN' ? record.index : r.startColumn;
        if (row < r.startRow || row > r.endRow || col < r.startColumn || col > r.endColumn)
          continue;
        add(
          field.fieldCode || field.id,
          field.name,
          b.bindingId,
          region,
          record.id,
          sid,
          row,
          col,
          field.unit || '',
          field.valueType,
          groups.get(field.groupId) || '其他',
          source,
          field.required,
        );
      }
  }
  for (const p of instance.execution?.listProjections ?? []) {
    const labels = parseRange(p.labelRange),
      values = parseRange(p.valueRange);
    if (!labels || !values) continue;
    for (const item of instance.items.filter((i) => i.regionId === p.listProjectionId)) {
      const name =
        displayValue(
          value(
            item.sheetId,
            p.itemAxis === 'ROW' ? item.index : labels.startRow,
            p.itemAxis === 'COLUMN' ? item.index : labels.startColumn,
          ),
        ) || `明细 ${item.index}`;
      for (const record of instance.records.filter((r) => r.regionId === p.parentBindingId))
        add(
          `${p.listProjectionId}:${item.id}`,
          name,
          `${p.listProjectionId}:${item.id}`,
          record.regionId,
          record.id,
          item.sheetId,
          p.recordAxis === 'ROW' ? record.index : item.index,
          p.recordAxis === 'COLUMN' ? record.index : item.index,
          p.unit ?? '',
          'number',
          p.displayName ?? p.listProjectionId,
          'INPUT',
        );
    }
  }
  for (const region of result.regions) {
    const definitions = result.fieldDefinitions!.filter((f) => f.componentId === region.regionId);
    region.fieldCount = definitions.length;
    region.recordCount = result.records.filter((r) => r.regionId === region.regionId).length;
    region.fieldGroups = [...new Set(definitions.map((f) => f.groupPath || '其他'))].map(
      (name) => ({
        groupId: name,
        name,
        fieldCount: definitions.filter((f) => f.groupPath === name).length,
      }),
    );
  }
  return result;
}

function locatorText(value: unknown): string {
  return typeof value === 'string' ? value : '';
}
