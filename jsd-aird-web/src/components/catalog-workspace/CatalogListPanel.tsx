import { Empty, Spin, Tag, Typography } from 'antd';
import type { ReactNode } from 'react';

interface CatalogListPanelProps {
  title: string;
  count?: number;
  filters?: ReactNode;
  actions?: ReactNode;
  loading?: boolean;
  empty?: ReactNode;
  children: ReactNode;
}

export function CatalogListPanel({ title, count, filters, actions, loading, empty, children }: CatalogListPanelProps) {
  return (
    <section className="catalog-list-panel" aria-label={title}>
      <div className="catalog-list-heading">
        <div><Typography.Title level={3}>{title}</Typography.Title>{typeof count === 'number' && <Tag color="green">共 {count} 条</Tag>}</div>
        <div className="catalog-list-filters">{filters}</div>
      </div>
      <div className="catalog-list-actions">{actions}</div>
      {loading ? <div className="catalog-list-state"><Spin /></div> : children || <div className="catalog-list-state">{empty || <Empty description="暂无符合条件的记录" />}</div>}
    </section>
  );
}
