import type { ExperimentModel } from '@/services/experiments/experiment-api';
import { generateUUID } from '@/utils/uuid';

type ExperimentListKey = 'formulaItems' | 'processSteps' | 'testResults' | 'events';
type StableItemListKey = Exclude<ExperimentListKey, 'events'>;

/** 实验本在 Univer 编辑器中的固定工作表布局约定。 */
export interface ExperimentSheetLayout {
  key: ExperimentListKey;
  sheetName: string;
  columns: Array<{ key: string; title: string }>;
  columnWidths: number[];
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
    columnWidths: [240, 120, 120, 72],
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
    columnWidths: [82, 300, 110, 110],
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
    columnWidths: [190, 140, 90, 100],
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
    columnWidths: [160, 120, 340, 240],
  },
];

const DEFAULT_ROW_CAPACITY = 200;
const ITEM_ID_HEADER = '__itemId';

function hasStableItemId(key: ExperimentListKey): key is StableItemListKey {
  return key !== 'events';
}

function createItemId(): string {
  return generateUUID();
}

function makeSheet(sheetId: string, name: string, header: string[], columnWidths: number[] = []): Record<string, unknown> {
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
    ...(columnWidths.length ? {
      columnData: Object.fromEntries(columnWidths.map((width, column) => [String(column), { w: width }])),
    } : {}),
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

function isWorkbookSnapshot(snapshot: Record<string, unknown> | null | undefined): boolean {
  return Boolean(snapshot?.sheets && typeof snapshot.sheets === 'object' && !Array.isArray(snapshot.sheets));
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
          || !removedIds.some((sheetId) => resourceData.includes(`"${sheetId}"`));
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

function buildOptimizationPredictionSheet(editModel: ExperimentModel): Record<string, unknown> | undefined {
  const aiSource = editModel.aiSource as Record<string, unknown> | undefined;
  const predictions = aiSource?.predictions as Record<string, unknown> | undefined;
  const rows = Array.isArray(predictions?.targets) ? predictions.targets as Array<Record<string, unknown>> : [];
  if (!aiSource || rows.length === 0) return undefined;
  const sheet = makeSheet('sheet-ai-prediction-reference', '预测参考', ['性能', '预测结果', '预测范围', '说明'], [200, 150, 180, 340]);
  setCell(sheet, 2, 0, '以下是实验前模型预测，不能代替实测结果');
  rows.forEach((item, index) => {
    const result = (item.result && typeof item.result === 'object' ? item.result : item) as Record<string, unknown>;
    const row = index + 3;
    const statusValue = item.status ?? result.status;
    const status = typeof statusValue === 'string' ? statusValue : 'SUCCEEDED';
    const unit = typeof result.unit === 'string' && result.unit ? ` ${result.unit}` : '';
    let value = '当前条件无法预测';
    let range = '';
    if (status === 'SUCCEEDED') {
      if (result.resultType === 'CONTINUOUS' && typeof result.value === 'number') {
        value = `${Number(result.value.toFixed(2))}${unit}`;
        if (typeof result.lower === 'number' && typeof result.upper === 'number') {
          range = `${Number(result.lower.toFixed(2))}～${Number(result.upper.toFixed(2))}${unit}`;
        }
      } else {
        const rawLabel = result.label ?? result.value ?? result.predictedClass;
        const label = typeof rawLabel === 'string' || typeof rawLabel === 'number' || typeof rawLabel === 'boolean'
          ? String(rawLabel)
          : '—';
        value = result.label && typeof result.probability === 'number'
          ? `${label} · ${(result.probability * 100).toFixed(0)}%`
          : label;
      }
    }
    setCell(sheet, row, 0, item.targetName ?? result.targetName ?? '性能指标');
    setCell(sheet, row, 1, value);
    setCell(sheet, row, 2, range);
    setCell(sheet, row, 3, status === 'SUCCEEDED' ? '实验前预测值；实测结果请填写在“测试结果”页' : '该性能在当前条件下没有可用预测值');
  });
  return sheet;
}

function buildOptimizationConditionsSheet(
  editModel: ExperimentModel,
  alreadyMapped: Set<string> = new Set(),
): Record<string, unknown> | undefined {
  const aiSource = editModel.aiSource as Record<string, unknown> | undefined;
  const values = aiSource?.plannedInputs as Record<string, unknown> | undefined;
  if (!aiSource || !values || Object.keys(values).length === 0) return undefined;
  const sheet = makeSheet('sheet-ai-conditions', '实验条件', ['实验前条件', '设定值'], [220, 180]);
  const aliases: Record<string, string> = {
    substrate: '基材', substrateThickness: '基材厚度', temperature: '测试温度', testTemperature: '测试温度',
    humidity: '环境湿度', coatingSolids: '涂料固含', applicationMethod: '施工方式', curingSource: '固化方式',
    uvEnergy: 'UV能量', UVA: 'UVA光强', uva: 'UVA光强',
  };
  const remaining = Object.entries(values).filter(([code]) => !alreadyMapped.has(normalizeTemplateKey(code)));
  if (remaining.length === 0) return undefined;
  remaining.forEach(([code, value], index) => {
    const normalized = code.replace(/[-_]/g, '').toLowerCase();
    const inferred = normalized.includes('temperature') || normalized.includes('temp') ? '测试温度'
      : normalized.includes('humidity') ? '环境湿度'
        : normalized.includes('substrate') ? '基材'
          : normalized.includes('coatingsolids') || normalized.includes('solidcontent') ? '涂料固含'
            : normalized.includes('uvenergy') ? 'UV能量'
              : normalized.includes('uva') || normalized.includes('uvintensity') ? 'UVA光强'
                : normalized.includes('applicationmethod') ? '施工方式'
                  : normalized.includes('curing') ? '固化方式' : undefined;
    const label = aliases[code] ?? (/[\u3400-\u9fff]/.test(code) ? code : inferred ?? `实验条件 ${index + 1}`);
    setCell(sheet, index + 2, 0, label);
    setCell(sheet, index + 2, 1, value);
  });
  return sheet;
}

interface OptimizationTemplateBinding {
  fieldCode?: string;
  targetPath?: string;
  mappingKind?: string;
  repeatAxis?: string;
  recordHeight?: number;
  recordWidth?: number;
  recordStride?: number;
  valueSource?: string;
  locator?: Record<string, unknown>;
}

function normalizeTemplateKey(value: string): string {
  return value.replace(/[^\p{L}\p{N}]/gu, '').toLowerCase();
}

function templateCellRange(locator: Record<string, unknown>): string {
  const nestedValue = locator.value && typeof locator.value === 'object'
    ? locator.value as Record<string, unknown>
    : {};
  for (const key of ['anchorRange', 'logicalInputRange', 'anchorAddress', 'valueRange', 'valueAddress', 'address', 'range']) {
    const value = locator[key];
    if (typeof value === 'string' && value.trim()) return value.trim();
  }
  for (const key of ['range', 'address']) {
    const value = nestedValue[key];
    if (typeof value === 'string' && value.trim()) return value.trim();
  }
  return '';
}

function parseTemplateCellRange(address: string): { row: number; column: number } | undefined {
  const firstCell = address.replaceAll('$', '').split(':', 1)[0]?.trim().toUpperCase() ?? '';
  const match = firstCell.match(/^([A-Z]{1,4})([1-9][0-9]*)$/);
  if (!match) return undefined;
  let column = 0;
  for (const character of match[1] ?? '') column = column * 26 + character.charCodeAt(0) - 64;
  return { row: Number(match[2]), column };
}

function findTemplateSheet(
  sheets: Record<string, Record<string, unknown>>,
  locator: Record<string, unknown>,
): { id: string; sheet: Record<string, unknown> } | undefined {
  const selector = [locator.sheetId, locator.sheetCode, locator.sheetName, locator.sheet]
    .find((value): value is string => typeof value === 'string' && Boolean(value.trim()))?.trim();
  if (!selector) return undefined;
  const match = Object.entries(sheets).find(([id, sheet]) => id === selector || sheet.id === selector || sheet.name === selector);
  return match ? { id: match[0], sheet: match[1] } : undefined;
}

function templateValues(editModel: ExperimentModel, binding: OptimizationTemplateBinding): {
  values: unknown[];
  collection?: string;
  inputKey?: string;
} {
  const path = binding.targetPath ?? '';
  const itemMatch = path.match(/^\/editModel\/(formulaItems|processSteps|testResults)\/\*\/([^/]+)$/);
  if (itemMatch) {
    const collection = itemMatch[1] as StableItemListKey;
    const sourceRecords = Array.isArray(editModel[collection]) ? editModel[collection] as Array<Record<string, unknown>> : [];
    const records = collection === 'formulaItems' && editModel.aiSource
      ? roundOptimizationFormulaRatios(sourceRecords)
      : sourceRecords;
    return { values: records.map((record) => record[itemMatch[2] ?? '']), collection };
  }
  if (path === '/editModel/dynamicValues/*' || path === '/editModel/dynamicValues') {
    const inputKey = binding.fieldCode ?? '';
    const values = {
      ...(editModel.dynamicValues ?? {}),
      ...((editModel.aiSource as Record<string, unknown> | undefined)?.plannedInputs as Record<string, unknown> ?? {}),
    };
    const normalizedCode = normalizeTemplateKey(inputKey);
    const matchedKey = Object.keys(values).find((key) => normalizeTemplateKey(key) === normalizedCode);
    return { values: [matchedKey ? values[matchedKey] : undefined], inputKey: matchedKey };
  }
  const segments = path.replace(/^\/editModel\//, '').split('/').filter(Boolean);
  if (!path.startsWith('/editModel/') || segments.some((segment) => segment === '*')) return { values: [] };
  let value: unknown = editModel;
  for (const segment of segments) {
    const key = segment.replace(/~1/g, '/').replace(/~0/g, '~');
    value = value && typeof value === 'object' ? (value as Record<string, unknown>)[key] : undefined;
  }
  return { values: [value] };
}

function writeOptimizationValuesIntoTemplate(
  snapshot: Record<string, unknown>,
  editModel: ExperimentModel,
): { formulaFields: number; mappedInputs: Set<string> } {
  const sheets = snapshot.sheets as Record<string, Record<string, unknown>> | undefined;
  const aiSource = editModel.aiSource as Record<string, unknown> | undefined;
  const bindings = Array.isArray(aiSource?.templateBindings)
    ? aiSource.templateBindings as OptimizationTemplateBinding[]
    : [];
  if (!sheets || bindings.length === 0) return { formulaFields: 0, mappedInputs: new Set() };
  let formulaFields = 0;
  const mappedInputs = new Set<string>();
  for (const binding of bindings) {
    if (!binding.targetPath || binding.valueSource === 'FORMULA' || binding.valueSource === 'STATIC') continue;
    const locator = binding.locator ?? {};
    const target = findTemplateSheet(sheets, locator);
    const position = parseTemplateCellRange(templateCellRange(locator));
    if (!target || !position) continue;
    const resolved = templateValues(editModel, binding);
    if (resolved.values.length === 0 || resolved.values.every((value) => value === null || value === undefined || value === '')) continue;
    const cellData = (target.sheet.cellData as Record<string, Record<string, Record<string, unknown>>> | undefined) ?? {};
    target.sheet.cellData = cellData;
    const repeat = binding.mappingKind === 'REPEAT_FIELD' || binding.mappingKind === 'REPEAT_REGION';
    const horizontal = (binding.repeatAxis ?? 'ROW').toUpperCase() !== 'ROW';
    const stride = Math.max(1, Number(binding.recordStride) || (horizontal ? Number(binding.recordWidth) : Number(binding.recordHeight)) || 1);
    let wrote = false;
    resolved.values.forEach((value, index) => {
      if (value === null || value === undefined || value === '') return;
      const row = position.row - 1 + (repeat && !horizontal ? index * stride : 0);
      const column = position.column - 1 + (repeat && horizontal ? index * stride : 0);
      const rowKey = String(row);
      const columnKey = String(column);
      const rowData = (cellData[rowKey] ??= {});
      const current = rowData[columnKey];
      const currentCell = current && typeof current === 'object' ? current : {};
      if ('f' in currentCell || 'formula' in currentCell) return;
      rowData[columnKey] = { ...currentCell, v: value };
      wrote = true;
      target.sheet.rowCount = Math.max(Number(target.sheet.rowCount) || 0, row + 1);
      target.sheet.columnCount = Math.max(Number(target.sheet.columnCount) || 0, column + 1);
    });
    if (!wrote) continue;
    if (resolved.collection === 'formulaItems') formulaFields += 1;
    if (resolved.inputKey) mappedInputs.add(normalizeTemplateKey(resolved.inputKey));
  }
  return { formulaFields, mappedInputs };
}

function addOptimizationSheets(
  templateSnapshot: Record<string, unknown>,
  generated: Record<string, unknown>,
  editModel: ExperimentModel,
): Record<string, unknown> {
  const snapshot = JSON.parse(JSON.stringify(templateSnapshot)) as Record<string, unknown>;
  const rawTemplateSheets = snapshot.sheets as Record<string, Record<string, unknown>>;
  const emptyDefaultSheetIds = new Set(Object.entries(rawTemplateSheets)
    .filter(([sheetId, sheet]) => (sheetId === 'sheet-1' || sheet.name === 'Sheet1')
      && Object.keys((sheet.cellData as Record<string, unknown> | undefined) ?? {}).length === 0)
    .map(([sheetId]) => sheetId));
  const templateSheets = Object.fromEntries(Object.entries(rawTemplateSheets)
    .filter(([sheetId]) => !emptyDefaultSheetIds.has(sheetId)));
  snapshot.sheets = templateSheets;
  const generatedSheets = generated.sheets as Record<string, Record<string, unknown>>;
  const mapped = writeOptimizationValuesIntoTemplate(snapshot, editModel);
  const additions: Record<string, unknown>[] = [];
  const formulaItems = Array.isArray(editModel.formulaItems) ? editModel.formulaItems : [];
  if (formulaItems.length > 0 && mapped.formulaFields === 0) {
    const recipe = generatedSheets['sheet-配方数据'];
    if (recipe) additions.push({ ...recipe, name: '方案配方' });
  }
  const conditions = buildOptimizationConditionsSheet(editModel, mapped.mappedInputs);
  const predictions = buildOptimizationPredictionSheet(editModel);
  if (conditions) additions.push(conditions);
  if (predictions) additions.push(predictions);
  additions.forEach((sheet) => { templateSheets[String(sheet.id)] = sheet; });
  const templateOrder = (Array.isArray(snapshot.sheetOrder) ? snapshot.sheetOrder.map(String) : Object.keys(templateSheets))
    .filter((sheetId) => !emptyDefaultSheetIds.has(sheetId));
  return {
    ...snapshot,
    id: generated.id,
    sheets: templateSheets,
    sheetOrder: [...new Set([...templateOrder, ...additions.map((sheet) => String(sheet.id))])],
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
  // Keep AI candidates inside the selected published workbook by using its
  // field coordinates. Only values with no matching template field need a
  // small supplemental sheet; prediction references stay separate from actuals.
  const currentSnapshot = editModel.documentSnapshot;
  const aiTemplateSnapshot = isWorkbookSnapshot(currentSnapshot)
    ? currentSnapshot
    : isWorkbookSnapshot(templateSnapshot) ? templateSnapshot : undefined;
  const isAiWorkbook = Boolean(editModel.aiSource)
    && editModel.blankDocument !== true
    && Boolean(aiTemplateSnapshot);
  if (isAiWorkbook && aiTemplateSnapshot) {
    const currentSheets = aiTemplateSnapshot.sheets as Record<string, Record<string, unknown>>;
    const hasGeneratedSheets = Boolean(currentSheets['sheet-配方数据']);
    if (!hasGeneratedSheets) {
      const generated = buildExperimentSnapshot(
        { ...editModel, documentSnapshot: undefined, documentFormat: 'excel' },
        id,
      );
      return addOptimizationSheets(aiTemplateSnapshot, generated, editModel);
    }
    if (aiTemplateSnapshot === currentSnapshot && currentSnapshot) return withoutOcrSourceSheet(currentSnapshot);
  }
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
    if (editModel.aiSource && !editModel.documentSnapshot) {
      const generated = buildExperimentSnapshot(editModel, id);
      return addOptimizationSheets(templateSnapshot, generated, editModel);
    }
    return templateSnapshot;
  }

  const sheets: Record<string, Record<string, unknown>> = {};

  const recordRows: Array<[string, unknown]> = [
    ['实验标题', editModel.title ?? ''],
    ['实验目的', editModel.purpose ?? ''],
    ['实验方案', editModel.aiSource
      ? '先确定本次试样总量，再按“配方数据”中的比例换算投料；按“实验条件”完成测试后，把实测结果填写在“测试结果”页。'
      : editModel.plan ?? ''],
    ['结果状态', editModel.conclusion?.resultStatus ?? ''],
    ['失败原因分类', editModel.conclusion?.failureCategory ?? ''],
    ['主要结论', editModel.conclusion?.mainConclusion ?? ''],
  ];
  const recordSheet = makeSheet('sheet-record', '实验记录', ['字段', '内容'], [160, 400]);
  recordRows.forEach(([label, value], row) => {
    const targetRow = HEADER_ROW + 1 + row;
    setCell(recordSheet, targetRow, 0, label);
    setCell(recordSheet, targetRow, 1, value ?? '');
  });
  sheets['sheet-record'] = recordSheet;

  for (const layout of SHEET_LAYOUTS) {
    const sheet = makeSheet(`sheet-${layout.sheetName}`, layout.sheetName, layout.columns.map((c) => c.title), layout.columnWidths);
    const sourceRecords = Array.isArray(editModel[layout.key]) ? (editModel[layout.key] as Array<Record<string, unknown>>) : [];
    const records = layout.key === 'formulaItems' && editModel.aiSource
      ? roundOptimizationFormulaRatios(sourceRecords)
      : sourceRecords;
    const itemIdColumn = layout.columns.length;
    if (hasStableItemId(layout.key)) {
      setCell(sheet, HEADER_ROW, itemIdColumn, ITEM_ID_HEADER);
      sheet.columnData = {
        ...(sheet.columnData ?? {}),
        [itemIdColumn]: { hd: 1 },
      };
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

function roundOptimizationFormulaRatios(records: Array<Record<string, unknown>>): Array<Record<string, unknown>> {
  const parsed = records.map((record) => {
    const ratio = Number(record.ratio);
    return { record, ratio: Number.isFinite(ratio) ? ratio : undefined };
  });
  const numericIndexes = parsed.flatMap((item, index) => item.ratio === undefined ? [] : [index]);
  if (numericIndexes.length === 0) return records;
  const roundedTotal = Math.round(parsed.reduce((sum, item) => sum + (item.ratio ?? 0), 0) * 100) / 100;
  let allocated = 0;
  const lastNumericIndex = numericIndexes[numericIndexes.length - 1];
  return parsed.map(({ record, ratio }, index) => {
    if (ratio === undefined) return record;
    const rounded = index === lastNumericIndex
      ? Math.round((roundedTotal - allocated) * 100) / 100
      : Math.round(ratio * 100) / 100;
    allocated += rounded;
    return { ...record, ratio: rounded };
  });
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

function parseOptimizationTemplateBindings(
  snapshot: Record<string, unknown>,
  editModel: ExperimentModel,
): ExperimentModel {
  const sheets = snapshot.sheets as Record<string, Record<string, unknown>> | undefined;
  const aiSource = editModel.aiSource as Record<string, unknown> | undefined;
  const bindings = Array.isArray(aiSource?.templateBindings)
    ? aiSource.templateBindings as OptimizationTemplateBinding[]
    : [];
  if (!sheets || bindings.length === 0) return editModel;
  const next: ExperimentModel = { ...editModel };
  const groups = new Map<StableItemListKey, Map<string, Array<{ binding: OptimizationTemplateBinding; sheet: Record<string, unknown>; position: { row: number; column: number } }>>>();
  const nextDynamicValues = { ...(editModel.dynamicValues ?? {}) };
  for (const binding of bindings) {
    const path = binding.targetPath ?? '';
    if (!path || binding.valueSource === 'FORMULA' || binding.valueSource === 'STATIC') continue;
    const locator = binding.locator ?? {};
    const target = findTemplateSheet(sheets, locator);
    const position = parseTemplateCellRange(templateCellRange(locator));
    if (!target || !position) continue;
    const itemMatch = path.match(/^\/editModel\/(formulaItems|processSteps|testResults)\/\*\/([^/]+)$/);
    if (itemMatch) {
      const collection = itemMatch[1] as StableItemListKey;
      const fields = groups.get(collection)
        ?? new Map<string, Array<{ binding: OptimizationTemplateBinding; sheet: Record<string, unknown>; position: { row: number; column: number } }>>();
      const property = itemMatch[2] ?? '';
      const entries = fields.get(property) ?? [];
      entries.push({ binding, sheet: target.sheet, position });
      fields.set(property, entries);
      groups.set(collection, fields);
      continue;
    }
    if (path === '/editModel/dynamicValues/*' || path === '/editModel/dynamicValues') {
      if (binding.fieldCode) nextDynamicValues[binding.fieldCode] = readTemplateValue(target.sheet, position.row - 1, position.column - 1);
      continue;
    }
    if (!path.startsWith('/editModel/')) continue;
    const segments = path.replace(/^\/editModel\//, '').split('/').filter(Boolean);
    if (segments.some((segment) => segment === '*')) continue;
    const cellValue = readTemplateValue(target.sheet, position.row - 1, position.column - 1);
    let container = next as Record<string, unknown>;
    for (const segment of segments.slice(0, -1)) {
      const child = container[segment];
      if (!child || typeof child !== 'object' || Array.isArray(child)) container[segment] = {};
      container = container[segment] as Record<string, unknown>;
    }
    const lastSegment = segments[segments.length - 1];
    if (lastSegment) container[lastSegment] = cellValue;
  }
  if (Object.keys(nextDynamicValues).length > 0) next.dynamicValues = nextDynamicValues;
  for (const [collection, properties] of groups) {
    const previousRows = Array.isArray(editModel[collection])
      ? editModel[collection] as Array<Record<string, unknown>>
      : [];
    const rows: Array<Record<string, unknown>> = [];
    let emptyRows = 0;
    for (let index = 0; index < Math.max(previousRows.length + 10, 10) && emptyRows < 2; index += 1) {
      const row = { ...(previousRows[index] ?? {}) };
      let hasValue = false;
      for (const [property, entries] of properties) {
        const entry = entries[0];
        if (!entry) continue;
        const repeated = entry.binding.mappingKind === 'REPEAT_FIELD' || entry.binding.mappingKind === 'REPEAT_REGION';
        const horizontal = (entry.binding.repeatAxis ?? 'ROW').toUpperCase() !== 'ROW';
        const stride = Math.max(1, Number(entry.binding.recordStride) || (horizontal ? Number(entry.binding.recordWidth) : Number(entry.binding.recordHeight)) || 1);
        const rowIndex = entry.position.row - 1 + (repeated && !horizontal ? index * stride : 0);
        const columnIndex = entry.position.column - 1 + (repeated && horizontal ? index * stride : 0);
        const value = readTemplateValue(entry.sheet, rowIndex, columnIndex);
        row[property] = value;
        if (String(value ?? '').trim() !== '') hasValue = true;
      }
      if (!hasValue) {
        emptyRows += 1;
        continue;
      }
      emptyRows = 0;
      rows.push(row);
    }
    (next as Record<string, unknown>)[collection] = rows;
  }
  return next;
}

function readTemplateValue(sheet: Record<string, unknown>, row: number, column: number): string {
  return readCell(sheet, row, column);
}

/** 将 Univer 快照整体解析回实验本 editModel。 */
export function parseExperimentSnapshot(snapshot: Record<string, unknown>, editModel: ExperimentModel): ExperimentModel {
  if (editModel.documentFormat === 'word' || isDocumentSnapshot(snapshot)) {
    return { ...editModel, documentSnapshot: snapshot };
  }
  return {
    ...parseOptimizationTemplateBindings(snapshot,
      parseExperimentRecords(snapshot, parseExperimentRecord(snapshot, editModel))),
    documentSnapshot: snapshot,
  };
}
