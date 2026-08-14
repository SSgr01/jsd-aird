import type { SourceNode, StructuredDocument, StructuredNode } from '@/services/knowledge';

export const nodeSources = (node?: StructuredNode) => Array.isArray(node?.attrs?.sourceNodeKeys)
  ? node.attrs.sourceNodeKeys.filter((value): value is string => typeof value === 'string') : [];

export const nodeReviewId = (node?: StructuredNode) => typeof node?.attrs?.reviewNodeId === 'string'
  ? node.attrs.reviewNodeId : undefined;

export function findNodeByReviewId(document: StructuredDocument, reviewNodeId?: string): StructuredNode | undefined {
  if (!reviewNodeId) return undefined;
  const visit = (nodes?: StructuredNode[]): StructuredNode | undefined => {
    for (const node of nodes || []) {
      if (nodeReviewId(node) === reviewNodeId) return node;
      const nested = visit(node.content); if (nested) return nested;
    }
    return undefined;
  };
  return visit(document.content);
}

export function sourceNodeMap(nodes: SourceNode[]) { return new Map(nodes.map((node) => [node.sourceNodeKey, node])); }

export function anchorLabel(node?: SourceNode) {
  const anchor = node?.sourceAnchor;
  if (!anchor) return '人工补充内容，无原文位置';
  if (anchor.kind === 'page' || anchor.kind === 'page_region') return `原文件第 ${anchor.page || 1} 页`;
  if (anchor.kind === 'sheet_range') return `${anchor.sheetName || '工作表'}${anchor.range ? `!${anchor.range}` : ''}`;
  if (anchor.kind === 'docx_path') return anchor.paragraphId ? `Word · ${anchor.paragraphId}` : 'Word 原文位置';
  if (anchor.kind === 'time_range') return `音频 ${formatTime(anchor.startMs || 0)}${anchor.endMs ? `–${formatTime(anchor.endMs)}` : ''}`;
  return '原文件无精确位置';
}

const formatTime = (value: number) => `${Math.floor(value / 60000).toString().padStart(2, '0')}:${Math.floor(value / 1000 % 60).toString().padStart(2, '0')}`;
