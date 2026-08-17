import { Extension, mergeAttributes, Node } from '@tiptap/core';
import Link from '@tiptap/extension-link';
import { Table, TableCell, TableHeader, TableRow } from '@tiptap/extension-table';
import StarterKit from '@tiptap/starter-kit';

function attribute(attributes: unknown, name: string): unknown {
  if (!attributes || typeof attributes !== 'object') return undefined;
  return (attributes as Record<string, unknown>)[name];
}

function attributeText(attributes: unknown, name: string, fallback = ''): string {
  const value = attribute(attributes, name);
  return typeof value === 'string' || typeof value === 'number' ? String(value) : fallback;
}

const ReviewAttributes = Extension.create({
  name: 'reviewAttributes',
  addGlobalAttributes() {
    return [{
      types: ['paragraph', 'heading', 'blockquote', 'codeBlock', 'listItem', 'tableRow'],
      attributes: {
        // A split node keeps its source relation but must receive a fresh review identity.
        reviewNodeId: { default: null, keepOnSplit: false, renderHTML: (attrs) => {
          const id = attributeText(attrs, 'reviewNodeId');
          return id ? { 'data-review-node-id': id } : {};
        } },
        origin: { default: 'user', keepOnSplit: true, renderHTML: (attrs) => ({ 'data-origin': attributeText(attrs, 'origin', 'user') }) },
        sourceNodeKeys: { default: [], keepOnSplit: true, renderHTML: (attrs) => {
          const keys = attribute(attrs, 'sourceNodeKeys');
          return { 'data-source-node-keys': Array.isArray(keys) ? keys.filter((key): key is string => typeof key === 'string').join(',') : '' };
        } },
      },
    }];
  },
});

const Formula = Node.create({
  name: 'formula', group: 'block', content: 'text*',
  addAttributes() { return { reviewNodeId: { default: null }, origin: { default: 'user' }, sourceNodeKeys: { default: [] } }; },
  parseHTML() { return [{ tag: 'div[data-formula]' }]; },
  renderHTML({ HTMLAttributes }) { return ['div', mergeAttributes(HTMLAttributes, { 'data-formula': '', class: 'knowledge-formula' }), 0]; },
});

const AudioSegment = Node.create({
  name: 'audioSegment', group: 'block', content: 'text*',
  addAttributes() { return { reviewNodeId: { default: null }, origin: { default: 'source' }, sourceNodeKeys: { default: [] }, startMs: { default: null }, endMs: { default: null } }; },
  parseHTML() { return [{ tag: 'section[data-audio-segment]' }]; },
  renderHTML({ HTMLAttributes }) { return ['section', mergeAttributes(HTMLAttributes, { 'data-audio-segment': '', class: 'knowledge-audio-segment' }), 0]; },
});

const DataTableRef = Node.create({
  name: 'dataTableRef', group: 'block', atom: true, selectable: true,
  addAttributes() { return { reviewNodeId: { default: null }, origin: { default: 'source' }, sourceNodeKeys: { default: [] }, sourceTableId: { default: null }, sheetKey: { default: null }, sheetName: { default: null }, rowCount: { default: 0 }, columnCount: { default: 0 }, nonEmptyCount: { default: 0 } }; },
  parseHTML() { return [{ tag: 'div[data-table-ref]' }]; },
  renderHTML({ HTMLAttributes }) { const sheet = attributeText(HTMLAttributes, 'sheetName', '大型工作表'); return ['div', mergeAttributes(HTMLAttributes, { 'data-table-ref': '', class: 'knowledge-data-table-ref' }), ['strong', sheet], ['span', ` ${attributeText(HTMLAttributes, 'rowCount', '0')} 行 × ${attributeText(HTMLAttributes, 'columnCount', '0')} 列`]]; },
});

const SourceImage = Node.create({
  name: 'image', group: 'block', content: 'text*', selectable: true,
  addAttributes() { return { reviewNodeId: { default: null }, origin: { default: 'source' }, sourceNodeKeys: { default: [] } }; },
  parseHTML() { return [{ tag: 'figure[data-source-image]' }]; },
  renderHTML({ HTMLAttributes }) { return ['figure', mergeAttributes(HTMLAttributes, { 'data-source-image': '', class: 'knowledge-source-image' }), ['span', '图片'], ['figcaption', 0]]; },
});

export const knowledgeDocumentExtensions = [
  StarterKit.configure({ link: false }),
  Link.configure({ openOnClick: false, autolink: true }),
  Table.configure({ resizable: false }), TableRow, TableHeader, TableCell,
  ReviewAttributes, Formula, AudioSegment, DataTableRef, SourceImage,
];
