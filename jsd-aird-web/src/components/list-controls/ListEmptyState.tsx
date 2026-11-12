import { Button, Empty } from 'antd';
import type { ReactNode } from 'react';
import './list-empty-state.css';

interface ListEmptyStateProps {
  description: ReactNode;
  filtered?: boolean;
  onReset?: () => void;
  action?: ReactNode;
  className?: string;
  children?: ReactNode;
}

/** Use only after loading succeeds; query errors keep their retry feedback. */
export function ListEmptyState({ description, filtered = false, onReset, action, children, className = '' }: ListEmptyStateProps) {
  return <Empty className={`list-empty-state ${className}`} image={Empty.PRESENTED_IMAGE_SIMPLE}
    description={description}>
    {filtered && onReset ? <Button onClick={onReset}>清除筛选</Button> : action || children}
  </Empty>;
}
