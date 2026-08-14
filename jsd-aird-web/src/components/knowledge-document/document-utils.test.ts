import { describe, expect, it } from 'vitest';

import type { SourceNode, StructuredDocument } from '@/services/knowledge';
import { anchorLabel, findNodeByReviewId, nodeSources, sourceNodeMap } from './document-utils';

describe('knowledge document source relations', () => {
  const document: StructuredDocument = {
    type: 'doc', schemaVersion: 1, content: [{
      type: 'bulletList', content: [{
        type: 'listItem', attrs: { reviewNodeId: 'review-1', origin: 'source', sourceNodeKeys: ['source-a', 'source-b'] },
        content: [{ type: 'paragraph', content: [{ type: 'text', text: '确认内容' }] }],
      }],
    }],
  };

  it('keeps many-to-many source keys while locating nested review nodes', () => {
    const node = findNodeByReviewId(document, 'review-1');
    expect(node?.type).toBe('listItem');
    expect(nodeSources(node)).toEqual(['source-a', 'source-b']);
  });

  it('formats all supported source anchor kinds', () => {
    const nodes: SourceNode[] = [
      source('page', { version: 1, kind: 'page', page: 3 }),
      source('sheet', { version: 1, kind: 'sheet_range', sheetName: '配方', range: 'B2:D4' }),
      source('audio', { version: 1, kind: 'time_range', startMs: 62_000, endMs: 65_000 }),
    ];
    const values = sourceNodeMap(nodes);

    expect(anchorLabel(values.get('page'))).toBe('原文件第 3 页');
    expect(anchorLabel(values.get('sheet'))).toBe('配方!B2:D4');
    expect(anchorLabel(values.get('audio'))).toBe('音频 01:02–01:05');
    expect(anchorLabel()).toBe('人工补充内容，无原文位置');
  });
});

function source(sourceNodeKey: string, sourceAnchor: SourceNode['sourceAnchor']): SourceNode {
  return { sourceNodeKey, nodeNo: 0, nodeType: 'paragraph', rawText: '', sourceAnchor, confidence: {} };
}
