import { BoldOutlined, ItalicOutlined, OrderedListOutlined, StrikethroughOutlined, UnorderedListOutlined } from '@ant-design/icons';
import type { JSONContent } from '@tiptap/core';
import { EditorContent, useEditor } from '@tiptap/react';
import { Button, Divider, Space, Tooltip } from 'antd';
import { useEffect, useRef } from 'react';

import type { StructuredDocument } from '@/services/knowledge';
import { knowledgeDocumentExtensions } from './document-extensions';

export interface StructuredDocumentEditorProps {
  value: StructuredDocument;
  onChange: (value: StructuredDocument) => void;
  onSelectionChange?: (selection: { reviewNodeId?: string; sourceNodeKeys: string[]; origin?: string; type?: string }) => void;
  selectedSourceNodeKey?: string;
  disabled?: boolean;
}

const reviewable = new Set(['paragraph', 'heading', 'blockquote', 'codeBlock', 'listItem', 'tableRow', 'formula', 'audioSegment', 'dataTableRef']);

export function StructuredDocumentEditor({ value, onChange, onSelectionChange, selectedSourceNodeKey, disabled }: StructuredDocumentEditorProps) {
  const settingContent = useRef(false);
  const editor = useEditor({
    extensions: knowledgeDocumentExtensions,
    content: value,
    editable: !disabled,
    immediatelyRender: true,
    editorProps: { attributes: { class: 'knowledge-structured-content knowledge-structured-content--editing', 'aria-label': '完整解析结果' } },
    onUpdate: ({ editor: current }) => {
      if (settingContent.current) return;
      onChange(ensureReviewAttributes(current.getJSON() as StructuredDocument));
    },
    onSelectionUpdate: ({ editor: current }) => {
      const resolved = current.state.doc.resolve(current.state.selection.from);
      let fallback: { reviewNodeId?: string; sourceNodeKeys: string[]; origin?: string; type?: string } | undefined;
      for (let depth = resolved.depth; depth >= 0; depth -= 1) {
        const node = resolved.node(depth); const attrs = node.attrs as Record<string, unknown>;
        const sources = Array.isArray(attrs.sourceNodeKeys) ? attrs.sourceNodeKeys.filter((item): item is string => typeof item === 'string') : [];
        if (sources.length) {
          onSelectionChange?.({ reviewNodeId: typeof attrs.reviewNodeId === 'string' ? attrs.reviewNodeId : undefined, sourceNodeKeys: sources, origin: typeof attrs.origin === 'string' ? attrs.origin : undefined, type: node.type.name });
          return;
        }
        if (!fallback && typeof attrs.reviewNodeId === 'string') fallback = { reviewNodeId: attrs.reviewNodeId, sourceNodeKeys: [], origin: typeof attrs.origin === 'string' ? attrs.origin : undefined, type: node.type.name };
      }
      onSelectionChange?.(fallback || { sourceNodeKeys: [], origin: 'user' });
    },
  });

  useEffect(() => { editor?.setEditable(!disabled); }, [disabled, editor]);
  useEffect(() => {
    if (!editor) return;
    const current = JSON.stringify(editor.getJSON()); const next = JSON.stringify(value);
    if (current === next) return;
    settingContent.current = true; editor.commands.setContent(value, { emitUpdate: false }); settingContent.current = false;
  }, [editor, value]);
  useEffect(() => {
    if (!editor || !selectedSourceNodeKey) return;
    let target: number | undefined;
    editor.state.doc.descendants((node, pos) => {
      if (Array.isArray(node.attrs.sourceNodeKeys) && node.attrs.sourceNodeKeys.includes(selectedSourceNodeKey)) { target = pos + 1; return false; }
      return target === undefined;
    });
    if (target !== undefined) {
      editor.commands.setTextSelection(target);
      requestAnimationFrame(() => document.querySelector(`[data-source-node-keys*="${selectedSourceNodeKey}"]`)?.scrollIntoView({ block: 'center', behavior: 'smooth' }));
    }
  }, [editor, selectedSourceNodeKey]);

  if (!editor) return null;
  return <div className="knowledge-editor-shell">
    <div className="knowledge-editor-toolbar" role="toolbar" aria-label="文本格式">
      <Space size={2} split={<Divider type="vertical" />}>
        <Space size={2}>
          <Tooltip title="标题"><Button size="small" type={editor.isActive('heading') ? 'primary' : 'text'} onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()}>H2</Button></Tooltip>
          <Tooltip title="正文"><Button size="small" type={editor.isActive('paragraph') ? 'primary' : 'text'} onClick={() => editor.chain().focus().setParagraph().run()}>正文</Button></Tooltip>
        </Space>
        <Space size={2}>
          <Button aria-label="加粗" size="small" type={editor.isActive('bold') ? 'primary' : 'text'} icon={<BoldOutlined />} onClick={() => editor.chain().focus().toggleBold().run()} />
          <Button aria-label="斜体" size="small" type={editor.isActive('italic') ? 'primary' : 'text'} icon={<ItalicOutlined />} onClick={() => editor.chain().focus().toggleItalic().run()} />
          <Button aria-label="删除线" size="small" type={editor.isActive('strike') ? 'primary' : 'text'} icon={<StrikethroughOutlined />} onClick={() => editor.chain().focus().toggleStrike().run()} />
        </Space>
        <Space size={2}>
          <Button aria-label="无序列表" size="small" type={editor.isActive('bulletList') ? 'primary' : 'text'} icon={<UnorderedListOutlined />} onClick={() => editor.chain().focus().toggleBulletList().run()} />
          <Button aria-label="有序列表" size="small" type={editor.isActive('orderedList') ? 'primary' : 'text'} icon={<OrderedListOutlined />} onClick={() => editor.chain().focus().toggleOrderedList().run()} />
        </Space>
      </Space>
    </div>
    <EditorContent editor={editor} />
  </div>;
}

function ensureReviewAttributes(document: StructuredDocument): StructuredDocument {
  const visit = (node: JSONContent): JSONContent => {
    const content = node.content?.map(visit);
    if (!reviewable.has(node.type || '')) return { ...node, content };
    const attrs = { ...(node.attrs || {}) };
    if (!attrs.reviewNodeId) attrs.reviewNodeId = crypto.randomUUID();
    if (!attrs.origin) attrs.origin = 'user';
    if (!Array.isArray(attrs.sourceNodeKeys)) attrs.sourceNodeKeys = [];
    return { ...node, attrs, content };
  };
  return visit(document) as StructuredDocument;
}
