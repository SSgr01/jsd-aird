import { MoreOutlined } from '@ant-design/icons';
import { Button, Popover } from 'antd';
import { useId, useState, type ReactNode } from 'react';
import './row-actions.css';

/** Secondary controls remain mounted so their dialogs and confirmations keep working. */
export function RowActions({ primary, children, label = '更多操作' }: {
  primary: ReactNode;
  children: ReactNode;
  label?: string;
}) {
  const [open, setOpen] = useState(false);
  const controlsId = useId();
  return <div className="list-row-actions" onClick={event => event.stopPropagation()}>
    {primary}
    <Popover trigger="click" placement="bottomRight" open={open} onOpenChange={setOpen}
      content={<div id={controlsId} className="list-row-action-menu" role="group" aria-label={label}>{children}</div>}>
      <Button type="text" icon={<MoreOutlined />} aria-label={label} aria-expanded={open} aria-controls={open ? controlsId : undefined} />
    </Popover>
  </div>;
}
