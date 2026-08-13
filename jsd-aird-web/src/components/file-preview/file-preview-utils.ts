import { downloadBlob } from '@/services/files';

export type FilePreviewMode = 'pdf' | 'image' | 'audio' | 'text' | 'docx' | 'spreadsheet' | 'unsupported';

export interface FilePreviewDescriptor {
  fileName: string;
  contentType?: string;
  size?: number;
  load: () => Promise<Blob>;
}

export interface SpreadsheetMergeInput {
  s: { r: number; c: number };
  e: { r: number; c: number };
}

export interface SpreadsheetMerge {
  range: string;
  startRow: number;
  startColumn: number;
  endRow: number;
  endColumn: number;
  rowSpan: number;
  colSpan: number;
  clipped: boolean;
}

export interface SpreadsheetCell {
  value: string;
  rowNumber: number;
  columnNumber: number;
  rowSpan: number;
  colSpan: number;
  hidden: boolean;
  mergeRange?: string;
}

export interface SpreadsheetPreview {
  rows: Array<{ rowNumber: number; cells: SpreadsheetCell[] }>;
  columnCount: number;
  startColumn: number;
  merges: SpreadsheetMerge[];
  truncated: boolean;
}

export function excelColumnLabel(index: number) {
  let value = index + 1;
  let label = '';
  while (value > 0) {
    const remainder = (value - 1) % 26;
    label = String.fromCharCode(65 + remainder) + label;
    value = Math.floor((value - 1) / 26);
  }
  return label;
}

export function buildSpreadsheetPreview(
  rawRows: string[][],
  rawMerges: SpreadsheetMergeInput[] = [],
  startRow = 0,
  startColumn = 0,
  maxRows = 200,
  maxColumns = 50,
): SpreadsheetPreview {
  const visibleRows = rawRows.slice(0, maxRows);
  const sourceColumnCount = Math.max(
    0,
    ...rawRows.map((row) => row.length),
    ...rawMerges.map((merge) => merge.e.c - startColumn + 1),
  );
  const columnCount = Math.min(maxColumns, sourceColumnCount);
  const sourceEndRow = startRow + Math.max(0, rawRows.length - 1);
  const sourceEndColumn = startColumn + Math.max(0, sourceColumnCount - 1);
  const visibleEndRow = startRow + Math.max(0, visibleRows.length - 1);
  const visibleEndColumn = startColumn + Math.max(0, columnCount - 1);
  const merges = rawMerges
    .filter((merge) => merge.s.r <= visibleEndRow && merge.e.r >= startRow && merge.s.c <= visibleEndColumn && merge.e.c >= startColumn)
    .map((merge) => {
      const clippedStartRow = Math.max(merge.s.r, startRow);
      const clippedStartColumn = Math.max(merge.s.c, startColumn);
      const clippedEndRow = Math.min(merge.e.r, visibleEndRow);
      const clippedEndColumn = Math.min(merge.e.c, visibleEndColumn);
      return {
        range: `${excelColumnLabel(merge.s.c)}${merge.s.r + 1}:${excelColumnLabel(merge.e.c)}${merge.e.r + 1}`,
        startRow: clippedStartRow,
        startColumn: clippedStartColumn,
        endRow: clippedEndRow,
        endColumn: clippedEndColumn,
        rowSpan: Math.max(1, clippedEndRow - clippedStartRow + 1),
        colSpan: Math.max(1, clippedEndColumn - clippedStartColumn + 1),
        clipped: clippedStartRow !== merge.s.r || clippedStartColumn !== merge.s.c || clippedEndRow !== merge.e.r || clippedEndColumn !== merge.e.c,
      } satisfies SpreadsheetMerge;
    });
  const rows: SpreadsheetPreview['rows'] = visibleRows.map((row, rowIndex) => ({
    rowNumber: startRow + rowIndex + 1,
    cells: Array.from({ length: columnCount }, (_, columnIndex) => ({
      value: row[columnIndex] || '',
      rowNumber: startRow + rowIndex + 1,
      columnNumber: startColumn + columnIndex,
      rowSpan: 1,
      colSpan: 1,
      hidden: false,
    })),
  }));
  merges.forEach((merge) => {
    const anchorRow = merge.startRow - startRow;
    const anchorColumn = merge.startColumn - startColumn;
    const anchor = rows[anchorRow]?.cells[anchorColumn];
    if (!anchor) return;
    anchor.rowSpan = merge.rowSpan;
    anchor.colSpan = merge.colSpan;
    anchor.mergeRange = merge.range;
    for (let rowIndex = merge.startRow; rowIndex <= merge.endRow; rowIndex += 1) {
      for (let columnIndex = merge.startColumn; columnIndex <= merge.endColumn; columnIndex += 1) {
        if (rowIndex === merge.startRow && columnIndex === merge.startColumn) continue;
        const cell = rows[rowIndex - startRow]?.cells[columnIndex - startColumn];
        if (cell) {
          cell.hidden = true;
          cell.mergeRange = merge.range;
        }
      }
    }
  });
  return {
    rows,
    columnCount,
    startColumn,
    merges,
    truncated: rawRows.length > maxRows || sourceEndColumn > visibleEndColumn || sourceEndRow > visibleEndRow,
  };
}

export function detectPreviewMode(fileName: string, contentType = ''): FilePreviewMode {
  const extension = fileName.split('.').pop()?.toLowerCase() || '';
  const normalizedType = contentType.toLowerCase();
  if (normalizedType === 'application/pdf' || extension === 'pdf') return 'pdf';
  if (normalizedType.startsWith('image/')) return 'image';
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg', 'tif', 'tiff'].includes(extension)) return 'image';
  if (normalizedType.startsWith('audio/') || ['wav', 'mp3', 'm4a', 'aac', 'flac', 'ogg', 'opus'].includes(extension)) return 'audio';
  if (normalizedType.includes('wordprocessingml.document') || extension === 'docx') return 'docx';
  if (normalizedType.includes('spreadsheetml.sheet') || normalizedType.includes('ms-excel') || ['xls', 'xlsx', 'csv'].includes(extension)) return 'spreadsheet';
  if (normalizedType.startsWith('text/') || ['txt', 'md', 'json', 'xml', 'log'].includes(extension)) return 'text';
  return 'unsupported';
}

export async function downloadPreviewFile(file: FilePreviewDescriptor) {
  const blob = await file.load();
  downloadBlob(blob, file.fileName);
}
