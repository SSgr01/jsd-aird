import { Alert, Button, Spin } from 'antd';

interface ListQueryState {
  isPending: boolean;
  isFetching: boolean;
  isError: boolean;
  data: unknown;
  error: unknown;
  refetch: () => unknown;
}

/** An error is never represented as an empty list; stale rows stay usable. */
export function ListQueryFeedback({ query }: { query: ListQueryState }) {
  return <ListFeedback loading={query.isPending || query.isFetching} hasData={query.data !== undefined}
    error={query.isError ? (query.error instanceof Error ? query.error.message : '请稍后重试') : undefined}
    onRetry={() => void query.refetch()} />;
}

export function ListFeedback({ loading, hasData, error, onRetry }: {
  loading: boolean; hasData: boolean; error?: string; onRetry: () => void;
}) {
  if (error) return <Alert className="list-query-feedback" type="error" showIcon
    message={hasData ? '列表更新失败，当前显示上次加载的内容' : '列表加载失败'} description={error}
    action={<Button size="small" loading={loading} onClick={onRetry}>重试</Button>} />;
  if (loading && !hasData) return <div className="list-query-feedback list-query-loading" role="status"><Spin size="small" /><span>正在加载列表…</span></div>;
  if (loading) return <div className="list-query-feedback list-query-refresh" role="status"><Spin size="small" /><span>正在更新…</span></div>;
  return null;
}
