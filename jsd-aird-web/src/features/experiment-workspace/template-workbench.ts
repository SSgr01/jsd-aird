import { readWorkbookInstance } from '@/features/template-workspace/workbook-instance';
import { instanceWorkbookView } from '@/features/data-workbench/instance-workbook-view';
import type { DataFieldValueView, DataWorkbookSnapshot } from '@/services/data/data-api';
import { labAddress, labFieldPosition, labLastColumn, labSheet } from './lab-template';
import { displayValue } from '@/features/data-workbench/value-display';
import { workbookCellValue } from '@/features/data-workbench/cell-format';

/** Adapts template instances to the same view contract used by data/import workbenches. */
export function experimentTemplateWorkbook(
  snapshot: Record<string, unknown>,
  editable: boolean,
  issues: Array<{ code: string; address: string; message: string }> = [],
): DataWorkbookSnapshot {
  const instance = readWorkbookInstance(snapshot);
  if (instance) return instanceWorkbookView(snapshot, instance, editable);
  const sheet = labSheet(snapshot);
  const definition = sheet?.custom?.labTemplate;
  const result: DataWorkbookSnapshot = {
    fileName: '本次实验',
    format: 'XLSX',
    snapshot,
    editable,
    sheets: [],
    regions: [],
    records: [],
    fieldDefinitions: [],
    fields: [],
  };
  if (!sheet || !definition) return result;
  result.sheets = [
    {
      sheetId: sheet.id,
      sheetName: sheet.name,
      sheetOrder: 0,
      selected: true,
      confirmationStatus: 'CONFIRMED',
    },
  ];
  result.selectedSheetId = sheet.id;
  const text = displayValue;
  const valueAt = (row: number, column: number) => sheet.cellData?.[row]?.[column]?.v ?? '';
  const displayedValue = (row: number, column: number, code: string) => {
    const cell = sheet.cellData?.[row]?.[column],
      raw = valueAt(row, column);
    if (typeof raw !== 'number') return raw;
    const styles = snapshot.styles as Record<string, { n?: { pattern?: string } }> | undefined;
    const style = (typeof cell?.s === 'string' ? styles?.[cell.s] : cell?.s) as
      { n?: { pattern?: string } } | undefined;
    return workbookCellValue(raw, code === 'basic.date' ? 'date' : '', style?.n?.pattern ?? '');
  };
  const groups = new Map(definition.regions.map((region) => [region.id, region.label]));
  const identityRow = labFieldPosition(sheet, 'sample.experimentNo')?.row;
  const sampleRow = labFieldPosition(sheet, 'sample.number')?.row;
  const markedColumns = Object.entries(sheet.columnData ?? {})
    .filter(([, column]) => column.custom?.lab?.sampleKey)
    .map(([column]) => Number(column))
    .sort((a, b) => a - b);
  const columns = markedColumns.length
    ? markedColumns
    : Array.from(
        { length: labLastColumn(sheet) - definition.firstSampleColumn + 1 },
        (_, offset) => definition.firstSampleColumn + offset,
      );
  const records = columns.map((column, index) => ({
    recordId: sheet.columnData?.[column]?.custom?.lab?.sampleKey || `column:${column}`,
    regionId: 'comparison',
    label:
      text(sampleRow == null ? '' : valueAt(sampleRow, column)) ||
      text(identityRow == null ? '' : valueAt(identityRow, column)) ||
      `对照组 ${index + 1}`,
    sequence: index + 1,
    sheetId: sheet.id,
    sheetName: sheet.name,
    address: labAddress(identityRow ?? sampleRow ?? 6, column),
    excluded: false,
  }));
  const entries = Object.values(definition.fields)
    .flatMap((field) => {
      const at = labFieldPosition(sheet, field.fieldCode);
      return at ? [{ field, at }] : [];
    })
    .sort((a, b) => a.at.row - b.at.row || a.at.column - b.at.column);
  const addField = (
    field: (typeof entries)[number]['field'],
    row: number,
    column: number,
    regionId: string,
    recordId: string,
    groupPath: string,
    bindingId = field.fieldCode,
  ) => {
    const raw = displayedValue(row, column, field.fieldCode),
      address = labAddress(row, column);
    const required = Boolean((field as typeof field & { required?: boolean }).required);
    const issue = issues.find(
      (item) => item.address === address || item.address === labAddress(row, 1),
    );
    const formula = field.valueSource === 'FORMULA';
    const data: DataFieldValueView = {
      componentId: regionId,
      recordId,
      recordGroupId: recordId,
      bindingId,
      fieldCode: field.fieldCode,
      fieldName: field.label,
      labelPath: `${groupPath} > ${field.label}`,
      groupPath,
      valuePath: `/cells/${row}/${column}`,
      rawValue: raw,
      normalizedValue: raw,
      effectiveValue: raw,
      valueSource: formula ? 'FORMULA' : 'INPUT',
      valueStatus: issue || (required && !text(raw).trim()) ? 'BLOCKED' : 'VALID',
      valueType: field.resultKind === 'NUMBER' ? 'NUMBER' : 'TEXT',
      // Unit-bearing and qualitative input is already complete as written.
      unit: /^[<>≤≥]?\s*[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i.test(text(raw))
        ? field.unit
        : undefined,
      required,
      identity: field.semantic === 'SOURCE_IDENTITY',
      trainingEligible: field.trainingEligible !== false && !field.local,
      ragEligible: true,
      sheetId: sheet.id,
      sheetName: sheet.name,
      address,
      rowNumber: row + 1,
      editable: editable && !formula,
      excluded: false,
      mappingKind: regionId === 'comparison' ? 'REPEAT_FIELD' : 'SCALAR',
      repeatAxis: regionId === 'comparison' ? 'COLUMN' : undefined,
    };
    result.fields.push(data);
    if (!result.fieldDefinitions!.some((item) => item.bindingId === bindingId))
      result.fieldDefinitions!.push({
        componentId: regionId,
        bindingId,
        fieldCode: field.fieldCode,
        displayName: field.label,
        groupPath,
        labelPath: data.labelPath,
        mappingKind: data.mappingKind,
        repeatAxis: data.repeatAxis,
        valueType: data.valueType,
        unit: field.unit,
        required,
        identity: data.identity,
        sheetId: sheet.id,
        sheetName: sheet.name,
        sourceRange: address,
      });
  };
  for (const { field, at } of entries) {
    const group = groups.get(field.groupId) || field.groupId;
    if (field.scope === 'EXPERIMENT') addField(field, at.row, at.column, 'shared', 'shared', group);
    else
      for (const [index, column] of columns.entries())
        addField(field, at.row, column, 'comparison', records[index]!.recordId, group);
  }
  // Material rows are instance data, not new public indicator definitions.
  for (const [rowKey, cells] of Object.entries(sheet.cellData ?? {})) {
    const label = cells[1];
    if (label?.custom?.lab?.role !== 'MATERIAL_CODE' || !text(label.v).trim()) continue;
    const row = Number(rowKey),
      lineId = label.custom.lab.lineId || `row:${row}`;
    for (const [index, column] of columns.entries())
      addField(
        {
          fieldCode: `formula.material:${lineId}`,
          label: text(label.v),
          groupId: 'formula',
          scope: 'SAMPLE',
          domain: 'FORMULA',
          semantic: 'ACTUAL_QTY',
          unit: 'g',
          resultKind: 'MIXED',
          trainingEligible: true,
        },
        row,
        column,
        'comparison',
        records[index]!.recordId,
        '配方',
      );
  }
  result.fields.sort((a, b) => (a.rowNumber ?? 0) - (b.rowNumber ?? 0));
  result.fieldDefinitions!.sort((a, b) => {
    const row = (address?: string) => Number(address?.match(/\d+$/)?.[0] ?? 0);
    return row(a.sourceRange) - row(b.sourceRange);
  });
  const shared = result.fieldDefinitions!.filter((field) => field.componentId === 'shared');
  if (shared.length) {
    result.records.push({
      recordId: 'shared',
      regionId: 'shared',
      label: '本次实验',
      sequence: 1,
      excluded: false,
    });
    result.regions.push({
      regionId: 'shared',
      name: '共用信息',
      structureType: 'FORM_REGION',
      fieldCount: shared.length,
      recordCount: 1,
      fieldGroups: [],
    });
  }
  const comparison = result.fieldDefinitions!.filter((field) => field.componentId === 'comparison');
  if (comparison.length) {
    result.records.push(...records);
    result.regions.push({
      regionId: 'comparison',
      name: '实验对照组',
      structureType: 'COLUMN_TABLE',
      recordAxis: 'COLUMN',
      fieldCount: comparison.length,
      recordCount: records.length,
      fieldGroups: [],
    });
  }
  return result;
}
