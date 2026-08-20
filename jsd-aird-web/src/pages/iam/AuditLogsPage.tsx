import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { App, Button, Card, Input, Space, Table, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';

import { iamApi } from '@/services/iam/iam-api';
import { HttpError } from '@/services/http/errors';
import './iam.css';

interface AuditRow {
  id: string;
  actorId?: string;
  action: string;
  aggregateType: string;
  aggregateId: string;
  detail: Record<string, unknown>;
  createdAt: string;
}

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
    <div className="iam-page">
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
          rowKey="id"
          loading={loading}
          dataSource={rows}
          columns={[
            {
              title: '时间',
              dataIndex: 'createdAt',
              render: (value: string) => new Date(value).toLocaleString('zh-CN'),
            },
            {
              title: '动作',
              dataIndex: 'action',
              render: (value: string) => <Tag color="blue">{value}</Tag>,
            },
            {
              title: '对象',
              key: 'aggregate',
              render: (_: unknown, row: AuditRow) => `${row.aggregateType} / ${row.aggregateId}`,
            },
            {
              title: '操作人',
              dataIndex: 'actorId',
              render: (value?: string) => value || '匿名请求',
            },
            {
              title: '详情',
              dataIndex: 'detail',
              render: (value: Record<string, unknown>) => (
                <Typography.Text type="secondary">{JSON.stringify(value)}</Typography.Text>
              ),
            },
          ]}
          pagination={false}
        />
      </Card>
    </div>
  );
}
