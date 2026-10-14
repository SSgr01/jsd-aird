import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { App, Button, Card, Collapse, DatePicker, Descriptions, Empty, Input, Pagination, Select, Space, Spin, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';

import type { RangePickerProps } from 'antd/es/date-picker';
import { iamApi } from '@/services/iam/iam-api';
import type { AuditLogTechnicalDetail, AuditLogView } from '@/services/iam/iam-api';
import { HttpError } from '@/services/http/errors';
import './iam.css';
import '@/styles/management-list.css';

const PAGE_SIZE = 20;
type AuditDateRange = Parameters<NonNullable<RangePickerProps['onChange']>>[0];

const moduleOptions = [
  { value: 'IAM', label: '系统设置' },
  { value: 'CUSTOMER', label: '客户管理' },
  { value: 'PROJECT', label: '项目管理' },
  { value: 'EXPERIMENT', label: '电子实验记录本' },
  { value: 'QUALITY', label: '品管部数据' },
  { value: 'SPECTRUM', label: 'AI图谱中心' },
  { value: 'RESEARCH_TEST', label: '研发测试中心' },
  { value: 'AI', label: 'AI研发助手' },
  { value: 'DATA', label: '数据中心' },
  { value: 'KB', label: '研发知识库' },
  { value: 'TEMPLATE', label: '模板中心' },
  { value: 'PRODUCTION', label: '生产单管理' },
  { value: 'INVENTORY', label: '库存管理' },
];

const operationOptions = [
  { value: 'CREATE', label: '新增/复制' },
  { value: 'UPDATE', label: '修改/保存' },
  { value: 'DELETE', label: '删除' },
  { value: 'PERMISSION', label: '权限变更' },
  { value: 'STATUS', label: '状态变更' },
  { value: 'IMPORT', label: '导入/导出' },
  { value: 'AI', label: 'AI操作' },
];

type SearchQuery = {
  keyword?: string;
  module?: string;
  operation?: string;
  operator?: string;
  from?: string;
  to?: string;
};

const formatAuditTime = (value: string) => new Date(value).toLocaleString('zh-CN');

function TechnicalDetails({ detail }: { detail: AuditLogTechnicalDetail }) {
  const fields = Object.entries(detail.fields ?? {});
  return (
    <Collapse
      className="iam-audit-technical"
      ghost
      size="small"
      items={[{
        key: 'technical',
        label: '技术信息',
        children: (
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="日志编号">{detail.auditId}</Descriptions.Item>
            <Descriptions.Item label="动作编码">{detail.actionCode}</Descriptions.Item>
            <Descriptions.Item label="对象类型">{detail.aggregateType}</Descriptions.Item>
            <Descriptions.Item label="对象编号">{detail.aggregateId}</Descriptions.Item>
            {fields.map(([key, value]) => <Descriptions.Item key={key} label={key}>{value}</Descriptions.Item>)}
          </Descriptions>
        ),
      }]}
    />
  );
}

function AuditContent({ row }: { row: AuditLogView }) {
  return (
    <div className="iam-audit-content">
      <Typography.Text strong>{row.summary}</Typography.Text>
      <Typography.Text className="iam-audit-object" type="secondary">
        {row.objectType}：{row.objectName}
      </Typography.Text>
    </div>
  );
}

export function AuditLogsPage() {
  const { message } = App.useApp();
  const [rows, setRows] = useState<AuditLogView[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [draftKeyword, setDraftKeyword] = useState('');
  const [draftModule, setDraftModule] = useState<string>();
  const [draftOperation, setDraftOperation] = useState<string>();
  const [draftOperator, setDraftOperator] = useState('');
  const [draftDates, setDraftDates] = useState<AuditDateRange>(null);
  const [query, setQuery] = useState<SearchQuery>({});

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await iamApi.auditLogs({ ...query, page, size: PAGE_SIZE });
      setRows(result.items);
      setTotal(result.total);
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '操作日志加载失败');
    } finally {
      setLoading(false);
    }
  }, [message, page, query]);

  useEffect(() => { void load(); }, [load]);

  const submitSearch = () => {
    setPage(1);
    setQuery({
      keyword: draftKeyword.trim() || undefined,
      module: draftModule,
      operation: draftOperation,
      operator: draftOperator.trim() || undefined,
      from: draftDates?.[0]?.startOf('day').toISOString(),
      to: draftDates?.[1]?.add(1, 'day').startOf('day').toISOString(),
    });
  };

  const resetSearch = () => {
    setDraftKeyword('');
    setDraftModule(undefined);
    setDraftOperation(undefined);
    setDraftOperator('');
    setDraftDates(null);
    setPage(1);
    setQuery({});
  };

  return (
    <div className="iam-page pm-unified-list-page iam-audit-page">
      <div className="page-heading">
        <div>
          <Typography.Title level={2}>操作日志</Typography.Title>
          <Typography.Text type="secondary">
            查看账号、权限和业务操作记录；系统自动处理记录保留在后台审计中。
          </Typography.Text>
        </div>
        <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
      </div>
      <Card className="iam-card" variant="borderless">
        <Space className="iam-toolbar iam-audit-toolbar" wrap>
          <Input
            allowClear
            prefix={<SearchOutlined />}
            value={draftKeyword}
            onChange={(event) => setDraftKeyword(event.target.value)}
            onPressEnter={submitSearch}
            placeholder="搜索操作人、对象名称或说明"
          />
          <Select allowClear value={draftModule} placeholder="全部模块" options={moduleOptions} onChange={setDraftModule} />
          <Select allowClear value={draftOperation} placeholder="全部操作" options={operationOptions} onChange={setDraftOperation} />
          <Input allowClear value={draftOperator} placeholder="操作人" onChange={(event) => setDraftOperator(event.target.value)} onPressEnter={submitSearch} />
          <DatePicker.RangePicker value={draftDates} onChange={setDraftDates} />
          <Button type="primary" onClick={submitSearch}>查询</Button>
          <Button onClick={resetSearch}>重置</Button>
        </Space>
        <Table
          className="iam-audit-table"
          rowKey="id"
          loading={loading}
          dataSource={rows}
          pagination={false}
          columns={[
            { title: '时间', dataIndex: 'createdAt', width: 170, render: (value: string) => formatAuditTime(value) },
            { title: '操作人', dataIndex: 'operator', width: 120 },
            { title: '业务模块', dataIndex: 'module', width: 130, render: (value: string) => <Tag color="blue">{value}</Tag> },
            { title: '操作内容', key: 'content', render: (_: unknown, row: AuditLogView) => <AuditContent row={row} /> },
            { title: '操作类型', dataIndex: 'operation', width: 110 },
            { title: '更多', key: 'technical', width: 190, render: (_: unknown, row: AuditLogView) => <TechnicalDetails detail={row.technical} /> },
          ]}
          scroll={{ x: 1050 }}
        />
        <div className="iam-audit-mobile-list" aria-label="操作日志列表">
          {loading ? (
            <div className="iam-audit-mobile-state"><Spin /><Typography.Text type="secondary">日志加载中…</Typography.Text></div>
          ) : rows.length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无符合条件的操作记录" />
          ) : rows.map((row) => (
            <article className="iam-audit-mobile-item" key={row.id}>
              <div className="iam-audit-mobile-header">
                <Typography.Text type="secondary">{formatAuditTime(row.createdAt)}</Typography.Text>
                <Tag color="blue">{row.module}</Tag>
              </div>
              <div className="iam-audit-mobile-operator">{row.operator} · {row.operation}</div>
              <AuditContent row={row} />
              <TechnicalDetails detail={row.technical} />
            </article>
          ))}
        </div>
        {total > 0 && (
          <Pagination
            className="iam-audit-pagination"
            current={page}
            pageSize={PAGE_SIZE}
            total={total}
            showTotal={(value) => `共 ${value} 条记录`}
            onChange={setPage}
            showSizeChanger={false}
          />
        )}
      </Card>
    </div>
  );
}
