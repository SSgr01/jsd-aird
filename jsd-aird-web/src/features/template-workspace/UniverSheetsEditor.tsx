import {
  createUniver,
  LocaleType,
  mergeLocales,
  type FUniver,
} from '@univerjs/presets';
import { UniverSheetsCorePreset } from '@univerjs/preset-sheets-core';
import UniverPresetSheetsCoreZhCN from '@univerjs/preset-sheets-core/locales/zh-CN';
import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';

import '@univerjs/preset-sheets-core/lib/index.css';

import type { EditorHandle, EditorSelection, TemplateBinding } from './types';

interface Props {
  snapshot: Record<string, unknown>;
  onDirty: () => void;
  onEditorValue: (binding: TemplateBinding, value: unknown) => void;
  onEditorLabel?: (binding: TemplateBinding, value: unknown) => void;
  bindings: TemplateBinding[];
  editable?: boolean;
  onSelectionChange?: (selection: EditorSelection) => void;
  onUnboundCellChange?: (selection: EditorSelection, value: unknown) => void;
}

export const UniverSheetsEditor = forwardRef<EditorHandle, Props>(function UniverSheetsEditor(
  {
    snapshot, onDirty, onEditorValue, onEditorLabel, bindings, editable = true,
    onSelectionChange, onUnboundCellChange,
  },
  ref,
) {
  const containerRef = useRef<HTMLDivElement>(null);
  const univerRef = useRef<ReturnType<typeof createUniver>['univer']>();
  const apiRef = useRef<FUniver>();
  const bindingsRef = useRef(bindings);
  const callbacksRef = useRef({
    onDirty, onEditorValue, onEditorLabel, onSelectionChange, onUnboundCellChange,
  });
  const suppressUnboundRef = useRef(false);
  const editableRef = useRef(editable);
  const highlightRef = useRef<Array<{ dispose(): void }>>([]);
  const highlightTimersRef = useRef<number[]>([]);
  bindingsRef.current = bindings;
  callbacksRef.current = {
    onDirty, onEditorValue, onEditorLabel, onSelectionChange, onUnboundCellChange,
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
      for (const binding of bindingsRef.current) {
        labelValues.set(labelCacheKey(binding), readLabelCell(univerAPI, binding));
        bindingValues.set(valueCacheKey(binding), readCell(univerAPI, binding));
      }
      commandSubscription = univerAPI.onCommandExecuted((command) => {
        if (!isCellMutationCommand(command.id)) return;
        callbacksRef.current.onDirty();
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
              const value = latestSelection.address.includes(':') ? range.getValues() : range.getValue();
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
        return (apiRef.current?.getActiveWorkbook()?.getSnapshot() ?? {}) as Record<string, unknown>;
      },
      readBinding(binding) {
        return apiRef.current ? readCell(apiRef.current, binding) : undefined;
      },
      writeBinding(binding, value) {
        const range = resolveRange(apiRef.current, binding);
        if (range && Array.isArray(value)) range.setValues(value as never);
        else range?.setValue(value as never);
        return verifyWrite(() => apiRef.current ? readCell(apiRef.current, binding) : undefined, value);
      },
      writeLabel(binding, value) {
        const range = resolveLabelWriteRange(apiRef.current, binding);
        range?.setValue(value as never);
        return verifyWrite(() => range?.getValue(), value);
      },
      focusBinding(binding) {
        const workbook = apiRef.current?.getActiveWorkbook();
        const sheet = resolveSheet(apiRef.current, binding);
        const valueRange = resolveLocatorRange(apiRef.current, binding, 'logicalInputRange')
          ?? resolveRange(apiRef.current, binding);
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
            if (labelRange) highlightRef.current.push(labelRange.highlight({
              fill: strong ? 'rgba(245, 158, 11, 0.24)' : 'rgba(245, 158, 11, 0.10)',
              stroke: '#d97706',
              strokeWidth: strong ? 3 : 2,
            }));
            if (rowHeaderRange) highlightRef.current.push(rowHeaderRange.highlight({
              fill: strong ? 'rgba(245, 158, 11, 0.18)' : 'rgba(245, 158, 11, 0.08)',
              stroke: '#d97706',
              strokeWidth: strong ? 3 : 2,
            }));
            if (columnHeaderRange) highlightRef.current.push(columnHeaderRange.highlight({
              fill: strong ? 'rgba(124, 58, 237, 0.16)' : 'rgba(124, 58, 237, 0.07)',
              stroke: '#7c3aed',
              strokeWidth: strong ? 3 : 2,
            }));
            const fillRange = matrixDataRange ?? valueRange;
            if (fillRange) highlightRef.current.push(fillRange.highlight({
              fill: strong ? 'rgba(37, 99, 235, 0.22)' : 'rgba(37, 99, 235, 0.09)',
              stroke: '#2563eb',
              strokeWidth: strong ? 3 : 2,
            }));
          };
          drawHighlight(true);
          if (!window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
            [false, true, false, true].forEach((strong, index) => {
              highlightTimersRef.current.push(window.setTimeout(
                () => drawHighlight(strong),
                150 * (index + 1),
              ));
            });
          }
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
  const address = binding.role === 'FIELD'
    ? stringLocator(binding.locator.anchorAddress)
      || stringLocator(binding.locator.address)
      || stringLocator(binding.locator.range)
    : stringLocator(binding.locator.dataRange)
      || stringLocator(binding.locator.address)
      || stringLocator(binding.locator.range);
  const sheet = resolveSheet(api, binding);
  return sheet && validRange(address) ? sheet.getRange(address) : null;
}

function resolveLabelRange(api: FUniver | undefined, binding: TemplateBinding) {
  const address = stringLocator(binding.locator.labelRange)
    || stringLocator(binding.locator.labelAddress);
  const sheet = resolveSheet(api, binding);
  return sheet && validRange(address) ? sheet.getRange(address) : null;
}

function resolveLabelWriteRange(api: FUniver | undefined, binding: TemplateBinding) {
  const address = stringLocator(binding.locator.labelAddress);
  const sheet = resolveSheet(api, binding);
  return sheet && validCell(address) ? sheet.getRange(address) : null;
}

function resolveLocatorRange(
  api: FUniver | undefined,
  binding: TemplateBinding,
  property: string,
) {
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

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function readCell(api: FUniver, binding: TemplateBinding) {
  const range = resolveRange(api, binding);
  if (!range) return undefined;
  return binding.role === 'REPEAT_REGION' ? range.getValues() : range.getValue();
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
  return ['value', 'formula', 'paste', 'cut', 'clear', 'delete', 'fill', 'autofill', 'cell-data']
    .some((token) => id.includes(token));
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
      const address = stringLocator(binding.locator.anchorAddress)
        || stringLocator(binding.locator.address)
        || stringLocator(binding.locator.range);
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
  return editability === 'READ_ONLY'
    || valueSource === 'FORMULA'
    || valueSource === 'STATIC';
}

function protectionKey(binding: TemplateBinding, address: string) {
  return [
    stringLocator(binding.locator.sheetId) || stringLocator(binding.locator.sheetName),
    address.toUpperCase(),
  ].join('|');
}
