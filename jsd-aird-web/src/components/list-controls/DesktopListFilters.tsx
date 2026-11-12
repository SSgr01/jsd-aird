import { Button, DatePicker } from 'antd';
import { Children, isValidElement, type ReactElement, type ReactNode } from 'react';
import dayjs from 'dayjs';
import { ListFilterBar } from './ListFilterBar';
import { useDesktopLayout } from '@/hooks/use-desktop-layout';

type ControlProps = {
  children?: ReactNode;
  value?: unknown;
  placeholder?: ReactNode;
  'aria-label'?: string;
  options?: { value?: unknown; label?: ReactNode; options?: ControlProps['options'] }[];
};

function formatValue(value: unknown): string {
  if (dayjs.isDayjs(value)) return value.format('YYYY/MM/DD');
  return typeof value === 'string' || typeof value === 'number' ? String(value) : '';
}

function selectedLabel(control: ReactElement<ControlProps>): string {
  const value = control.props.value;
  const options = control.props.options?.flatMap(option => option.options || [option]) || [];
  const label = options.find(option => option.value === value)?.label;
  if (typeof label === 'string') return label;
  if (Array.isArray(value)) return value.map(formatValue).join(' – ');
  return formatValue(value);
}

/** Recompose existing controlled inputs on desktop; retain their original layout on smaller screens. */
export function DesktopListFilters({ children, labels = [], advancedAfter = 2, activeCount, summary: appliedSummary }: {
  children: ReactElement<{ children?: ReactNode }>;
  labels?: string[];
  advancedAfter?: number;
  activeCount?: number;
  summary?: string;
}) {
  const desktop = useDesktopLayout();
  if (!desktop) return children;
  const controls = Children.toArray(children.props.children).filter(isValidElement) as ReactElement<ControlProps>[];
  const search = controls[0];
  const actions = controls.slice(1).filter(control => control.type === Button);
  const fields = controls.slice(1).filter(control => control.type !== Button);
  const active = controls.filter(control => {
    const value = control.props.value;
    return value !== undefined && value !== null && value !== '' && value !== 'ALL'
      && (!Array.isArray(value) || value.some(Boolean));
  });
  const summary = active.map(control => {
    const index = fields.indexOf(control);
    const label = index < 0 ? '搜索' : labels[index] || control.props['aria-label'] || control.props.placeholder;
    return `${typeof label === 'string' ? label : '筛选'}：${selectedLabel(control)}`;
  }).join('；');
  return <ListFilterBar className="desktop-list-filters" search={search} fields={fields.map((control, index) => ({
    key: control.key?.toString() || String(index),
    label: labels[index] || control.props['aria-label'] || (typeof control.props.placeholder === 'string' ? control.props.placeholder : '筛选条件'),
    control,
    wide: control.type === DatePicker.RangePicker,
    advanced: index >= advancedAfter,
  }))} activeCount={activeCount ?? active.length} summary={appliedSummary ?? summary} actions={actions} />;
}
