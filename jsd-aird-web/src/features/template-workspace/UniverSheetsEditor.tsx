import { markPerformance } from '@/services/performance/metrics';
import { editorReady } from './workspace-loading';
import {
  createUniver,
  ICommandService,
  IUndoRedoService,
  CommandType,
  CanceledError,
  Direction,
  LocaleType,
  mergeLocales,
  type FUniver,
} from '@univerjs/presets';
import { UniverSheetsCorePreset, IRenderManagerService, ISheetClipboardService, SHEET_VIEWPORT_KEY, ScrollCommand, ScrollToRangeOperation, unmount } from '@univerjs/preset-sheets-core';
import UniverPresetSheetsCoreZhCN from '@univerjs/preset-sheets-core/locales/zh-CN';
import { UniverSheetsDrawingPreset } from '@univerjs/preset-sheets-drawing';
import UniverPresetSheetsDrawingZhCN from '@univerjs/preset-sheets-drawing/locales/zh-CN';
import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from 'react';
import { App } from 'antd';

import '@univerjs/preset-sheets-core/lib/index.css';
import '@univerjs/preset-sheets-drawing/lib/index.css';

import {
  isCellMutationCommand,
  isNewFieldLabelChange,
  isSingleCellAddress,
  mutationCells,
} from './live-field-discovery';
import { locatorLabelRange, locatorValueRange } from './locator';
import { focusScrollDelta, sourceFocusBinding } from './source-focus';
import { operationFromUniverCommand } from './structure-migration';
import { sheetsFeatureLocale, sheetsFeaturePresets } from './sheets-features';
import { isWorkbookContentCommand } from './office-content';
import { EditorChanges, isPersistentMutation } from './editor-changes';
import { copyWorkbookCells, readWorkbookInstance, discoverWorkbookField, renameWorkbookFieldAt, recordUnboundCell, migrateWorkbookInstance, structureCopies, retargetAggregateFormula, workbookAddress, type WorkbookTemplateInstance, type WorkbookCell } from './workbook-instance';
import { generateUUID } from '@/utils/uuid';
import { templateLocalFieldCode } from './field-model';
import { WorkbookUndoBatch } from './undo-batch';
import { workbookOperationBlock, isGuardedWorkbookOperation, copyableSheetMetadata } from './workbook-operation-guard';
import { writesReadOnlyValue } from './read-only-values';
import { sectionMergeExtensions, migratedWorkbookMerges } from './section-merges';
import { templatePasteStyleHook } from './paste-styles';
import { fieldNumberFormatEdits, fieldNumberFormat } from './field-number-format';
import { workbookCellInput } from '@/features/data-workbench/cell-format';
import { instanceSheets, workbookWithExplicitRowHeights } from './workbook-instance';
import type {
  EditorHandle,
  EditorCellChange,
  EditorSelection,
  TemplateBinding,
  WorkbookStructureOperation,
} from './types';

interface Props {
  snapshot: Record<string, unknown>;
  ariaLabel?: string;
  onDirty: () => void;
  onEditorValue: (binding: TemplateBinding, value: unknown) => void;
  onEditorLabel?: (binding: TemplateBinding, value: unknown) => void;
  bindings: TemplateBinding[];
  editable?: boolean;
  /** Hide Univer's built-in permission dialog for read-only workbooks. */
  permissionDialogVisible?: boolean;
  onSelectionChange?: (selection: EditorSelection) => void;
  onUnboundCellChange?: (selection: EditorSelection, value: unknown) => void;
  onCellChange?: (change: EditorCellChange) => void;
  onStructureChange?: (operation: WorkbookStructureOperation) => void;
  instance?: WorkbookTemplateInstance;
  onInstanceChange?: (instance: WorkbookTemplateInstance) => void;
  onReady?: () => void;
  fieldCreationOrigin?: 'TEMPLATE_LOCAL' | 'INSTANCE_LOCAL';
}

export const UniverSheetsEditor = forwardRef<EditorHandle, Props>(function UniverSheetsEditor(
  {
    snapshot,
    ariaLabel = 'Excel 模板编辑器',
    onDirty,
    onEditorValue,
    onEditorLabel,
    bindings,
    editable = true,
    permissionDialogVisible = true,
    onSelectionChange,
    onUnboundCellChange,
    onCellChange,
    onStructureChange,
    onInstanceChange,
    onReady,
    instance,
    fieldCreationOrigin = 'INSTANCE_LOCAL',
  },
  ref,
) {
  const { message } = App.useApp();
  const messageRef = useRef(message); messageRef.current = message;
  const containerRef = useRef<HTMLDivElement>(null);
  const [initializationError, setInitializationError] = useState<Error>();
  const univerRef = useRef<ReturnType<typeof createUniver>['univer']>();
  const apiRef = useRef<FUniver>();
  const changesRef = useRef<EditorChanges>();
  const contentRevisionRef = useRef(0);
  const sourceSnapshotRef = useRef(snapshot);
  const bindingsRef = useRef(bindings);
  const callbacksRef = useRef({
    onDirty,
    onEditorValue,
    onEditorLabel,
    onSelectionChange,
    onUnboundCellChange,
    onCellChange,
    onStructureChange,
    onInstanceChange,
    onReady,
  });
  const instanceRef = useRef(instance);
  const incomingInstanceSignature = useRef('');
  instanceRef.current = instance;
  const suppressUnboundRef = useRef(false);
  const applyingInstanceRef = useRef(false);
  const undoBatchRef = useRef(new WorkbookUndoBatch());
  const creationOriginRef = useRef(fieldCreationOrigin);
  creationOriginRef.current = fieldCreationOrigin;
  const editableRef = useRef(editable);
  const highlightRef = useRef<Array<{ dispose(): void }>>([]);
  const highlightTimersRef = useRef<number[]>([]);
  const highlightedCellRef = useRef<string>();
  bindingsRef.current = bindings;
  sourceSnapshotRef.current = snapshot;
  callbacksRef.current = {
    onDirty,
    onEditorValue,
    onEditorLabel,
    onSelectionChange,
    onUnboundCellChange,
    onCellChange,
    onStructureChange,
    onInstanceChange,
    onReady,
  };
  editableRef.current = editable;

  // Univer 1.2's sheet/column metadata setters bypass undo. Route them through
  // a mutation so identities and temporary definitions share the structural undo batch.
  const writeInstanceMetadata = (sheetId: string, value: Record<string, unknown>, column?: number) => {
    const api = apiRef.current,
      workbook = api?.getActiveWorkbook();
    const injector = univerRef.current?.__getInjector();
    if (!api || !workbook || !injector) return;
    const commands = injector.get(ICommandService),
      id = 'jsd-workbook.mutation.set-metadata';
    if (!commands.hasCommand(id))
      commands.registerCommand({
        id,
        type: CommandType.MUTATION,
        handler: (_accessor, raw) => {
          const params = raw as {
            sheetId: string;
            column?: number;
            value: Record<string, unknown>;
          };
          const target = api.getActiveWorkbook()?.getSheetBySheetId(params.sheetId);
          if (!target) return false;
          if (params.column === undefined) target.setCustomMetadata(params.value);
          else target.setColumnCustomMetadata(params.column, params.value);
          return true;
        },
      });
    const sheet = workbook.getSheetBySheetId(sheetId);
    if (!sheet) return;
    const before =
      column === undefined ? sheet.getCustomMetadata() : sheet.getColumnCustomMetadata(column);
    const redo = {
      id,
      params: { unitId: workbook.getId(), sheetId, column, value: structuredClone(value) },
    };
    const undo = {
      id,
      params: { unitId: workbook.getId(), sheetId, column, value: structuredClone(before ?? {}) },
    };
    commands.syncExecuteCommand(id, redo.params);
    injector
      .get(IUndoRedoService)
      .pushUndoRedo({ unitID: workbook.getId(), undoMutations: [undo], redoMutations: [redo] });
  };

  const beginStructureBatch = () => {
    const service = univerRef.current?.__getInjector().get(IUndoRedoService);
    const workbook = apiRef.current?.getActiveWorkbook();
    return undoBatchRef.current.begin(() => workbook && service ? service.beginUndoRedoGroup(workbook.getId(), generateUUID(), 'append') : undefined);
  };
  const applyInstance = (next: WorkbookTemplateInstance, notify: boolean, writeLabels = true) => {
    const workbook = apiRef.current?.getActiveWorkbook();
    const sheet = workbook?.getSheets()[0];
    if (!sheet) return;
    const batch = beginStructureBatch();
    const wasApplying = applyingInstanceRef.current;
    applyingInstanceRef.current = true;
    try {
      const previous = readWorkbookInstance(workbook.save() as unknown as Record<string, unknown>);
      if (previous) for (const edit of fieldNumberFormatEdits(previous.model, next.model, next.mapping)) {
        const range = resolveRange(apiRef.current, edit.binding);
        if (!range) continue;
        if (edit.initialize) range.setNumberFormat(fieldNumberFormat(edit.field, true, ''));
        else {
          const formats = range.getNumberFormats();
          const updated = formats.map(row => row.map(pattern => fieldNumberFormat(edit.field, false, pattern, edit.typeChanged)));
          if (JSON.stringify(formats) !== JSON.stringify(updated)) range.setNumberFormats(updated);
        }
        if (edit.field.valueType === 'datetime' && (edit.initialize || edit.typeChanged)) {
          range.setWrap(true);
          const targetSheet = apiRef.current?.getActiveWorkbook()?.getSheetBySheetId(String(edit.binding.locator.sheetId));
          const bounds = parseA1Range(locatorValueRange(edit.binding.locator));
          if (targetSheet && bounds) for (let row = bounds.startRow; row <= bounds.endRow; row++)
            if (targetSheet.getRowHeight(row - 1) < 36) targetSheet.setRowHeight(row - 1, 36);
          if (targetSheet && bounds) for (let column = bounds.startColumn; column <= bounds.endColumn; column++)
            if (targetSheet.getColumnWidth(column - 1) < 170) targetSheet.setColumnWidth(column - 1, 170);
        }
      }
      for (const field of next.model.fields) {
        if (field.displayRole === 'REGION') continue;
        const old = previous?.model.fields.find(f => f.id === field.id);
        const binding = next.mapping.find(b => b.bindingId === field.bindingId);
        const label = binding && resolveLabelWriteRange(apiRef.current, binding);
        if (writeLabels && field.labelStatus !== 'UNRESOLVED' && label && ((old && old.name !== field.name) || (!label.getValue() && !['新字段','新明细字段'].includes(field.name)))) label.setValue(field.name);
      }
      writeInstanceMetadata(sheet.getSheetId(), { ...sheet.getCustomMetadata(), templateInstance: next });
      bindingsRef.current = next.mapping;
      if (notify) {
        callbacksRef.current.onInstanceChange?.(next);
        callbacksRef.current.onDirty();
      }
    } finally { applyingInstanceRef.current = wasApplying; batch?.dispose(); }
  };
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    markPerformance('editor-init-start');
    const host = document.createElement('div');
    host.className = 'univer-editor-host';
    host.setAttribute('aria-busy', 'true');
    host.setAttribute('data-loading-label', '初始化内容…');
    container.replaceChildren(host);
    let cancelled = false;
    let animationFrame = 0;
    let commandSubscription: { dispose(): void } | undefined;
    let beforeCommandSubscription: { dispose(): void } | undefined;
    let selectionSubscription: { dispose(): void } | undefined;
    let clipboardSubscription: { dispose(): void } | undefined;
    let ownedUniver: ReturnType<typeof createUniver>['univer'] | undefined;
    let ownedApi: FUniver | undefined;
    const labelValues = new Map<string, unknown>();
    const bindingValues = new Map<string, unknown>();
    const pendingCellValues = new Map<string, { selection: EditorSelection; value: unknown }>();
    let latestSelection: EditorSelection | undefined;
    let latestSelectionValue: unknown;
    let latestSelectionFormula = '';
    let hasLatestSelectionValue = false;
    let recentUnboundKey = '';
    let recentUnboundAt = 0;
    let cellChangeEventsArmed = false;
    let fieldEditGroup: { dispose(): void } | undefined;
    let fieldEditCommand: object | undefined;
    let structureOwner: object | undefined;
    let sheetScope: { command: object; ids: Set<string>; instance?: WorkbookTemplateInstance; group?: { dispose(): void } } | undefined;

    const initializeRuntime = () => {
      if (cancelled) return;
      const { width, height } = container.getBoundingClientRect();
      if (width < 320 || height < 240) {
        animationFrame = window.requestAnimationFrame(initialize);
        return;
      }
      const { univer, univerAPI } = createUniver({
        locale: LocaleType.ZH_CN,
        locales: {
          [LocaleType.ZH_CN]: mergeLocales(
            UniverPresetSheetsCoreZhCN as Record<string, unknown>,
            UniverPresetSheetsDrawingZhCN,
            sheetsFeatureLocale,
          ),
        },
        presets: [
          UniverSheetsCorePreset({
            container: host,
            ribbonType: editableRef.current ? 'classic' : 'collapsed',
            toolbar: editableRef.current,
            footer: { sheetBar: true, statisticBar: true },
            sheets: {
              // Imported OCR/legacy snapshots can legitimately contain numeric-looking
              // text. Univer 0.25.1 renders those cells with a force-string alert whose
              // zh-CN locale entry is missing, exposing the internal translation key.
              // The marker is not actionable for our document-style workbooks, so keep
              // the cell value editable without showing the misleading error UI.
              disableForceStringAlert: true,
              disableForceStringMark: true,
            },
          }),
          // Required for OCR workbooks: the original source image is stored
          // as a native SHEET_DRAWING_PLUGIN resource on the OCR原文 sheet.
          UniverSheetsDrawingPreset({ allowImageSize: 5 * 1024 * 1024 }),
          ...sheetsFeaturePresets(),
        ],
      });
      ownedUniver = univer;
      ownedApi = univerAPI;
      univerRef.current = univer;
      apiRef.current = univerAPI;
      const workbook = univerAPI.createWorkbook(workbookWithExplicitRowHeights(snapshot));
      const structureOperationFor = (command: { id: string; params?: object }) => operationFromUniverCommand(command.id === 'sheet.command.set-worksheet-name'
        ? { ...command, params: { ...command.params, subUnitId: (command.params as { subUnitId?: string })?.subUnitId ?? workbook.getActiveSheet()?.getSheetId() } } : command);
      clipboardSubscription = univer.__getInjector().get(ISheetClipboardService).addClipboardHook(templatePasteStyleHook(
        (sheetId, row, column) => workbook.getSheetBySheetId(sheetId)?.getSheet().getCellStyle(row, column),
      ));
      const startingInstance = readWorkbookInstance(snapshot) ?? instanceRef.current;
      incomingInstanceSignature.current = instanceRef.current ? JSON.stringify([instanceRef.current.model, instanceRef.current.mapping, instanceRef.current.execution]) : '';
      const metadataSheet = workbook.getSheets()[0];
      if (startingInstance && metadataSheet) metadataSheet.setCustomMetadata({ ...metadataSheet.getCustomMetadata(), templateInstance: startingInstance });
      let structureBefore: WorkbookTemplateInstance | undefined;
      let sectionMerges: ReturnType<typeof sectionMergeExtensions> = [];
      let expectedMerges: ReturnType<typeof migratedWorkbookMerges> = [];
      let structureGroup: { dispose(): void } | undefined;
      const mergeMutationId = 'jsd-workbook.mutation.restore-merges';
      const mergeCommands = univer.__getInjector().get(ICommandService);
      mergeCommands.registerCommand({ id: mergeMutationId, type: CommandType.MUTATION, handler: (_accessor, raw) => {
        const params = raw as { sheetId: string; ranges: ReturnType<typeof migratedWorkbookMerges> };
        const sheet = workbook.getSheetBySheetId(params.sheetId);
        if (!sheet) return false;
        const target = { unitId: workbook.getId(), subUnitId: params.sheetId };
        const existing = structuredClone(sheet.getSheet().getMergeData());
        if (existing.length) mergeCommands.syncExecuteCommand('sheet.mutation.remove-worksheet-merge', { ...target, ranges: existing });
        if (params.ranges.length) mergeCommands.syncExecuteCommand('sheet.mutation.add-worksheet-merge', { ...target, ranges: structuredClone(params.ranges) });
        return true;
      } });
      const mergeCheckpoint = (sheetId: string, ranges: ReturnType<typeof migratedWorkbookMerges>, phase: 'before' | 'after') => {
        const mutation = { id: mergeMutationId, params: { unitId: workbook.getId(), sheetId, ranges: structuredClone(ranges) } };
        univer.__getInjector().get(IUndoRedoService).pushUndoRedo({ unitID: workbook.getId(), undoMutations: phase === 'before' ? [mutation] : [], redoMutations: phase === 'after' ? [mutation] : [] });
      };
      const publishInstance = () => {
        const current = readWorkbookInstance(workbook.save() as unknown as Record<string, unknown>);
        if (current) { bindingsRef.current = current.mapping; callbacksRef.current.onInstanceChange?.(current);
          labelValues.clear(); bindingValues.clear();
          for (const binding of current.mapping) { labelValues.set(labelCacheKey(binding), readLabelCell(univerAPI, binding)); bindingValues.set(valueCacheKey(binding), readCell(univerAPI, binding)); }
        }
      };

      contentRevisionRef.current = 0;
      const changes = new EditorChanges(
        () => callbacksRef.current.onDirty(),
        () => {
          contentRevisionRef.current += 1;
        },
      );
      changesRef.current = changes;
      workbook.setEditable(editableRef.current);
      // In preview/history mode the surrounding page provides the read-only
      // hint. Suppress the generic Univer permission modal so a click simply
      // remains non-editable instead of showing a second dialog.
      univerAPI.setPermissionDialogVisible?.(permissionDialogVisible);
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
          latestSelectionFormula = initialRange.getFormula();
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
            editorReady();
            host.removeAttribute('aria-busy');
            callbacksRef.current.onReady?.();
            cellChangeEventsArmed = true;
          }
        });
      });
      for (const binding of bindingsRef.current) {
        labelValues.set(labelCacheKey(binding), readLabelCell(univerAPI, binding));
        bindingValues.set(valueCacheKey(binding), readCell(univerAPI, binding));
      }
      const emitUnboundCellChange = (
        selection: EditorSelection,
        value: unknown,
        previousValue: unknown,
      ) => {
        if (!cellChangeEventsArmed) return;
        if (!isSingleCellAddress(selection.address)) return;
        if (applyingInstanceRef.current || suppressUnboundRef.current || sameValue(value, previousValue)) return;
        const live = workbook.save() as unknown as Record<string, unknown>;
        const current = readWorkbookInstance(live);
        const renamed = current && renameWorkbookFieldAt(current, selection, value);
        if (renamed?.matched) { if (renamed.instance) applyInstance(renamed.instance, true, false); return; }
        if (!isNewFieldLabelChange(previousValue, value)) return;
        const at = parseA1Range(selection.address);
        if (at && instanceSheets(live).find(sheet => sheet.id === selection.sheetId)?.cellData?.[String(at.startRow - 1)]?.[String(at.startColumn - 1)]?.f) return;
        const key = `${selection.sheetId}|${selection.address}|${stableValueKey(value)}`;
        const now = Date.now();
        if (key === recentUnboundKey && now - recentUnboundAt < 120) return;
        recentUnboundKey = key;
        recentUnboundAt = now;
        const discovered = current && discoverWorkbookField(current, selection, value, live);
        if (discovered && creationOriginRef.current === 'TEMPLATE_LOCAL') {
          for (const field of discovered.model.fields) if (!current?.model.fields.some(old => old.id === field.id)) {
            field.fieldOrigin = 'TEMPLATE_LOCAL'; field.fieldCode = templateLocalFieldCode(current?.mapping[0]?.bindingId ?? 'template', field);
            for (const binding of discovered.mapping) if (binding.bindingId === field.bindingId) binding.fieldCode = field.fieldCode;
          }
        }
        if (discovered) {
          applyInstance(discovered, true);
        } else callbacksRef.current.onUnboundCellChange?.(selection, value);
      };
      const emitCellChange = (
        selection: EditorSelection,
        value: unknown,
        previousValue: unknown,
      ) => {
        if (!cellChangeEventsArmed) return;
        if (!isSingleCellAddress(selection.address) || sameValue(value, previousValue)) return;
        const live = workbook.save() as unknown as Record<string, unknown>;
        const current=readWorkbookInstance(live);
        const checked=current&&recordUnboundCell(current,selection,value,live);
        if(checked){const first=workbook.getSheets()[0];if(first)writeInstanceMetadata(first.getSheetId(),{...first.getCustomMetadata(),templateInstance:checked});callbacksRef.current.onInstanceChange?.(checked);}
        let numberFormat: string | undefined;
        try { numberFormat = workbook.getSheetBySheetId(selection.sheetId)?.getRange(selection.address).getNumberFormats()[0]?.[0]; }
        catch { /* Sheet may have been removed during the edit. */ }
        callbacksRef.current.onCellChange?.({ ...selection, value, previousValue, numberFormat });
      };
      const synchronizeAfterCommand = (
        command: { id: string; type?: number; params?: object },
        options?: {
          fromChangeset?: boolean;
          syncOnly?: boolean;
          applyFormulaCalculationResult?: boolean;
        },
      ) => {
        const unitId = (command.params as { unitId?: string } | undefined)?.unitId;
        if (unitId && unitId !== workbook.getId()) return;
        if (!editableRef.current || applyingInstanceRef.current) return;
        if (options?.fromChangeset || options?.syncOnly || options?.applyFormulaCalculationResult)
          return;
        if (command.id === 'univer.command.undo' || command.id === 'univer.command.redo') {
          publishInstance();
          changes.synchronize('history-values', () => {
            for (const binding of bindingsRef.current) {
              if (binding.role !== 'FIELD' || binding.syncDirection === 'DATA_TO_EDITOR') continue;
              const value = readCell(univerAPI, binding);
              if (value !== undefined) callbacksRef.current.onEditorValue(binding, value);
            }
          });
          changes.change(); return;
        }
        const structureOperation = structureOperationFor(command);
        const cellMutation = isCellMutationCommand(command.id, command.params);
        const contentMutation =
          isWorkbookContentCommand(command) && isPersistentMutation(command, options);
        if (!structureOperation && !cellMutation && !contentMutation) return;
        // Restoring the initial snapshot emits the same commands as a user
        // edit in some Univer versions.  Ignore those commands until the
        // editor is armed, otherwise a freshly opened production order is
        // incorrectly shown as “未保存”.
        if (!cellChangeEventsArmed) return;
        if (contentMutation) changes.change();
        let appliedStructure = false;
        if (structureOperation && structureBefore && command === structureOwner) {
          appliedStructure = true;
          const before = structureBefore;
          structureBefore = undefined;
          const wasApplying = applyingInstanceRef.current;
          applyingInstanceRef.current = true;
          try {
            const targetSheet = workbook.getSheetBySheetId(structureOperation.sheetId);
            for (const copy of structureCopies(before, structureOperation)) {
              if (!targetSheet) continue;
              const source = targetSheet.getRange(copy.source), target = targetSheet.getRange(copy.target);
              target.setValues(copyWorkbookCells(source.getCellDatas() as unknown as Array<Array<WorkbookCell | null>>, copy.rowOffset, copy.columnOffset) as Parameters<typeof target.setValues>[0]);
              if (copy.axis === 'COLUMN') targetSheet.setColumnWidth(copy.targetIndex - 1, targetSheet.getColumnWidth(copy.sourceIndex - 1));
              else targetSheet.setRowHeight(copy.targetIndex - 1, targetSheet.getRowHeight(copy.sourceIndex - 1));
            }
            for (const merge of sectionMerges) {
              if (!targetSheet) continue;
              if (merge.cell) targetSheet.getRange(merge.anchor).setValues([[merge.cell]] as Parameters<ReturnType<typeof targetSheet.getRange>['setValues']>[0]);
            }
            mergeCommands.syncExecuteCommand(mergeMutationId, { sheetId: structureOperation.sheetId, ranges: expectedMerges });
            const next = migrateWorkbookInstance(before, structureOperation);
            const formulaChanges: Array<{ before: string; after: string; axis: 'ROW' | 'COLUMN' }> = [];
            for (const projection of before.execution?.listProjections ?? []) {
              const updated = next.execution?.listProjections.find(item => item.listProjectionId === projection.listProjectionId);
              const parent = before.mapping.find(binding => binding.bindingId === projection.parentBindingId || projection.componentId && binding.componentId === projection.componentId);
              if (!updated || parent?.locator.sheetId !== structureOperation.sheetId) continue;
              for (const axis of [projection.itemAxis, projection.recordAxis]) formulaChanges.push({ before: projection.valueRange, after: updated.valueRange, axis });
              formulaChanges.push({ before: projection.labelRange, after: updated.labelRange, axis: projection.itemAxis });
            }
            for (const binding of before.mapping.filter(item => item.mappingKind === 'REPEAT_REGION' && item.locator.sheetId === structureOperation.sheetId)) {
              const updated = next.mapping.find(item => item.bindingId === binding.bindingId);
              if (updated) formulaChanges.push({ before: stringLocator(binding.locator.dataRange) || locatorValueRange(binding.locator),
                after: stringLocator(updated.locator.dataRange) || locatorValueRange(updated.locator), axis: binding.repeatAxis === 'COLUMN' ? 'COLUMN' : 'ROW' });
            }
            // Older contracts omit totalRange. Update actual dependent formula tokens instead of relying on that optional hint.
            const liveSheet = instanceSheets(workbook.save() as unknown as Record<string, unknown>).find(sheet => sheet.id === structureOperation.sheetId);
            for (const [row, columns] of Object.entries(liveSheet?.cellData ?? {})) for (const [column, cell] of Object.entries(columns)) {
              if (!cell.f) continue;
              const formula = retargetAggregateFormula(cell.f, formulaChanges);
              if (formula !== cell.f) targetSheet?.getRange(workbookAddress(Number(row), Number(column))).setValues([[{ ...cell, f: formula }]] as Parameters<ReturnType<NonNullable<typeof targetSheet>['getRange']>['setValues']>[0]);
            }
            const first = workbook.getSheets()[0];
            if (first) writeInstanceMetadata(first.getSheetId(), { ...first.getCustomMetadata(), templateInstance: next });
            bindingsRef.current = next.mapping;
            callbacksRef.current.onInstanceChange?.(next);
            // SDK merge undo mutations can overlap neighbouring sections after rows move.
            // Restore exact merge geometry last on undo, and last on redo, inside this group.
            mergeCheckpoint(structureOperation.sheetId, expectedMerges, 'after');
          } finally { applyingInstanceRef.current = wasApplying; structureOwner = undefined; sectionMerges = []; structureGroup?.dispose(); structureGroup = undefined; }
        }
        if (structureOperation && !appliedStructure) return;
        if (appliedStructure && structureOperation)
          changes.enqueue(() => callbacksRef.current.onStructureChange?.(structureOperation));
        if (!structureOperation && !cellMutation) return;
        // Capture the cell before Univer moves the selection after Enter or a
        // click. This is the cell that was actually edited, not the next blank
        // cell that happened to become active.
        const targets = cellMutation ? mutationCells(command.params) : [];
        const target = targets[0];
        const additionalCells = targets.slice(1).flatMap(cell => {
          const captured = pendingCellValues.get(`${cell.sheetId}|${cell.address}`);
          return captured ? [captured] : [];
        });
        const cellKey = target ? `${target.sheetId}|${target.address}` : undefined;
        const captured = cellKey ? pendingCellValues.get(cellKey) : undefined;
        const editedSelection = captured?.selection;
        const editedPreviousValue = captured?.value;
        changes.synchronize(
          editedSelection ? `${editedSelection.sheetId}|${editedSelection.address}` : 'bindings',
          () => {
            for (const cell of targets) pendingCellValues.delete(`${cell.sheetId}|${cell.address}`);
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
                if (!readWorkbookInstance(workbook.save() as unknown as Record<string, unknown>)) callbacksRef.current.onEditorLabel?.(binding, labelValue);
              }
            }
            if (editedSelection && !suppressUnboundRef.current) {
              const activeWorkbook = univerAPI.getActiveWorkbook() ?? workbook;
              const currentSheet = activeWorkbook.getActiveSheet();
              const currentRange =
                currentSheet?.getActiveRange() ?? activeWorkbook.getActiveRange();
              const editedSheet = editedSelection
                ? (activeWorkbook.getSheetBySheetId(editedSelection.sheetId) ?? currentSheet)
                : currentSheet;
              const editedRange = editedSelection
                ? editedSheet?.getRange(editedSelection.address)
                : undefined;
              const activeSheet = editedSheet ?? currentSheet;
              const activeRange = editedRange ?? currentRange;
              const activeAddress =
                editedSelection?.address ??
                activeRange?.getA1Notation() ??
                latestSelection?.address;
              const activeSheetId =
                editedSelection?.sheetId ?? activeSheet?.getSheetId() ?? latestSelection?.sheetId;
              const activeSheetName =
                editedSelection?.sheetName ??
                activeSheet?.getSheetName() ??
                latestSelection?.sheetName;
              if (
                activeSheet &&
                activeAddress &&
                activeSheetId &&
                activeSheetName &&
                isSingleCellAddress(activeAddress)
              ) {
                const selection: EditorSelection = {
                  sheetId: activeSheetId,
                  sheetName: activeSheetName,
                  address: activeAddress,
                };
                const value =
                  activeRange?.getValue() ?? activeSheet.getRange(activeAddress).getValue();
                const previousValue = editedSelection ? editedPreviousValue : undefined;
                if (!editedSelection) latestSelection = selection;
                if (
                  editedSelection &&
                  latestSelection?.sheetId === editedSelection.sheetId &&
                  latestSelection.address === editedSelection.address
                ) {
                  latestSelectionValue = value;
                  latestSelectionFormula = activeRange?.getFormula() ?? '';
                  hasLatestSelectionValue = true;
                }
                emitUnboundCellChange(selection, value, previousValue);
                if (editedSelection) emitCellChange(selection, value, previousValue);
              }
            }
            if (!suppressUnboundRef.current) for (const cell of additionalCells) {
              const range = workbook.getSheetBySheetId(cell.selection.sheetId)?.getRange(cell.selection.address);
              if (!range) continue;
              const value = range.getValue();
              emitUnboundCellChange(cell.selection, value, cell.value);
              emitCellChange(cell.selection, value, cell.value);
            }
          },
        );
      };
      const commands = univer.__getInjector().get(ICommandService);
      let restoringHistory = false;
      beforeCommandSubscription = commands.beforeCommandExecuted((command, options) => {
        if (cellChangeEventsArmed && editableRef.current && !applyingInstanceRef.current && !restoringHistory && !structureGroup && !options?.fromChangeset && !options?.syncOnly && isGuardedWorkbookOperation(command.id)) {
          const unitId = (command.params as { unitId?: string })?.unitId;
          if (!unitId || unitId === workbook.getId()) {
            const reason = workbookOperationBlock(command, { instance: readWorkbookInstance(workbook.save() as unknown as Record<string, unknown>),
              bindings: bindingsRef.current, sheetId: workbook.getActiveSheet()?.getSheetId() ?? '', selection: latestSelection });
            if (reason) { void messageRef.current.warning({ key: 'workbook-operation', content: reason }); throw new CanceledError(); }
          }
        }
        if (cellChangeEventsArmed && editableRef.current && !applyingInstanceRef.current && !restoringHistory && !sheetScope && ['sheet.command.copy-sheet','sheet.command.remove-sheet'].includes(command.id)) {
          sheetScope = { command, ids: new Set(workbook.getSheets().map(sheet => sheet.getSheetId())),
            instance: readWorkbookInstance(workbook.save() as unknown as Record<string, unknown>), group: beginStructureBatch() };
        }
        if (command.id === 'univer.command.undo' || command.id === 'univer.command.redo') { changes.flush(); restoringHistory = true; }
        if (cellChangeEventsArmed && editableRef.current && !applyingInstanceRef.current && !restoringHistory && !structureGroup && !fieldEditGroup
          && !structureOperationFor(command) && isCellMutationCommand(command.id, command.params) && !options?.fromChangeset
          && command.type === CommandType.COMMAND) { fieldEditGroup = beginStructureBatch(); fieldEditCommand = command; }
        if (cellChangeEventsArmed && editableRef.current && !structureGroup && !restoringHistory && !options?.fromChangeset
          && command.type === CommandType.MUTATION && isCellMutationCommand(command.id, command.params)
          && writesReadOnlyValue(bindingsRef.current, command.params)) throw new CanceledError();
        const operation = structureOperationFor(command);
        if (cellChangeEventsArmed && editableRef.current && operation && !structureBefore && !options?.fromChangeset) {
          const beforeSnapshot = workbook.save() as unknown as Record<string, unknown>;
          structureBefore = readWorkbookInstance(beforeSnapshot);
          structureOwner = command;
          const direction = (command.params as { direction?: number } | undefined)?.direction;
          const sections = (structureBefore?.execution?.listProjections ?? []).filter(projection => projection.itemAxis === (operation.type.endsWith('ROWS') ? 'ROW' : 'COLUMN')
            && structureBefore?.mapping.find(binding => binding.bindingId === projection.parentBindingId || projection.componentId && binding.componentId === projection.componentId)?.locator.sheetId === operation.sheetId)
            .map(projection => ({ range: projection.labelRange, name: projection.displayName ?? '' }));
          sectionMerges = sectionMergeExtensions(instanceSheets(beforeSnapshot).find(sheet => sheet.id === operation.sheetId), operation, direction === Direction.DOWN || direction === Direction.RIGHT, sections);
          const beforeSheet = instanceSheets(beforeSnapshot).find(sheet => sheet.id === operation.sheetId);
          expectedMerges = migratedWorkbookMerges(beforeSheet, operation, direction === Direction.DOWN || direction === Direction.RIGHT, sections);
          if (structureBefore) {
            structureGroup = beginStructureBatch();
            mergeCheckpoint(operation.sheetId, beforeSheet?.mergeData ?? [], 'before');
          }
        }
        if (
          !cellChangeEventsArmed ||
          !editableRef.current ||
          applyingInstanceRef.current ||
          !isPersistentMutation(command, options) ||
          !isCellMutationCommand(command.id, command.params)
        )
          return;
        const unitId = (command.params as { unitId?: string } | undefined)?.unitId;
        if (unitId && unitId !== workbook.getId()) return;
        for (const target of mutationCells(command.params)) {
        const key = `${target.sheetId}|${target.address}`;
        if (pendingCellValues.has(key)) continue;
        const sheet = workbook.getSheetBySheetId(target.sheetId);
        try {
          if (sheet)
            pendingCellValues.set(key, {
              selection: { ...target, sheetName: sheet.getSheetName() },
              value: sheet.getRange(target.address).getValue(),
            });
        } catch {
          /* A transient range must not prevent the actual SDK mutation. */
        }
        }
      });
      commandSubscription = commands.onCommandExecuted((command, options) => {
        try { synchronizeAfterCommand(command, options); }
        finally {
          if (command === sheetScope?.command) {
            try {
              for (const sheet of workbook.getSheets()) if (!sheetScope.ids.has(sheet.getSheetId())) {
                const custom = sheet.getCustomMetadata();
                if (custom?.templateInstance || custom?.labTemplate) writeInstanceMetadata(sheet.getSheetId(), copyableSheetMetadata(custom));
              }
              if (sheetScope.instance && !readWorkbookInstance(workbook.save() as unknown as Record<string, unknown>)) {
                const first = workbook.getSheets()[0];
                if (first) writeInstanceMetadata(first.getSheetId(), { ...first.getCustomMetadata(), templateInstance: sheetScope.instance });
              }
            } finally { sheetScope.group?.dispose(); sheetScope = undefined; }
          }
          if (command === fieldEditCommand) { try { changes.flush(); } finally { fieldEditGroup?.dispose(); fieldEditGroup = undefined; fieldEditCommand = undefined; } }
          if (command.id === 'univer.command.undo' || command.id === 'univer.command.redo') restoringHistory = false;
        }
      });
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
              const previousRange = previousSheet.getRange(latestSelection.address);
              const previousValue = previousRange.getValue(),
                formula = previousRange.getFormula();
              // A selection can arrive before Univer exposes the value of the
              // previous range. Without a baseline there is no evidence of an
              // edit; treating it as one makes a plain cell click call the
              // import correction API with an incomplete/stale field identity.
              if (
                editableRef.current &&
                cellChangeEventsArmed &&
                hasLatestSelectionValue &&
                (formula !== latestSelectionFormula ||
                  (!formula && !sameValue(previousValue, latestSelectionValue)))
              ) {
                changes.change();
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
        const cellKey = `${latestSelection.sheetId}|${latestSelection.address.split(':')[0]}`;
        if (highlightedCellRef.current && highlightedCellRef.current !== cellKey) {
          highlightTimersRef.current.forEach((timer) => window.clearTimeout(timer));
          highlightTimersRef.current = [];
          highlightRef.current.forEach((highlight) => highlight.dispose());
          highlightRef.current = [];
          highlightedCellRef.current = undefined;
        }
        try {
          latestSelectionValue = range.getValue();
          latestSelectionFormula = range.getFormula();
          hasLatestSelectionValue = true;
        } catch {
          latestSelectionValue = undefined;
          hasLatestSelectionValue = false;
        }
        callbacksRef.current.onSelectionChange?.(latestSelection);
      });
    };
    const initialize = () => {
      try {
        initializeRuntime();
      } catch (error) {
        setInitializationError(
          error instanceof Error ? error : new Error('Excel 文档编辑器初始化失败'),
        );
      }
    };
    animationFrame = window.requestAnimationFrame(initialize);

    return () => {
      cancelled = true;
      cellChangeEventsArmed = false;
      window.cancelAnimationFrame(animationFrame);
      commandSubscription?.dispose();
      beforeCommandSubscription?.dispose();
      pendingCellValues.clear();
      fieldEditGroup?.dispose();
      sheetScope?.group?.dispose();
      changesRef.current?.dispose();
      changesRef.current = undefined;
      selectionSubscription?.dispose();
      clipboardSubscription?.dispose();
      highlightRef.current.forEach((highlight) => highlight.dispose());
      highlightRef.current = [];
      highlightTimersRef.current.forEach((timer) => window.clearTimeout(timer));
      highlightTimersRef.current = [];
      highlightedCellRef.current = undefined;
      if (apiRef.current === ownedApi) apiRef.current = undefined;
      if (univerRef.current === ownedUniver) univerRef.current = undefined;
      // Univer publishes internal React updates while disposing. Deferring
      // disposal out of React's cleanup stack prevents the renderer warning,
      // while the host is detached immediately so two editors are never
      // visible or interactive at the same time.
      if (host.parentNode) host.parentNode.removeChild(host);
      window.setTimeout(() => {
        unmount(host);
        ownedUniver?.dispose();
      }, 0);
    };
  }, [snapshot]);

  useEffect(() => {
    editableRef.current = editable;
    apiRef.current?.getActiveWorkbook()?.setEditable(editable);
    apiRef.current?.setPermissionDialogVisible?.(permissionDialogVisible);
    if (editable && apiRef.current) {
      void protectReadOnlyRanges(apiRef.current, bindings).catch(() => undefined);
    }
  }, [bindings, editable, permissionDialogVisible]);

  useEffect(() => {
    const workbook = apiRef.current?.getActiveWorkbook();
    const first = workbook?.getSheets()[0];
    if (!instance || !first || !editable) return;
    const signature = JSON.stringify([instance.model, instance.mapping, instance.execution]);
    if (signature === incomingInstanceSignature.current) return;
    incomingInstanceSignature.current = signature;
    const current = readWorkbookInstance(workbook.save() as unknown as Record<string, unknown>);
    if (!current || JSON.stringify([current.model, current.mapping, current.execution]) === signature) return;
    // Existing field-manager edits use the same metadata mutation as row/column edits.
    applyInstance({ ...current, model: instance.model, mapping: instance.mapping, execution: instance.execution }, false);
  }, [instance, editable]);

  const focusBinding = (binding: TemplateBinding) => {
    const workbook = apiRef.current?.getActiveWorkbook();
    const sheet = resolveSheet(apiRef.current, binding);
    const valueRange = mergedFocusRange(sheet,
      resolveLocatorRange(apiRef.current, binding, 'logicalInputRange') ??
      resolveRange(apiRef.current, binding));
    const labelRange = mergedFocusRange(sheet, resolveLabelRange(apiRef.current, binding));
    const rowHeaderRange = mergedFocusRange(sheet, resolveLocatorRange(apiRef.current, binding, 'rowHeaderRange'));
    const columnHeaderRange = mergedFocusRange(sheet, resolveLocatorRange(apiRef.current, binding, 'columnHeaderRange'));
    const tableDataRange = mergedFocusRange(sheet, resolveLocatorRange(apiRef.current, binding, 'dataRange'));
    const regionRange = !isStructuredLeaf(binding)
      ? mergedFocusRange(sheet, resolveLocatorRange(apiRef.current, binding, 'range'))
      : null;
    // A table-column field owns its value range. Do not let the parent
    // repeat-region data range swallow the selected child field.
    const activeRange = isStructuredLeaf(binding)
      ? (valueRange ?? tableDataRange ?? labelRange)
      : (regionRange ?? tableDataRange ?? valueRange ?? labelRange);
    if (workbook && sheet && activeRange) {
      try {
        highlightTimersRef.current.forEach((timer) => window.clearTimeout(timer));
        highlightTimersRef.current = [];
        highlightRef.current.forEach((highlight) => highlight.dispose());
        highlightRef.current = [];
        if (workbook.getActiveSheet()?.getSheetId() !== sheet.getSheetId()) workbook.setActiveSheet(sheet);
        highlightedCellRef.current = `${sheet.getSheetId()}|${activeRange.getA1Notation().split(':')[0]}`;
        const injector = univerRef.current?.__getInjector();
        const commands = injector?.get(ICommandService);
        const viewport = injector?.get(IRenderManagerService).getRenderUnitById(workbook.getId())
          ?.scene.getViewport(SHEET_VIEWPORT_KEY.VIEW_MAIN);
        // Selecting a wide field makes the SDK reveal its far edge. Capture the
        // user's viewport first, then reveal the first value with its label context.
        const scrollBefore = sheet.getScrollState();
        const boundsBefore = viewport ? { ...viewport.calcViewportInfo().viewBound } : undefined;
        sheet.setActiveRange(activeRange);
        if (viewport?.isActive) {
        // Use actual sheet-coordinate bounds, including zoom and frozen panes.
        // The SDK's row-index reveal can leave a bottom cell partially clipped.
        // Focus the first cell (including its merge), not the end of a tall field.
        const cell = activeRange.getCell();
        const target = { ...(cell.isMerged ? cell.mergeInfo : cell) };
        if (labelRange) {
          const labelCell = labelRange.getCell();
          target.startX = Math.min(target.startX, (labelCell.isMerged ? labelCell.mergeInfo : labelCell).startX);
          const group = labelRange.getColumn() > 0 ? sheet.getCellMergeData(activeRange.getRow(), labelRange.getColumn() - 1) : undefined;
          if (group) {
            const groupCell = sheet.getRange(group.getA1Notation()).getCell();
            target.startX = Math.min(target.startX, (groupCell.isMerged ? groupCell.mergeInfo : groupCell).startX);
          }
        }
        const freeze = sheet.getFreeze();
        const delta = focusScrollDelta(target, boundsBefore ?? viewport.calcViewportInfo().viewBound, {
          x: freeze.xSplit > 0 && activeRange.getColumn() < freeze.startColumn,
          y: freeze.ySplit > 0 && activeRange.getRow() < freeze.startRow,
        });
        const currentScroll = sheet.getScrollState();
        if (delta.offsetX || delta.offsetY || currentScroll.offsetX !== scrollBefore.offsetX || currentScroll.offsetY !== scrollBefore.offsetY
          || currentScroll.sheetViewStartRow !== scrollBefore.sheetViewStartRow || currentScroll.sheetViewStartColumn !== scrollBefore.sheetViewStartColumn) {
          commands?.syncExecuteCommand(ScrollCommand.id, {
            sheetViewStartRow: scrollBefore.sheetViewStartRow,
            sheetViewStartColumn: scrollBefore.sheetViewStartColumn,
            offsetX: scrollBefore.offsetX + delta.offsetX,
            offsetY: scrollBefore.offsetY + delta.offsetY,
            duration: 0,
          });
        }
        } else {
          commands?.syncExecuteCommand(ScrollToRangeOperation.id, {
            range: { startRow: activeRange.getRow(), endRow: activeRange.getRow(),
              startColumn: activeRange.getColumn(), endColumn: activeRange.getColumn() },
          });
        }
        const drawHighlight = (strong: boolean) => {
          try {
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
              ? (valueRange ?? tableDataRange)
              : (regionRange ?? tableDataRange ?? valueRange);
            if (fillRange)
              highlightRef.current.push(
                fillRange.highlight({
                  fill: strong ? 'rgba(37, 99, 235, 0.22)' : 'rgba(37, 99, 235, 0.09)',
                  stroke: '#2563eb',
                  strokeWidth: strong ? 3 : 2,
                }),
              );
          } catch (error) {
            if (!isTransientRenderDependencyError(error)) throw error;
            // The renderer can be replaced while the workbench reloads a saved
            // snapshot. A field click should remain harmless during that short gap.
            highlightRef.current = [];
          }
        };
        drawHighlight(true);
        if (!window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
          [false, true, false, true].forEach((strong, index) => {
            highlightTimersRef.current.push(
              window.setTimeout(() => drawHighlight(strong), 150 * (index + 1)),
            );
          });
        }
      } catch (error) {
        if (!isTransientRenderDependencyError(error)) throw error;
        // Selection services are ready before the optional scroll/highlight
        // services. Keep the user's selection when a snapshot is reloading.
        try {
          if (workbook.getActiveSheet()?.getSheetId() !== sheet.getSheetId()) workbook.setActiveSheet(sheet);
          sheet.setActiveRange(activeRange);
        } catch {
          // The editor is being disposed; the next user action will focus it.
        }
      }
    }
  };

  const focusSourceRange = (sheetId: string, address: string) => {
    const workbook = apiRef.current?.getActiveWorkbook();
    const sheet = workbook?.getSheetBySheetId(sheetId) ?? workbook?.getSheetByName(sheetId);
    if (!sheet || !validRange(address)) return;
    const binding = sourceFocusBinding(bindingsRef.current, {
      sheetId: sheet.getSheetId(), sheetName: sheet.getSheetName(), address,
    }, instanceRef.current);
    if (binding) focusBinding(binding);
  };

  useImperativeHandle(
    ref,
    () => ({
      getSelection() {
        const sheet = apiRef.current?.getActiveWorkbook()?.getActiveSheet();
        const range = sheet?.getActiveRange();
        return sheet && range ? { sheetId: sheet.getSheetId(), sheetName: sheet.getSheetName(), address: range.getA1Notation() } : undefined;
      },
      flushChanges() {
        changesRef.current?.flush();
      },
      getContentRevision() {
        return contentRevisionRef.current;
      },
      getSnapshot() {
        changesRef.current?.flush();
        return (apiRef.current?.getActiveWorkbook()?.save() ?? sourceSnapshotRef.current) as Record<
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
        if (!range) return Promise.resolve();
        range?.setValue(value as never);
        return verifyWrite(() => range?.getValue(), value);
      },
      focusBinding,
      focusCell: focusSourceRange,
      focusRange: focusSourceRange,
      writeCell(sheetId, address, value, options) {
        const workbook = apiRef.current?.getActiveWorkbook();
        const sheet = workbook?.getSheetBySheetId(sheetId) ?? workbook?.getSheetByName(sheetId);
        const range = sheet && validCell(address) ? sheet.getRange(address) : null;
        if (!range) return Promise.reject(new Error(`无法定位单元格 ${address}`));
        suppressUnboundRef.current = true;
        const pattern = range.getNumberFormats()[0]?.[0] ?? '';
        const safeValue = workbookCellInput(value == null ? '' : value, pattern, options?.raw);
        range.setValue(safeValue as never);
        return verifyWrite(() => range.getRawValue(), safeValue).finally(() => {
          suppressUnboundRef.current = false;
        });
      },
      clearRangeConstants(sheetId, address) {
        const workbook = apiRef.current?.getActiveWorkbook();
        const sheet = workbook?.getSheetBySheetId(sheetId) ?? workbook?.getSheetByName(sheetId);
        const range = sheet && validRange(address) ? sheet.getRange(address) : null;
        if (!sheet || !range) return Promise.reject(new Error(`无法定位清理区域 ${address}`));
        suppressUnboundRef.current = true;
        try {
          const formulas = range.getFormulas();
          const values = range.getValues();
          const parsed = parseA1Range(address);
          if (!parsed) return Promise.reject(new Error(`无法解析清理区域 ${address}`));
          let cleared = 0;
          let formulasKept = 0;
          formulas.forEach((row, rowIndex) =>
            row.forEach((formula, columnIndex) => {
              if (formula) {
                formulasKept += 1;
                return;
              }
              const value = values[rowIndex]?.[columnIndex];
              if (value === '' || value == null) return;
              sheet
                .getRange(
                  formatA1Range({
                    startColumn: parsed.startColumn + columnIndex,
                    endColumn: parsed.startColumn + columnIndex,
                    startRow: parsed.startRow + rowIndex,
                    endRow: parsed.startRow + rowIndex,
                  }),
                )
                .setValue('');
              cleared += 1;
            }),
          );
          return Promise.resolve({ cleared, formulasKept });
        } finally {
          suppressUnboundRef.current = false;
        }
      },
      async appendRepeatRecord(binding, options) {
        const count = options?.count ?? 1;
        if (!Number.isInteger(count) || count < 1 || count > 100) throw new Error('每次可增加1至100条记录');
        if (!['REPEAT_REGION', 'REPEAT_FIELD'].includes(binding.mappingKind || '')) {
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
        const insertIndex = options?.index ?? (axis === 'ROW' ? parsed.endRow : parsed.endColumn) + 1;
        const commandRange =
          axis === 'ROW'
            ? {
                startRow: insertIndex - 1,
                endRow: insertIndex - 1 + stride * count - 1,
                startColumn: parsed.startColumn - 1,
                endColumn: parsed.endColumn - 1,
              }
            : {
                startRow: parsed.startRow - 1,
                endRow: parsed.endRow - 1,
                startColumn: insertIndex - 1,
                endColumn: insertIndex - 1 + stride * count - 1,
              };
        await api.executeCommand(
          axis === 'ROW' ? 'sheet.command.insert-row' : 'sheet.command.insert-col',
          { unitId, subUnitId, range: commandRange, direction: axis === 'ROW' ? Direction.DOWN : Direction.RIGHT },
        );
        // The command hook handles copying and metadata for every entry point.
      },
      getTemplateInstance() {
        const saved = apiRef.current?.getActiveWorkbook()?.save();
        return saved ? readWorkbookInstance(saved as unknown as Record<string, unknown>) : instanceRef.current;
      },
      async setTemplateInstance(next) {
        await Promise.resolve();
        applyInstance(next, true);
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
    [snapshot],
  );

  if (initializationError) throw initializationError;
  return <div ref={containerRef} className="univer-editor-surface" aria-label={ariaLabel} />;
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

function mergedFocusRange(sheet: ReturnType<typeof resolveSheet>, range: ReturnType<typeof resolveRange>) {
  if (!sheet || !range || range.getHeight() !== 1 || range.getWidth() !== 1) return range;
  const merge = sheet.getCellMergeData(range.getRow(), range.getColumn());
  // The SDK merge object can lack unitId/sheetId on its raw range. Recreate
  // it through this sheet so selecting a merged title retains workbook identity.
  return merge ? sheet.getRange(merge.getA1Notation()) : range;
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
          locatorValueRange(binding.locator)
      : stringLocator(binding.locator.dataRange) ||
        stringLocator(binding.locator.address) ||
        stringLocator(binding.locator.range);
  const sheet = resolveSheet(api, binding);
  return sheet && validRange(address) ? sheet.getRange(address) : null;
}

function resolveLabelRange(api: FUniver | undefined, binding: TemplateBinding) {
  const address = locatorLabelRange(binding.locator);
  const sheet = resolveSheet(api, binding);
  return sheet && validRange(address) ? sheet.getRange(address) : null;
}

function resolveLabelWriteRange(api: FUniver | undefined, binding: TemplateBinding) {
  const address = stringLocator(binding.locator.labelRange).split(':')[0] || stringLocator(binding.locator.labelAddress);
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

function isTransientRenderDependencyError(error: unknown) {
  const message = error instanceof Error ? error.message : String(error);
  return message.includes('Cannot find') && message.includes('registered by any injector');
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
  return binding.mappingKind === 'REPEAT_FIELD';
}

function isInlineTextBinding(binding: TemplateBinding) {
  return (
    binding.locatorType === 'INLINE_TEXT' ||
    stringLocator(binding.locator.locatorType) === 'INLINE_TEXT' ||
    stringLocator(binding.locator.valueMode) === 'INLINE_TEXT'
  );
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
  if (Array.isArray(left) && left.length === 1 && !Array.isArray(right)) {
    return sameValue(left[0], right);
  }
  if (typeof left === 'number' && typeof right === 'string' && String(left) === right.trim())
    return true;
  if (typeof right === 'number' && typeof left === 'string' && String(right) === left.trim())
    return true;
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
    let attempt = 0;
    const check = () => {
      if (sameValue(read(), expected)) {
        resolve();
      } else if (attempt++ < 8) {
        window.setTimeout(check, 50);
      } else {
        reject(new Error('写入后回读的单元格内容不一致'));
      }
    };
    window.requestAnimationFrame(check);
  });
}

async function protectReadOnlyRanges(api: FUniver, bindings: TemplateBinding[]) {
  const workbook = api.getActiveWorkbook();
  if (workbook && readWorkbookInstance(workbook.save() as unknown as Record<string, unknown>)) {
    // Univer's range protection also disables insert/delete on any intersecting column.
    // Instance workspaces guard value mutations instead; retain user-authored protection rules.
    for (const sheet of workbook.getSheets()) for (const rule of await sheet.getWorksheetPermission().listRangeProtectionRules()) {
      if (rule.options.metadata?.source === 'FIELD_MODEL_V4') await rule.remove();
    }
    return;
  }
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
