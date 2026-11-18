import { ExclamationCircleOutlined, InfoCircleOutlined, LoadingOutlined } from '@ant-design/icons';
import type { ReactNode } from 'react';
import './inline-notice.css';

export function InlineNotice({
  children,
  tone = 'info',
  busy = false,
  action,
  details,
  detailsLabel = '处理详情',
}: {
  children: ReactNode;
  tone?: 'info' | 'warning' | 'error';
  busy?: boolean;
  action?: ReactNode;
  details?: ReactNode;
  detailsLabel?: string;
}) {
  return (
    <div className={`inline-notice inline-notice--${tone}`}>
      <div className="inline-notice-row">
        <span className="inline-notice-icon" aria-hidden="true">
          {busy ? (
            <LoadingOutlined spin />
          ) : tone === 'info' ? (
            <InfoCircleOutlined />
          ) : (
            <ExclamationCircleOutlined />
          )}
        </span>
        <div className="inline-notice-message" role={tone === 'error' ? 'alert' : 'status'}>
          {children}
        </div>
        {action && <div className="inline-notice-action">{action}</div>}
      </div>
      {details && (
        <details className="inline-notice-details">
          <summary>{detailsLabel}</summary>
          <div>{details}</div>
        </details>
      )}
    </div>
  );
}
