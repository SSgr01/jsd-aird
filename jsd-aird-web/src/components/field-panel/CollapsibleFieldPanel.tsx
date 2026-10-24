import { DoubleLeftOutlined, DoubleRightOutlined } from '@ant-design/icons';
import { Button } from 'antd';
import { useId } from 'react';
import type { ReactNode } from 'react';

import { useAppStore } from '@/stores/app-store';

export function FieldPanelToggle({ controls }: { controls: string }) {
  const collapsed = useAppStore((state) => state.fieldPanelCollapsed);
  const setCollapsed = useAppStore((state) => state.setFieldPanelCollapsed);
  const label = collapsed ? '展开字段面板' : '收起字段面板';

  return <Button
    type="text"
    className="field-panel-toggle"
    icon={collapsed ? <DoubleLeftOutlined /> : <DoubleRightOutlined />}
    title={label}
    aria-label={label}
    aria-expanded={!collapsed}
    aria-controls={controls}
    onClick={() => setCollapsed(!collapsed)}
  />;
}

export function CollapsibleFieldPanel({ tabs, children, className = '' }: {
  tabs: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  const collapsed = useAppStore((state) => state.fieldPanelCollapsed);
  const contentId = useId();

  return (
    <aside className={`template-field-manager collapsible-field-panel ${className}`} aria-label="字段管理" data-collapsed={collapsed}>
      <div className="field-manager-heading">
        <FieldPanelToggle controls={contentId} />
        {!collapsed && tabs}
      </div>
      {collapsed && <span className="field-panel-collapsed-label" aria-hidden="true">字段管理</span>}
      <div id={contentId} className="field-manager-content" hidden={collapsed}>
        {children}
      </div>
    </aside>
  );
}
