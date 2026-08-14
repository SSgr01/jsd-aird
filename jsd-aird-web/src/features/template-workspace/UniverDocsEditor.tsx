import {
  createUniver,
  DocumentFlavor,
  LocaleType,
  mergeLocales,
  type FUniver,
  type IDocumentData,
} from '@univerjs/presets';
import {
  DocBackScrollRenderController,
  IRenderManagerService,
  UniverDocsCorePreset,
} from '@univerjs/preset-docs-core';
import UniverPresetDocsCoreZhCN from '@univerjs/preset-docs-core/locales/zh-CN';
import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';

import { generateUUID } from '@/utils/uuid';

import '@univerjs/preset-docs-core/lib/index.css';

import type {
  BindingRole,
  DocumentStructureNode,
  EditorHandle,
  TemplateBinding,
} from './types';

interface Props {
  snapshot: Record<string, unknown>;
  editable: boolean;
  onDirty: () => void;
  onEditorValue?: (binding: TemplateBinding, value: unknown) => void;
  bindings?: TemplateBinding[];
}

export const UniverDocsEditor = forwardRef<EditorHandle, Props>(function UniverDocsEditor(
  {
    snapshot,
    editable,
    onDirty,
    onEditorValue = () => undefined,
    bindings = [],
  },
  ref,
) {
  const containerRef = useRef<HTMLDivElement>(null);
  const univerRef = useRef<ReturnType<typeof createUniver>['univer']>();
  const apiRef = useRef<FUniver>();
  const sourceSnapshotRef = useRef(snapshot);
  const bindingsRef = useRef(bindings);
  const callbacksRef = useRef({ onDirty, onEditorValue });
  sourceSnapshotRef.current = snapshot;
  bindingsRef.current = bindings;
  callbacksRef.current = { onDirty, onEditorValue };

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    const host = document.createElement('div');
    host.className = 'univer-editor-host';
    container.replaceChildren(host);
    let cancelled = false;
    let animationFrame = 0;
    let initialSelectionTimer = 0;
    let initialized = false;
    let lastDocumentSignature = '';
    let commandSubscription: { dispose(): void } | undefined;
    let ownedUniver: ReturnType<typeof createUniver>['univer'] | undefined;
    let ownedApi: FUniver | undefined;
    const preventReadOnlyInput = (event: Event) => {
      event.preventDefault();
      event.stopPropagation();
    };
    const preventReadOnlyKey = (event: KeyboardEvent) => {
      const printable = !event.ctrlKey && !event.metaKey && !event.altKey && event.key.length === 1;
      const editingKey = printable || event.key === 'Backspace' || event.key === 'Delete' || event.key === 'Enter';
      const pasteOrCut = (event.ctrlKey || event.metaKey) && ['v', 'x'].includes(event.key.toLowerCase());
      if (editingKey || pasteOrCut) preventReadOnlyInput(event);
    };
    if (!editable) {
      host.addEventListener('beforeinput', preventReadOnlyInput, true);
      host.addEventListener('paste', preventReadOnlyInput, true);
      host.addEventListener('drop', preventReadOnlyInput, true);
      host.addEventListener('keydown', preventReadOnlyKey, true);
    }

    const initialize = () => {
      if (cancelled || initialized) return;
      const { width, height } = container.getBoundingClientRect();
      if (width < 320 || height < 240) {
        if (!animationFrame) animationFrame = window.requestAnimationFrame(() => {
          animationFrame = 0;
          initialize();
        });
        return;
      }
      try {
        const { univer, univerAPI } = createUniver({
          locale: LocaleType.ZH_CN,
          locales: {
            [LocaleType.ZH_CN]: mergeLocales(UniverPresetDocsCoreZhCN),
          },
          presets: [
            UniverDocsCorePreset({
              container: host,
              // Docs Core already supplies the common formatting operations needed by
              // template authors: font family/size, bold/italic/underline, text and
              // highlight colour, paragraph alignment, lists and tables.
              toolbar: editable,
              ribbonType: editable ? 'classic' : 'collapsed',
              header: true,
              footer: true,
            }),
          ],
        });
        ownedUniver = univer;
        ownedApi = univerAPI;
        univerRef.current = univer;
        apiRef.current = univerAPI;
        const document = univerAPI.createUniverDoc(normalizeDocumentSnapshot(snapshot));
        lastDocumentSignature = documentContentSignature(document.getSnapshot());
        // Docs disables paragraph commands until it has an active text range.
        // Put the editable surface at a valid caret position so alignment and
        // other paragraph commands work immediately after the document opens.
        // The first call can happen before the document skeleton has rendered,
        // so repeat it after the first layout pass.
        if (editable) {
          const body = snapshot.body && typeof snapshot.body === 'object'
            ? snapshot.body as { dataStream?: unknown }
            : undefined;
          const dataStream = typeof body?.dataStream === 'string' ? body.dataStream : '';
          const initialOffset = Math.max(0, dataStream.length - 2);
          const focusDocument = () => {
            document.setSelection(initialOffset, initialOffset);
            // The Docs toolbar derives its enabled state from the canvas text
            // selection event. Re-emit the same first-page click that a user
            // would make, because setting a facade range alone does not refresh
            // that toolbar state in the current Univer release.
            const canvas = host.querySelector('canvas');
            if (!canvas) return;
            const rect = canvas.getBoundingClientRect();
            const clientX = rect.left + Math.min(250, Math.max(24, rect.width / 2));
            const clientY = rect.top + Math.min(180, Math.max(24, rect.height / 2));
            for (const [type, buttons] of [
              ['pointerdown', 1],
              ['mousedown', 1],
              ['pointerup', 0],
              ['mouseup', 0],
              ['click', 0],
            ] as const) {
              canvas.dispatchEvent(new MouseEvent(type, {
                bubbles: true,
                button: 0,
                buttons,
                clientX,
                clientY,
              }));
            }
          };
          focusDocument();
          initialSelectionTimer = window.setTimeout(() => {
            focusDocument();
          }, 800);
        }
        initialized = true;
        commandSubscription = univerAPI.onCommandExecuted((commandInfo) => {
          // Tables, lists, paragraph styles, page breaks, undo and redo all
          // mutate the document. Compare the content snapshot instead of
          // maintaining a brittle list of text-only command IDs.
          const commandParams = commandInfo.params as { unitId?: string } | undefined;
          if (commandParams?.unitId && commandParams.unitId !== document.getId()) return;
          window.setTimeout(() => {
            const nextSignature = documentContentSignature(document.getSnapshot());
            if (nextSignature === lastDocumentSignature) return;
            lastDocumentSignature = nextSignature;
            callbacksRef.current.onDirty();
            for (const binding of bindingsRef.current) {
              if (binding.syncDirection === 'DATA_TO_EDITOR') continue;
              callbacksRef.current.onEditorValue(binding, readMarkerValue(univerAPI, binding));
            }
          }, 0);
        });
      } catch (error) {
        host.textContent = 'Word 文档编辑器初始化失败，请刷新后重试';
        host.classList.add('univer-editor-error');
        console.error('Univer Docs 初始化失败', error);
      }
    };
    const resizeObserver = typeof ResizeObserver === 'undefined'
      ? undefined
      : new ResizeObserver(initialize);
    resizeObserver?.observe(container);
    initialize();

    return () => {
      cancelled = true;
      window.cancelAnimationFrame(animationFrame);
      window.clearTimeout(initialSelectionTimer);
      resizeObserver?.disconnect();
      commandSubscription?.dispose();
      host.removeEventListener('beforeinput', preventReadOnlyInput, true);
      host.removeEventListener('paste', preventReadOnlyInput, true);
      host.removeEventListener('drop', preventReadOnlyInput, true);
      host.removeEventListener('keydown', preventReadOnlyKey, true);
      if (apiRef.current === ownedApi) apiRef.current = undefined;
      if (univerRef.current === ownedUniver) univerRef.current = undefined;
      window.setTimeout(() => {
        ownedUniver?.dispose();
        if (host.parentNode) host.parentNode.removeChild(host);
      }, 32);
    };
  }, [snapshot, editable]);

  useImperativeHandle(
    ref,
    () => ({
      getSnapshot() {
        const document = apiRef.current?.getActiveDocument();
        return preserveWordSnapshotMetadata(
          (document?.getSnapshot() ?? {}) as Record<string, unknown>,
          sourceSnapshotRef.current,
        );
      },
      readBinding(binding) {
        return apiRef.current ? readMarkerValue(apiRef.current, binding) : undefined;
      },
      async writeBinding(binding, value) {
        const document = apiRef.current?.getActiveDocument();
        const marker = document ? locateMarker(document.getSnapshot(), binding.markerId) : null;
        if (document && marker && binding.markerId) {
          await apiRef.current?.executeCommand('doc.command.insert-custom-range', {
            unitId: document.getId(),
            rangeId: binding.markerId,
            text: toEditorText(value),
            wholeEntity: false,
            textRanges: [{ startOffset: marker.start, endOffset: marker.end }],
            properties: {
              source: 'JSD_MAPPING',
              dataPath: binding.dataPath,
              fieldCode: binding.fieldCode,
              role: binding.role,
            },
          });
        }
      },
      focusBinding(binding) {
        const document = apiRef.current?.getActiveDocument();
        const marker = document ? locateMarker(document.getSnapshot(), binding.markerId) : null;
        if (document && marker) {
          document.setSelection(marker.start, marker.end);
        }
      },
      focusNode(node: DocumentStructureNode) {
        const selectNode = () => {
          const document = apiRef.current?.getActiveDocument();
          if (!document) return;
          const currentSnapshot = document.getSnapshot();
          const locator = node.editorLocator;
          let start = locator?.startOffset;
          let end = locator?.endOffset;
          if (!Number.isFinite(start) || !Number.isFinite(end)) {
            const text = node.text || node.title || '';
            const found = text ? currentSnapshot.body?.dataStream?.indexOf(text) ?? -1 : -1;
            if (found < 0) return;
            start = found;
            end = found + text.length - 1;
          }
          const renderManager = univerRef.current?.__getInjector().get(IRenderManagerService);
          const render = renderManager?.getRenderById(document.getId());
          document.setSelection(start as number, (end as number) + 1);
          render?.with(DocBackScrollRenderController)?.scrollToRange({
            startOffset: start as number,
            endOffset: (end as number) + 1,
            collapsed: start === end,
          });
        };
        selectNode();
        window.setTimeout(selectNode, 100);
      },
      async insertWordControl(role: BindingRole, fieldCode: string, dataPath: string) {
        const document = apiRef.current?.getActiveDocument();
        if (!document) throw new Error('Word 编辑器尚未就绪');
        const markerId = generateUUID();
        const placeholder = role === 'REPEAT_REGION' ? '重复内容' : role === 'CONDITIONAL' ? '条件内容' : '请输入值';
        const inserted = await apiRef.current?.executeCommand('doc.command.insert-custom-range', {
          unitId: document.getId(),
          rangeId: markerId,
          text: placeholder,
          wholeEntity: false,
          properties: { source: 'JSD_MAPPING', dataPath, fieldCode, role },
        });
        if (!inserted) throw new Error('字段插入失败，请重新选择正文位置后再试');
        onDirty();
        return {
          markerId,
          locatorType:
            role === 'REPEAT_REGION' ? 'REPEATING_CONTENT_CONTROL' : 'CONTENT_CONTROL_TAG',
          locator: { markerId, tag: fieldCode, dataPath, role },
        };
      },
    }),
    [onDirty],
  );

  return <div ref={containerRef} className="univer-editor-surface" aria-label="Word 模板编辑器" />;
});

function toEditorText(value: unknown) {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(value);
}

function normalizeDocumentSnapshot(snapshot: Record<string, unknown>) {
  const result = structuredClone(snapshot) as unknown as IDocumentData;
  // Univer's segment-aware document commands expect these collections to be
  // present even when the imported DOCX has no headers or footers. Keep them
  // empty for the body-only case so native tables, lists and styles can use
  // the normal body path safely.
  if (result.headers == null) result.headers = {};
  if (result.footers == null) result.footers = {};
  const style = result.documentStyle && typeof result.documentStyle === 'object'
    ? result.documentStyle
    : {};
  result.documentStyle = {
    ...style,
    documentFlavor: DocumentFlavor.MODERN,
    pageSize: style.pageSize && typeof style.pageSize === 'object'
      ? style.pageSize
      : { width: 595, height: 842 },
    marginTop: style.marginTop ?? 72,
    marginRight: style.marginRight ?? 72,
    marginBottom: style.marginBottom ?? 72,
    marginLeft: style.marginLeft ?? 72,
  };
  normalizeDocumentTextStyles(result as unknown as Record<string, unknown>);
  normalizeDocumentTableBorders(result as unknown as Record<string, unknown>);
  return result;
}

function normalizeDocumentTextStyles(snapshot: Record<string, unknown>) {
  const body = snapshot.body;
  if (!isRecord(body)) return;
  const textRuns = body.textRuns;
  if (Array.isArray(textRuns)) {
    for (const run of textRuns) {
      if (!isRecord(run) || !isRecord(run.ts)) continue;
      if (run.ts.ff == null) run.ts.ff = '宋体';
      if (run.ts.fs == null) run.ts.fs = 14;
    }
  }
  const paragraphs = body.paragraphs;
  if (!Array.isArray(paragraphs)) return;
  for (const paragraph of paragraphs) {
    if (!isRecord(paragraph) || !isRecord(paragraph.paragraphStyle)) continue;
    const textStyle = paragraph.paragraphStyle.textStyle;
    if (!isRecord(textStyle)) continue;
    if (textStyle.ff == null) textStyle.ff = '宋体';
    if (textStyle.fs == null) textStyle.fs = 14;
  }
}

function normalizeDocumentTableBorders(snapshot: Record<string, unknown>) {
  const tableSource = snapshot.tableSource;
  if (!isRecord(tableSource)) return;
  for (const table of Object.values(tableSource)) {
    if (!isRecord(table) || !Array.isArray(table.tableRows)) continue;
    for (const row of table.tableRows) {
      if (!isRecord(row) || !Array.isArray(row.tableCells)) continue;
      for (const cell of row.tableCells) {
        if (!isRecord(cell)) continue;
        for (const side of ['Top', 'Right', 'Bottom', 'Left']) {
          const key = `border${side}`;
          if (!isRecord(cell[key])) {
            cell[key] = {
              color: { rgb: '#000000' },
              width: { v: 1 },
              padding: 0,
              dashStyle: 1,
            };
          }
        }
      }
    }
  }
}

function preserveWordSnapshotMetadata(
  current: Record<string, unknown>,
  source: Record<string, unknown>,
) {
  const currentBody = current.body;
  const sourceBody = source.body;
  if (!isRecord(currentBody) || !isRecord(sourceBody)) return current;
  const sourceParagraphs = sourceBody.sourceParagraphs;
  const result = {
    ...current,
    snapshotFormatVersion: Math.max(
      Number(current.snapshotFormatVersion ?? 0),
      Number(source.snapshotFormatVersion ?? 5),
    ),
    editorMode: 'UNIVER_DOCS',
  } as Record<string, unknown>;
  const metadataKeys = ['wordImport', 'tableSource', 'resources', 'drawings', 'headers', 'footers'] as const;
  for (const key of metadataKeys) {
    if (source[key] !== undefined && current[key] === undefined) result[key] = structuredClone(source[key]);
  }
  if (!Array.isArray(sourceParagraphs)) return result;
  return {
    ...result,
    body: {
      ...currentBody,
      sourceParagraphs: structuredClone(sourceParagraphs),
    },
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function locateMarker(snapshot: IDocumentData, markerId?: string) {
  if (!markerId) return null;
  const stream = snapshot.body?.dataStream ?? '';
  const range = snapshot.body?.customRanges?.find((item) => item.rangeId === markerId);
  if (!range) return null;
  return {
    start: range.startIndex,
    end: range.endIndex + 1,
    value: stream.slice(range.startIndex, range.endIndex + 1),
  };
}

function readMarkerValue(api: FUniver, binding: TemplateBinding) {
  const document = api.getActiveDocument();
  const marker = document ? locateMarker(document.getSnapshot(), binding.markerId) : null;
  return marker?.value;
}

function documentContentSignature(snapshot: IDocumentData) {
  const body = snapshot.body;
  return JSON.stringify({
    dataStream: body?.dataStream,
    paragraphs: body?.paragraphs,
    textRuns: body?.textRuns,
    tables: body?.tables,
    customRanges: body?.customRanges,
    lists: snapshot.lists,
    tableSource: snapshot.tableSource,
  });
}
