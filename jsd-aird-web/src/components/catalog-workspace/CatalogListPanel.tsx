import { Alert, Button, Empty, Skeleton, Tag, Typography } from 'antd';
import type { ReactNode } from 'react';

interface CatalogListPanelProps {
  title: string;
  count?: number;
  filters?: ReactNode;
  actions?: ReactNode;
  loading?: boolean;
  error?: string;
  onRetry?: () => void;
  empty?: ReactNode;
  children: ReactNode;
}

export function CatalogListPanel({ title, count, filters, actions, loading, error, onRetry, empty, children }: CatalogListPanelProps) {
  return (
    <section className="catalog-list-panel" aria-label={title}>
      <div className="catalog-list-heading">
        <div><Typography.Title level={3}>{title}</Typography.Title>{typeof count === 'number' && <Tag color="green">共 {count} 条</Tag>}</div>
        <div className="catalog-list-filters">{filters}</div>
      </div>
      <div className="catalog-list-actions">{actions}</div>
      {loading ? (
        <div className="catalog-list-state catalog-list-loading" role="status" aria-label="正在加载列表">
          <Skeleton active title={false} paragraph={{ rows: 6, width: ['100%', '92%', '96%', '88%', '94%', '72%'] }} />
        </div>
      ) : error ? (
        <div className="catalog-list-state">
          <Alert
            type="error"
            showIcon
            message="资料列表加载失败"
            description={error}
            action={onRetry ? <Button size="small" onClick={onRetry}>重新加载</Button> : undefined}
          />
        </div>
      ) : children || <div className="catalog-list-state">{empty || <Empty description="暂无符合条件的记录" />}</div>}
    </section>
  );
}
