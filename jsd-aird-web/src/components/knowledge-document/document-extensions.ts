import { Extension, Mark, mergeAttributes, Node, type NodeViewRendererProps } from '@tiptap/core';
import Link from '@tiptap/extension-link';
import { Table, TableCell, TableHeader, TableRow } from '@tiptap/extension-table';
import StarterKit from '@tiptap/starter-kit';

import { renderLatexInto } from '@/components/markdown/latex-rendering';

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

const Superscript = Mark.create({
  name: 'superscript',
  parseHTML() { return [{ tag: 'sup' }]; },
  renderHTML({ HTMLAttributes }) { return ['sup', mergeAttributes(HTMLAttributes), 0]; },
});

const Subscript = Mark.create({
  name: 'subscript',
  parseHTML() { return [{ tag: 'sub' }]; },
  renderHTML({ HTMLAttributes }) { return ['sub', mergeAttributes(HTMLAttributes), 0]; },
});

function rawLatex(node: NodeViewRendererProps['node']) {
  const attributeValue = attribute(node.attrs, 'latexRaw');
  return typeof attributeValue === 'string' && attributeValue.length ? attributeValue : node.textContent;
}

function mathNodeView(displayMode: boolean) {
  return ({ node: initialNode }: NodeViewRendererProps) => {
    let currentNode = initialNode;
    const dom = document.createElement(displayMode ? 'div' : 'span');
    dom.contentEditable = 'false';
    dom.className = displayMode ? 'knowledge-formula' : 'knowledge-inline-math';
    if (displayMode) dom.setAttribute('data-formula', ''); else dom.setAttribute('data-inline-math', '');
    renderLatexInto(dom, rawLatex(currentNode), displayMode);
    return {
      dom,
      update(nextNode: NodeViewRendererProps['node']) {
        if (nextNode.type !== currentNode.type) return false;
        currentNode = nextNode;
        renderLatexInto(dom, rawLatex(currentNode), displayMode);
        return true;
      },
    };
  };
}

const InlineMath = Node.create({
  name: 'inlineMath', group: 'inline', inline: true, atom: true, selectable: true,
  addAttributes() { return { latexRaw: { default: '' } }; },
  parseHTML() { return [{ tag: 'span[data-inline-math]' }]; },
  renderHTML({ HTMLAttributes }) { return ['span', mergeAttributes(HTMLAttributes, { 'data-inline-math': '', class: 'knowledge-inline-math' })]; },
  addNodeView() { return mathNodeView(false); },
});

const Formula = Node.create({
  name: 'formula', group: 'block', content: 'text*',
  addAttributes() { return { reviewNodeId: { default: null }, origin: { default: 'user' }, sourceNodeKeys: { default: [] }, latexRaw: { default: '' } }; },
  parseHTML() { return [{ tag: 'div[data-formula]' }]; },
  renderHTML({ HTMLAttributes }) { return ['div', mergeAttributes(HTMLAttributes, { 'data-formula': '', class: 'knowledge-formula' }), 0]; },
  addNodeView() { return mathNodeView(true); },
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
  name: 'image', group: 'block', content: 'inline*', selectable: true,
  addAttributes() {
    return {
      reviewNodeId: { default: null }, origin: { default: 'source' }, sourceNodeKeys: { default: [] },
      assetFileId: { default: null }, resultFileId: { default: null }, resultEntryPath: { default: null },
      caption: { default: '' }, footnote: { default: '' }, ocrText: { default: '' },
      visualType: { default: 'IMAGE' },
    };
  },
  parseHTML() { return [{ tag: 'figure[data-source-image]' }]; },
  renderHTML({ HTMLAttributes }) {
    const assetFileId = attributeText(HTMLAttributes, 'assetFileId');
    const caption = attributeText(HTMLAttributes, 'caption');
    const visualType = attributeText(HTMLAttributes, 'visualType', 'IMAGE').toUpperCase();
    const label = visualType === 'CHART' ? '图表' : '图片';
    const children = assetFileId
      ? [['img', { src: `/api/v1/knowledge/assets/${assetFileId}/content`, alt: caption || label, loading: 'lazy' }], ['figcaption', 0]]
      : [['span', label], ['figcaption', 0]];
    return ['figure', mergeAttributes(HTMLAttributes, { 'data-source-image': '', 'data-visual-type': visualType, class: 'knowledge-source-image' }), ...children];
  },
});

export const knowledgeDocumentExtensions = [
  StarterKit.configure({ link: false }),
  Link.configure({ openOnClick: false, autolink: true }),
  Table.configure({ resizable: false }), TableRow, TableHeader, TableCell,
  ReviewAttributes, Superscript, Subscript, InlineMath, Formula, AudioSegment, DataTableRef, SourceImage,
];
