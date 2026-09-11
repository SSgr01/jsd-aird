import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { App, Button, Card, Empty, Input, Space, Spin, Table, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';

import { iamApi } from '@/services/iam/iam-api';
import { HttpError } from '@/services/http/errors';
import './iam.css';
import '@/styles/management-list.css';

interface AuditRow {
  id: string;
  actorId?: string;
  action: string;
  aggregateType: string;
  aggregateId: string;
  detail: Record<string, unknown>;
  createdAt: string;
}

const formatAuditTime = (value: string) => new Date(value).toLocaleString('zh-CN');
const formatAuditDetail = (value: Record<string, unknown>) => JSON.stringify(value);

export function AuditLogsPage() {
  const { message } = App.useApp();
  const [rows, setRows] = useState<AuditRow[]>([]);
  const [action, setAction] = useState('');
  const [loading, setLoading] = useState(false);
  const load = async () => {
    setLoading(true);
    try {
      setRows(await iamApi.auditLogs({ action: action || undefined }));
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '操作日志加载失败');
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => {
    void load();
  }, []);
  return (
    <div className="iam-page pm-unified-list-page iam-audit-page">
      <div className="page-heading">
        <div>
          <Typography.Title level={2}>操作日志</Typography.Title>
          <Typography.Text type="secondary">
            记录账号、权限和高风险业务操作，支持按动作检索。
          </Typography.Text>
        </div>
        <Button icon={<ReloadOutlined />} onClick={() => void load()}>
          刷新
        </Button>
      </div>
      <Card className="iam-card" variant="borderless">
        <Space className="iam-toolbar">
          <Input
            allowClear
            prefix={<SearchOutlined />}
            value={action}
            onChange={(event) => setAction(event.target.value)}
            onPressEnter={() => void load()}
            placeholder="输入动作编码，例如 IAM_USER_UPDATED"
          />
          <Button type="primary" onClick={() => void load()}>
            查询
          </Button>
        </Space>
        <Table
          className="iam-audit-table"
          rowKey="id"
          loading={loading}
          dataSource={rows}
          columns={[
            {
              title: '时间',
              dataIndex: 'createdAt',
              width: 180,
              render: (value: string) => formatAuditTime(value),
            },
            {
              title: '动作',
              dataIndex: 'action',
              width: 190,
              render: (value: string) => <Tag color="blue">{value}</Tag>,
            },
            {
              title: '对象',
              key: 'aggregate',
              width: 260,
              render: (_: unknown, row: AuditRow) => `${row.aggregateType} / ${row.aggregateId}`,
            },
            {
              title: '操作人',
              dataIndex: 'actorId',
              width: 140,
              render: (value?: string) => value || '匿名请求',
            },
            {
              title: '详情',
              dataIndex: 'detail',
              width: 360,
              render: (value: Record<string, unknown>) => (
                <Typography.Text className="iam-audit-detail" type="secondary">
                  {formatAuditDetail(value)}
                </Typography.Text>
              ),
            },
          ]}
          scroll={{ x: 1130 }}
          pagination={false}
        />
        <div className="iam-audit-mobile-list" aria-label="操作日志列表">
          {loading ? (
            <div className="iam-audit-mobile-state">
              <Spin />
              <Typography.Text type="secondary">日志加载中…</Typography.Text>
            </div>
          ) : rows.length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无操作日志" />
          ) : (
            rows.map((row) => (
              <article className="iam-audit-mobile-item" key={row.id}>
                <div className="iam-audit-mobile-header">
                  <Typography.Text type="secondary">{formatAuditTime(row.createdAt)}</Typography.Text>
                  <Tag color="blue">{row.action}</Tag>
                </div>
                <dl className="iam-audit-mobile-fields">
                  <div className="iam-audit-mobile-field">
                    <dt>对象</dt>
                    <dd>{row.aggregateType} / {row.aggregateId}</dd>
                  </div>
                  <div className="iam-audit-mobile-field">
                    <dt>操作人</dt>
                    <dd>{row.actorId || '匿名请求'}</dd>
                  </div>
                  <div className="iam-audit-mobile-field iam-audit-mobile-detail-field">
                    <dt>详情</dt>
                    <dd><Typography.Text className="iam-audit-mobile-detail" type="secondary">{formatAuditDetail(row.detail)}</Typography.Text></dd>
                  </div>
                </dl>
              </article>
            ))
          )}
        </div>
      </Card>
    </div>
  );
}
