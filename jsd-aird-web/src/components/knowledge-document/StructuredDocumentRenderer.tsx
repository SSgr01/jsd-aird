import { EditorContent, useEditor } from '@tiptap/react';
import { useEffect, useRef } from 'react';

import type { StructuredDocument } from '@/services/knowledge';
import { knowledgeDocumentExtensions } from './document-extensions';
import { DocumentParts } from './DocumentParts';
import './knowledge-document.css';

type RendererProps = { value: StructuredDocument; onSourceSelect?: (sourceNodeKeys: string[]) => void; selectedSourceNodeKey?: string };

export function StructuredDocumentRenderer(props: RendererProps) {
  return <DocumentParts value={props.value} selectedSourceNodeKey={props.selectedSourceNodeKey}>
    {(page, _change, key) => <StructuredDocumentPage key={key} {...props} value={page} />}
  </DocumentParts>;
}

function StructuredDocumentPage({ value, onSourceSelect, selectedSourceNodeKey }: RendererProps) {
  const renderedValue = useRef(value);
  const container = useRef<HTMLDivElement>(null);
  const editor = useEditor({ extensions: knowledgeDocumentExtensions, content: value, editable: false, immediatelyRender: true, editorProps: { attributes: { class: 'knowledge-structured-content knowledge-structured-content--readonly', 'aria-label': '已发布内容' }, handleClick: (_view, _pos, event) => { const element = (event.target as HTMLElement).closest<HTMLElement>('[data-source-node-keys]'); const sources = element?.dataset.sourceNodeKeys?.split(',').filter(Boolean) || []; if (sources.length) onSourceSelect?.(sources); return false; } } });
  useEffect(() => { if (editor && renderedValue.current !== value) { renderedValue.current = value; editor.commands.setContent(value, { emitUpdate: false }); } }, [value, editor]);
  useEffect(() => {
    if (!container.current || !selectedSourceNodeKey) return;
    Array.from(container.current.querySelectorAll<HTMLElement>('[data-source-node-keys]'))
      .find((element) => element.dataset.sourceNodeKeys?.split(',').includes(selectedSourceNodeKey))
      ?.scrollIntoView({ block: 'center', behavior: 'auto' });
  }, [editor, value, selectedSourceNodeKey]);
  return <div className="knowledge-renderer" ref={container}><EditorContent editor={editor} /></div>;
}
