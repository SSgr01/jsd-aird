import { FilterOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button } from 'antd';
import { useId, useState, type ReactNode } from 'react';
import './list-filter-bar.css';
import { useDesktopLayout } from '@/hooks/use-desktop-layout';

interface FilterField {
  key: string;
  label: string;
  control: ReactNode;
  wide?: boolean;
  advanced?: boolean;
}

interface ListFilterBarProps {
  search: ReactNode;
  fields: FilterField[];
  activeCount: number;
  summary?: string;
  canReset?: boolean;
  onReset?: () => void;
  actions?: ReactNode;
  className?: string;
}

/** Keep search in reach; disclose the same filter controls on narrow screens. */
export function ListFilterBar({ search, fields, activeCount, summary, canReset, onReset, actions, className = '' }: ListFilterBarProps) {
  const [expanded, setExpanded] = useState(false);
  const fieldsId = useId();
  const desktop = useDesktopLayout();
  const hasAdvanced = fields.some(field => field.advanced);
  const toggle = <Button className="list-filter-toggle" icon={<FilterOutlined />} aria-controls={fieldsId}
    aria-expanded={expanded} onClick={() => setExpanded(value => !value)}>
    <span className="list-filter-toggle-mobile">筛选</span><span className="list-filter-toggle-desktop">{expanded ? '收起筛选' : '更多筛选'}</span>{activeCount > 0 && <span className="list-filter-count">{activeCount}</span>}
  </Button>;
  const reset = <>
    {onReset && <Button className="list-filter-reset" icon={<ReloadOutlined />} disabled={!canReset} onClick={onReset}>重置</Button>}
    {actions && <div className="list-filter-actions">{actions}</div>}
  </>;
  return <div className={`list-filter-bar ${className}`} data-expanded={expanded} data-has-advanced={hasAdvanced}>
    <div className="list-filter-search">{search}</div>
    {!desktop && toggle}
    <div className="list-filter-fields" id={fieldsId}>
      {fields.map(field => <div className="list-filter-field" role="group" aria-label={field.label} data-wide={field.wide || undefined} data-advanced={field.advanced || undefined} key={field.key}>
        <span className="list-filter-label" aria-hidden="true">{field.label}</span>
        {field.control}
      </div>)}
      {!desktop && reset}
    </div>
    {desktop && <div className="list-filter-tail">{hasAdvanced && toggle}{reset}</div>}
    {activeCount > 0 && <div className="list-filter-summary">
      <span title={summary}>{summary || `已设置 ${activeCount} 项筛选`}</span>
      {onReset && <Button type="text" onClick={onReset}>重置</Button>}
    </div>}
  </div>;
}
