import type { TemplateBinding } from '@/features/template-workspace/types';

export interface LabField {
  fieldCode: string;
  label: string;
  groupId: string;
  scope: 'EXPERIMENT' | 'SAMPLE';
  domain: string;
  semantic: string;
  resultKind: string;
  unit: string;
  valueSource?: string;
  local?: boolean;
  confirmed?: boolean;
  trainingEligible?: boolean;
}
export interface LabDefinition {
  version: number;
  templateCode: string;
  kind: string;
  firstSampleColumn: number;
  lastSampleColumn?: number;
  initialLastSampleColumn: number;
  fields: Record<string, LabField>;
  regions: Array<{ id: string; label: string; startRow: number; endRow: number }>;
}
export type Cell = {
  v?: unknown;
  f?: string;
  custom?: {
    lab?: { role?: string; fieldCode?: string; lineId?: string; groupId?: string };
    [key: string]: unknown;
  };
  [key: string]: unknown;
};
export type LabSheet = {
  id: string;
  name: string;
  custom?: { labTemplate?: LabDefinition; [key: string]: unknown };
  cellData?: Record<string, Record<string, Cell>>;
  columnData?: Record<
    string,
    { custom?: { lab?: { sampleKey?: string }; [key: string]: unknown }; [key: string]: unknown }
  >;
  mergeData?: Array<{ startRow: number; endRow: number; startColumn: number; endColumn: number }>;
  [key: string]: unknown;
};
export function labSheet(snapshot?: Record<string, unknown>): LabSheet | undefined {
  return Object.values((snapshot?.sheets ?? {}) as Record<string, LabSheet>).find(
    (sheet) => sheet.custom?.labTemplate?.version === 1,
  );
}
export function labDefinition(snapshot?: Record<string, unknown>): LabDefinition | undefined {
  return labSheet(snapshot)?.custom?.labTemplate;
}
export function labAddress(row: number, column: number): string {
  let letters = '';
  for (let n = column + 1; n > 0; n = Math.floor((n - 1) / 26))
    letters = String.fromCharCode(65 + ((n - 1) % 26)) + letters;
  return `${letters}${row + 1}`;
}
export function labFieldPosition(
  sheet: LabSheet,
  code: string,
): { row: number; column: number } | undefined {
  for (const [row, cells] of Object.entries(sheet.cellData ?? {}))
    for (const [column, cell] of Object.entries(cells)) {
      if (cell.custom?.lab?.fieldCode === code) return { row: Number(row), column: Number(column) };
    }
  return undefined;
}
export function labLastColumn(sheet: LabSheet): number {
  return Math.max(
    sheet.custom?.labTemplate?.initialLastSampleColumn ?? 7,
    ...Object.entries(sheet.columnData ?? {})
      .filter(([, col]) => col.custom?.lab?.sampleKey)
      .map(([col]) => Number(col)),
  );
}
export function labBindings(snapshot?: Record<string, unknown>): TemplateBinding[] {
  const sheet = labSheet(snapshot),
    def = sheet?.custom?.labTemplate;
  if (!sheet || !def) return [];
  const bindings: TemplateBinding[] = [];
  for (const [row, cells] of Object.entries(sheet.cellData ?? {}))
    for (const [column, cell] of Object.entries(cells)) {
      const meta = cell.custom?.lab;
      if (meta?.role !== 'VALUE' || !meta.fieldCode) continue;
      const field = def.fields[meta.fieldCode];
      if (!field) continue;
      const address = labAddress(Number(row), Number(column));
      bindings.push({
        bindingId: `${meta.fieldCode}:${column}`,
        fieldCode: meta.fieldCode,
        dataPath: `/fields/${meta.fieldCode}${field.scope === 'SAMPLE' ? `/${Number(column) - def.firstSampleColumn}` : ''}`,
        role: 'FIELD',
        mappingKind: 'SCALAR',
        locatorType: 'CELL_RANGE',
        locator: {
          sheetId: sheet.id,
          sheetName: sheet.name,
          address,
          valueRange: address,
          valueSource: field.valueSource,
        },
        // Native range protection also blocks inserting material rows before the total.
        // The server always rebuilds calculated fields from the full recipe on save.
        syncDirection: 'TWO_WAY',
        primaryBinding: true,
        bindingStatus: 'VALID',
      });
    }
  return bindings;
}

export function labMassFormula(column: number, firstRow: number, lastRow: number): string {
  const start = labAddress(firstRow, column),
    end = labAddress(lastRow, column),
    values = `${start}:${end}`;
  const labels = `$B$${firstRow + 1}:$B$${lastRow + 1}`;
  const numbers = `IFERROR(--SUBSTITUTE(SUBSTITUTE(LOWER(TRIM(${values})),"g",""),"/","0"),-1)`;
  return `=IF(COUNTA(${values})=0,"",IF(OR(SUMPRODUCT((${labels}<>"")*(${values}=""))>0,SUMPRODUCT((${labels}="")*(${values}<>"")*(${values}<>"/"))>0,SUMPRODUCT(--(${numbers}<0))>0,SUMPRODUCT(--ISNUMBER(SEARCH("%",${values})))>0,SUMPRODUCT((${values}<>"/")*ISNUMBER(SEARCH("/",${values})))>0),"",SUMPRODUCT(${numbers})))`;
}

export { copyWorkbookCells as copyLabCells } from '@/features/template-workspace/workbook-instance';
