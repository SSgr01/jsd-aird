import {
  createUniver,
  LocaleType,
  mergeLocales,
  type FUniver,
  type IDocumentData,
} from '@univerjs/presets';
import { UniverDocsCorePreset } from '@univerjs/preset-docs-core';
import UniverPresetDocsCoreZhCN from '@univerjs/preset-docs-core/locales/zh-CN';
import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';

import '@univerjs/preset-docs-core/lib/index.css';

import type { BindingRole, EditorHandle, TemplateBinding } from './types';

interface Props {
  snapshot: Record<string, unknown>;
  onDirty: () => void;
  onEditorValue: (binding: TemplateBinding, value: unknown) => void;
  bindings: TemplateBinding[];
}

export const UniverDocsEditor = forwardRef<EditorHandle, Props>(function UniverDocsEditor(
  { snapshot, onDirty, onEditorValue, bindings },
  ref,
) {
  const containerRef = useRef<HTMLDivElement>(null);
  const univerRef = useRef<ReturnType<typeof createUniver>['univer']>();
  const apiRef = useRef<FUniver>();
  const bindingsRef = useRef(bindings);
  const callbacksRef = useRef({ onDirty, onEditorValue });
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
    let commandSubscription: { dispose(): void } | undefined;
    let ownedUniver: ReturnType<typeof createUniver>['univer'] | undefined;
    let ownedApi: FUniver | undefined;

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
          [LocaleType.ZH_CN]: mergeLocales(UniverPresetDocsCoreZhCN),
        },
        presets: [
          UniverDocsCorePreset({
            container: host,
            ribbonType: 'collapsed',
            header: true,
            footer: true,
          }),
        ],
      });
      ownedUniver = univer;
      ownedApi = univerAPI;
      univerRef.current = univer;
      apiRef.current = univerAPI;
      univerAPI.createUniverDoc(normalizeDocumentSnapshot(snapshot));
      commandSubscription = univerAPI.onCommandExecuted(() => {
        callbacksRef.current.onDirty();
        for (const binding of bindingsRef.current) {
          if (binding.syncDirection === 'DATA_TO_EDITOR') continue;
          callbacksRef.current.onEditorValue(binding, readMarkerValue(univerAPI, binding));
        }
      });
    };
    animationFrame = window.requestAnimationFrame(initialize);

    return () => {
      cancelled = true;
      window.cancelAnimationFrame(animationFrame);
      commandSubscription?.dispose();
      if (apiRef.current === ownedApi) apiRef.current = undefined;
      if (univerRef.current === ownedUniver) univerRef.current = undefined;
      window.setTimeout(() => {
        ownedUniver?.dispose();
        if (host.parentNode) host.parentNode.removeChild(host);
      }, 32);
    };
  }, [snapshot]);

  useImperativeHandle(
    ref,
    () => ({
      getSnapshot() {
        const document = apiRef.current?.getActiveDocument();
        return (document?.getSnapshot() ?? {}) as Record<string, unknown>;
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
      async insertWordControl(role: BindingRole, fieldCode: string, dataPath: string) {
        const document = apiRef.current?.getActiveDocument();
        if (!document) throw new Error('Word 编辑器尚未就绪');
        const markerId = crypto.randomUUID();
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
  const style = result.documentStyle;
  if (!style || typeof style.pageSize !== 'object') {
    result.documentStyle = {
      ...style,
      pageSize: { width: 595, height: 842 },
      marginTop: style?.marginTop ?? 72,
      marginRight: style?.marginRight ?? 72,
      marginBottom: style?.marginBottom ?? 72,
      marginLeft: style?.marginLeft ?? 72,
    };
  }
  return result;
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
