import { App, Empty, Table, Tabs, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';

import { downloadFile } from '@/services/files/file-api';
import { qualityApi, type QualityUpload } from '@/services/quality/quality-api';
import { productionUploadApi, type ProductionUpload } from '@/services/production-orders/production-upload-api';
import { listResearchTests, type ResearchTestSummary } from '@/services/research-test/research-test-api';
import { useNavigate } from 'react-router-dom';

import './project-materials-tab.css';

interface Props {
  projectId: string;
}

function formatDate(value?: string) {
  return value ? value.slice(0, 10) : '—';
}

export function ProjectMaterialsTab({ projectId }: Props) {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [qualityItems, setQualityItems] = useState<QualityUpload[]>([]);
  const [productionItems, setProductionItems] = useState<ProductionUpload[]>([]);
  const [reportItems, setReportItems] = useState<ResearchTestSummary[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!projectId) return;
    setLoading(true);
    void Promise.all([
      qualityApi.uploads({ projectId, page: 1, size: 100 }),
      productionUploadApi.list({ projectId, viewableOnly: true, page: 1, size: 100 }),
      listResearchTests('REPORT', { projectId, page: 1, size: 100 }),
    ]).then(([quality, production, reports]) => {
      setQualityItems(quality.items);
      setProductionItems(production.items);
      setReportItems(reports.items);
    }).catch((reason) => {
      message.error(reason instanceof Error ? reason.message : '项目关联资料加载失败');
      setQualityItems([]);
      setProductionItems([]);
      setReportItems([]);
    }).finally(() => setLoading(false));
  }, [message, projectId]);

  const download = (fileId: string, name: string) => {
    void downloadFile(fileId, name)
      .then(() => message.success('文件读取已开始'))
      .catch((reason) => message.error(reason instanceof Error ? reason.message : '文件读取失败'));
  };

  const qualityColumns = [
    { title: '文件名称', dataIndex: 'originalName', render: (value: string) => <Typography.Text strong>{value || '—'}</Typography.Text> },
    { title: '资料分类', dataIndex: 'categoryName', render: (value: string) => value || '—' },
    { title: '阶段 / 任务', key: 'relation', render: (_: unknown, row: QualityUpload) => `${row.stageName || '—'} / ${row.taskName || '—'}` },
    { title: '可见范围', dataIndex: 'visibility', render: (value: string) => <Tag>{value === 'PROJECT' ? '项目组可见' : value === 'QUALITY' ? '品管部可见' : '全员可见'}</Tag> },
    { title: '上传时间', dataIndex: 'createdAt', render: (value: string) => formatDate(value) },
    { title: '读取', key: 'read', width: 80, render: (_: unknown, row: QualityUpload) => <a onClick={() => download(row.fileId, row.originalName)}>读取</a> },
  ];

  const productionColumns = [
    { title: '生产单文件', dataIndex: 'originalName', render: (value: string) => <Typography.Text strong>{value || '—'}</Typography.Text> },
    { title: '生产单名称', dataIndex: 'productionName', render: (value: string) => value || '—' },
    { title: '订单号', dataIndex: 'orderNo', render: (value: string) => value || '—' },
    { title: '品名', dataIndex: 'productName', render: (value: string) => value || '—' },
    { title: '阶段 / 任务', key: 'relation', render: (_: unknown, row: ProductionUpload) => `${row.stageName || '—'} / ${row.taskName || '—'}` },
    { title: '上传时间', dataIndex: 'createdAt', render: (value: string) => formatDate(value) },
    { title: '读取', key: 'read', width: 80, render: (_: unknown, row: ProductionUpload) => <a onClick={() => download(row.fileId, row.originalName)}>读取</a> },
  ];
  const reportColumns = [
    { title: '报告编号', dataIndex: 'businessNo' },
    { title: '报告名称', dataIndex: 'name', render: (value: string) => <Typography.Text strong>{value}</Typography.Text> },
    { title: '阶段 / 任务', key: 'relation', render: (_: unknown, row: ResearchTestSummary) => `${row.stageName || '—'} / ${row.taskName || '—'}` },
    { title: '负责人', dataIndex: 'ownerName', render: (value?: string) => value || '—' },
    { title: '状态', dataIndex: 'status', render: (value: string) => <Tag color={value === 'PUBLISHED' ? 'green' : 'blue'}>{value === 'PUBLISHED' ? '已发布' : '草稿/审核中'}</Tag> },
    { title: '查看', key: 'read', width: 80, render: (_: unknown, row: ResearchTestSummary) => <a onClick={() => navigate(`/research-test/reports/${row.id}`, { state: { returnTo: `/projects/${projectId}?section=assets` } })}>查看</a> },
  ];

  return (
    <div className="pm-pm-tab pm-readonly-materials-tab">
      <Typography.Paragraph type="secondary" className="pm-readonly-materials-tip">
        以下资料由品管部、生产单和研发测试模块维护，项目详情提供只读查看。
      </Typography.Paragraph>
      <Tabs items={[
        { key: 'quality', label: `品管部（${qualityItems.length}）`, children: <Table rowKey="id" loading={loading} columns={qualityColumns} dataSource={qualityItems} pagination={false} scroll={{ x: 920 }} locale={{ emptyText: <Empty description="暂无品管部资料" /> }} /> },
        { key: 'production', label: `生产单（${productionItems.length}）`, children: <Table rowKey="id" loading={loading} columns={productionColumns} dataSource={productionItems} pagination={false} scroll={{ x: 1080 }} locale={{ emptyText: <Empty description="暂无生产单资料" /> }} /> },
        { key: 'research-test', label: `综合测试报告（${reportItems.length}）`, children: <Table rowKey="id" loading={loading} columns={reportColumns} dataSource={reportItems} pagination={false} scroll={{ x: 920 }} locale={{ emptyText: <Empty description="暂无综合测试报告" /> }} /> },
      ]} />
    </div>
  );
}
