import {
  AlignCenterOutlined,
  AlignLeftOutlined,
  AlignRightOutlined,
  BoldOutlined,
  FontSizeOutlined,
  ItalicOutlined,
  UnderlineOutlined,
} from '@ant-design/icons';
import { Button, Input, Select, Space, Tag, Tooltip, Typography } from 'antd';
import { renderAsync } from 'docx-preview';
import { forwardRef, useCallback, useEffect, useImperativeHandle, useRef, useState } from 'react';

import { generateUUID } from '@/utils/uuid';

import type { BindingRole, DocumentStructure, EditorHandle, TemplateBinding } from './types';
import type { WordPatchOperation } from './WordTemplateEditor';

interface Props {
  versionId: string;
  snapshot: Record<string, unknown>;
  documentStructure?: DocumentStructure;
  editable: boolean;
  bindings: TemplateBinding[];
  onDirty: () => void;
  onEditorValue: (binding: TemplateBinding, value: unknown) => void;
  onEditorLabel?: (binding: TemplateBinding, value: unknown) => void;
  onPatch?: (operation: WordPatchOperation) => void;
  loadPreview: (versionId: string) => Promise<Blob>;
}

interface TargetSelection {
  targetId: string;
  text: string;
  element?: HTMLElement;
}

/**
 * DOCX is rendered from the original OOXML artifact. React only adds a thin
 * overlay for stable field markers and selection; it never rebuilds the page
 * from paragraphs, so pagination, fonts, tables and images remain authoritative.
 */
export const WordNativePreview = forwardRef<EditorHandle, Props>(function WordNativePreview(
  { versionId, snapshot, documentStructure, editable, bindings, onDirty, onEditorValue, onEditorLabel, onPatch, loadPreview },
  ref,
) {
  const bodyRef = useRef<HTMLDivElement>(null);
  const styleRef = useRef<HTMLDivElement>(null);
  const selectionRef = useRef<TargetSelection>();
  const [selected, setSelected] = useState<TargetSelection>();
  const [textDraft, setTextDraft] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();

  useEffect(() => setTextDraft(selected?.text ?? ''), [selected]);

  const decorateControls = () => {
    const root = bodyRef.current;
    if (!root) return;
    const controls = documentStructure?.contentControls ?? [];
    const used = new Set<string>();
    controls.forEach((control) => {
      const markerId = control.markerId || control.contentControlId || control.nodeId;
      const text = (control.text || control.alias || control.tag || '').trim();
      const element = text ? wrapFirstText(root, text, markerId, control.nodeId, editable) : undefined;
      if (element) used.add(control.nodeId);
    });
    // Existing bindings can point to a marker which has no visible text (for
    // example an empty content control). Give it a marker on the nearest page.
    bindings.forEach((binding) => {
      const markerId = bindingMarker(binding);
      if (!markerId || root.querySelector(`[data-word-marker-id="${cssEscape(markerId)}"]`)) return;
      const locatorText = [binding.locator?.text, binding.locator?.alias, binding.locator?.tag]
        .find((value): value is string => typeof value === 'string' && value.trim().length > 0)?.trim();
      const element = locatorText
        ? wrapFirstText(root, locatorText, markerId, bindingNodeId(binding), editable)
        : undefined;
      if (element) return;
      const paragraph = root.querySelector('p');
      if (paragraph) {
        paragraph.setAttribute('data-word-marker-id', markerId);
        paragraph.setAttribute('data-word-node-id', bindingNodeId(binding));
        paragraph.setAttribute('data-word-virtual-marker', 'true');
      }
    });
    void used;
  };

  useEffect(() => {
    let active = true;
    const load = async () => {
      setLoading(true);
      setError(undefined);
      try {
        const blob = await loadPreview(versionId);
        if (!active || !bodyRef.current || !styleRef.current) return;
        bodyRef.current.replaceChildren();
        styleRef.current.replaceChildren();
        await renderAsync(blob, bodyRef.current, styleRef.current, {
          breakPages: true,
          renderHeaders: true,
          renderFooters: true,
          renderFootnotes: true,
          renderEndnotes: true,
          useBase64URL: true,
        });
        if (active) decorateControls();
      } catch (reason) {
        if (active) setError(reason instanceof Error ? reason.message : 'Word 原版式预览失败');
      } finally {
        if (active) setLoading(false);
      }
    };
    void load();
    return () => { active = false; };
  }, [versionId, documentStructure, bindings, loadPreview]);

  const emit = useCallback((operation: WordPatchOperation) => {
    onPatch?.({
      ...operation,
      baseStructureHash: operation.baseStructureHash ?? documentStructure?.structureHash,
    });
    onDirty();
  }, [documentStructure?.structureHash, onDirty, onPatch]);

  const pickTarget = useCallback((target?: EventTarget | null) => {
    if (!(target instanceof HTMLElement)) return;
    const marker = target.closest<HTMLElement>('[data-word-marker-id]');
    const node = target.closest<HTMLElement>('[data-word-node-id]');
    const element = marker || node || target.closest<HTMLElement>('td, p');
    if (!element) return;
    const selectedText = window.getSelection()?.toString().trim() || '';
    const selectedAnchor = selectedText
      ? documentStructure?.anchors?.find((anchor) =>
          (anchor.kind === 'TEXT' || anchor.kind === 'RUN') && anchor.text?.trim() === selectedText)
      : undefined;
    const nodeId = selectedAnchor?.nodeId || element.dataset.wordNodeId || inferNodeId(element, documentStructure);
    if (!nodeId) return;
    const selection = { targetId: nodeId, text: selectedText || element.textContent?.trim() || '', element };
    selectionRef.current = selection;
    setSelected(selection);
  }, [documentStructure]);

  const flushControlInput = useCallback((target?: EventTarget | null) => {
    if (!(target instanceof HTMLElement)) return;
    const control = target.closest<HTMLElement>('[data-word-marker-id]');
    if (!control || control.dataset.wordVirtualMarker === 'true') return;
    const markerId = control.dataset.wordMarkerId;
    if (!markerId) return;
    const currentText = control.textContent ?? '';
    const originalText = control.dataset.wordOriginalText ?? '';
    if (currentText === originalText || control.dataset.wordPatchPending !== 'true') return;
    emit({
      type: 'REPLACE_CONTENT_CONTROL',
      targetId: markerId,
      text: currentText,
      baseText: originalText,
    });
    const binding = bindings.find((candidate) => bindingMarker(candidate) === markerId);
    if (binding) {
      const locatorText = typeof binding.locator?.text === 'string' ? binding.locator.text.trim() : '';
      const locatorAlias = typeof binding.locator?.alias === 'string' ? binding.locator.alias.trim() : '';
      const locatorTag = typeof binding.locator?.tag === 'string' ? binding.locator.tag.trim() : '';
      const isLabelControl = [locatorText, locatorAlias, locatorTag]
        .filter(Boolean)
        .some((value) => value === originalText.trim());
      if (isLabelControl) onEditorLabel?.(binding, currentText);
      else onEditorValue(binding, currentText);
    }
    control.dataset.wordOriginalText = currentText;
    delete control.dataset.wordPatchPending;
  }, [bindings, emit, onEditorLabel, onEditorValue]);

  const emitRunStyle = (style: WordPatchOperation) => {
    if (!editable || !selected || !canStyleTarget(selected.targetId)) return;
    emit({ ...style, targetId: selected.targetId });
    applyLocalStyle(selected.element, style);
  };

  const emitParagraphAlignment = (alignment: string) => {
    if (!editable || !selected) return;
    const targetId = paragraphTargetId(selected.element, selected.targetId, documentStructure);
    if (!targetId) return;
    emit({ type: 'SET_PARAGRAPH_ALIGNMENT', targetId, alignment });
    const paragraph = selected.element?.closest<HTMLElement>('p');
    if (paragraph) paragraph.style.textAlign = alignment;
  };

  const replaceSelectedText = () => {
    if (!editable || !selected || !canReplaceText(selected)) return;
    const markerId = selected.element?.dataset.wordMarkerId;
    if (markerId && selected.element?.dataset.wordVirtualMarker !== 'true') {
      emit({
        type: 'REPLACE_CONTENT_CONTROL',
        targetId: markerId,
        text: textDraft,
        baseText: selected.text,
      });
    } else {
      emit({
        type: 'REPLACE_TEXT',
        targetId: selected.targetId,
        text: textDraft,
        baseText: selected.text,
      });
    }
    if (selected.element) {
      selected.element.textContent = textDraft;
      selected.element.dataset.wordOriginalText = textDraft;
    }
    setSelected((current) => current ? { ...current, text: textDraft } : current);
  };

  // Browser selection changes are the reliable signal for selecting a range in
  // a rendered DOCX.  We only update the selection state here; ordinary prose
  // input never creates a field automatically.
  useEffect(() => {
    const handleSelectionChange = () => {
      const root = bodyRef.current;
      const range = window.getSelection();
      if (!root || !range || !range.anchorNode || !root.contains(range.anchorNode)) return;
      pickTarget(range.anchorNode.parentElement ?? range.anchorNode);
    };
    document.addEventListener('selectionchange', handleSelectionChange);
    return () => document.removeEventListener('selectionchange', handleSelectionChange);
  }, [pickTarget]);

  useImperativeHandle(ref, () => ({
    getSnapshot: () => snapshot,
    readBinding: (binding) => {
      const marker = bindingMarker(binding);
      const element = marker
        ? bodyRef.current?.querySelector<HTMLElement>(`[data-word-marker-id="${cssEscape(marker)}"]`)
        : undefined;
      return element?.dataset.wordVirtualMarker === 'true'
        ? binding.locator?.text
        : element?.textContent ?? binding.locator?.text;
    },
    writeBinding: (binding, value) => {
      const marker = bindingMarker(binding);
      if (!marker) return Promise.resolve();
      const element = bodyRef.current?.querySelector<HTMLElement>(`[data-word-marker-id="${cssEscape(marker)}"]`);
      const text = typeof value === 'string' ? value : JSON.stringify(value ?? '') ?? '';
      emit({
        type: 'REPLACE_CONTENT_CONTROL',
        targetId: marker,
        text,
        baseText: element?.dataset.wordVirtualMarker === 'true' ? undefined : element?.textContent || undefined,
      });
      onEditorValue(binding, value);
      return Promise.resolve();
    },
    focusBinding: (binding) => {
      const marker = bindingMarker(binding);
      const element = marker
        ? bodyRef.current?.querySelector<HTMLElement>(`[data-word-marker-id="${cssEscape(marker)}"]`)
        : undefined;
      element?.scrollIntoView({ block: 'center', behavior: 'smooth' });
      if (element) {
        element.classList.add('word-native-focus');
        window.setTimeout(() => element.classList.remove('word-native-focus'), 1600);
      }
    },
    insertWordControl: (role: BindingRole, fieldCode: string, dataPath: string) => {
      const target = selectionRef.current;
      if (!target) return Promise.reject(new Error('请先在 Word 中选择文本或点击插入位置'));
      const markerId = generateUUID();
      emit({
        type: 'INSERT_CONTENT_CONTROL',
        targetId: target.targetId,
        markerId,
        tag: fieldCode,
        alias: fieldCode,
        role,
        dataPath,
        text: target.text,
        baseText: target.text,
      });
      target.element?.setAttribute('data-word-marker-id', markerId);
      target.element?.setAttribute('data-word-node-id', target.targetId);
      selectionRef.current = undefined;
      setSelected(undefined);
      return Promise.resolve({
        markerId,
        locatorType: 'DOCX_CONTENT_CONTROL',
        locator: {
          nodeId: target.targetId,
          markerId,
          locatorType: 'DOCX_CONTENT_CONTROL',
          targetId: target.targetId,
          tag: fieldCode,
          dataPath,
          role,
        },
      });
    },
  }), [bindings, documentStructure, emit, onEditorValue, onDirty, onPatch, snapshot]);

  return (
    <div className="word-native-editor" aria-label="Word 原版式预览">
      <div className="word-native-toolbar">
        <Space size="small" wrap>
          <Tag color={editable ? 'processing' : 'default'}>{editable ? '原版式字段编辑' : '只读预览'}</Tag>
          {selected && <Typography.Text type="secondary">已选择 {selected.targetId}{selected.text ? `：“${selected.text.slice(0, 24)}”` : ''}</Typography.Text>}
          {editable && <Typography.Text type="secondary">选择文本后可编辑文字和样式；选中位置后可从右侧插入字段</Typography.Text>}
          <Tooltip title="加粗"><Button size="small" disabled={!editable || !selected || !canStyleTarget(selected.targetId)} icon={<BoldOutlined />} onClick={() => emitRunStyle({ type: 'SET_RUN_STYLE', targetId: selected?.targetId ?? '', bold: true })} /></Tooltip>
          <Tooltip title="斜体"><Button size="small" disabled={!editable || !selected || !canStyleTarget(selected.targetId)} icon={<ItalicOutlined />} onClick={() => emitRunStyle({ type: 'SET_RUN_STYLE', targetId: selected?.targetId ?? '', italic: true })} /></Tooltip>
          <Tooltip title="下划线"><Button size="small" disabled={!editable || !selected || !canStyleTarget(selected.targetId)} icon={<UnderlineOutlined />} onClick={() => emitRunStyle({ type: 'SET_RUN_STYLE', targetId: selected?.targetId ?? '', underline: true })} /></Tooltip>
          <Select
            size="small"
            aria-label="字号"
            placeholder={<><FontSizeOutlined /> 字号</>}
            disabled={!editable || !selected || !canStyleTarget(selected.targetId)}
            options={[10, 11, 12, 14, 16, 18, 20, 24].map((value) => ({ label: `${value} pt`, value }))}
            onChange={(value: number) => emitRunStyle({ type: 'SET_RUN_STYLE', targetId: selected?.targetId ?? '', fontSize: value })}
            style={{ width: 82 }}
          />
          <Select
            size="small"
            aria-label="字体"
            placeholder="字体"
            disabled={!editable || !selected || !canStyleTarget(selected.targetId)}
            options={['宋体', '微软雅黑', '黑体', 'Arial', 'Calibri'].map((value) => ({ label: value, value }))}
            onChange={(value: string) => emitRunStyle({ type: 'SET_RUN_STYLE', targetId: selected?.targetId ?? '', fontFamily: value })}
            style={{ width: 96 }}
          />
          <Input
            size="small"
            aria-label="字体颜色"
            type="color"
            disabled={!editable || !selected || !canStyleTarget(selected.targetId)}
            onChange={(event) => emitRunStyle({ type: 'SET_RUN_STYLE', targetId: selected?.targetId ?? '', color: event.target.value })}
            style={{ width: 34, padding: 2 }}
          />
          <Tooltip title="左对齐"><Button size="small" disabled={!editable || !selected} icon={<AlignLeftOutlined />} onClick={() => emitParagraphAlignment('left')} /></Tooltip>
          <Tooltip title="居中"><Button size="small" disabled={!editable || !selected} icon={<AlignCenterOutlined />} onClick={() => emitParagraphAlignment('center')} /></Tooltip>
          <Tooltip title="右对齐"><Button size="small" disabled={!editable || !selected} icon={<AlignRightOutlined />} onClick={() => emitParagraphAlignment('right')} /></Tooltip>
          {selected && canReplaceText(selected) && (
            <Space.Compact>
              <Input size="small" aria-label="替换选中文本" value={textDraft} onChange={(event) => setTextDraft(event.target.value)} onPressEnter={replaceSelectedText} style={{ width: 220 }} />
              <Button size="small" type="primary" disabled={!editable} onClick={replaceSelectedText}>应用文字</Button>
            </Space.Compact>
          )}
        </Space>
      </div>
      {error && <div className="word-native-error">{error}</div>}
      {loading && <div className="word-native-loading">正在加载 Word 原版式…</div>}
      <div ref={styleRef} className="word-native-style-host" aria-hidden="true" />
      <div
        ref={bodyRef}
        className="word-native-document"
        onClick={(event) => pickTarget(event.target)}
        onMouseUp={(event) => pickTarget(event.target)}
        onInput={(event) => {
          const target = event.target as HTMLElement;
          const control = target.closest<HTMLElement>('[data-word-marker-id]');
          if (control && control.dataset.wordVirtualMarker !== 'true') {
            control.dataset.wordPatchPending = 'true';
          }
        }}
        onBlur={(event) => flushControlInput(event.target)}
      />
      {!loading && !error && !bodyRef.current?.childElementCount && <div className="word-template-empty">Word 文档没有可预览内容</div>}
      <Button className="word-native-selection-reset" size="small" onClick={() => { selectionRef.current = undefined; setSelected(undefined); }} disabled={!selected}>
        清除选区
      </Button>
    </div>
  );
});

function wrapFirstText(root: HTMLElement, text: string, markerId: string, nodeId: string, editable: boolean) {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  let current: Node | null;
  while ((current = walker.nextNode())) {
    const value = current.textContent || '';
    const index = value.indexOf(text);
    if (index < 0 || !current.parentNode) continue;
    const range = document.createRange();
    range.setStart(current, index);
    range.setEnd(current, index + text.length);
    const span = document.createElement('span');
    span.className = 'word-native-control';
    span.dataset.wordMarkerId = markerId;
    span.dataset.wordNodeId = nodeId;
    span.dataset.wordOriginalText = text;
    span.contentEditable = editable ? 'true' : 'false';
    try {
      range.surroundContents(span);
      return span;
    } catch {
      // Word labels can be split across multiple OOXML text nodes. Preserve
      // the original render and let the user bind the location manually.
      return undefined;
    }
  }
  return undefined;
}

function inferNodeId(element: HTMLElement, structure?: DocumentStructure) {
  const text = element.textContent?.trim() || '';
  const match = structure?.anchors?.find((anchor) => anchor.text?.trim() === text);
  return match?.nodeId || structure?.blocks?.find((block) => block.text?.trim() === text)?.id;
}

function canStyleTarget(targetId: string) {
  return /^(text|run|content-control)-/.test(targetId);
}

function canReplaceText(selection: TargetSelection) {
  return canStyleTarget(selection.targetId)
    || Boolean(
      selection.element?.dataset.wordMarkerId
      && selection.element.dataset.wordVirtualMarker !== 'true',
    );
}

function paragraphTargetId(
  element: HTMLElement | undefined,
  targetId: string,
  structure?: DocumentStructure,
) {
  if (targetId.startsWith('paragraph-')) return targetId;
  const paragraph = element?.closest<HTMLElement>('p');
  const text = paragraph?.textContent?.trim() || '';
  const anchor = structure?.anchors?.find(
    (item) => item.kind === 'PARAGRAPH' && item.text?.trim() === text,
  );
  if (anchor?.nodeId?.startsWith('paragraph-')) return anchor.nodeId;
  const block = structure?.blocks?.find(
    (item) => item.type === 'PARAGRAPH' && item.text?.trim() === text,
  );
  return block?.id?.startsWith('paragraph-') ? block.id : undefined;
}

function applyLocalStyle(element: HTMLElement | undefined, operation: WordPatchOperation) {
  if (!element) return;
  if (operation.bold !== undefined) element.style.fontWeight = operation.bold ? '700' : '400';
  if (operation.italic !== undefined) element.style.fontStyle = operation.italic ? 'italic' : 'normal';
  if (operation.underline !== undefined) element.style.textDecoration = operation.underline ? 'underline' : 'none';
  if (operation.color) element.style.color = operation.color;
  if (operation.fontSize) element.style.fontSize = `${operation.fontSize}pt`;
  if (operation.fontFamily) element.style.fontFamily = operation.fontFamily;
}

function cssEscape(value: string) {
  return value.replace(/(["\\])/g, '\\$1');
}

function bindingMarker(binding: TemplateBinding) {
  if (binding.markerId) return binding.markerId;
  const marker = binding.locator?.markerId;
  return typeof marker === 'string' ? marker : '';
}

function bindingNodeId(binding: TemplateBinding) {
  const nodeId = binding.locator?.nodeId;
  return typeof nodeId === 'string' ? nodeId : binding.markerId || '';
}
