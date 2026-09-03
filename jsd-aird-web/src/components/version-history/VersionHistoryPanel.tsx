import { Empty, Typography } from 'antd';
import type { ReactNode } from 'react';
import './VersionHistoryPanel.css';

export interface VersionHistoryItem {
  id: string;
  versionNo: number;
  createdBy?: string;
  createdAt: string;
}

interface VersionHistoryPanelProps<T extends VersionHistoryItem> {
  versions: T[];
  onSelect: (version: T) => void;
  description?: ReactNode;
  emptyDescription?: ReactNode;
  getLabel?: (version: T) => ReactNode;
}

export function VersionHistoryPanel<T extends VersionHistoryItem>({
  versions,
  onSelect,
  description = '每次发布都会保留完整数据快照，可追溯发布人和发布时间。',
  emptyDescription = '暂无版本记录',
  getLabel = () => '发布版本',
}: VersionHistoryPanelProps<T>) {
  return (
    <section className="version-history-panel">
      <Typography.Title level={4}>版本记录</Typography.Title>
      <Typography.Text type="secondary">{description}</Typography.Text>
      {versions.length ? (
        <div className="version-history-list">
          {versions.map((version) => (
            <button
              type="button"
              className="version-history-card"
              key={version.id}
              onClick={() => onSelect(version)}
            >
              <span className="version-history-no">V{version.versionNo}</span>
              <span className="version-history-meta">
                <b>{getLabel(version)}</b>
                <small>
                  {version.createdBy || '未知'} · {new Date(version.createdAt).toLocaleString()}
                </small>
              </span>
            </button>
          ))}
        </div>
      ) : (
        <Empty description={emptyDescription} />
      )}
    </section>
  );
}
