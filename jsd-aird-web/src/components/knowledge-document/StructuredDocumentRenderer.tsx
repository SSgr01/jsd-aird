import { EditorContent, useEditor } from '@tiptap/react';
import { useEffect } from 'react';

import type { StructuredDocument } from '@/services/knowledge';
import { knowledgeDocumentExtensions } from './document-extensions';

export function StructuredDocumentRenderer({ value, onSourceSelect, selectedSourceNodeKey }: { value: StructuredDocument; onSourceSelect?: (sourceNodeKeys: string[]) => void; selectedSourceNodeKey?: string }) {
  const editor = useEditor({ extensions: knowledgeDocumentExtensions, content: value, editable: false, immediatelyRender: true, editorProps: { attributes: { class: 'knowledge-structured-content knowledge-structured-content--readonly', 'aria-label': '已发布内容' }, handleClick: (_view, _pos, event) => { const element = (event.target as HTMLElement).closest<HTMLElement>('[data-source-node-keys]'); const sources = element?.dataset.sourceNodeKeys?.split(',').filter(Boolean) || []; if (sources.length) onSourceSelect?.(sources); return false; } } });
  useEffect(() => { if (editor && JSON.stringify(editor.getJSON()) !== JSON.stringify(value)) editor.commands.setContent(value, { emitUpdate: false }); }, [value, editor]);
  useEffect(() => { if (!selectedSourceNodeKey) return; document.querySelector(`[data-source-node-keys*="${selectedSourceNodeKey}"]`)?.scrollIntoView({ block: 'center', behavior: 'smooth' }); }, [selectedSourceNodeKey]);
  return <div className="knowledge-renderer"><EditorContent editor={editor} /></div>;
}
