import type { ExperimentModel } from '@/services/experiments/experiment-api';

type ExperimentListKey = 'formulaItems' | 'processSteps' | 'testResults' | 'events';

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

/**
 * 将实验本 editModel 构建为一个可直接被 UniverSheetsEditor 渲染的 workbook 快照。
 * 布局约定：
 *  - sheet「实验记录」：A 列为字段标签，B 列为内容（第 1 行为表头“字段/内容”）。
 *  - 各数据 sheet：第 1 行为表头，第 2 行起为明细记录。
 */
export function buildExperimentSnapshot(editModel: ExperimentModel, id: string): Record<string, unknown> {
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
    records.forEach((record, row) => {
      const targetRow = HEADER_ROW + 1 + row;
      layout.columns.forEach((column, columnIndex) => {
        setCell(sheet, targetRow, columnIndex, record[column.key] ?? '');
      });
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
    const rows: Array<Record<string, unknown>> = [];
    let row = HEADER_ROW + 1;
    while (row < DEFAULT_ROW_CAPACITY) {
      const record: Record<string, unknown> = {};
      let hasValue = false;
      layout.columns.forEach((column, columnIndex) => {
        const value = readCell(sheet, row, columnIndex);
        if (value.trim() !== '') hasValue = true;
        record[column.key] = value;
      });
      if (!hasValue) break;
      rows.push(record);
      row += 1;
    }
    next[layout.key] = rows;
  }
  return next;
}

/** 将 Univer 快照整体解析回实验本 editModel。 */
export function parseExperimentSnapshot(snapshot: Record<string, unknown>, editModel: ExperimentModel): ExperimentModel {
  return parseExperimentRecords(snapshot, parseExperimentRecord(snapshot, editModel));
}
