import type { ExperimentModel } from '@/services/experiments/experiment-api';

type ExperimentListKey = 'formulaItems' | 'processSteps' | 'testResults' | 'events';
type StableItemListKey = Exclude<ExperimentListKey, 'events'>;

/** 实验本在 Univer 编辑器中的固定工作表布局约定。 */
export interface ExperimentSheetLayout {
  key: ExperimentListKey;
  sheetName: string;
  columns: Array<{ key: string; title: string }>;
}

const HEADER_ROW = 1; // 表头位于第 1 行（0 基索引）

const SHEET_LAYOUTS: ExperimentSheetLayout[] = [
  {
    key: 'formulaItems',
    sheetName: '配方数据',
    columns: [
      { key: 'materialName', title: '物料' },
      { key: 'ratio', title: '比例' },
      { key: 'actualQty', title: '实际量' },
      { key: 'unit', title: '单位' },
    ],
  },
  {
    key: 'processSteps',
    sheetName: '工艺过程',
    columns: [
      { key: 'stepNo', title: '步骤' },
      { key: 'operation', title: '操作' },
      { key: 'temperature', title: '温度' },
      { key: 'duration', title: '时长' },
    ],
  },
  {
    key: 'testResults',
    sheetName: '测试结果',
    columns: [
      { key: 'testItem', title: '项目' },
      { key: 'value', title: '结果' },
      { key: 'unit', title: '单位' },
      { key: 'judgement', title: '判定' },
    ],
  },
  {
    key: 'events',
    sheetName: '异常事件',
    columns: [
      { key: 'eventTime', title: '时间' },
      { key: 'eventType', title: '类型' },
      { key: 'description', title: '说明' },
      { key: 'action', title: '处置' },
    ],
  },
];

const DEFAULT_ROW_CAPACITY = 200;
const ITEM_ID_HEADER = '__itemId';

function hasStableItemId(key: ExperimentListKey): key is StableItemListKey {
  return key !== 'events';
}

function createItemId(): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') return globalThis.crypto.randomUUID();
  return `item-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function makeSheet(sheetId: string, name: string, header: string[]): Record<string, unknown> {
  const cellData: Record<string, Record<string, { v: string }>> = {};
  const headerKey = String(HEADER_ROW);
  const headerRow = (cellData[headerKey] ??= {});
  header.forEach((title, column) => {
    headerRow[String(column)] = { v: title };
  });
  return {
    id: sheetId,
    name,
    rowCount: DEFAULT_ROW_CAPACITY,
    columnCount: Math.max(header.length, 8),
    cellData,
  };
}

function toWorkbookValue(value: unknown): unknown {
  if (value === null || value === undefined) return '';
  return value;
}

function setCell(
  sheet: Record<string, unknown>,
  row: number,
  column: number,
  value: unknown,
): void {
  const cellData = (sheet.cellData as Record<string, Record<string, { v: unknown }>>) ?? {};
  const rowKey = String(row);
  const rowNode = (cellData[rowKey] ??= {});
  rowNode[String(column)] = { v: toWorkbookValue(value) };
  sheet.cellData = cellData;
}

function readCell(sheet: Record<string, unknown>, row: number, column: number): string {
  const cellData = (sheet.cellData as Record<string, Record<string, { v: unknown }>>) ?? {};
  const value = cellData[String(row)]?.[String(column)]?.v;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return typeof value === 'string' ? value : '';
}

export function isDocumentSnapshot(snapshot: Record<string, unknown> | null | undefined): boolean {
  if (!snapshot) return false;
  return 'body' in snapshot || 'documentStyle' in snapshot || 'editorMode' in snapshot;
}

/** Remove the legacy image-only OCR source sheet; the source file is downloaded separately. */
export function withoutOcrSourceSheet(snapshot: Record<string, unknown>): Record<string, unknown> {
  const sourceSheets = snapshot.sheets as Record<string, Record<string, unknown>> | undefined;
  if (!sourceSheets) return snapshot;
  const removedIds = Object.entries(sourceSheets)
    .filter(([sheetId, sheet]) => sheetId === 'sheet-ocr-text' || sheet.name === 'OCR原文')
    .map(([sheetId]) => sheetId);
  if (removedIds.length === 0) return snapshot;
  const removed = new Set(removedIds);
  const sheets = Object.fromEntries(Object.entries(sourceSheets).filter(([sheetId]) => !removed.has(sheetId)));
  const sheetOrder = Array.isArray(snapshot.sheetOrder)
    ? snapshot.sheetOrder.filter((sheetId) => typeof sheetId !== 'string' || !removed.has(sheetId))
    : snapshot.sheetOrder;
  const resources = Array.isArray(snapshot.resources)
    ? snapshot.resources.filter((resource) => {
        if (!resource || typeof resource !== 'object') return true;
        const item = resource as Record<string, unknown>;
        const resourceData = item.data;
        return item.name !== 'SHEET_DRAWING_PLUGIN'
          || typeof resourceData !== 'string'
          || !removedIds.some((sheetId) => resourceData.includes(`\"${sheetId}\"`));
      })
    : snapshot.resources;
  return { ...snapshot, sheets, sheetOrder, resources };
}

function buildBlankDocumentSnapshot(id: string, title?: string): Record<string, unknown> {
  const content = title?.trim() ? title.trim() : '';
  const textLength = content.length;
  return {
    id,
    snapshotFormatVersion: 5,
    editorMode: 'UNIVER_DOCS',
    documentStyle: {},
    body: {
      dataStream: `${content}\r\n`,
      textRuns: textLength
        ? [
            {
              st: 0,
              ed: textLength,
              ts: { fs: 24, bl: 1 },
            },
          ]
        : [],
      paragraphs: [
        {
          startIndex: textLength,
          paragraphStyle: {
            spaceAbove: 12,
            spaceBelow: 12,
          },
        },
      ],
    },
  };
}

function buildBlankWorkbookSnapshot(id: string): Record<string, unknown> {
  const sheetId = 'sheet-1';
  return {
    id,
    snapshotFormatVersion: 3,
    name: 'Sheet1',
    sheetOrder: [sheetId],
    sheets: {
      [sheetId]: {
        id: sheetId,
        name: 'Sheet1',
        rowCount: DEFAULT_ROW_CAPACITY,
        columnCount: 26,
        cellData: {},
      },
    },
    styles: {},
  };
}

/**
 * 将实验本 editModel 构建为 Univer 编辑器可直接渲染的快照。
 * - Word 实验：返回文档快照（已有 documentSnapshot/模板快照时复用，否则生成空白文档）。
 * - Excel 实验：返回 workbook 快照。
 */
export function buildExperimentSnapshot(
  editModel: ExperimentModel,
  id: string,
  templateSnapshot?: Record<string, unknown> | null,
): Record<string, unknown> {
  if (editModel.documentSnapshot && Object.keys(editModel.documentSnapshot).length > 0) {
    return withoutOcrSourceSheet(editModel.documentSnapshot);
  }
  if (editModel.blankDocument === true) {
    return editModel.documentFormat === 'word'
      ? buildBlankDocumentSnapshot(id)
      : buildBlankWorkbookSnapshot(id);
  }
  const isWord =
    editModel.documentFormat === 'word' ||
    isDocumentSnapshot(editModel.documentSnapshot) ||
    isDocumentSnapshot(templateSnapshot);

  if (isWord) {
    if (isDocumentSnapshot(editModel.documentSnapshot)) return editModel.documentSnapshot as Record<string, unknown>;
    if (isDocumentSnapshot(templateSnapshot)) return templateSnapshot as Record<string, unknown>;
    return buildBlankDocumentSnapshot(id, editModel.title);
  }

  if (templateSnapshot?.sheets && typeof templateSnapshot.sheets === 'object') {
    return templateSnapshot;
  }

  const sheets: Record<string, Record<string, unknown>> = {};

  const recordRows: Array<[string, unknown]> = [
    ['实验标题', editModel.title ?? ''],
    ['实验目的', editModel.purpose ?? ''],
    ['实验方案', editModel.plan ?? ''],
    ['结果状态', editModel.conclusion?.resultStatus ?? ''],
    ['失败原因分类', editModel.conclusion?.failureCategory ?? ''],
    ['主要结论', editModel.conclusion?.mainConclusion ?? ''],
  ];
  const recordSheet = makeSheet('sheet-record', '实验记录', ['字段', '内容']);
  recordRows.forEach(([label, value], row) => {
    const targetRow = HEADER_ROW + 1 + row;
    setCell(recordSheet, targetRow, 0, label);
    setCell(recordSheet, targetRow, 1, value ?? '');
  });
  sheets['sheet-record'] = recordSheet;

  for (const layout of SHEET_LAYOUTS) {
    const sheet = makeSheet(`sheet-${layout.sheetName}`, layout.sheetName, layout.columns.map((c) => c.title));
    const records = Array.isArray(editModel[layout.key]) ? (editModel[layout.key] as Array<Record<string, unknown>>) : [];
    const itemIdColumn = layout.columns.length;
    if (hasStableItemId(layout.key)) {
      setCell(sheet, HEADER_ROW, itemIdColumn, ITEM_ID_HEADER);
      sheet.columnData = { [itemIdColumn]: { hd: 1 } };
    }
    records.forEach((record, row) => {
      const targetRow = HEADER_ROW + 1 + row;
      layout.columns.forEach((column, columnIndex) => {
        setCell(sheet, targetRow, columnIndex, record[column.key] ?? '');
      });
      if (hasStableItemId(layout.key)) {
        const itemId = typeof record.itemId === 'string' && record.itemId.trim()
          ? record.itemId.trim()
          : createItemId();
        setCell(sheet, targetRow, itemIdColumn, itemId);
      }
    });
    sheets[`sheet-${layout.sheetName}`] = sheet;
  }

  return {
    id,
    snapshotFormatVersion: 3,
    name: editModel.title ?? '实验记录',
    sheetOrder: ['sheet-record', ...SHEET_LAYOUTS.map((layout) => `sheet-${layout.sheetName}`)],
    sheets,
    styles: {},
  };
}

/** 从 workbook 快照读回实验记录 sheet 的标量字段。 */
export function parseExperimentRecord(snapshot: Record<string, unknown>, editModel: ExperimentModel): ExperimentModel {
  const sheet = (snapshot.sheets as Record<string, Record<string, unknown>> | undefined)?.['sheet-record'];
  const next: ExperimentModel = { ...editModel };
  if (sheet) {
    const conclusion = { ...(editModel.conclusion ?? {}) };
    next.title = readCell(sheet, HEADER_ROW + 1 + 0, 1);
    next.purpose = readCell(sheet, HEADER_ROW + 1 + 1, 1);
    next.plan = readCell(sheet, HEADER_ROW + 1 + 2, 1);
    conclusion.resultStatus = readCell(sheet, HEADER_ROW + 1 + 3, 1);
    conclusion.failureCategory = readCell(sheet, HEADER_ROW + 1 + 4, 1);
    conclusion.mainConclusion = readCell(sheet, HEADER_ROW + 1 + 5, 1);
    next.conclusion = conclusion;
  }
  return next;
}

/** 从 workbook 快照读回各明细 sheet 的数组记录。 */
export function parseExperimentRecords(snapshot: Record<string, unknown>, editModel: ExperimentModel): ExperimentModel {
  const next: ExperimentModel = { ...editModel };
  for (const layout of SHEET_LAYOUTS) {
    const sheetId = `sheet-${layout.sheetName}`;
    const sheet = (snapshot.sheets as Record<string, Record<string, unknown>> | undefined)?.[sheetId];
    if (!sheet) continue;
    const previousRows = Array.isArray(editModel[layout.key])
      ? (editModel[layout.key] as Array<Record<string, unknown>>)
      : [];
    const previousById = new Map<string, Record<string, unknown>>();
    if (hasStableItemId(layout.key)) {
      previousRows.forEach((record) => {
        if (typeof record.itemId === 'string' && record.itemId.trim()) {
          previousById.set(record.itemId.trim(), record);
        }
      });
    }
    const seenItemIds = new Set<string>();
    const sheetHasItemIds = hasStableItemId(layout.key)
      && readCell(sheet, HEADER_ROW, layout.columns.length) === ITEM_ID_HEADER;
    const rows: Array<Record<string, unknown>> = [];
    let row = HEADER_ROW + 1;
    while (row < DEFAULT_ROW_CAPACITY) {
      const rowIndex = row - (HEADER_ROW + 1);
      const itemIdColumn = layout.columns.length;
      let itemId = sheetHasItemIds ? readCell(sheet, row, itemIdColumn).trim() : '';
      if (hasStableItemId(layout.key) && (!itemId || seenItemIds.has(itemId))) {
        const indexedItemId = typeof previousRows[rowIndex]?.itemId === 'string'
          ? previousRows[rowIndex].itemId.trim()
          : '';
        itemId = indexedItemId && !seenItemIds.has(indexedItemId) ? indexedItemId : createItemId();
      }
      const previous = hasStableItemId(layout.key)
        ? (previousById.get(itemId) ?? previousRows[rowIndex])
        : previousRows[rowIndex];
      const record: Record<string, unknown> = { ...(previous ?? {}) };
      let hasValue = false;
      layout.columns.forEach((column, columnIndex) => {
        const value = readCell(sheet, row, columnIndex);
        if (value.trim() !== '') hasValue = true;
        // An imported missing value is represented by JSON null while its
        // source text (for example "/") remains in rawValue. Rendering the
        // cell as blank and saving it unchanged must not turn that null into
        // an empty string, otherwise later data-quality projection loses the
        // distinction between a missing measurement and entered text.
        const sourceMissing = (column.key === 'value' || column.key === 'ratio')
          && previous?.rawValue === '/';
        record[column.key] = value === '' && (previous?.[column.key] === null || sourceMissing)
          ? null
          : value;
      });
      if (!hasValue) break;
      if (hasStableItemId(layout.key)) {
        record.itemId = itemId;
        record.sourceRefs = Array.isArray(record.sourceRefs) ? record.sourceRefs : [];
        seenItemIds.add(itemId);
      }
      rows.push(record);
      row += 1;
    }
    (next as Record<string, unknown>)[layout.key] = rows;
  }
  return next;
}

/** 将 Univer 快照整体解析回实验本 editModel。 */
export function parseExperimentSnapshot(snapshot: Record<string, unknown>, editModel: ExperimentModel): ExperimentModel {
  if (editModel.documentFormat === 'word' || isDocumentSnapshot(snapshot)) {
    return { ...editModel, documentSnapshot: snapshot };
  }
  return {
    ...parseExperimentRecords(snapshot, parseExperimentRecord(snapshot, editModel)),
    documentSnapshot: snapshot,
  };
}
