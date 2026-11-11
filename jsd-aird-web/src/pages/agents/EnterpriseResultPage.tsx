import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Alert, Spin } from 'antd';
import { httpClient } from '@/services/http/client';
import { MarkdownContent } from '@/components/markdown/MarkdownContent';
import type { ApiResponse } from '@/types/api';

// Business reports retain their source checks. Files use the native dsh preview.
export function EnterpriseResultPage() {
  const { id } = useParams();
  const [value, setValue] = useState<{ title: string; text: string; evidence?: Record<string, {source: {kind: string; document_id: string; version_id: string; anchor?: {page?: number; original?: {page?: number}}}}> }>();
  const [error, setError] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    setValue(undefined);
    setError(false);
    httpClient.get<ApiResponse<NonNullable<typeof value>>>(`/api/v1/agents/research/report/${id}`, { signal: controller.signal })
      .then(response => setValue(response.data.data))
      .catch(() => { if (!controller.signal.aborted) setError(true); });
    return () => controller.abort();
  }, [id]);
  if (error) return <Alert type="error" message="报告不可用，或当前权限已变化。" />;
  if (!value) return <Spin />;
  const text = value.text.replace(/\[E:(e[0-9a-f]{24})\]/g, (original, id: string) => {
    const source = value.evidence?.[id]?.source;
    if (source?.kind !== 'KB_PUBLICATION') return original;
    const page = source.anchor?.original?.page ?? source.anchor?.page;
    const url = `/api/v1/knowledge/documents/${source.document_id}/versions/${source.version_id}/content${page ? `#page=${page}` : ''}`;
    return `[原文${page ? `·第${page}页` : ''}](${url})`;
  });
  return <article style={{ maxWidth: 900, padding: 24, margin: 'auto' }}><h1>{value.title}</h1><MarkdownContent value={text} /></article>;
}
