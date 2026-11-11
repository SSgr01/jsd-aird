import { FilterOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button } from 'antd';
import { useId, useState, type ReactNode } from 'react';
import './list-filter-bar.css';

interface FilterField {
  key: string;
  label: string;
  control: ReactNode;
  wide?: boolean;
}

interface ListFilterBarProps {
  search: ReactNode;
  fields: FilterField[];
  activeCount: number;
  summary?: string;
  canReset: boolean;
  onReset: () => void;
  className?: string;
}

/** Keep search in reach; disclose the same filter controls on narrow screens. */
export function ListFilterBar({ search, fields, activeCount, summary, canReset, onReset, className = '' }: ListFilterBarProps) {
  const [expanded, setExpanded] = useState(false);
  const fieldsId = useId();
  return <div className={`list-filter-bar ${className}`} data-expanded={expanded}>
    <div className="list-filter-search">{search}</div>
    <Button className="list-filter-toggle" icon={<FilterOutlined />} aria-controls={fieldsId}
      aria-expanded={expanded} onClick={() => setExpanded(value => !value)}>
      筛选{activeCount > 0 && <span className="list-filter-count">{activeCount}</span>}
    </Button>
    <div className="list-filter-fields" id={fieldsId}>
      {fields.map(field => <div className="list-filter-field" data-wide={field.wide || undefined} key={field.key}>
        <span className="list-filter-label" aria-hidden="true">{field.label}</span>
        {field.control}
      </div>)}
      <Button className="list-filter-reset" icon={<ReloadOutlined />} disabled={!canReset} onClick={onReset}>重置</Button>
    </div>
    {activeCount > 0 && <div className="list-filter-summary">
      <span title={summary}>{summary || `已设置 ${activeCount} 项筛选`}</span>
      <Button type="text" onClick={onReset}>重置</Button>
    </div>}
  </div>;
}
