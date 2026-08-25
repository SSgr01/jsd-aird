import {
  DeleteOutlined,
  DownloadOutlined,
  EyeOutlined,
  FileExcelOutlined,
  FileWordOutlined,
  HistoryOutlined,
  CopyOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import {
  App,
  Button,
  Card,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Timeline,
  Typography,
} from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { downloadFile } from '@/services/files/file-api';
import { usePermission } from '@/components/auth/usePermission';
import { templateApi } from '@/services/templates/template-api';
import {
  productionUploadApi,
  productionOrderRecordApi,
  type ProductionUpload,
  type ProductionUploadVersion,
  type ProductionUploadVisibility,
} from '@/services/production-orders/production-upload-api';
import { productionOrderApi } from '@/services/production-orders/production-order-api';

const visibilityText: Record<ProductionUploadVisibility, string> = {
  ALL: '全员可见',
  QUALITY: '品管部可见',
  PROJECT: '项目组可见',
};

const statusOptions = [
  { value: 'QUEUED', label: '排队中' },
  { value: 'PARSING', label: '解析中' },
  { value: 'REVIEW_REQUIRED', label: '待复核' },
  { value: 'SAVED', label: '已保存' },
  { value: 'PUBLISHED', label: '已发布' },
  { value: 'FAILED', label: '失败' },
];

const statusText: Record<string, string> = Object.fromEntries(
  statusOptions.map((item) => [item.value, item.label]),
);

export function ProductionOrderListPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const canCreate = usePermission('production.create');
  const canUpdate = usePermission('production.update');
  const canDelete = usePermission('production.delete');
  const [items, setItems] = useState<ProductionUpload[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState<string>();
  const [page, setPage] = useState({ current: 1, pageSize: 20, total: 0 });
  const [downloadingId, setDownloadingId] = useState<string>();
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [drafts, setDrafts] = useState<Record<string, ProductionUpload>>({});
  const [selectedKeys, setSelectedKeys] = useState<string[]>([]);
  const [audit, setAudit] = useState<{ item: ProductionUpload; versions: ProductionUploadVersion[] }>();
  const [createOpen, setCreateOpen] = useState(false);
  const [copying, setCopying] = useState(false);
  const [creating, setCreating] = useState(false);
  const [templateOptions, setTemplateOptions] = useState<Array<{
    versionId: string;
    currentPublishedVersionId?: string;
    currentPublishedVersionNo?: number;
    templateCode: string;
    name: string;
    versionNo: number;
  }>>([]);
  const [createForm] = Form.useForm<{
    orderNo: string;
    templateVersionId: string;
    quantity?: number;
    unitCode?: string;
    plannedDate?: string;
  }>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await productionUploadApi.list({
        keyword: keyword || undefined,
        status: status || undefined,
        page: page.current,
        size: page.pageSize,
      });
      setItems(result.items);
      setPage((current) => ({
        ...current,
        current: result.page,
        pageSize: result.size,
        total: result.total,
      }));
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '生产单列表加载失败');
    } finally {
      setLoading(false);
    }
  }, [keyword, message, page.current, page.pageSize, status]);

  useEffect(() => {
    void load();
  }, [load]);

  const saveFile = (item: ProductionUpload) => {
    setDownloadingId(item.id);
    void downloadFile(item.fileId, item.originalName)
      .then(() => void message.success('文件下载已开始'))
      .catch((error) => void message.error(error instanceof Error ? error.message : '文件下载失败'))
      .finally(() => setDownloadingId(undefined));
  };

  const startEdit = () => {
    setDrafts(Object.fromEntries(items.map((item) => [item.id, { ...item }])));
    setEditing(true);
  };

  const deleteSelected = () => {
    if (!selectedKeys.length) return;
    Modal.confirm({
      title: `确认删除 ${selectedKeys.length} 条生产单记录？`,
      content: '删除后记录会进入作废状态，原始文件不会被物理删除。',
      okText: '确认删除',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await Promise.all(selectedKeys.map((id) => productionUploadApi.delete(id)));
          setSelectedKeys([]);
          void message.success('选中的生产单记录已作废');
          await load();
        } catch (error) {
          void message.error(error instanceof Error ? error.message : '批量删除失败');
        }
      },
    });
  };

  const openAudit = async (item: ProductionUpload) => {
    try {
      const versions = await productionOrderRecordApi.versions(item.id);
      setAudit({ item, versions });
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '操作留痕加载失败');
    }
  };

  const openCreate = async () => {
    setCopying(false);
    setCreateOpen(true);
    try {
      const result = await templateApi.list({ format: 'XLSX', status: 'PUBLISHED', page: 1, size: 100 });
      setTemplateOptions(result.items);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '已发布模板加载失败');
    }
  };

  const openCopy = async (item: ProductionUpload) => {
    setCopying(true);
    createForm.setFieldsValue({
      orderNo: `${item.orderNo || item.id}-COPY`,
      templateVersionId: item.selectedTemplateVersionId,
    });
    await openCreate();
    setCopying(true);
  };

  const createOrder = async () => {
    try {
      const values = await createForm.validateFields();
      setCreating(true);
      const workspace = await productionOrderApi.create({
        ...values,
        templateVersionId: values.templateVersionId,
      });
      setCreateOpen(false);
      createForm.resetFields();
      void message.success('生产单草稿已创建');
      navigate(`/production-orders/${workspace.id}/workspace`);
    } catch (error) {
      if (error && typeof error === 'object' && 'errorFields' in error) return;
      void message.error(error instanceof Error ? error.message : '生产单创建失败');
    } finally {
      setCreating(false);
    }
  };

  const updateDraft = (id: string, field: keyof ProductionUpload, value: string) => {
    setDrafts((current) => ({
      ...current,
      [id]: { ...(current[id] ?? items.find((item) => item.id === id)!), [field]: value },
    }));
  };

  const saveTable = async () => {
    setSaving(true);
    try {
      await productionOrderRecordApi.saveBatch({
        records: items.map((item) => {
          const draft = drafts[item.id] ?? item;
          return {
            id: item.id,
            lockVersion: item.lockVersion ?? 0,
            productionName: draft.productionName,
            orderNo: draft.orderNo,
            productName: draft.productName,
            category: draft.category,
            manufactureDate: draft.manufactureDate,
          };
        }),
      });
      void message.success('生产单表格已保存');
      setEditing(false);
      setDrafts({});
      await load();
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '生产单表格保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <>
      <div className="business-page">
        <div className="page-heading">
          <div>
            <Typography.Title level={2}>生产单查看</Typography.Title>
            <Typography.Text type="secondary">
              查看已上传的生产单资料，当前不进入模板填写或提交流程。
            </Typography.Text>
          </div>
          <Space>
            {editing ? (
              <>
                <Button
                  onClick={() => {
                    setEditing(false);
                    setDrafts({});
                  }}
                >
                  取消
                </Button>
                <Button type="primary" loading={saving} onClick={() => void saveTable()}>
                  保存
                </Button>
              </>
            ) : (
              <Button onClick={startEdit} disabled={!items.length || !canUpdate}>
                编辑表格
              </Button>
            )}
            <Button
              type="primary"
              icon={<PlusOutlined />}
              disabled={!canCreate}
              onClick={() => void openCreate()}
            >
              新建生产单
            </Button>
            <Button
              icon={<PlusOutlined />}
              disabled={!canCreate}
              onClick={() => navigate('/production-orders/upload')}
            >
              上传生产单
            </Button>
          </Space>
        </div>

        <Card className="content-card filter-card">
          <Space wrap>
            <Input.Search
              allowClear
              placeholder="生产单名称、订单号、品名或文件名"
              value={keyword}
              onChange={(event) => {
                setKeyword(event.target.value);
                setPage((current) => ({ ...current, current: 1 }));
              }}
              style={{ width: 300 }}
            />
            <Select
              allowClear
              placeholder="全部状态"
              value={status}
              onChange={(value) => {
                setStatus(value);
                setPage((current) => ({ ...current, current: 1 }));
              }}
              options={statusOptions}
              style={{ width: 140 }}
            />
            <Typography.Text type="secondary">已选 {selectedKeys.length} 条</Typography.Text>
            <Button
              danger
              icon={<DeleteOutlined />}
              disabled={!selectedKeys.length || editing || !canDelete}
              onClick={deleteSelected}
            >
              批量作废
            </Button>
            <Button disabled={!selectedKeys.length} onClick={() => setSelectedKeys([])}>
              清除选择
            </Button>
            <Button icon={<ReloadOutlined />} onClick={() => void load()}>
              刷新
            </Button>
          </Space>
        </Card>

        <Card className="content-card" styles={{ body: { padding: 0 } }}>
          <Table
            rowKey="id"
            rowSelection={{
              selectedRowKeys: selectedKeys,
              onChange: (keys) => setSelectedKeys(keys.map(String)),
              getCheckboxProps: () => ({ disabled: editing }),
            }}
            loading={loading}
            dataSource={items}
            locale={{ emptyText: <Empty description="暂无已保存的生产单" /> }}
            pagination={{
              current: page.current,
              pageSize: page.pageSize,
              total: page.total,
              showSizeChanger: true,
              onChange: (current, pageSize) =>
                setPage((value) => ({ ...value, current, pageSize })),
            }}
            columns={[
              {
                title: '生产单名称',
                dataIndex: 'productionName',
                render: (value: string, item: ProductionUpload) =>
                  editing ? (
                    <Input
                      value={drafts[item.id]?.productionName ?? value ?? ''}
                      onChange={(event) =>
                        updateDraft(item.id, 'productionName', event.target.value)
                      }
                    />
                  ) : (
                    <Typography.Text strong>{value || '—'}</Typography.Text>
                  ),
              },
              {
                title: '订单号',
                dataIndex: 'orderNo',
                render: (value: string, item: ProductionUpload) =>
                  editing ? (
                    <Input
                      value={drafts[item.id]?.orderNo ?? value ?? ''}
                      onChange={(event) => updateDraft(item.id, 'orderNo', event.target.value)}
                    />
                  ) : (
                    value || '—'
                  ),
              },
              {
                title: '品名',
                dataIndex: 'productName',
                render: (value: string, item: ProductionUpload) =>
                  editing ? (
                    <Input
                      value={drafts[item.id]?.productName ?? value ?? ''}
                      onChange={(event) => updateDraft(item.id, 'productName', event.target.value)}
                    />
                  ) : (
                    value || '—'
                  ),
              },
              {
                title: '类别',
                dataIndex: 'category',
                render: (value: string, item: ProductionUpload) =>
                  editing ? (
                    <Input
                      value={drafts[item.id]?.category ?? value ?? ''}
                      onChange={(event) => updateDraft(item.id, 'category', event.target.value)}
                    />
                  ) : (
                    value || '—'
                  ),
              },
              {
                title: '制造日期',
                dataIndex: 'manufactureDate',
                width: 150,
                render: (value: string, item: ProductionUpload) =>
                  editing ? (
                    <Input
                      type="date"
                      value={drafts[item.id]?.manufactureDate ?? value ?? ''}
                      onChange={(event) =>
                        updateDraft(item.id, 'manufactureDate', event.target.value)
                      }
                    />
                  ) : (
                    value || '—'
                  ),
              },
              {
                title: '关联项目 / 阶段 / 任务',
                width: 220,
                render: (_: unknown, item: ProductionUpload) =>
                  [item.projectName, item.stageName, item.taskName].filter(Boolean).join(' · ') ||
                  '未关联项目',
              },
              {
                title: '权限可见',
                width: 120,
                render: (value: ProductionUploadVisibility) => <Tag>{visibilityText[value]}</Tag>,
              },
              {
                title: '业务状态',
                dataIndex: 'status',
                width: 110,
                render: (value: string) => (
                  <Tag color={value === 'PUBLISHED' ? 'success' : value === 'FAILED' ? 'error' : 'processing'}>
                    {statusText[value] || value}
                  </Tag>
                ),
              },
              {
                title: '文件',
                width: 230,
                render: (_: unknown, item: ProductionUpload) => (
                  <Space>
                    {/\.docx?$/i.test(item.originalName) ? (
                      <FileWordOutlined className="excel-icon" />
                    ) : (
                      <FileExcelOutlined className="excel-icon" />
                    )}
                    <Typography.Text ellipsis={{ tooltip: item.originalName }}>
                      {item.originalName}
                    </Typography.Text>
                  </Space>
                ),
              },
              {
                title: '上传时间',
                width: 170,
                render: (_: unknown, item: ProductionUpload) =>
                  new Date(item.createdAt).toLocaleString('zh-CN'),
              },
              {
                title: '操作',
                width: 320,
                fixed: 'right' as const,
                render: (_: unknown, item: ProductionUpload) => (
                  <Space>
                    <Button
                      type="link"
                      icon={<EyeOutlined />}
                      disabled={editing}
                      onClick={() => navigate(`/production-orders/uploads/${item.id}/workspace`)}
                    >
                      查看
                    </Button>
                    <Button
                      type="link"
                      icon={<DownloadOutlined />}
                      loading={downloadingId === item.id}
                      disabled={editing}
                      onClick={() => saveFile(item)}
                    >
                      下载
                    </Button>
                    <Button
                      type="link"
                      icon={<HistoryOutlined />}
                      disabled={editing}
                      onClick={() => void openAudit(item)}
                    >
                      留痕
                    </Button>
                    <Button
                      type="link"
                      icon={<CopyOutlined />}
                      disabled={editing || !canCreate}
                      onClick={() => void openCopy(item)}
                    >
                      复制
                    </Button>
                  </Space>
                ),
              },
            ]}
            scroll={{ x: 1650 }}
          />
        </Card>
      </div>
      <Drawer
        title={audit ? `生产单留痕 · ${audit.item.originalName}` : '生产单留痕'}
        open={Boolean(audit)}
        onClose={() => setAudit(undefined)}
        width={520}
      >
        {audit && (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Descriptions size="small" column={1} bordered items={[
              { key: 'created', label: '创建人', children: audit.item.createdBy },
              { key: 'createdAt', label: '创建时间', children: new Date(audit.item.createdAt).toLocaleString('zh-CN') },
              { key: 'updatedAt', label: '最近更新', children: new Date(audit.item.updatedAt).toLocaleString('zh-CN') },
              { key: 'status', label: '当前状态', children: statusText[audit.item.status] || audit.item.status },
            ]} />
            <Typography.Title level={5} style={{ margin: 0 }}>版本与操作记录</Typography.Title>
            <Timeline items={[
              { color: 'blue', children: `创建 · ${new Date(audit.item.createdAt).toLocaleString('zh-CN')}` },
              ...audit.versions.map((item) => ({
                color: 'green',
                children: `发布 V${item.versionNo} · ${item.createdBy} · ${new Date(item.createdAt).toLocaleString('zh-CN')}`,
              })),
              ...(audit.item.updatedAt !== audit.item.createdAt ? [{
                color: 'gray',
                children: `最近编辑 · ${new Date(audit.item.updatedAt).toLocaleString('zh-CN')}`,
              }] : []),
            ]} />
          </Space>
        )}
      </Drawer>
      <Modal
        title={copying ? '复制生产单（状态重置为未下单）' : '新建生产单'}
        open={createOpen}
        confirmLoading={creating}
        okText="创建并进入工作台"
        cancelText="取消"
        onOk={() => void createOrder()}
        onCancel={() => {
          setCreateOpen(false);
          createForm.resetFields();
        }}
        destroyOnHidden
      >
          <Form form={createForm} layout="vertical">
          <Form.Item
            name="orderNo"
            label="生产单号"
            rules={[{ required: true, whitespace: true, message: '请输入生产单号' }]}
          >
            <Input placeholder="例如 PO-20260824-001" maxLength={80} />
          </Form.Item>
          <Form.Item
            name="templateVersionId"
            label="生产模板"
            rules={[{ required: true, message: '请选择已发布的 XLSX 模板' }]}
          >
            <Select
              showSearch
              optionFilterProp="label"
              placeholder="选择已发布模板"
              options={templateOptions.map((item) => ({
                value: item.currentPublishedVersionId ?? item.versionId,
                label: `${item.templateCode} · ${item.name} · V${item.currentPublishedVersionNo ?? item.versionNo}`,
              }))}
            />
          </Form.Item>
          <Space style={{ width: '100%' }} align="start">
            <Form.Item name="quantity" label="计划数量">
              <InputNumber min={0} precision={3} style={{ width: 160 }} />
            </Form.Item>
            <Form.Item name="unitCode" label="单位">
              <Select allowClear style={{ width: 120 }} options={[
                { value: 'kg', label: 'kg' },
                { value: 't', label: 't' },
                { value: 'L', label: 'L' },
                { value: 'pcs', label: 'pcs' },
              ]} />
            </Form.Item>
            <Form.Item name="plannedDate" label="计划日期">
              <Input type="date" />
            </Form.Item>
          </Space>
          {!templateOptions.length && (
            <Typography.Text type="warning">当前没有已发布的 XLSX 模板，请先到模板中心完成发布。</Typography.Text>
          )}
          </Form>
          {copying && <Typography.Text type="secondary">将复制基础信息和模板关系；新记录不继承已发布状态、库存联动、签名和操作留痕。</Typography.Text>}
      </Modal>
    </>
  );
}
