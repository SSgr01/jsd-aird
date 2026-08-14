import type { DocumentStructure, DocumentStructureNode } from './types';

/**
 * 从 Univer Docs 编辑器快照推导文档结构（大纲 / 章节树）。
 *
 * 后端项目文档链路目前不会为 DOCX 生成 contentStructure，导致目录面板
 * 一直显示「0 个结构节点」。前端可直接依据快照里的段落样式
 * （headingId / namedStyleType）与 dataStream 还原标题层级，作为兜底。
 */
export function deriveDocumentStructureFromSnapshot(
  snapshot: Record<string, unknown> | undefined,
  documentId?: string,
): DocumentStructure | undefined {
  if (!snapshot || typeof snapshot !== 'object') return undefined;
  const body = snapshot.body as
    | { dataStream?: unknown; paragraphs?: unknown[]; tables?: unknown[] }
    | undefined;
  if (!body || typeof body.dataStream !== 'string') return undefined;

  const dataStream = body.dataStream;
  const paragraphs = Array.isArray(body.paragraphs) ? (body.paragraphs as Array<Record<string, unknown>>) : [];
  if (paragraphs.length === 0) return undefined;

  // 段落按 startIndex 升序，便于截取文本。
  const ordered = [...paragraphs]
    .filter((p) => typeof p.startIndex === 'number')
    .sort((a, b) => (a.startIndex as number) - (b.startIndex as number));

  const nodes: DocumentStructureNode[] = [];
  let headingCount = 0;
  let order = 0;

  ordered.forEach((paragraph, index) => {
    // paragraphs[i].startIndex 指向该段段落结束符（\r）所在位置。
    // 因此段落文本区间为：0 段落 [0, startIndex]，后续段落 (prevStart+1, startIndex]。
    const endIndex = typeof paragraph.startIndex === 'number' ? paragraph.startIndex : 0;
    const previous = ordered[index - 1];
    const previousStart = previous && typeof previous.startIndex === 'number' ? previous.startIndex : 0;
    const startIndex = index === 0 ? 0 : previousStart + 1;
    const raw = dataStream.slice(startIndex, endIndex).replace(/\r+$/g, '');
    const text = raw.trim();
    if (!text) return;

    const style = (paragraph.paragraphStyle ?? {}) as Record<string, unknown>;
    const headingId = style.headingId;
    const hasHeading = typeof headingId === 'string' && headingId.length > 0;
    if (!hasHeading) return;

    // Univer namedStyleType 当前实践中：4=标题1，5=标题2，依此类推。
    const namedStyleType = typeof style.namedStyleType === 'number' ? style.namedStyleType : 0;
    const level = namedStyleType >= 4 ? namedStyleType - 3 : 1;

    headingCount += 1;
    nodes.push({
      nodeId: `heading-${endIndex}`,
      type: 'HEADING',
      level,
      title: text,
      text,
      sortOrder: order++,
      sourceLocator: { paragraphStart: startIndex, headingId },
      editorLocator: { startOffset: startIndex, endOffset: endIndex, snapshotRevision: 0 },
    });
  });

  return {
    schemaVersion: 1,
    documentType: 'WORD',
    documentId,
    nodeCount: nodes.length,
    headingCount,
    nodes,
  };
}
