import { createUniver, LocaleType, mergeLocales, type FUniver } from '@univerjs/presets';
import { UniverSheetsCorePreset } from '@univerjs/preset-sheets-core';
import UniverPresetSheetsCoreZhCN from '@univerjs/preset-sheets-core/locales/zh-CN';
import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';

import '@univerjs/preset-sheets-core/lib/index.css';

import { operationFromUniverCommand } from './structure-migration';
import type {
  EditorHandle,
  EditorSelection,
  TemplateBinding,
  WorkbookStructureOperation,
} from './types';

interface Props {
  snapshot: Record<string, unknown>;
  onDirty: () => void;
  onEditorValue: (binding: TemplateBinding, value: unknown) => void;
  onEditorLabel?: (binding: TemplateBinding, value: unknown) => void;
  bindings: TemplateBinding[];
  editable?: boolean;
  onSelectionChange?: (selection: EditorSelection) => void;
  onUnboundCellChange?: (selection: EditorSelection, value: unknown) => void;
  onStructureChange?: (operation: WorkbookStructureOperation) => void;
  onReady?: () => void;
}

export const UniverSheetsEditor = forwardRef<EditorHandle, Props>(function UniverSheetsEditor(
  {
    snapshot,
    onDirty,
    onEditorValue,
    onEditorLabel,
    bindings,
    editable = true,
    onSelectionChange,
    onUnboundCellChange,
    onStructureChange,
    onReady,
  },
  ref,
) {
  const containerRef = useRef<HTMLDivElement>(null);
  const univerRef = useRef<ReturnType<typeof createUniver>['univer']>();
  const apiRef = useRef<FUniver>();
  const bindingsRef = useRef(bindings);
  const callbacksRef = useRef({
    onDirty,
    onEditorValue,
    onEditorLabel,
    onSelectionChange,
    onUnboundCellChange,
    onStructureChange,
    onReady,
  });
  const suppressUnboundRef = useRef(false);
  const editableRef = useRef(editable);
  const highlightRef = useRef<Array<{ dispose(): void }>>([]);
  const highlightTimersRef = useRef<number[]>([]);
  bindingsRef.current = bindings;
  callbacksRef.current = {
    onDirty,
    onEditorValue,
    onEditorLabel,
    onSelectionChange,
    onUnboundCellChange,
    onStructureChange,
    onReady,
  };
  editableRef.current = editable;

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    const host = document.createElement('div');
    host.className = 'univer-editor-host';
    container.replaceChildren(host);
    let cancelled = false;
    let animationFrame = 0;
    let commandSubscription: { dispose(): void } | undefined;
    let selectionSubscription: { dispose(): void } | undefined;
    let ownedUniver: ReturnType<typeof createUniver>['univer'] | undefined;
    let ownedApi: FUniver | undefined;
    const labelValues = new Map<string, unknown>();
    const bindingValues = new Map<string, unknown>();
    let pendingSynchronization = 0;
    let latestSelection: EditorSelection | undefined;

    const initialize = () => {
      if (cancelled) return;
      const { width, height } = container.getBoundingClientRect();
      if (width < 320 || height < 240) {
        animationFrame = window.requestAnimationFrame(initialize);
        return;
      }
      const { univer, univerAPI } = createUniver({
        locale: LocaleType.ZH_CN,
        locales: {
          [LocaleType.ZH_CN]: mergeLocales(UniverPresetSheetsCoreZhCN),
        },
        presets: [
          UniverSheetsCorePreset({
            container: host,
            ribbonType: 'collapsed',
            footer: { sheetBar: true, statisticBar: true },
          }),
        ],
      });
      ownedUniver = univer;
      ownedApi = univerAPI;
      univerRef.current = univer;
      apiRef.current = univerAPI;
      const workbook = univerAPI.createWorkbook(snapshot);
      workbook.setEditable(editableRef.current);
      if (editableRef.current) {
        void protectReadOnlyRanges(univerAPI, bindingsRef.current).catch(() => undefined);
      }
      window.requestAnimationFrame(() => {
        window.requestAnimationFrame(() => {
          if (!cancelled) callbacksRef.current.onReady?.();
        });
      });
      for (const binding of bindingsRef.current) {
        labelValues.set(labelCacheKey(binding), readLabelCell(univerAPI, binding));
        bindingValues.set(valueCacheKey(binding), readCell(univerAPI, binding));
      }
      commandSubscription = univerAPI.onCommandExecuted((command) => {
        const structureOperation = operationFromUniverCommand(command);
        if (!structureOperation && !isCellMutationCommand(command.id)) return;
        callbacksRef.current.onDirty();
        if (structureOperation) callbacksRef.current.onStructureChange?.(structureOperation);
        window.cancelAnimationFrame(pendingSynchronization);
        pendingSynchronization = window.requestAnimationFrame(() => {
          for (const binding of bindingsRef.current) {
            if (binding.syncDirection === 'DATA_TO_EDITOR') continue;
            const valueKey = valueCacheKey(binding);
            const value = readCell(univerAPI, binding);
            if (!bindingValues.has(valueKey)) {
              bindingValues.set(valueKey, value);
            } else if (!sameValue(bindingValues.get(valueKey), value)) {
              bindingValues.set(valueKey, value);
              callbacksRef.current.onEditorValue(binding, value);
            }
            const cacheKey = labelCacheKey(binding);
            const labelValue = readLabelCell(univerAPI, binding);
            if (!labelValues.has(cacheKey)) {
              labelValues.set(cacheKey, labelValue);
            } else if (!sameValue(labelValues.get(cacheKey), labelValue)) {
              labelValues.set(cacheKey, labelValue);
              callbacksRef.current.onEditorLabel?.(binding, labelValue);
            }
          }
          if (!suppressUnboundRef.current && latestSelection) {
            const activeSheet = univerAPI.getActiveWorkbook()?.getActiveSheet();
            if (activeSheet && activeSheet.getSheetId() === latestSelection.sheetId) {
              const range = activeSheet.getRange(latestSelection.address);
              const value = latestSelection.address.includes(':')
                ? range.getValues()
                : range.getValue();
              callbacksRef.current.onUnboundCellChange?.(latestSelection, value);
            }
          }
        });
      });
      selectionSubscription = workbook.onSelectionChange((selections) => {
        const selection = selections.at(-1);
        const sheet = workbook.getActiveSheet();
        if (!selection || !sheet) return;
        const range = sheet.getRange(selection);
        latestSelection = {
          sheetId: sheet.getSheetId(),
          sheetName: sheet.getSheetName(),
          address: range.getA1Notation(),
        };
        callbacksRef.current.onSelectionChange?.(latestSelection);
      });
    };
    animationFrame = window.requestAnimationFrame(initialize);

    return () => {
      cancelled = true;
      window.cancelAnimationFrame(animationFrame);
      window.cancelAnimationFrame(pendingSynchronization);
      commandSubscription?.dispose();
      selectionSubscription?.dispose();
      highlightRef.current.forEach((highlight) => highlight.dispose());
      highlightRef.current = [];
      highlightTimersRef.current.forEach((timer) => window.clearTimeout(timer));
      highlightTimersRef.current = [];
      if (apiRef.current === ownedApi) apiRef.current = undefined;
      if (univerRef.current === ownedUniver) univerRef.current = undefined;
      window.setTimeout(() => {
        ownedUniver?.dispose();
        if (host.parentNode) host.parentNode.removeChild(host);
      }, 32);
    };
  }, [snapshot]);

  useEffect(() => {
    editableRef.current = editable;
    apiRef.current?.getActiveWorkbook()?.setEditable(editable);
    if (editable && apiRef.current) {
      void protectReadOnlyRanges(apiRef.current, bindings).catch(() => undefined);
    }
  }, [bindings, editable]);

  useImperativeHandle(
    ref,
    () => ({
      getSnapshot() {
        return (apiRef.current?.getActiveWorkbook()?.getSnapshot() ?? {}) as Record<
          string,
          unknown
        >;
      },
      readBinding(binding) {
        return apiRef.current ? readCell(apiRef.current, binding) : undefined;
      },
      writeBinding(binding, value) {
        const range = resolveRange(apiRef.current, binding);
        if (range && isInlineTextBinding(binding)) {
          range.setValue(writeInlineText(range.getValue(), binding, value));
          return verifyWrite(
            () => (apiRef.current ? readCell(apiRef.current, binding) : undefined),
            value,
          );
        }
        const values = unknownArray(value);
        if (range && binding.mappingKind === 'REPEAT_FIELD' && values) {
          if (binding.locator.valueMode === 'ARRAY_ROW') {
            range.setValues([values] as never);
          } else {
            range.setValues(values.map((item): unknown[] => [item]) as never);
          }
        } else if (range && values) range.setValues(values as never);
        else range?.setValue(value as never);
        return verifyWrite(
          () => (apiRef.current ? readCell(apiRef.current, binding) : undefined),
          value,
        );
      },
      writeLabel(binding, value) {
        const range = resolveLabelWriteRange(apiRef.current, binding);
        range?.setValue(value as never);
        return verifyWrite(() => range?.getValue(), value);
      },
      focusBinding(binding) {
        const workbook = apiRef.current?.getActiveWorkbook();
        const sheet = resolveSheet(apiRef.current, binding);
        const valueRange =
          resolveLocatorRange(apiRef.current, binding, 'logicalInputRange') ??
          resolveRange(apiRef.current, binding);
        const labelRange = resolveLabelRange(apiRef.current, binding);
        const rowHeaderRange = resolveLocatorRange(apiRef.current, binding, 'rowHeaderRange');
        const columnHeaderRange = resolveLocatorRange(apiRef.current, binding, 'columnHeaderRange');
        const matrixDataRange = resolveLocatorRange(apiRef.current, binding, 'dataRange');
        const activeRange = matrixDataRange ?? valueRange ?? labelRange;
        if (workbook && sheet && activeRange) {
          highlightTimersRef.current.forEach((timer) => window.clearTimeout(timer));
          highlightTimersRef.current = [];
          highlightRef.current.forEach((highlight) => highlight.dispose());
          highlightRef.current = [];
          workbook.setActiveSheet(sheet);
          sheet.setActiveRange(activeRange);
          const drawHighlight = (strong: boolean) => {
            highlightRef.current.forEach((highlight) => highlight.dispose());
            highlightRef.current = [];
            if (labelRange)
              highlightRef.current.push(
                labelRange.highlight({
                  fill: strong ? 'rgba(245, 158, 11, 0.24)' : 'rgba(245, 158, 11, 0.10)',
                  stroke: '#d97706',
                  strokeWidth: strong ? 3 : 2,
                }),
              );
            if (rowHeaderRange)
              highlightRef.current.push(
                rowHeaderRange.highlight({
                  fill: strong ? 'rgba(245, 158, 11, 0.18)' : 'rgba(245, 158, 11, 0.08)',
                  stroke: '#d97706',
                  strokeWidth: strong ? 3 : 2,
                }),
              );
            if (columnHeaderRange)
              highlightRef.current.push(
                columnHeaderRange.highlight({
                  fill: strong ? 'rgba(124, 58, 237, 0.16)' : 'rgba(124, 58, 237, 0.07)',
                  stroke: '#7c3aed',
                  strokeWidth: strong ? 3 : 2,
                }),
              );
            const fillRange = matrixDataRange ?? valueRange;
            if (fillRange)
              highlightRef.current.push(
                fillRange.highlight({
                  fill: strong ? 'rgba(37, 99, 235, 0.22)' : 'rgba(37, 99, 235, 0.09)',
                  stroke: '#2563eb',
                  strokeWidth: strong ? 3 : 2,
                }),
              );
          };
          drawHighlight(true);
          if (!window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
            [false, true, false, true].forEach((strong, index) => {
              highlightTimersRef.current.push(
                window.setTimeout(() => drawHighlight(strong), 150 * (index + 1)),
              );
            });
          }
        }
      },
      async appendRepeatRecord(binding) {
        if (binding.mappingKind !== 'REPEAT_REGION' && binding.mappingKind !== 'REPEAT_FIELD') {
          return;
        }
        const workbook = apiRef.current?.getActiveWorkbook();
        const sheet = resolveSheet(apiRef.current, binding);
        const api = apiRef.current;
        const dataRange =
          stringLocator(binding.locator.dataRange) ||
          stringLocator(binding.locator.address) ||
          stringLocator(binding.locator.range);
        const parsed = parseA1Range(dataRange);
        if (!api || !workbook || !sheet || !parsed) return;
        const axis = binding.repeatAxis || 'ROW';
        const height = Math.max(1, binding.recordHeight || 1);
        const width = Math.max(1, binding.recordWidth || 1);
        const recordSize = axis === 'ROW' ? height : width;
        const stride = Math.max(recordSize, binding.recordStride || 1);
        const unitId = String(
          (workbook as unknown as { getId?: () => string; getUnitId?: () => string }).getId?.() ||
            (workbook as unknown as { getUnitId?: () => string }).getUnitId?.() ||
            '',
        );
        const subUnitId = String(
          (sheet as unknown as { getSheetId?: () => string }).getSheetId?.() || '',
        );
        const commandRange =
          axis === 'ROW'
            ? {
                startRow: parsed.endRow - 1,
                endRow: parsed.endRow - 1 + stride - 1,
                startColumn: parsed.startColumn - 1,
                endColumn: parsed.endColumn - 1,
              }
            : {
                startRow: parsed.startRow - 1,
                endRow: parsed.endRow - 1,
                startColumn: parsed.endColumn - 1,
                endColumn: parsed.endColumn - 1 + stride - 1,
              };
        await api.executeCommand(
          axis === 'ROW' ? 'sheet.command.insert-row' : 'sheet.command.insert-col',
          { unitId, subUnitId, range: commandRange, direction: axis === 'ROW' ? 2 : 1 },
        );
        const sourceAddress =
          axis === 'ROW'
            ? formatA1Range({
                ...parsed,
                startRow: parsed.endRow - recordSize + 1,
                endRow: parsed.endRow,
              })
            : formatA1Range({
                ...parsed,
                startColumn: parsed.endColumn - recordSize + 1,
                endColumn: parsed.endColumn,
              });
        const targetAddress =
          axis === 'ROW'
            ? formatA1Range({
                ...parsed,
                startRow: parsed.endRow + 1,
                endRow: parsed.endRow + recordSize,
              })
            : formatA1Range({
                ...parsed,
                startColumn: parsed.endColumn + 1,
                endColumn: parsed.endColumn + recordSize,
              });
        const source = sheet.getRange(sourceAddress) as unknown as RangeAdapter;
        const target = sheet.getRange(targetAddress) as unknown as RangeAdapter;
        if (typeof target.copyFrom === 'function') target.copyFrom(source, 'all');
        else if (typeof target.setValues === 'function' && typeof source.getValues === 'function') {
          target.setValues(source.getValues());
        }
        // The copied record is intentionally blanked after format/formula copy;
        // formulas remain available and user input cells become a new record.
        if (typeof target.setValues === 'function' && typeof source.getValues === 'function') {
          const values = target.getValues?.();
          if (values)
            target.setValues(
              values.map((row) =>
                row.map((value) =>
                  typeof value === 'string' && value.startsWith('=') ? value : '',
                ),
              ),
            );
        }
      },
      async applyCellPatch(patch) {
        const operations = Array.isArray(patch.operations) ? patch.operations : [];
        suppressUnboundRef.current = true;
        try {
          for (const operation of operations) {
            if (!isRecord(operation) || operation.op !== 'SET_CELL') {
              throw new Error('当前修正包含不支持的工作簿操作');
            }
            const workbook = apiRef.current?.getActiveWorkbook();
            const sheetId = stringLocator(operation.sheetId);
            const address = stringLocator(operation.address);
            const sheet = workbook?.getSheetBySheetId(sheetId);
            if (!sheet || !validCell(address)) throw new Error(`无法定位修正位置 ${address}`);
            const range = sheet.getRange(address);
            const actual = range.getValue();
            const expected = operation.expectedValue ?? '';
            if (!sameValue(actual ?? '', expected)) {
              throw new Error(`单元格 ${address} 已发生变化，请重新识别后再处理`);
            }
            range.setValue(operation.value ?? '');
          }
          await new Promise<void>((resolve) => window.requestAnimationFrame(() => resolve()));
        } finally {
          suppressUnboundRef.current = false;
        }
      },
    }),
    [],
  );

  return <div ref={containerRef} className="univer-editor-surface" aria-label="Excel 模板编辑器" />;
});

function resolveSheet(api: FUniver | undefined, binding: TemplateBinding) {
  const workbook = api?.getActiveWorkbook();
  if (!workbook) return null;
  const sheetId = stringLocator(binding.locator.sheetId);
  const sheetName =
    stringLocator(binding.locator.sheetCode) || stringLocator(binding.locator.sheetName);
  if (sheetId) return workbook.getSheetBySheetId(sheetId);
  if (sheetName) return workbook.getSheetByName(sheetName);
  return workbook.getActiveSheet();
}

function resolveRange(api: FUniver | undefined, binding: TemplateBinding) {
  const address =
    binding.role === 'FIELD'
      ? binding.mappingKind === 'REPEAT_FIELD'
        ? stringLocator(binding.locator.anchorRange) ||
          stringLocator(binding.locator.logicalInputRange) ||
          stringLocator(binding.locator.anchorAddress)
        : stringLocator(binding.locator.anchorAddress) ||
          stringLocator(binding.locator.address) ||
          stringLocator(binding.locator.range)
      : stringLocator(binding.locator.dataRange) ||
        stringLocator(binding.locator.address) ||
        stringLocator(binding.locator.range);
  const sheet = resolveSheet(api, binding);
  return sheet && validRange(address) ? sheet.getRange(address) : null;
}

function resolveLabelRange(api: FUniver | undefined, binding: TemplateBinding) {
  const address =
    stringLocator(binding.locator.labelRange) || stringLocator(binding.locator.labelAddress);
  const sheet = resolveSheet(api, binding);
  return sheet && validRange(address) ? sheet.getRange(address) : null;
}

function resolveLabelWriteRange(api: FUniver | undefined, binding: TemplateBinding) {
  const address = stringLocator(binding.locator.labelAddress);
  const sheet = resolveSheet(api, binding);
  return sheet && validCell(address) ? sheet.getRange(address) : null;
}

function resolveLocatorRange(api: FUniver | undefined, binding: TemplateBinding, property: string) {
  const address = stringLocator(binding.locator[property]);
  const sheet = resolveSheet(api, binding);
  return sheet && validRange(address) ? sheet.getRange(address) : null;
}

function validCell(value: string) {
  return /^[A-Z]{1,4}[1-9][0-9]*$/i.test(value);
}

function validRange(value: string) {
  return /^[A-Z]{1,4}[1-9][0-9]*(?::[A-Z]{1,4}[1-9][0-9]*)?$/i.test(value);
}

function stringLocator(value: unknown) {
  return typeof value === 'string' ? value : '';
}

function unknownArray(value: unknown): unknown[] | undefined {
  return Array.isArray(value) ? (value as unknown[]) : undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function readCell(api: FUniver, binding: TemplateBinding) {
  const range = resolveRange(api, binding);
  if (!range) return undefined;
  if (isInlineTextBinding(binding)) return readInlineText(range.getValue(), binding);
  if (binding.locator.valueMode === 'ARRAY_ROW') {
    return range.getValues()[0] ?? [];
  }
  if (binding.mappingKind === 'REPEAT_FIELD' || binding.locator.valueMode === 'ARRAY_COLUMN') {
    return range.getValues().map((row: unknown[]) => row[0]);
  }
  return binding.role === 'REPEAT_REGION' ? range.getValues() : range.getValue();
}

function isInlineTextBinding(binding: TemplateBinding) {
  return binding.locatorType === 'INLINE_TEXT'
    || stringLocator(binding.locator.locatorType) === 'INLINE_TEXT'
    || stringLocator(binding.locator.valueMode) === 'INLINE_TEXT';
}

function readInlineText(rawValue: unknown, binding: TemplateBinding) {
  const raw = cellText(rawValue);
  const delimiter = raw.search(/[：:]/);
  if (delimiter >= 0) return raw.slice(delimiter + 1).trim();
  const prefix = stringLocator(binding.locator.labelPrefix).trim();
  if (prefix && raw.startsWith(prefix)) return raw.slice(prefix.length).trim();
  return raw;
}

function writeInlineText(rawValue: unknown, binding: TemplateBinding, value: unknown) {
  const raw = cellText(rawValue);
  const replacement = cellText(value);
  const delimiter = raw.search(/[：:]/);
  if (delimiter >= 0) return `${raw.slice(0, delimiter + 1)}${replacement}`;
  const prefix = stringLocator(binding.locator.labelPrefix).trim();
  return prefix ? `${prefix}：${replacement}` : replacement;
}

function cellText(value: unknown) {
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'bigint') {
    return String(value);
  }
  if (value == null) return '';
  try {
    const serialized = JSON.stringify(value);
    return typeof serialized === 'string' ? serialized : '';
  } catch {
    return '';
  }
}

interface RangeAdapter {
  copyFrom?: (source: RangeAdapter, type?: string) => unknown;
  getValues?: () => unknown[][];
  setValues?: (values: unknown[][]) => unknown;
  getValue?: () => unknown;
  setValue?: (value: unknown) => unknown;
}

interface A1Range {
  startColumn: number;
  startRow: number;
  endColumn: number;
  endRow: number;
}

function parseA1Range(value: string): A1Range | undefined {
  const match = /^([A-Z]{1,4})([1-9][0-9]*)(?::([A-Z]{1,4})([1-9][0-9]*))?$/i.exec(value);
  if (!match) return undefined;
  return {
    startColumn: columnNumber(match[1] || ''),
    startRow: Number(match[2]),
    endColumn: columnNumber(match[3] || match[1] || ''),
    endRow: Number(match[4] || match[2]),
  };
}

function formatA1Range(range: A1Range) {
  const start = `${columnLetters(range.startColumn)}${range.startRow}`;
  const end = `${columnLetters(range.endColumn)}${range.endRow}`;
  return start === end ? start : `${start}:${end}`;
}

function columnNumber(value: string) {
  return [...value.toUpperCase()].reduce(
    (result, letter) => result * 26 + letter.charCodeAt(0) - 64,
    0,
  );
}

function columnLetters(value: number) {
  let current = value;
  let result = '';
  while (current > 0) {
    current -= 1;
    result = String.fromCharCode(65 + (current % 26)) + result;
    current = Math.floor(current / 26);
  }
  return result;
}

function readLabelCell(api: FUniver, binding: TemplateBinding) {
  return resolveLabelRange(api, binding)?.getValue();
}

function labelCacheKey(binding: TemplateBinding) {
  return [
    binding.bindingId,
    stringLocator(binding.locator.sheetId) || stringLocator(binding.locator.sheetName),
    stringLocator(binding.locator.labelAddress).toUpperCase(),
  ].join('|');
}

function valueCacheKey(binding: TemplateBinding) {
  return [
    binding.bindingId,
    stringLocator(binding.locator.sheetId) || stringLocator(binding.locator.sheetName),
    (stringLocator(binding.locator.address) || stringLocator(binding.locator.range)).toUpperCase(),
  ].join('|');
}

function sameValue(left: unknown, right: unknown) {
  return Object.is(left, right) || JSON.stringify(left) === JSON.stringify(right);
}

function isCellMutationCommand(commandId: string) {
  const id = commandId.toLowerCase();
  return [
    'value',
    'formula',
    'paste',
    'cut',
    'clear',
    'delete',
    'fill',
    'autofill',
    'cell-data',
  ].some((token) => id.includes(token));
}

function verifyWrite(read: () => unknown, expected: unknown) {
  return new Promise<void>((resolve, reject) => {
    window.requestAnimationFrame(() => {
      if (sameValue(read(), expected)) resolve();
      else reject(new Error('写入后回读的单元格内容不一致'));
    });
  });
}

async function protectReadOnlyRanges(api: FUniver, bindings: TemplateBinding[]) {
  const targets = new Map<string, { binding: TemplateBinding; address: string }>();
  for (const binding of bindings) {
    if (binding.syncDirection === 'EDITOR_TO_DATA' && binding.role === 'FIELD') {
      const address =
        stringLocator(binding.locator.anchorAddress) ||
        stringLocator(binding.locator.address) ||
        stringLocator(binding.locator.range);
      if (validRange(address)) targets.set(protectionKey(binding, address), { binding, address });
    }
    const tableModel = binding.diagnostic?.tableModel;
    if (!isRecord(tableModel) || !Array.isArray(tableModel.columns)) continue;
    for (const column of tableModel.columns) {
      if (!isRecord(column) || !readOnlyColumn(column)) continue;
      const address = stringLocator(column.valueRange);
      if (validRange(address)) targets.set(protectionKey(binding, address), { binding, address });
    }
  }
  for (const { binding, address } of targets.values()) {
    const sheet = resolveSheet(api, binding);
    if (!sheet) continue;
    const range = sheet.getRange(address);
    const permission = range.getRangePermission();
    if (permission.isProtected()) continue;
    const rule = await permission.protect({
      name: '系统识别的只读区域',
      metadata: { source: 'FIELD_MODEL_V4', bindingId: binding.bindingId },
    });
    await rule.setPoint(api.Enum.RangePermissionPoint.Edit, false);
  }
}

function readOnlyColumn(column: Record<string, unknown>) {
  const editability = stringLocator(column.editability);
  const valueSource = stringLocator(column.valueSource);
  return editability === 'READ_ONLY' || valueSource === 'FORMULA' || valueSource === 'STATIC';
}

function protectionKey(binding: TemplateBinding, address: string) {
  return [
    stringLocator(binding.locator.sheetId) || stringLocator(binding.locator.sheetName),
    address.toUpperCase(),
  ].join('|');
}
