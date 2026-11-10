import {
  DeleteOutlined,
  DownloadOutlined,
  EyeOutlined,
  ReloadOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import {
  Button,
  Card,
  DatePicker,
  Empty,
  Input,
  Popconfirm,
  Select,
  Space,
  Table,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { usePermission } from '@/components/auth/usePermission';

import { downloadFile } from '@/services/files/file-api';
import { useBusinessQuery } from '@/services/query/use-business-query';
import { useListPageBounds } from '@/services/query/use-list-page-bounds';
import { useListKeyword, useListUrl } from '@/services/query/use-list-url';
import {
  deleteQuotation,
  listQuotations,
  type QuotationSummary,
} from '@/services/quotations/quotation-api';
import '@/styles/management-list.css';
import { quotationReviewLabels, quotationReviewTones } from './quotation-review-status';
import './quotation-list.css';

export function QuotationListPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const url = useListUrl();
  const [page, setPage] = url.field<number>('page', 1, false);
  const [pageSize, setPageSize] = url.field<number>('size', 10, false);
  const [keyword, setKeyword] = useListKeyword(url);
  const [customerName, setCustomerName] = url.field<string | undefined>('customerName', undefined);
  const [dateFrom, setDateFrom] = url.field<string | undefined>('dateFrom', undefined);
  const [dateTo, setDateTo] = url.field<string | undefined>('dateTo', undefined);
  const canDelete = usePermission('quotation.delete');
  const [deletingId, setDeletingId] = useState<string>();
  const [downloadingId, setDownloadingId] = useState<string>();

  const filters = {
    keyword: keyword || undefined,
    customerName,
    dateFrom,
    dateTo,
    page,
    size: pageSize,
  };
  const listQuery = useBusinessQuery('quotations', filters, (signal) =>
    listQuotations(filters, signal),
  { keepPage: true },
  );
  useListPageBounds(url, listQuery, 10);
  const customerQuery = useBusinessQuery(
    'quotations',
    { kind: 'customers', page: 1, size: 200 },
    (signal) => listQuotations({ page: 1, size: 200 }, signal),
    { dictionary: true },
  );

  const rows = listQuery.data?.items ?? [];
  const total = listQuery.data?.total ?? 0;
  const loading = listQuery.isPending;
  const customers = useMemo(
    () => [...new Set((customerQuery.data?.items ?? []).map((item) => item.customerName))],
    [customerQuery.data],
  );
  const dateRange =
    dateFrom && dateTo ? ([dayjs(dateFrom), dayjs(dateTo)] as [dayjs.Dayjs, dayjs.Dayjs]) : undefined;
  const selectedCustomer = customerName || undefined;

  const changePage = (nextPage: number, nextPageSize: number) => {
    setPage(nextPageSize !== pageSize ? 1 : nextPage);
    setPageSize(nextPageSize);
  };

  const reset = () => {
    setKeyword('');
    setCustomerName(undefined);
    setDateFrom(undefined);
    setDateTo(undefined);
    setPage(1);
  };

  const openDetail = (row: QuotationSummary) => navigate(`/quotations/${row.id}`, {
    state: { returnTo: `${location.pathname}${location.search}` },
  });

  const download = (fileId?: string | null, fileName?: string | null) => {
    if (!fileId) {
      message.warning('该报价记录没有可下载的原始文件');
      return;
    }
    setDownloadingId(fileId);
    void downloadFile(fileId, fileName || '报价记录')
      .then(() => message.success('文件下载已开始'))
      .catch((error) => message.error(error instanceof Error ? error.message : '文件下载失败'))
      .finally(() => setDownloadingId(undefined));
  };

  const remove = async (row: QuotationSummary) => {
    setDeletingId(row.id);
    try {
      await deleteQuotation(row.id);
      message.success('报价记录已删除');
      await listQuery.refetch();
      await customerQuery.refetch();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '报价记录删除失败');
    } finally {
      setDeletingId(undefined);
    }
  };

  const columns: ColumnsType<QuotationSummary> = [
    {
      title: '报价编号',
      dataIndex: 'quotationNo',
      width: 190,
      render: (value: string, row) => (
        <Button type="link" className="pm-name-link" onClick={() => void openDetail(row)}>
          {value}
        </Button>
      ),
    },
    {
      title: '客户名称',
      dataIndex: 'customerName',
      width: 240,
      render: (value: string, row) => (
        <span className="eln-name-cell">
          <b className="quotation-customer-name" title={value}>{value}</b>
          {row.relatedCompany ? <small>{row.relatedCompany}</small> : null}
        </span>
      ),
    },
    {
      title: '联系人',
      key: 'contact',
      width: 120,
      render: (_, row) => row.contactName || '—',
    },
    {
      title: '明细',
      dataIndex: 'itemCount',
      width: 90,
      render: (value: number) => `${value} 条`,
    },
    {
      title: '状态',
      dataIndex: 'reviewStatus',
      width: 130,
      render: (value: QuotationSummary['reviewStatus']) => {
        const status = value ?? 'NOT_SUBMITTED';
        return <span className={`pm-tag status-${quotationReviewTones[status]}`}>
          {quotationReviewLabels[status]}
        </span>;
      },
    },
    {
      title: '上传时间',
      dataIndex: 'createdAt',
      width: 165,
      render: (value: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—'),
    },
    {
      title: '操作',
      key: 'action',
      width: 200,
      render: (_, row) => (
        <Space size={0}>
          <Button type="link" icon={<EyeOutlined />} onClick={() => void openDetail(row)}>
            查看
          </Button>
          <Button
            type="link"
            icon={<DownloadOutlined />}
            loading={downloadingId === row.sourceFileId}
            onClick={() => download(row.sourceFileId, row.sourceFileName)}
          >
            下载
          </Button>
          {canDelete && (
            <Popconfirm
              title="确认删除该报价记录？"
              description="删除后报价记录及其明细将从列表中移除。"
              okText="删除"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              onConfirm={() => void remove(row)}
            >
              <Button type="link" danger icon={<DeleteOutlined />} loading={deletingId === row.id}>
                删除
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <section className="pm-unified-list-page quotation-list-page">
      <header className="page-heading">
        <div>
          <Typography.Title level={2}>报价记录</Typography.Title>
          <Typography.Text type="secondary">
            上传的报价单按客户归档，可查看产品单价、规格与报价日期明细。
          </Typography.Text>
        </div>
        <div className="pm-page-actions">
          <Button icon={<ReloadOutlined />} onClick={() => void listQuery.refetch()}>
            刷新
          </Button>
          <Button
            type="primary"
            icon={<UploadOutlined />}
            onClick={() => navigate('/quotations/upload')}
          >
            上传报价
          </Button>
        </div>
      </header>

      <Card className="quotation-filter">
        <Space wrap>
          <Input.Search
            allowClear
            placeholder="搜索报价编号/客户/产品"
            style={{ width: 300 }}
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            onSearch={() => setPage(1)}
          />
          <Select<string>
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder="全部客户"
            style={{ width: 220 }}
            popupMatchSelectWidth={false}
            loading={customerQuery.isPending}
            value={selectedCustomer}
            options={customers.map((value) => ({ value, label: value }))}
            onChange={(value) => {
              setCustomerName(value);
              setPage(1);
            }}
          />
          <DatePicker.RangePicker
            value={dateRange}
            onChange={(values) => {
              setDateFrom(values?.[0]?.format('YYYY-MM-DD'));
              setDateTo(values?.[1]?.format('YYYY-MM-DD'));
              setPage(1);
            }}
          />
          <Button className="pm-filter-reset" icon={<ReloadOutlined />} onClick={reset}>
            重置
          </Button>
        </Space>
      </Card>

      <Table<QuotationSummary>
        rowKey="id"
        className="quotation-table"
        loading={{ spinning: loading, delay: 200 }}
        dataSource={rows}
        columns={columns}
        scroll={{ x: 'max-content' }}
        locale={{ emptyText: <Empty description="没有符合筛选条件的报价记录" /> }}
        pagination={{
          current: page,
          pageSize,
          total,
          showSizeChanger: true,
          pageSizeOptions: [10, 20, 30, 50],
          showTotal: (value) => `共 ${value} 条报价记录`,
          onChange: changePage,
        }}
      />

    </section>
  );
}
