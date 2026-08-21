import { createUniver, LocaleType, mergeLocales, type FUniver } from '@univerjs/presets';
import { UniverSheetsCorePreset } from '@univerjs/preset-sheets-core';
import UniverPresetSheetsCoreZhCN from '@univerjs/preset-sheets-core/locales/zh-CN';
import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';

import '@univerjs/preset-sheets-core/lib/index.css';

import { isCellMutationCommand, isNewFieldLabelChange, isSingleCellAddress } from './live-field-discovery';
import { locatorValueRange } from './locator';
import { operationFromUniverCommand } from './structure-migration';
import type {
  EditorHandle,
  EditorCellChange,
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
  onCellChange?: (change: EditorCellChange) => void;
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
    onCellChange,
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
    onCellChange,
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
    onCellChange,
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
    let globalCommandSubscription: { dispose(): void } | undefined;
    let selectionSubscription: { dispose(): void } | undefined;
    let ownedUniver: ReturnType<typeof createUniver>['univer'] | undefined;
    let ownedApi: FUniver | undefined;
    const labelValues = new Map<string, unknown>();
    const bindingValues = new Map<string, unknown>();
    let pendingSynchronization = 0;
    let latestSelection: EditorSelection | undefined;
    let latestSelectionValue: unknown;
    let hasLatestSelectionValue = false;
    let recentCommandKey = '';
    let recentCommandAt = 0;
    let recentUnboundKey = '';
    let recentUnboundAt = 0;
    let cellChangeEventsArmed = false;
    let cellChangeArmTimer = 0;

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
      const initialSheet = workbook.getActiveSheet();
      const initialRange = initialSheet?.getActiveRange() ?? workbook.getActiveRange();
      if (initialSheet && initialRange) {
        latestSelection = {
          sheetId: initialSheet.getSheetId(),
          sheetName: initialSheet.getSheetName(),
          address: initialRange.getA1Notation(),
        };
        try {
          latestSelectionValue = initialRange.getValue();
          hasLatestSelectionValue = true;
        } catch {
          latestSelectionValue = undefined;
        }
      }
      if (editableRef.current) {
        void protectReadOnlyRanges(univerAPI, bindingsRef.current).catch(() => undefined);
      }
      window.requestAnimationFrame(() => {
        window.requestAnimationFrame(() => {
          if (!cancelled) {
            // createWorkbook emits value-mutation commands while restoring the snapshot. They are not user
            // edits and must never trigger field discovery or data correction callbacks.
            callbacksRef.current.onReady?.();
            cellChangeArmTimer = window.setTimeout(() => {
              if (!cancelled) cellChangeEventsArmed = true;
            }, 650);
          }
        });
      });
      for (const binding of bindingsRef.current) {
        labelValues.set(labelCacheKey(binding), readLabelCell(univerAPI, binding));
        bindingValues.set(valueCacheKey(binding), readCell(univerAPI, binding));
      }
      const emitUnboundCellChange = (selection: EditorSelection, value: unknown, previousValue: unknown) => {
        if (!cellChangeEventsArmed) return;
        if (!isSingleCellAddress(selection.address)) return;
        // Live discovery is only for a brand-new label typed into an empty
        // cell. Clicking a cell or editing an existing template label must
        // never create another field.
        if (!isNewFieldLabelChange(previousValue, value)) return;
        const key = `${selection.sheetId}|${selection.address}|${stableValueKey(value)}`;
        const now = Date.now();
        if (key === recentUnboundKey && now - recentUnboundAt < 120) return;
        recentUnboundKey = key;
        recentUnboundAt = now;
        callbacksRef.current.onUnboundCellChange?.(selection, value);
      };
      const emitCellChange = (selection: EditorSelection, value: unknown, previousValue: unknown) => {
        if (!cellChangeEventsArmed) return;
        if (!isSingleCellAddress(selection.address) || sameValue(value, previousValue)) return;
        callbacksRef.current.onCellChange?.({ ...selection, value, previousValue });
      };
      const synchronizeAfterCommand = (command: { id: string; params?: object }) => {
        const paramsKey = (() => {
          try {
            return JSON.stringify(command.params ?? {});
          } catch {
            return '';
          }
        })();
        const commandKey = `${command.id}|${paramsKey}`;
        const now = Date.now();
        // The workbook and global command buses can report the same edit. Do
        // not schedule two discovery timers for that single keystroke.
        if (commandKey === recentCommandKey && now - recentCommandAt < 32) return;
        recentCommandKey = commandKey;
        recentCommandAt = now;
        const structureOperation = operationFromUniverCommand(command);
        const cellMutation = isCellMutationCommand(command.id, command.params);
        if (!structureOperation && !cellMutation) return;
        callbacksRef.current.onDirty();
        if (structureOperation) callbacksRef.current.onStructureChange?.(structureOperation);
        // Capture the cell before Univer moves the selection after Enter or a
        // click. This is the cell that was actually edited, not the next blank
        // cell that happened to become active.
        const editedSelection = cellMutation && latestSelection
          && isSingleCellAddress(latestSelection.address) ? latestSelection : undefined;
        window.cancelAnimationFrame(pendingSynchronization);
        pendingSynchronization = window.requestAnimationFrame(() => {
          const structuralParentIds = new Set(
            bindingsRef.current
              .map((item) => item.parentBindingId)
              .filter((value): value is string => Boolean(value)),
          );
          for (const binding of bindingsRef.current) {
            if (binding.syncDirection === 'DATA_TO_EDITOR') continue;
            // A repeating parent is a layout surface. Its raw 2D values must
            // not overwrite the business records assembled from child fields.
            if (structuralParentIds.has(binding.bindingId)) continue;
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
          if (!suppressUnboundRef.current) {
            const activeWorkbook = univerAPI.getActiveWorkbook() ?? workbook;
            const currentSheet = activeWorkbook.getActiveSheet();
            const currentRange = currentSheet?.getActiveRange() ?? activeWorkbook.getActiveRange();
            const editedSheet = editedSelection
              ? activeWorkbook.getSheetBySheetId(editedSelection.sheetId) ?? currentSheet
              : currentSheet;
            const editedRange = editedSelection
              ? editedSheet?.getRange(editedSelection.address)
              : undefined;
            const activeSheet = editedSheet ?? currentSheet;
            const activeRange = editedRange ?? currentRange;
            const activeAddress = editedSelection?.address
              ?? activeRange?.getA1Notation() ?? latestSelection?.address;
            const activeSheetId = editedSelection?.sheetId
              ?? activeSheet?.getSheetId() ?? latestSelection?.sheetId;
            const activeSheetName = editedSelection?.sheetName
              ?? activeSheet?.getSheetName() ?? latestSelection?.sheetName;
            if (
              activeSheet
              && activeAddress
              && activeSheetId
              && activeSheetName
              && isSingleCellAddress(activeAddress)
            ) {
              const selection: EditorSelection = {
                sheetId: activeSheetId,
                sheetName: activeSheetName,
                address: activeAddress,
              };
              const value = activeRange?.getValue() ?? activeSheet.getRange(activeAddress).getValue();
              const previousValue = editedSelection
                && latestSelection?.sheetId === editedSelection.sheetId
                && latestSelection.address === editedSelection.address
                && hasLatestSelectionValue
                ? latestSelectionValue : undefined;
              if (!editedSelection) latestSelection = selection;
              if (editedSelection
                && latestSelection?.sheetId === editedSelection.sheetId
                && latestSelection.address === editedSelection.address) {
                latestSelectionValue = value;
                hasLatestSelectionValue = true;
              }
              emitUnboundCellChange(selection, value, previousValue);
              if (editedSelection) emitCellChange(selection, value, previousValue);
            }
          }
        });
      };
      // Subscribe on both buses. Direct typing is emitted by the workbook
      // command service in some Univer versions and only by the global facade
      // in others.
      commandSubscription = workbook.onCommandExecuted(synchronizeAfterCommand);
      globalCommandSubscription = univerAPI.addEvent(
        univerAPI.Event.CommandExecuted,
        synchronizeAfterCommand,
      );
      selectionSubscription = workbook.onSelectionChange((selections) => {
        const selection = selections.at(-1);
        const sheet = workbook.getActiveSheet();
        if (!selection || !sheet) return;
        // Some Univer input paths do not publish a recognizable cell-value
        // command. When the user leaves the edited cell, compare its current
        // value with the value captured on entry and emit the same callback as
        // the command path. This is the reliable path for typing a label and
        // then clicking another cell.
        if (latestSelection && isSingleCellAddress(latestSelection.address)) {
          const previousSheet = workbook.getSheetBySheetId(latestSelection.sheetId);
          if (previousSheet) {
            try {
              const previousValue = previousSheet.getRange(latestSelection.address).getValue();
              // A selection can arrive before Univer exposes the value of the
              // previous range. Without a baseline there is no evidence of an
              // edit; treating it as one makes a plain cell click call the
              // import correction API with an incomplete/stale field identity.
              if (hasLatestSelectionValue && !sameValue(previousValue, latestSelectionValue)) {
                emitUnboundCellChange(latestSelection, previousValue, latestSelectionValue);
                emitCellChange(latestSelection, previousValue, latestSelectionValue);
              }
            } catch {
              // Merged or transient ranges can be unavailable during a
              // selection transition; the command path will handle them.
            }
          }
        }
        const range = sheet.getRange(selection);
        latestSelection = {
          sheetId: sheet.getSheetId(),
          sheetName: sheet.getSheetName(),
          address: range.getA1Notation(),
        };
        try {
          latestSelectionValue = range.getValue();
          hasLatestSelectionValue = true;
        } catch {
          latestSelectionValue = undefined;
          hasLatestSelectionValue = false;
        }
        callbacksRef.current.onSelectionChange?.(latestSelection);
      });
    };
    animationFrame = window.requestAnimationFrame(initialize);

    return () => {
      cancelled = true;
      cellChangeEventsArmed = false;
      window.cancelAnimationFrame(animationFrame);
      window.cancelAnimationFrame(pendingSynchronization);
      window.clearTimeout(cellChangeArmTimer);
      commandSubscription?.dispose();
      globalCommandSubscription?.dispose();
      selectionSubscription?.dispose();
      highlightRef.current.forEach((highlight) => highlight.dispose());
      highlightRef.current = [];
      highlightTimersRef.current.forEach((timer) => window.clearTimeout(timer));
      highlightTimersRef.current = [];
      if (apiRef.current === ownedApi) apiRef.current = undefined;
      if (univerRef.current === ownedUniver) univerRef.current = undefined;
      // Univer publishes internal React updates while disposing. Deferring
      // disposal out of React's cleanup stack prevents the renderer warning,
      // while the host is detached immediately so two editors are never
      // visible or interactive at the same time.
      if (host.parentNode) host.parentNode.removeChild(host);
      window.setTimeout(() => ownedUniver?.dispose(), 0);
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
        return (apiRef.current?.getActiveWorkbook()?.save() ?? {}) as Record<
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
        if (range && isStructuredLeaf(binding) && values) {
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
        // A table-column field owns its value range. Do not let the parent
        // repeat-region data range swallow the selected child field.
        const activeRange = isStructuredLeaf(binding)
          ? valueRange ?? matrixDataRange ?? labelRange
          : matrixDataRange ?? valueRange ?? labelRange;
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
            const fillRange = isStructuredLeaf(binding)
              ? valueRange ?? matrixDataRange
              : matrixDataRange ?? valueRange;
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
      focusCell(sheetId, address) {
        focusWorkbookRange(apiRef.current, sheetId, address);
      },
      focusRange(sheetId, address) {
        focusWorkbookRange(apiRef.current, sheetId, address);
      },
      writeCell(sheetId, address, value) {
        const workbook = apiRef.current?.getActiveWorkbook();
        const sheet = workbook?.getSheetBySheetId(sheetId) ?? workbook?.getSheetByName(sheetId);
        const range = sheet && validCell(address) ? sheet.getRange(address) : null;
        if (!range) return Promise.reject(new Error(`无法定位单元格 ${address}`));
        suppressUnboundRef.current = true;
        const safeValue = value == null ? '' : value;
        range.setValue(safeValue);
        return verifyWrite(() => range.getValue(), safeValue).finally(() => {
          suppressUnboundRef.current = false;
        });
      },
      async appendRepeatRecord(binding) {
        if (!['REPEAT_REGION', 'REPEAT_FIELD', 'MATRIX_REGION', 'MATRIX_FIELD']
          .includes(binding.mappingKind || '')) {
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

function focusWorkbookRange(api: FUniver | undefined, sheetId: string, address: string) {
  const workbook = api?.getActiveWorkbook();
  const sheet = workbook?.getSheetBySheetId(sheetId) ?? workbook?.getSheetByName(sheetId);
  if (!workbook || !sheet || !validRange(address)) return;
  const range = sheet.getRange(address);
  workbook.setActiveSheet(sheet);
  sheet.setActiveRange(range);
}

function resolveRange(api: FUniver | undefined, binding: TemplateBinding) {
  const address =
    binding.role === 'FIELD'
      ? isStructuredLeaf(binding)
        ? stringLocator(binding.locator.anchorRange) ||
          stringLocator(binding.locator.logicalInputRange) ||
          stringLocator(binding.locator.anchorAddress) ||
          locatorValueRange(binding.locator)
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
  if (isStructuredLeaf(binding) || binding.locator.valueMode === 'ARRAY_COLUMN') {
    return range.getValues().map((row: unknown[]) => row[0]);
  }
  return binding.role === 'REPEAT_REGION' ? range.getValues() : range.getValue();
}

function isStructuredLeaf(binding: TemplateBinding) {
  return binding.mappingKind === 'REPEAT_FIELD' || binding.mappingKind === 'MATRIX_FIELD';
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
  const range = resolveLabelRange(api, binding);
  if (!range) return undefined;
  try {
    const direct = range.getValue?.();
    if (direct !== undefined && direct !== null) return direct;
    const values = range.getValues?.();
    return values?.[0]?.[0];
  } catch {
    // A merged range can reject getValue() in some Univer builds; the first
    // cell of getValues() is still the stable label value.
    try {
      return range.getValues?.()?.[0]?.[0];
    } catch {
      return undefined;
    }
  }
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

function stableValueKey(value: unknown) {
  if (value == null) return '';
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  try {
    return JSON.stringify(value) ?? '';
  } catch {
    return '';
  }
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
