import { Pagination } from 'antd';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { StructuredDocument } from '@/services/knowledge';
import { containsSource, documentParts, partDocument, replaceDocumentPart } from './document-parts';

export function DocumentParts({ value, onChange, selectedSourceNodeKey, children }: {
  value: StructuredDocument;
  onChange?: (value: StructuredDocument) => void;
  selectedSourceNodeKey?: string;
  children: (page: StructuredDocument, change: (page: StructuredDocument) => void, key: string) => ReactNode;
}) {
  const [requestedIndex, setIndex] = useState(0);
  const latest = useRef(value);
  latest.current = value;
  const parts = documentParts(value);
  const index = Math.min(requestedIndex, Math.max(0, parts.length - 1));
  useEffect(() => {
    if (!selectedSourceNodeKey) return;
    const found = documentParts(latest.current).findIndex((part) => containsSource(part, selectedSourceNodeKey));
    if (found >= 0) setIndex(found);
  }, [selectedSourceNodeKey, parts.length]);
  return <>
    {parts.length > 1 && <Pagination size="small" current={index + 1} pageSize={1} total={parts.length}
      showSizeChanger={false} showQuickJumper showTotal={(total) => `共 ${total} 段`}
      onChange={(page) => setIndex(page - 1)} style={{ marginBottom: 12 }} />}
    {children(partDocument(value, index), (updated) => onChange?.(replaceDocumentPart(value, index, updated)), parts.length ? `part-${index}` : 'whole')}
  </>;
}
