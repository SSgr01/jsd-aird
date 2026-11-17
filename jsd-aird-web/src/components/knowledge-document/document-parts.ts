import type { StructuredDocument, StructuredNode } from '@/services/knowledge';

export function documentParts(value: StructuredDocument): StructuredNode[] {
  const content = value.content || [];
  return content.length && content.every((node) => node.type === 'documentPart') ? content : [];
}

export function partDocument(value: StructuredDocument, index: number): StructuredDocument {
  const parts = documentParts(value);
  return parts.length ? { ...value, content: parts[index]?.content || [] } : value;
}

export function replaceDocumentPart(value: StructuredDocument, index: number, updated: StructuredDocument): StructuredDocument {
  const parts = documentParts(value);
  if (!parts.length) return updated;
  return { ...value, content: parts.map((part, position) => position === index ? { ...part, content: updated.content || [] } : part) };
}

export function containsSource(node: StructuredNode, key: string): boolean {
  return (Array.isArray(node.attrs?.sourceNodeKeys) && node.attrs.sourceNodeKeys.includes(key))
    || !!node.content?.some((child) => containsSource(child, key));
}
