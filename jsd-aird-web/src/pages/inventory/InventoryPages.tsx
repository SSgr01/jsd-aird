import { CheckOutlined, DownloadOutlined, EditOutlined, PlusOutlined, ReloadOutlined, UploadOutlined } from '@ant-design/icons';
import {
  Alert,
  AutoComplete,
  Button,
  Card,
  Col,
  DatePicker,
  Descriptions,
  Form,
  Input,
  InputNumber,
  message,
  Modal,
  Popconfirm,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Tooltip,
  Upload,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import * as XLSX from 'xlsx';
import {
  inventoryApi,
  type AlertStatus,
  type InventoryBalance,
  type InventoryDirection,
  type InventoryScope,
  type InventoryTransaction,
  type SampleDispatch,
  type Shipment,
  type ShipmentHeader,
} from '@/services/inventory/inventory-api';
import type { InventoryProductOption } from '@/services/inventory/inventory-api';
import { getPartnerContacts, getPartners, type BusinessPartner } from '@/services/partners/partner-api';
import { qualityApi, type QualityRecord } from '@/services/quality/quality-api';
import { errorMessage } from '@/services/http/errors';
import { useAuthStore } from '@/stores/auth-store';
import { parseRecipient } from './sample-recipient-parse';
import { isShipmentWorkbook, parseShipmentWorkbook, shipmentWorkbookTemplate } from './shipment-workbook';
import './inventory-pages.css';
import '@/styles/management-list.css';

const scopeLabels: Record<InventoryScope, string> = { RND: '研发库存', PRODUCTION: '生产库存' };
const alertLabels: Record<AlertStatus, string> = {
  NORMAL: '正常',
  LOW_STOCK: '低库存',
  OUT_OF_STOCK: '无库存',
};
const reasonOptions: Record<InventoryScope, string[]> = {
  RND: [
    'PURCHASE',
    'RND_SAMPLE_IN',
    'SUPPLIER_SAMPLE',
    'EXPERIMENT_PRODUCT',
    'EXPERIMENT',
    'SAMPLE_DISPATCH',
    'OPENING_BALANCE',
    'STOCKTAKE',
    'COMPENSATION',
  ],
  PRODUCTION: [
    'PURCHASE',
    'FINISHED_PRODUCT',
    'INTERMEDIATE_PRODUCT',
    'PRODUCTION_ORDER',
    'RND_SAMPLE',
    'OPENING_BALANCE',
    'STOCKTAKE',
    'SHIPMENT',
    'COMPENSATION',
  ],
};
const reasonLabels: Record<string, string> = {
  PURCHASE: '采购',
  RND_SAMPLE_IN: '研发取样',
  SUPPLIER_SAMPLE: '供应商送样',
  EXPERIMENT_PRODUCT: '实验成品小样入库',
  EXPERIMENT: '实验单',
  SAMPLE_DISPATCH: '发样',
  STOCKTAKE: '盘点',
  FINISHED_PRODUCT: '成品入库',
  INTERMEDIATE_PRODUCT: '中间体入库',
  PRODUCTION_ORDER: '生产单',
  RND_SAMPLE: '研发取样',
  SHIPMENT: '出货',
  OPENING_BALANCE: '期初盘点',
  REVERSAL: '冲销',
  COMPENSATION: '补偿',
};
function inventoryIsReadOnly() {
  return window.localStorage.getItem('inventory-role') === 'READ_ONLY';
}
const kg = (v?: number) =>
  `${Number(v || 0).toLocaleString('zh-CN', { maximumFractionDigits: 6 })} KG`;

function SummaryCards({
  summary,
}: {
  summary?: {
    totalKg: number;
    productCount: number;
    lowStockCount: number;
    outOfStockCount: number;
  };
}) {
  return (
    <Row gutter={[12, 12]} className="inventory-summary">
      <Col xs={12} md={6}>
        <Card>
          <Statistic title="库存总量" value={summary?.totalKg || 0} suffix="KG" />
        </Card>
      </Col>
      <Col xs={12} md={6}>
        <Card>
          <Statistic title="库存项" value={summary?.productCount || 0} />
        </Card>
      </Col>
      <Col xs={12} md={6}>
        <Card>
          <Statistic
            title="低库存"
            value={summary?.lowStockCount || 0}
            valueStyle={{ color: '#d97706' }}
          />
        </Card>
      </Col>
      <Col xs={12} md={6}>
        <Card>
          <Statistic
            title="无库存"
            value={summary?.outOfStockCount || 0}
            valueStyle={{ color: '#dc2626' }}
          />
        </Card>
      </Col>
    </Row>
  );
}

export function InventoryQueryPage() {
  const [data, setData] = useState<Awaited<ReturnType<typeof inventoryApi.balances>>>();
  const [loading, setLoading] = useState(false);
  const [filters, setFilters] = useState<Record<string, unknown>>({ page: 1, size: 10 });
  const [selected, setSelected] = useState<InventoryBalance>();
  const [policyForm] = Form.useForm();
  const load = useCallback(async () => {
    setLoading(true);
    try {
      setData(await inventoryApi.balances(filters));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '库存加载失败');
    } finally {
      setLoading(false);
    }
  }, [filters]);
  useEffect(() => {
    void load();
  }, [load]);
  const columns: ColumnsType<InventoryBalance> = [
    {
      title: '更新日期',
      dataIndex: 'updatedAt',
      render: (v) => dayjs(v).format('YYYY-MM-DD HH:mm'),
    },
    {
      title: '库存归属',
      dataIndex: 'scope',
      render: (v) => <Tag color="blue">{scopeLabels[v as InventoryScope]}</Tag>,
    },
    { title: '类别', dataIndex: 'category' },
    { title: '产品编码', dataIndex: 'productCode' },
    { title: '产品名称', dataIndex: 'productName' },
    { title: '库存', dataIndex: 'quantityKg', align: 'right', render: kg },
    {
      title: '桶数',
      render: (_, r) =>
        r.packageKg
          ? (r.quantityKg / r.packageKg).toLocaleString('zh-CN', { maximumFractionDigits: 2 })
          : '—',
    },
    {
      title: '预警规则',
      render: (_, r) => (r.lowStockKg > 0 ? `低于 ${kg(r.lowStockKg)}` : '未配置'),
    },
    {
      title: '状态',
      dataIndex: 'alertStatus',
      render: (v) => (
        <Tag color={v === 'NORMAL' ? 'green' : v === 'LOW_STOCK' ? 'orange' : 'red'}>
          {alertLabels[v as AlertStatus]}
        </Tag>
      ),
    },
    {
      title: '操作',
      width: 100,
      render: (_, r) => (
        <Button type="link" onClick={() => setSelected(r)}>
          查看
        </Button>
      ),
    },
  ];
  return (
    <InventoryShell title="库存查询">
      <SummaryCards summary={data?.summary} />
      <Card className="inventory-table-card">
        <div className="inventory-filters">
          <Input.Search
            allowClear
            placeholder="搜索名称、编码或类别"
            onSearch={(keyword) => setFilters((x) => ({ ...x, keyword, page: 1 }))}
          />
          <Select
            allowClear
            placeholder="库存归属"
            options={Object.entries(scopeLabels).map(([value, label]) => ({ value, label }))}
            onChange={(scope) => setFilters((x) => ({ ...x, scope, page: 1 }))}
          />
          <Select
            allowClear
            placeholder="预警状态"
            options={Object.entries(alertLabels).map(([value, label]) => ({ value, label }))}
            onChange={(alert) => setFilters((x) => ({ ...x, alert, page: 1 }))}
          />
        </div>
        <Table
          rowKey={(r) => `${r.productId}-${r.scope}`}
          loading={loading}
          columns={columns}
          dataSource={data?.items}
          scroll={{ x: 'max-content' }}
          pagination={{
            current: data?.page,
            total: data?.total,
            pageSize: data?.size,
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 条记录`,
            onChange: (page, size) => setFilters((x) => ({ ...x, page, size })),
          }}
        />
      </Card>
      <Modal
        open={!!selected}
        onCancel={() => setSelected(undefined)}
        title="库存详情"
        width={560}
        okText="保存策略"
        cancelText="关闭"
        okButtonProps={{ disabled: inventoryIsReadOnly() }}
        onOk={async () => {
          if (!selected) return;
          if (inventoryIsReadOnly()) {
            message.error('当前角色为只读用户，不能修改库存策略');
            return;
          }
          try {
            const values = await policyForm.validateFields();
            const updated = await inventoryApi.updatePolicy(
              selected.productId,
              selected.scope,
              { ...values, revision: selected.policyRevision },
            );
            setSelected(updated);
            message.success('库存策略已保存');
            await load();
          } catch (error) {
            message.error(errorMessage(error, '库存策略保存失败，请稍后重试'));
          }
        }}
        destroyOnClose
      >
        <Descriptions
          column={1}
          bordered
          items={
            selected
              ? [
                  { label: '产品名称', children: `${selected.productCode} · ${selected.productName}` },
                  { label: '库存归属', children: scopeLabels[selected.scope] },
                  { label: '当前库存', children: kg(selected.quantityKg) },
                  {
                    label: '包装规格',
                    children: selected.packageKg ? `${kg(selected.packageKg)}/桶` : '未配置',
                  },
                  { label: '预警阈值', children: kg(selected.lowStockKg) },
                  { label: '版本', children: selected.version },
                ]
              : []
          }
        />
        {selected && (
          <Form
            form={policyForm}
            layout="vertical"
            initialValues={{ packageKg: selected.packageKg, lowStockKg: selected.lowStockKg }}
            key={`${selected.productId}-${selected.scope}`}
          >
            <Form.Item name="packageKg" label="包装规格（KG/桶）">
              <InputNumber min={0.000001} precision={6} className="inventory-full" />
            </Form.Item>
            <Form.Item name="lowStockKg" label="低库存预警阈值（KG）" rules={[{ required: true }]}>
              <InputNumber min={0} precision={6} className="inventory-full" />
            </Form.Item>
          </Form>
        )}
      </Modal>
    </InventoryShell>
  );
}

export function InventoryLedgerPage({ scope }: { scope: InventoryScope }) {
  const [data, setData] = useState<Awaited<ReturnType<typeof inventoryApi.transactions>>>();
  const [filters, setFilters] = useState<Record<string, unknown>>({ scope, page: 1, size: 10 });
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm();
  const [products, setProducts] = useState<InventoryProductOption[]>([]);
  const [productSearch, setProductSearch] = useState('');
  const [saving, setSaving] = useState(false);
  const [readOnly] = useState(() => inventoryIsReadOnly());
  const [detail, setDetail] = useState<InventoryTransaction>();
  const [editing, setEditing] = useState<InventoryTransaction>();
  const [searchKeyword, setSearchKeyword] = useState('');
  const [searchDirection, setSearchDirection] = useState<InventoryDirection>();
  const currentUserName = useAuthStore((state) => state.user?.displayName ?? '');
  const [reasonSearch, setReasonSearch] = useState('');
  const [historyReasons, setHistoryReasons] = useState<string[]>([]);
  const load = useCallback(
    async () => setData(await inventoryApi.transactions({ ...filters, scope })),
    [filters, scope],
  );
  useEffect(() => {
    setFilters({ scope, page: 1, size: 10 });
    setSearchKeyword('');
    setSearchDirection(undefined);
    setOpen(false);
    setDetail(undefined);
    setEditing(undefined);
    form.resetFields();
  }, [form, scope]);
  useEffect(() => {
    void load().catch(() => message.error('流水加载失败'));
  }, [load]);
  const loadProducts = useCallback(async () => {
    const balances = await inventoryApi.balances({ scope, page: 1, size: 100 });
    setProducts(
      balances.items
        .filter((item) => item.version > 0)
        .map((item) => ({
          id: item.productId,
          code: item.productCode,
          name: item.productName,
          category: item.category,
          status: 'IN_STOCK',
        })),
    );
  }, [scope]);
  useEffect(() => {
    void loadProducts().catch(() => message.error('库存产品加载失败'));
  }, [loadProducts]);
  useEffect(() => {
    let cancelled = false;
    inventoryApi
      .transactionReasons(scope)
      .then((reasons) => {
        if (!cancelled) setHistoryReasons(reasons ?? []);
      })
      .catch(() => {
        if (!cancelled) setHistoryReasons([]);
      });
    return () => {
      cancelled = true;
    };
  }, [scope]);
  const save = async () => {
    const v = await form.validateFields();
    if (readOnly) {
      message.error('当前角色为只读用户，不能修改库存流水');
      return;
    }
    setSaving(true);
    try {
      if (editing) {
        await inventoryApi.updateTransaction(editing.id, { revision: editing.revision,
          businessDate: v.businessDate.format('YYYY-MM-DD'),
          documentNo: v.documentNo,
          reason: v.reason,
          note: v.note,
          actorName: v.actorName,
        });
        setHistoryReasons((prev) =>
          v.reason && !prev.includes(v.reason) ? [v.reason, ...prev] : prev,
        );
        message.success('库存流水已更新');
        setOpen(false);
        setEditing(undefined);
        form.resetFields();
        await load();
        return;
      }
      const selectedProduct = String(v.productId.value);
      const existingProduct = products.find((product) => product.id === selectedProduct);
      const productId = existingProduct
        ? existingProduct.id
        : await inventoryApi.createCustomProduct(selectedProduct, scope);
      await inventoryApi.move({
        ...v,
        productId,
        scope,
        businessDate: v.businessDate.format('YYYY-MM-DD'),
      });
      setHistoryReasons((prev) =>
        v.reason && !prev.includes(v.reason) ? [v.reason, ...prev] : prev,
      );
      message.success('库存变动已保存');
      setOpen(false);
      setProductSearch('');
      form.resetFields();
      await Promise.all([load(), loadProducts()]);
    } catch (error) {
      message.error(errorMessage(error, '库存变动保存失败，请稍后重试'));
    } finally {
      setSaving(false);
    }
  };
  const productOptions = useMemo(() => {
    const options = products.map((p) => ({
      value: p.id,
      label: `${p.code} · ${p.name}${p.category ? `（${p.category}）` : ''}`,
    }));
    const keyword = productSearch.trim();
    if (
      keyword &&
      !products.some(
        (p) => p.name.toLocaleLowerCase() === keyword.toLocaleLowerCase() || p.code === keyword,
      )
    ) {
      options.push({ value: keyword, label: keyword });
    }
    return options;
  }, [productSearch, products]);
  const reasonSelectOptions = useMemo(() => {
    const source = historyReasons.length > 0 ? historyReasons : reasonOptions[scope];
    const values: string[] = [];
    for (const value of source) {
      const trimmed = (value ?? '').trim();
      if (trimmed && !values.includes(trimmed)) values.push(trimmed);
    }
    const base = values.map((value) => ({ value, label: reasonLabels[value] || value }));
    const keyword = reasonSearch.trim();
    if (
      keyword &&
      !base.some(
        (option) =>
          String(option.value).toLowerCase() === keyword.toLowerCase() ||
          option.label.toLowerCase() === keyword.toLowerCase(),
      )
    ) {
      base.unshift({ value: keyword, label: `${keyword}（自定义）` });
    }
    return base;
  }, [scope, reasonSearch, historyReasons]);
  const columns: ColumnsType<InventoryTransaction> = [
    { title: '日期', dataIndex: 'businessDate' },
    { title: '单号', dataIndex: 'documentNo' },
    { title: '产品名称', render: (_, r) => `${r.productCode} · ${r.productName}` },
    {
      title: '变动方向',
      dataIndex: 'direction',
      render: (v) => (
        <Tag color={v === 'INBOUND' ? 'green' : 'orange'}>{v === 'INBOUND' ? '入库' : '扣减'}</Tag>
      ),
    },
    { title: '业务原因', dataIndex: 'reason', render: (v) => reasonLabels[v] || v },
    { title: '数量 KG', dataIndex: 'quantityKg', align: 'right', render: kg },
    { title: '变动前', dataIndex: 'beforeKg', render: kg },
    { title: '变动后', dataIndex: 'afterKg', render: kg },
    { title: '经办人', dataIndex: 'actorName' },
    { title: '备注', dataIndex: 'note', render: (v) => v || '—' },
    {
      title: '操作',
      width: 250,
      render: (_, r) => (
        <Space className="management-table-actions">
          <Button type="link" onClick={() => setDetail(r)}>
            查看
          </Button>
          <Button
            type="link"
            disabled={readOnly || r.reversed || !!r.reversalOf}
            onClick={() => {
              form.setFieldsValue({
                businessDate: dayjs(r.businessDate),
                documentNo: r.documentNo,
                productId: { value: r.productId, label: `${r.productCode} · ${r.productName}` },
                direction: r.direction,
                reason: r.reason,
                quantityKg: r.quantityKg,
                note: r.note,
                actorName: r.actorName,
              });
              setEditing(r);
              setOpen(true);
            }}
          >
            编辑
          </Button>
          {!r.reversed && !r.reversalOf && (
            <Popconfirm
              title="确认生成反向冲销流水？"
              onConfirm={async () => {
                await inventoryApi.reverse(r.id,r.revision);
                message.success('已冲销');
                await load();
              }}
            >
              <Button type="link" danger disabled={readOnly}>
                冲销
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];
  return (
    <InventoryShell
      title={scopeLabels[scope] + '表'}
      description={
        scope === 'RND'
          ? '记录实验、发样、盘点扣减及研发相关入库流水。'
          : '记录生产、研发取样、盘点、出货扣减及生产相关入库流水。'
      }
      extra={
        <Space>
          <LedgerImportExport scope={scope} products={products} onDone={load} disabled={readOnly} />
          <Button
            type="primary"
            icon={<PlusOutlined />}
            disabled={readOnly}
            onClick={() => {
              setEditing(undefined);
              form.setFieldsValue({
                direction: 'INBOUND',
                businessDate: dayjs(),
                documentNo: `${scope === 'RND' ? 'RD' : 'PD'}-${dayjs().format('YYYYMMDD-HHmmss')}`,
                actorName: currentUserName,
              });
              setOpen(true);
            }}
          >
            新增库存变动
          </Button>
        </Space>
      }
    >
      <Card className="inventory-table-card">
        <div className="inventory-filters inventory-ledger-filters">
          <Input.Search
            allowClear
            value={searchKeyword}
            placeholder="搜索单号、产品或备注"
            onChange={(event) => {
              const value = event.target.value;
              setSearchKeyword(value);
              if (!value) setFilters((current) => ({ ...current, keyword: '', page: 1 }));
            }}
            onSearch={(keyword) => setFilters((current) => ({ ...current, keyword, page: 1 }))}
          />
          <Select
            allowClear
            placeholder="变动方向"
            value={searchDirection}
            options={[
              { value: 'INBOUND', label: '入库' },
              { value: 'OUTBOUND', label: '扣减' },
            ]}
            onChange={(value) => {
              setSearchDirection(value);
              setFilters((current) => ({ ...current, direction: value, page: 1 }));
            }}
          />
          <Button
            icon={<ReloadOutlined />}
            onClick={() => {
              setSearchKeyword('');
              setSearchDirection(undefined);
              setFilters({ scope, page: 1, size: 10 });
            }}
          >
            重置
          </Button>
        </div>
        <Table
          rowKey="id"
          columns={columns}
          dataSource={data?.items}
          scroll={{ x: 'max-content' }}
          pagination={{
            current: data?.page,
            total: data?.total,
            pageSize: data?.size,
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 条记录`,
            onChange: (page, size) => setFilters((x) => ({ ...x, page, size })),
          }}
        />
      </Card>
      <Modal
        title={`${editing ? '编辑' : '新增'}${scopeLabels[scope]}变动`}
        open={open}
        width={640}
        className="inventory-entry-modal"
        okText="保存"
        cancelText="取消"
        confirmLoading={saving}
        okButtonProps={{ disabled: readOnly }}
        onCancel={() => {
          setOpen(false);
          setEditing(undefined);
          setProductSearch('');
          form.resetFields();
        }}
        onOk={save}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="businessDate" label="日期" rules={[{ required: true }]}>
                <DatePicker format="YYYY/MM/DD" className="inventory-full" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="documentNo" label="单号" rules={[{ required: true }]}>
                <Input />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="productId" label="产品名称" rules={[{ required: true }]}>
                <Select
                  disabled={!!editing}
                  labelInValue
                  showSearch
                  filterOption
                  optionFilterProp="label"
                  placeholder="选择或输入自定义产品名称"
                  searchValue={productSearch}
                  onSearch={setProductSearch}
                  onChange={() => setProductSearch('')}
                  options={productOptions}
                  notFoundContent={productSearch ? null : '当前类型暂无库存产品，可输入名称创建'}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="direction" label="变动方向" rules={[{ required: true }]}>
                <Select
                  disabled={!!editing}
                  options={[
                    { value: 'INBOUND', label: '入库' },
                    { value: 'OUTBOUND', label: '扣减' },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="reason" label="业务原因" rules={[{ required: true, message: '请选择或输入业务原因' }]}>
                <Select
                  showSearch
                  allowClear
                  placeholder="选择或输入自定义原因"
                  filterOption={(input, option) =>
                    (option?.label ?? '').toLowerCase().includes(input.toLowerCase())
                  }
                  onSearch={setReasonSearch}
                  options={reasonSelectOptions}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="quantityKg" label="数量 KG" rules={[{ required: true }]}>
                <InputNumber
                  disabled={!!editing}
                  min={0.000001}
                  precision={6}
                  placeholder="请输入正数"
                  className="inventory-full"
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="actorName" label="经办人" rules={[{ required: true, message: '请输入经办人' }]}>
                <Input placeholder="请输入经办人姓名" />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="note" label="备注">
            <Input.TextArea rows={3} placeholder="填写批次、用途等补充信息" />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        open={!!detail}
        title="库存流水详情"
        width={640}
        footer={null}
        onCancel={() => setDetail(undefined)}
        destroyOnClose
      >
        <Descriptions
          bordered
          column={2}
          items={
            detail
              ? Object.entries({
                  日期: detail.businessDate,
                  单号: detail.documentNo,
                  产品名称: detail.productName,
                  方向: detail.direction === 'INBOUND' ? '入库' : '扣减',
                  原因: reasonLabels[detail.reason] || detail.reason,
                  数量: kg(detail.quantityKg),
                  变动前: kg(detail.beforeKg),
                  变动后: kg(detail.afterKg),
                  经办人: detail.actorName,
                  备注: detail.note || '—',
                }).map(([label, children]) => ({ label, children }))
              : []
          }
        />
      </Modal>
    </InventoryShell>
  );
}

export function SampleRecordsPage() {
  return <BusinessRecords type="sample" />;
}
export function ShipmentRecordsPage() {
  return <ShipmentHeaders />;
}
export function ShipmentDetailsPage() {
  const [searchParams] = useSearchParams();
  const shipmentNo = searchParams.get('shipmentNo') ?? '';
  return <BusinessRecords type="shipment" shipmentNo={shipmentNo} />;
}
function ShipmentHeaders() {
  const navigate = useNavigate();
  const [productsByScope, setProductsByScope] = useState<Record<InventoryScope, InventoryProductOption[]>>({ RND: [], PRODUCTION: [] });
  const [customers, setCustomers] = useState<BusinessPartner[]>([]);
  const [data, setData] = useState<{ items: ShipmentHeader[]; page: number; size: number; total: number }>();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [keyword, setKeyword] = useState('');
  const [open, setOpen] = useState(false);
  const [editingHeader, setEditingHeader] = useState<ShipmentHeader>();
  const [saving, setSaving] = useState(false);
  const [completingNo, setCompletingNo] = useState<string>();
  const [selectedHeaderNos, setSelectedHeaderNos] = useState<string[]>([]);
  const [bulkSaving, setBulkSaving] = useState(false);
  const [form] = Form.useForm();
  const load = useCallback(async () => setData(await inventoryApi.shipmentHeaders({ page, size, keyword })), [page, size, keyword]);
  useEffect(() => { void load().catch((error: unknown) => message.error(errorMessage(error, '出货单加载失败'))); }, [load]);
  useEffect(() => {
    void Promise.all([inventoryApi.productOptions('RND'), inventoryApi.productOptions('PRODUCTION'),
      getPartners({ status: 'ACTIVE', page: 1, size: 100 })])
      .then(([rnd, production, partners]) => { setProductsByScope({ RND: rnd, PRODUCTION: production }); setCustomers(partners.items); })
      .catch((error: unknown) => message.error(errorMessage(error, '导入选项加载失败')));
  }, []);
  const saveHeader = async () => {
    try {
      const values = await form.validateFields() as { shipmentNo: string; businessDate: Dayjs };
      setSaving(true);
      if (editingHeader) await inventoryApi.updateShipmentHeaderDate(editingHeader.shipmentNo, values.businessDate.format('YYYY-MM-DD'));
      else await inventoryApi.createShipmentHeader({ shipmentNo: values.shipmentNo.trim(), businessDate: values.businessDate.format('YYYY-MM-DD') });
      setOpen(false); form.resetFields(); await load();
      if (!editingHeader) navigate(`/inventory/shipment-details?shipmentNo=${encodeURIComponent(values.shipmentNo.trim())}`);
      setEditingHeader(undefined);
    } catch (error) { message.error(errorMessage(error, '保存出货单失败')); }
    finally { setSaving(false); }
  };
  const completeHeader = async (number: string) => {
    setCompletingNo(number);
    try {
      const changed = await inventoryApi.completeShipmentHeader(number);
      message.success(`已完成 ${changed} 条出货明细`);
      setSelectedHeaderNos((current) => current.filter((item) => item !== number));
      await load();
    } catch (error) { message.error(errorMessage(error, '出货单完成失败')); }
    finally { setCompletingNo(undefined); }
  };
  const completeSelectedHeaders = async () => {
    setBulkSaving(true);
    try {
      const result = await inventoryApi.completeShipmentHeaders(selectedHeaderNos);
      message.success(`已完成 ${result.headerCount} 张出货单、${result.detailCount} 条明细`);
      setSelectedHeaderNos([]);
      await load();
    } catch (error) { message.error(errorMessage(error, '批量完成出货单失败')); }
    finally { setBulkSaving(false); }
  };
  return <InventoryShell title="出货记录表" description="按出货单号查看出货明细。"
    extra={<Space wrap><BusinessImportExport type="shipment" hideExport productsByScope={productsByScope} customers={customers} onDone={load} disabled={inventoryIsReadOnly()} />
      <Button type="primary" icon={<PlusOutlined />} disabled={inventoryIsReadOnly()} onClick={() => {
      setEditingHeader(undefined);
      form.setFieldsValue({ shipmentNo: `CH-${dayjs().format('YYYYMMDD-HHmmss')}`, businessDate: dayjs() });
      setOpen(true);
    }}>新增出货单</Button></Space>}>
    <div className="inventory-filters inventory-record-filters">
      <Input.Search allowClear placeholder="搜索出货单号" onSearch={(value) => { setPage(1); setKeyword(value); }} />
      <Popconfirm title="确认批量完成出货" description={`将选中的 ${selectedHeaderNos.length} 张出货单设为已完成并扣减库存？`}
        onConfirm={() => { void completeSelectedHeaders(); }} okButtonProps={{ loading: bulkSaving }}>
        <Button icon={<CheckOutlined />} disabled={inventoryIsReadOnly() || selectedHeaderNos.length === 0 || bulkSaving}>
          批量设为已完成{selectedHeaderNos.length > 0 ? ` (${selectedHeaderNos.length})` : ''}
        </Button>
      </Popconfirm>
    </div>
    <Table rowKey="shipmentNo" dataSource={data?.items} rowSelection={{
      selectedRowKeys: selectedHeaderNos,
      preserveSelectedRowKeys: true,
      getCheckboxProps: (row: ShipmentHeader) => ({ disabled: inventoryIsReadOnly() || row.detailCount === 0 || row.completedCount === row.detailCount }),
      onChange: (keys) => {
        if (keys.length > 100) { message.warning('最多选择 100 张出货单'); return; }
        setSelectedHeaderNos(keys.map(String));
      },
    }} columns={[
      { title: '出货单号', dataIndex: 'shipmentNo', render: (value: string) =>
        <Button type="link" onClick={() => navigate(`/inventory/shipment-details?shipmentNo=${encodeURIComponent(value)}`)}>{value}</Button> },
      { title: '日期', dataIndex: 'businessDate' },
      { title: '明细条数', dataIndex: 'detailCount' },
      { title: '总数量 KG', dataIndex: 'totalQuantityKg' },
      { title: '完成进度', render: (_: unknown, row: ShipmentHeader) => `${row.completedCount}/${row.detailCount}` },
      { title: '操作', render: (_: unknown, row: ShipmentHeader) => <Space>
        <Popconfirm title="确认出货完成" description={`将该单 ${row.detailCount - row.completedCount} 条未完成明细设为已完成并扣减库存？`}
          onConfirm={() => { void completeHeader(row.shipmentNo); }}
          okButtonProps={{ loading: completingNo === row.shipmentNo }}>
          <Button type="link" disabled={inventoryIsReadOnly() || row.detailCount === 0 || row.completedCount === row.detailCount}>设为已完成</Button>
        </Popconfirm>
        <Button type="link" disabled={inventoryIsReadOnly() || row.completedCount > 0}
          onClick={() => { setEditingHeader(row); form.setFieldsValue({ shipmentNo: row.shipmentNo, businessDate: dayjs(row.businessDate) }); setOpen(true); }}>编辑日期</Button>
      </Space> },
    ]} pagination={{ current: page, pageSize: size, total: data?.total, showSizeChanger: true,
      onChange: (nextPage, nextSize) => { setPage(nextPage); setSize(nextSize); } }} />
    <Modal title={editingHeader ? '编辑出货日期' : '新增出货单'} open={open} confirmLoading={saving} onCancel={() => { setOpen(false); setEditingHeader(undefined); form.resetFields(); }}
      onOk={() => { void saveHeader(); }}>
      <Form form={form} layout="vertical">
        <Form.Item name="shipmentNo" label="出货单号" rules={[{ required: true, whitespace: true }]}><Input disabled={!!editingHeader} /></Form.Item>
        <Form.Item name="businessDate" label="日期" rules={[{ required: true }]}><DatePicker className="inventory-full" /></Form.Item>
      </Form>
    </Modal>
  </InventoryShell>;
}
type BusinessFormValues = {
  businessDate: Dayjs;
  dispatchNo: string;
  shipmentNo: string;
  productId: { value: string; label?: string };
  scope: InventoryScope;
  quantityKg: number;
  packageQuantity?: number;
  customerId: string;
  newCustomerName?: string;
  recipientName: string;
  recipientPhone: string;
  sampleAddress?: string;
  courierCompany?: string;
  trackingNo?: string;
  senderName?: string;
  clientContact?: string;
  clientContactPhone?: string;
  customerRequirement?: string;
  customerFeedback?: string;
  status?: 'PENDING' | 'COMPLETED';
  batchNo?: string;
  specification?: string;
  totalQuantityKg?: number;
  shippingAddress?: string;
  coaNo?: string;
  note?: string;
};

function ShipmentSheet({ date, rows, total, page, pages, readOnly, addDisabled, onEdit, onAdd, onPage }: {
  date?: string;
  rows: Shipment[];
  total: number;
  page: number;
  pages: number;
  readOnly: boolean;
  addDisabled: boolean;
  onEdit: (row: Shipment) => void;
  onAdd: () => void;
  onPage: (page: number) => void;
}) {
  return <div className="shipment-sheet-layout">
    <div className="shipment-sheet-actions">
      <Button type="primary" icon={<PlusOutlined />} disabled={addDisabled} onClick={onAdd}>添加明细</Button>
    </div>
    <div className="shipment-sheet-scroll">
      <div className="shipment-sheet-mobile-title">出货记录表</div>
      <table className="shipment-sheet" aria-label="出货记录表">
        <colgroup><col className="shipment-sheet-customer" /><col className="shipment-sheet-product" />
          <col className="shipment-sheet-batch" /><col className="shipment-sheet-number" />
          <col className="shipment-sheet-number" /><col className="shipment-sheet-number" />
          <col className="shipment-sheet-courier" /><col className="shipment-sheet-note" /></colgroup>
        <thead>
          <tr className="shipment-sheet-title"><th colSpan={8}>出货记录表</th></tr>
          <tr className="shipment-sheet-date"><th>日期</th><th>{date ?? '—'}</th><th colSpan={6} /></tr>
          <tr><th>客户名</th><th>品名</th><th>批次号</th><th>规格</th><th>数量</th><th>总数量</th><th>快递公司</th><th>备注</th></tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.id}>
              <td><span className="shipment-sheet-select"><span>{row.customerName || '—'}</span>
                {row.status === 'COMPLETED' && <CheckOutlined title="已完成" />}</span></td>
              <td title={row.productName}>{row.productName}</td>
              <td>{row.batchNo || '—'}</td>
              <td>{row.specification || '—'}</td>
              <td>{row.packageQuantity ?? '—'}</td>
              <td>{row.totalQuantityKg ?? row.quantityKg}</td>
              <td>{row.courierCompany || '—'}</td>
              <td><div className="shipment-sheet-note-content"><span>{row.note || '—'}</span>
                <Tooltip title="编辑明细"><Button type="text" size="small" icon={<EditOutlined />}
                  aria-label={`编辑 ${row.productName}`} disabled={readOnly} onClick={() => onEdit(row)} /></Tooltip>
              </div></td>
            </tr>
          ))}
          {Array.from({ length: Math.max(0, 12 - rows.length) }, (_, index) => (
            <tr key={`empty-${index}`} className="shipment-sheet-empty"><td /><td /><td /><td /><td /><td /><td /><td /></tr>
          ))}
        </tbody>
      </table>
    </div>
    <div className="shipment-sheet-pagination">
      <span>共 {total} 条明细</span>
      <Button disabled={page <= 1} onClick={() => onPage(page - 1)}>上一页</Button>
      <span>{page} / {pages || 1}</span>
      <Button disabled={page >= pages} onClick={() => onPage(page + 1)}>下一页</Button>
    </div>
  </div>;
}

function BusinessRecords({ type, shipmentNo }: { type: 'sample' | 'shipment'; shipmentNo?: string }) {
  const sample = type === 'sample';
  const navigate = useNavigate();
  const [header, setHeader] = useState<ShipmentHeader>();
  const [data, setData] = useState<PageData>();
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm();
  const selectedCustomerId = Form.useWatch('customerId', form) as string | undefined;
  const sampleSpecification = Form.useWatch('specification', form) as number | undefined;
  const samplePackageQuantity = Form.useWatch('packageQuantity', form) as number | undefined;
  const sampleTotalKg = sampleSpecification != null && samplePackageQuantity != null
    ? Math.round(sampleSpecification * samplePackageQuantity * 1_000_000) / 1_000_000 : undefined;
  const recipientRequest = useRef(0);
  const [productsByScope, setProductsByScope] = useState<
    Record<InventoryScope, InventoryProductOption[]>
  >({ RND: [], PRODUCTION: [] });
  const [formScope, setFormScope] = useState<InventoryScope>(sample ? 'RND' : 'PRODUCTION');
  const [productSearch, setProductSearch] = useState('');
  const [customers, setCustomers] = useState<BusinessPartner[]>([]);
  const [recipientText, setRecipientText] = useState('');
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState<SampleDispatch | Shipment>();
  const [selectedRecordIds, setSelectedRecordIds] = useState<string[]>([]);
  const [statusOpen, setStatusOpen] = useState(false);
  const [statusSaving, setStatusSaving] = useState(false);
  const [coaOptions, setCoaOptions] = useState<QualityRecord[]>([]);
  const [coaSearch, setCoaSearch] = useState('');
  const [coaPage, setCoaPage] = useState(1);
  const [coaHasMore, setCoaHasMore] = useState(false);
  const [coaLoading, setCoaLoading] = useState(false);
  const [coaOpen, setCoaOpen] = useState(false);
  const [coaVerified, setCoaVerified] = useState(true);
  const coaRequest = useRef(0);
  const [readOnly] = useState(() => inventoryIsReadOnly());
  const [searchKeyword, setSearchKeyword] = useState('');
  const [filters, setFilters] = useState({ keyword: '', page: 1, size: 10 });
  const currentUserName = useAuthStore((state) => state.user?.displayName ?? '');
  const canCreateCustomer = useAuthStore((state) => state.can('customer.create'));
  const recognizeRecipient = (text: string) => {
    recipientRequest.current += 1;
    setRecipientText(text);
    const parsed = parseRecipient(text);
    form.setFieldsValue({ recipientName: parsed.name,
      recipientPhone: parsed.phone, [sample ? 'sampleAddress' : 'shippingAddress']: parsed.address,
      clientContact: parsed.name, clientContactPhone: parsed.phone });
  };
  const searchCustomers = async (keyword: string) => {
    if (!keyword.trim()) return;
    try { setCustomers((await getPartners({ keyword, status: 'ACTIVE', page: 1, size: 30 })).items); }
    catch { message.error('客户搜索失败'); }
  };
  const loadCoaOptions = useCallback(async (keyword: string, page: number) => {
    const request = ++coaRequest.current;
    setCoaLoading(true);
    try {
      const result = await qualityApi.records({ type: 'coa', status: '已签发', keyword: keyword.trim(), page, size: 20 });
      if (request !== coaRequest.current) return;
      setCoaOptions((current) => page === 1 ? result.items : [
        ...current, ...result.items.filter((item) => !current.some((existing) => existing.id === item.id)),
      ]);
      setCoaPage(page);
      setCoaHasMore(page < result.totalPages);
    } catch (error) {
      if (request === coaRequest.current) message.error(errorMessage(error, 'COA 列表加载失败'));
    } finally {
      if (request === coaRequest.current) setCoaLoading(false);
    }
  }, []);
  useEffect(() => {
    if (!coaOpen) return;
    const timer = window.setTimeout(() => void loadCoaOptions(coaSearch, 1), 250);
    return () => window.clearTimeout(timer);
  }, [coaOpen, coaSearch, loadCoaOptions]);
  const selectCustomer = async (id?: string) => {
    const request = ++recipientRequest.current;
    const customer = customers.find((item) => item.id === id);
    form.setFieldsValue({ recipientName: undefined, recipientPhone: undefined,
      [sample ? 'sampleAddress' : 'shippingAddress']: customer?.address,
      clientContact: undefined, clientContactPhone: undefined });
    if (!id) return;
    const [contactsResult, preferenceResult] = await Promise.allSettled([
      getPartnerContacts(id), inventoryApi.sampleRecipientPreference(id),
    ]);
    if (request !== recipientRequest.current || form.getFieldValue('customerId') !== id) return;
    const active = contactsResult.status === 'fulfilled'
      ? contactsResult.value.filter((contact) => contact.status === 'ACTIVE') : [];
    const contact = active.find((item) => item.primaryContact) ?? active[0];
    const preference = preferenceResult.status === 'fulfilled' ? preferenceResult.value : null;
    form.setFieldsValue({
      recipientName: preference?.recipientName ?? contact?.name,
      recipientPhone: preference?.recipientPhone ?? contact?.phone,
      [sample ? 'sampleAddress' : 'shippingAddress']: preference?.sampleAddress ?? customer?.address,
      clientContact: contact?.name, clientContactPhone: contact?.phone,
    });
    if (contactsResult.status === 'rejected' || preferenceResult.status === 'rejected')
      message.warning('部分收件信息加载失败，请核对填写');
  };
  const load = useCallback(
    async () =>
      setData(sample ? await inventoryApi.samples(filters) : shipmentNo
        ? await inventoryApi.shipmentDetails(shipmentNo, filters) : await inventoryApi.shipments(filters)),
    [filters, sample, shipmentNo],
  );
  const productScope: InventoryScope = formScope;
  const loadProducts = useCallback(async () => {
    const scopes: InventoryScope[] = ['RND', 'PRODUCTION'];
    const loaded = await Promise.all(
      scopes.map(async (scope) => [scope, await inventoryApi.productOptions(scope)] as const),
    );
    setProductsByScope((current) => {
      const next = { ...current };
      loaded.forEach(([scope, options]) => {
        next[scope] = options;
      });
      return next;
    });
  }, []);
  const products = productsByScope[productScope];
  useEffect(() => {
    void load();
    void loadProducts();
    void getPartners({ status: 'ACTIVE', page: 1, size: 100 })
      .then((result) => setCustomers(result.items))
      .catch(() => message.error('客户列表加载失败'));
  }, [load, loadProducts]);
  useEffect(() => {
    if (shipmentNo) void inventoryApi.shipmentHeader(shipmentNo).then(setHeader)
      .catch((error: unknown) => message.error(errorMessage(error, '出货单加载失败')));
  }, [shipmentNo]);
  const openEdit = (record: SampleDispatch | Shipment) => {
    coaRequest.current += 1;
    setCoaOptions([]);
    setCoaSearch('');
    setEditing(record);
    const values: Record<string, unknown> = {
      businessDate: dayjs(record.businessDate),
      productId: { value: record.productId, label: record.productName },
      quantityKg: record.quantityKg,
      customerId: record.customerId,
    };
    if ('dispatchNo' in record) {
      setFormScope(record.scope ?? 'RND');
      Object.assign(values, {
        scope: record.scope ?? 'RND',
        dispatchNo: record.dispatchNo,
        newCustomerName: record.customerName,
        specification: record.specification && Number.isFinite(Number(record.specification))
          ? Number(record.specification) : undefined,
        packageQuantity: record.packageQuantity,
        recipientName: record.recipientName,
        recipientPhone: record.recipientPhone,
        sampleAddress: record.sampleAddress,
        courierCompany: record.courierCompany,
        trackingNo: record.trackingNo,
        senderName: record.senderName || record.operator,
        clientContact: record.clientContact,
        clientContactPhone: record.clientContactPhone,
        customerRequirement: record.customerRequirement,
        customerFeedback: record.customerFeedback,
        status: record.status,
      });
    } else {
      const existingCoa = record.coaNo;
      setCoaVerified(!existingCoa);
      if (existingCoa) {
        void qualityApi.recordByBusinessNo('coa', existingCoa)
          .then((result) => {
            if (form.getFieldValue('coaNo') === existingCoa)
              setCoaVerified(result.data.status === '已签发');
          })
          .catch(() => {
            if (form.getFieldValue('coaNo') === existingCoa) setCoaVerified(false);
          });
      }
      setFormScope(record.scope ?? 'PRODUCTION');
      Object.assign(values, {
        scope: record.scope ?? 'PRODUCTION',
        shipmentNo: record.shipmentNo,
        newCustomerName: record.customerName,
        senderName: record.senderName || record.operator,
        batchNo: record.batchNo,
        specification: record.specification && Number.isFinite(Number(record.specification))
          ? Number(record.specification) : undefined,
        packageQuantity: record.packageQuantity,
        totalQuantityKg: record.totalQuantityKg ?? record.quantityKg,
        recipientName: record.recipientName,
        recipientPhone: record.recipientPhone,
        shippingAddress: record.shippingAddress,
        courierCompany: record.courierCompany,
        trackingNo: record.trackingNo,
        coaNo: existingCoa,
        note: record.note,
        status: record.status,
      });
    }
    form.setFieldsValue(values);
    setOpen(true);
  };
  const save = async () => {
    const v = (await form.validateFields()) as BusinessFormValues;
    if (inventoryIsReadOnly()) {
      message.error(`当前角色为只读用户，不能修改${sample ? '发样' : '出货'}记录`);
      return;
    }
    if (sample && editing && 'dispatchNo' in editing && editing.status === 'COMPLETED' && sampleTotalKg !== editing.quantityKg) {
      message.error('已扣库存的总数量不能在编辑时修改');
      return;
    }
    setSaving(true);
    try {
      if (editing) {
        if (sample && 'dispatchNo' in editing) {
          const updateInput = {
            revision: editing.revision,
            dispatchNo: v.dispatchNo,
            businessDate: v.businessDate.format('YYYY-MM-DD'),
            specification: v.specification == null ? undefined : String(v.specification),
            packageQuantity: v.packageQuantity,
            customerId: v.customerId,
            recipientName: v.recipientName,
            recipientPhone: v.recipientPhone,
            sampleAddress: v.sampleAddress,
            courierCompany: v.courierCompany,
            trackingNo: v.trackingNo,
            clientContact: v.clientContact,
            clientContactPhone: v.clientContactPhone,
            senderName: v.senderName,
            customerRequirement: v.customerRequirement,
            customerFeedback: v.customerFeedback,
          };
          if (v.customerId) await inventoryApi.updateSample(editing.id, updateInput);
          else await inventoryApi.updateSampleForNewCustomer(editing.id, updateInput, v.newCustomerName?.trim() ?? '');
        } else {
          const updateInput = {
            revision: editing.revision,
            shipmentNo: v.shipmentNo,
            businessDate: v.businessDate.format('YYYY-MM-DD'),
            customerId: v.customerId,
            senderName: v.senderName,
            batchNo: v.batchNo,
            recipientName: v.recipientName,
            recipientPhone: v.recipientPhone,
            shippingAddress: v.shippingAddress,
            courierCompany: v.courierCompany,
            trackingNo: v.trackingNo,
            note: v.note,
            coaNo: v.coaNo,
          };
          if (v.customerId) await inventoryApi.updateShipment(editing.id, updateInput);
          else await inventoryApi.updateShipmentForNewCustomer(editing.id, updateInput, v.newCustomerName?.trim() ?? '');
        }
        message.success(sample ? '发样记录已更新' : '出货记录已更新');
        setOpen(false);
        setEditing(undefined);
        form.resetFields();
        await load();
        return;
      }
      const selectedProduct = String(v.productId.value);
      const existingProduct = products.find((product) => product.id === selectedProduct);
      const productId = existingProduct
        ? existingProduct.id
        : await inventoryApi.createCustomProduct(selectedProduct, productScope);
      if (sample) {
        const { newCustomerName, ...sampleValues } = v;
        const sampleInput = { ...sampleValues, specification: String(v.specification),
          status: 'PENDING', quantityKg: sampleTotalKg, productId, businessDate: v.businessDate.format('YYYY-MM-DD') };
        if (!v.customerId) {
          await inventoryApi.createSampleForNewCustomer(sampleInput, newCustomerName?.trim() ?? '');
        } else {
          await inventoryApi.createSample(sampleInput);
        }
      } else {
        const { batchNo, coaNo, note } = v;
        const shipmentInput = {
          shipmentNo: v.shipmentNo,
          scope: v.scope,
          packageQuantity: v.packageQuantity,
          customerId: v.customerId,
          senderName: v.senderName,
          recipientName: v.recipientName,
          recipientPhone: v.recipientPhone,
          shippingAddress: v.shippingAddress,
          courierCompany: v.courierCompany,
          trackingNo: v.trackingNo,
          specification: v.specification == null ? undefined : String(v.specification),
          productId,
          note,
          coaNo,
          status: 'PENDING',
          batchNo,
          businessDate: v.businessDate.format('YYYY-MM-DD'),
        };
        if (v.customerId) await inventoryApi.createShipment(shipmentInput);
        else await inventoryApi.createShipmentForNewCustomer(shipmentInput, v.newCustomerName?.trim() ?? '');
      }
      message.success(sample ? '发样记录已保存' : '出货记录已保存');
      setOpen(false);
      setProductSearch('');
      form.resetFields();
      await Promise.all([load(), loadProducts()]);
    } catch (error) {
      message.error(errorMessage(error, `${sample ? '发样' : '出货'}记录保存失败，请稍后重试`));
    } finally {
      setSaving(false);
    }
  };
  const productOptions = useMemo(() => {
    const options = products.map((p) => ({
      value: p.id,
      label: `${p.code} · ${p.name} · ${p.category}`,
    }));
    const keyword = productSearch.trim();
    if (
      keyword &&
      !products.some(
        (p) => p.name.toLocaleLowerCase() === keyword.toLocaleLowerCase() || p.code === keyword,
      )
    ) {
      options.push({ value: keyword, label: keyword });
    }
    return options;
  }, [productSearch, products]);
  const columns = useMemo<ColumnsType<SampleDispatch | Shipment>>(
    () =>
      sample
        ? [
            { title: '日期', dataIndex: 'businessDate' },
            { title: '发样单号', dataIndex: 'dispatchNo', render: (value: string, record: SampleDispatch | Shipment) => (
              <Button type="link" disabled={readOnly} style={{ padding: 0 }} onClick={() => openEdit(record)}>
                {value}
              </Button>
            ) },
            { title: '状态', dataIndex: 'status', render: (status: string) =>
              <Tag color={status === 'COMPLETED' ? 'success' : 'processing'}>{status === 'COMPLETED' ? '已完成' : '未完成'}</Tag> },
            {
              title: '库存类型',
              dataIndex: 'scope',
              render: (value: InventoryScope | undefined) => scopeLabels[value ?? 'RND'],
            },
            { title: '产品名称', dataIndex: 'productName' },
            { title: '规格 KG', dataIndex: 'specification', render: (value: string | undefined) => value || '—' },
            { title: '数量', dataIndex: 'packageQuantity', align: 'right', render: (value: number | undefined) => value ?? '—' },
            { title: '总数量 KG', dataIndex: 'quantityKg', align: 'right', render: kg },
            {
              title: '本公司发样人',
              dataIndex: 'senderName',
              render: (value: string | undefined, record: SampleDispatch | Shipment) =>
                value || record.operator,
            },
            { title: '客户名称', dataIndex: 'customerName' },
            { title: '收件人', dataIndex: 'recipientName' },
            { title: '收件人电话', dataIndex: 'recipientPhone', render: (v: string | undefined) => v || '—' },
            { title: '发样地址', dataIndex: 'sampleAddress', render: (v: string | undefined) => v || '—' },
            { title: '快递公司', dataIndex: 'courierCompany', render: (v: string | undefined) => v || '—' },
            { title: '快递单号', dataIndex: 'trackingNo', render: (v: string | undefined) => v || '—' },
            {
              title: '客户联系人',
              dataIndex: 'clientContact',
              render: (v: string | undefined) => v || '—',
            },
            {
              title: '客户联系人电话',
              dataIndex: 'clientContactPhone',
              render: (v: string | undefined) => v || '—',
            },
            {
              title: '客户要求',
              dataIndex: 'customerRequirement',
              width: 220,
              render: (value: string | undefined) =>
                value ? <span className="inventory-cell-wrap">{value}</span> : '—',
            },
            {
              title: '客户测试反馈',
              dataIndex: 'customerFeedback',
              width: 220,
              render: (value: string | undefined) =>
                value ? <span className="inventory-cell-wrap">{value}</span> : '—',
            },
            {
              title: '操作',
              width: 100,
              render: (_: unknown, record: SampleDispatch | Shipment) => (
                <Space className="management-table-actions">
                  <Button type="link" disabled={readOnly} onClick={() => openEdit(record)}>
                    编辑
                  </Button>
                </Space>
              ),
            },
          ]
        : [
            { title: '日期', dataIndex: 'businessDate' },
            { title: '出货单号', dataIndex: 'shipmentNo', render: (value: string, record: SampleDispatch | Shipment) => (
              <Button type="link" disabled={readOnly} style={{ padding: 0 }} onClick={() => openEdit(record)}>
                {value}
              </Button>
            ) },
            { title: '状态', dataIndex: 'status', render: (v: string) =>
              <Tag color={v === 'COMPLETED' ? 'success' : 'processing'}>{v === 'COMPLETED' ? '已完成' : '未完成'}</Tag> },
            {
              title: '库存类型',
              dataIndex: 'scope',
              render: (value: InventoryScope | undefined) => scopeLabels[value ?? 'PRODUCTION'],
            },
            { title: '产品名称', dataIndex: 'productName' },
            { title: '数量', dataIndex: 'packageQuantity', align: 'right', render: (v: number | undefined) => v == null ? '—' : v },
            { title: '客户名称', dataIndex: 'customerName' },
            { title: '批次号', dataIndex: 'batchNo', render: (v: string | undefined) => v || '—' },
            { title: '规格', dataIndex: 'specification', render: (v: string | undefined) => v || '—' },
            { title: '总数量 KG', dataIndex: 'totalQuantityKg', align: 'right', render: (v: number | undefined, row: SampleDispatch | Shipment) => kg(v ?? row.quantityKg) },
            { title: '收件人', dataIndex: 'recipientName', render: (v: string | undefined) => v || '—' },
            { title: '收件人电话', dataIndex: 'recipientPhone', render: (v: string | undefined) => v || '—' },
            { title: '收货地址', dataIndex: 'shippingAddress', render: (v: string | undefined) => v || '—' },
            { title: '快递公司', dataIndex: 'courierCompany', render: (v: string | undefined) => v || '—' },
            { title: '快递单号', dataIndex: 'trackingNo', render: (v: string | undefined) => v || '—' },
            {
              title: '出货人',
              dataIndex: 'senderName',
              render: (value: string | undefined, record: SampleDispatch | Shipment) =>
                value || record.operator,
            },
            {
              title: '备注',
              dataIndex: 'note',
              width: 280,
              render: (value: string | undefined) =>
                value ? <span className="inventory-cell-wrap">{value}</span> : '—',
            },
            {
              title: '操作',
              width: 100,
              render: (_: unknown, record: SampleDispatch | Shipment) => (
                <Space className="management-table-actions">
                  <Button
                    type="link"
                    disabled={inventoryIsReadOnly()}
                    onClick={() => openEdit(record)}
                  >
                    编辑
                  </Button>
                </Space>
              ),
            },
          ],
    [openEdit, sample],
  );
  const openCreate = () => {
    form.resetFields();
    setEditing(undefined);
    setRecipientText('');
    setFormScope(sample ? 'RND' : 'PRODUCTION');
    setProductSearch('');
    coaRequest.current += 1;
    setCoaOptions([]);
    setCoaSearch('');
    form.setFieldsValue({
      businessDate: header ? dayjs(header.businessDate) : dayjs(),
      status: 'PENDING',
      coaNo: undefined,
      scope: sample ? 'RND' : 'PRODUCTION',
      senderName: currentUserName,
      [sample ? 'dispatchNo' : 'shipmentNo']: sample
        ? `FY-${dayjs().format('YYYYMMDD-HHmmss')}` : shipmentNo,
    });
    setOpen(true);
    setCoaVerified(true);
  };
  return (
    <InventoryShell
      title={sample ? '发样记录表' : `出货明细 · ${shipmentNo ?? ''}`}
      description={
        sample
          ? '发样完成后按所选库存类型扣减研发或生产库存并生成对应库存流水。'
          : '出货记录完成后按所选库存类型扣减库存并生成对应库存流水。'
      }
      extra={
        <div className="inventory-record-actions">
          {!sample && <Button onClick={() => navigate('/inventory/shipments')}>返回主表</Button>}
          <BusinessImportExport
            type={type}
            shipmentNo={shipmentNo}
            hideImport={!sample}
            productsByScope={productsByScope}
            customers={customers}
            onDone={load}
            disabled={readOnly}
          />
          {sample && <Button
            type="primary"
            icon={<PlusOutlined />}
            disabled={readOnly}
            onClick={openCreate}
          >
            新增发样
          </Button>}
        </div>
      }
    >
      {sample ? <Card className="inventory-table-card">
        <div className="inventory-filters inventory-record-filters">
          <Input.Search
            allowClear
            value={searchKeyword}
            placeholder={
              sample
                ? '搜索发样单号、产品、客户、收件人、电话、快递单号'
                : '搜索出货单号、产品、客户、批次号、收件人、快递公司'
            }
            onChange={(event) => {
              const value = event.target.value;
              setSearchKeyword(value);
              if (!value) setFilters((current) => ({ ...current, keyword: '', page: 1 }));
            }}
            onSearch={(value) => setFilters((current) => ({ ...current, keyword: value, page: 1 }))}
          />
          <Button
            icon={<ReloadOutlined />}
            onClick={() => {
              setSearchKeyword('');
              setFilters((current) => ({ ...current, keyword: '', page: 1 }));
            }}
          >
            重置
          </Button>
          <Button icon={<CheckOutlined />} disabled={readOnly || selectedRecordIds.length === 0}
            onClick={() => setStatusOpen(true)}>
            设为已完成{selectedRecordIds.length > 0 ? ` (${selectedRecordIds.length})` : ''}
          </Button>
        </div>
        <Table
          rowKey="id"
          rowSelection={{
            selectedRowKeys: selectedRecordIds,
            preserveSelectedRowKeys: true,
            onChange: (keys) => {
              if (keys.length > 100) { message.warning(`最多选择 100 条${sample ? '发样' : '出货'}记录`); return; }
              setSelectedRecordIds(keys.map(String));
            },
          }}
          columns={columns}
          dataSource={data?.items}
          scroll={{ x: 'max-content' }}
          pagination={{
            current: data?.page,
            total: data?.total,
            pageSize: data?.size,
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 条记录`,
            onChange: (page, size) => setFilters((current) => ({ ...current, page, size })),
          }}
        />
      </Card> : <ShipmentSheet date={header?.businessDate}
        rows={data?.items.filter((row): row is Shipment => 'shipmentNo' in row) ?? []}
        total={data?.total ?? 0} page={data?.page ?? 1} pages={data?.totalPages ?? 0}
        readOnly={readOnly} addDisabled={readOnly || !header}
        onEdit={openEdit} onAdd={openCreate}
        onPage={(page) => setFilters((current) => ({ ...current, page }))} />}
      {sample && <Modal title="确认发样完成" open={statusOpen} okText="设为已完成" cancelText="取消"
        confirmLoading={statusSaving} okButtonProps={{ disabled: !selectedRecordIds.length }}
        onCancel={() => setStatusOpen(false)}
        onOk={async () => {
          setStatusSaving(true);
          try {
            const changed = await inventoryApi.updateSampleStatuses(selectedRecordIds);
            message.success(`已完成 ${changed} 条发样记录`);
            setStatusOpen(false);
            setSelectedRecordIds([]);
            await load();
          } catch (error) {
            message.error(errorMessage(error, '修改状态失败'));
          } finally {
            setStatusSaving(false);
          }
        }}>
        确认将选中的 {selectedRecordIds.length} 条发样记录设为已完成？
      </Modal>}
      <Modal
        width={650}
        className="inventory-entry-modal"
        title={`${editing ? '编辑' : '新增'}${sample ? '发样' : '出货明细'}`}
        open={open}
        okText="保存"
        cancelText="取消"
        confirmLoading={saving}
        okButtonProps={{ disabled: readOnly || (!selectedCustomerId && !canCreateCustomer) }}
        onCancel={() => {
          setOpen(false);
          setEditing(undefined);
          setProductSearch('');
          setRecipientText('');
          form.resetFields();
        }}
        onOk={save}
      >
        <Form form={form} layout="vertical" onValuesChange={(changed) => {
          if (['recipientName', 'recipientPhone', 'sampleAddress', 'shippingAddress'].some((field) => field in changed))
            recipientRequest.current += 1;
          if (!sample && ('specification' in changed || 'packageQuantity' in changed)) {
            const specification = Number(form.getFieldValue('specification'));
            const quantity = Number(form.getFieldValue('packageQuantity'));
            form.setFieldValue('totalQuantityKg', specification > 0 && quantity > 0
              ? Number((specification * quantity).toFixed(6)) : undefined);
          }
        }}>
          <Form.Item name="customerId" hidden><Input /></Form.Item>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="businessDate" label="日期" rules={[{ required: true }]}>
                <DatePicker className="inventory-full" disabled={!sample && !!shipmentNo} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name={sample ? 'dispatchNo' : 'shipmentNo'}
                label={sample ? '发样单号' : '出货单号'}
                rules={[{ required: true }]}
              >
                <Input disabled={!sample && !!shipmentNo} />
              </Form.Item>
            </Col>
          </Row>
          {sample && (
            <>
              <Row gutter={16}>
                <Col span={12}>
                  <Form.Item name="scope" label="库存类型" rules={[{ required: true }]}>
                    <Select
                      disabled={!!editing}
                      options={[
                        { value: 'RND', label: '研发库存' },
                        { value: 'PRODUCTION', label: '生产库存' },
                      ]}
                      onChange={(value: InventoryScope) => {
                        setFormScope(value);
                        setProductSearch('');
                        form.setFieldValue('productId', undefined);
                      }}
                    />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item name="productId" label="产品名称" rules={[{ required: true }]}>
                    <Select
                      disabled={!!editing}
                      labelInValue
                      showSearch
                      filterOption
                      optionFilterProp="label"
                      placeholder="选择或输入产品名称"
                      searchValue={productSearch}
                      onSearch={setProductSearch}
                      onChange={() => setProductSearch('')}
                      options={productOptions}
                      notFoundContent={
                        productSearch ? null : '当前类型暂无库存产品，可输入名称创建'
                      }
                    />
                  </Form.Item>
                </Col>
              </Row>
              <Row gutter={16}>
                <Col span={12}>
                  <Form.Item name="specification" label="规格 KG" rules={[{ required: true, type: 'number', min: 0.000001 }]}>
                    <InputNumber min={0.000001} precision={6} className="inventory-full" />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item name="packageQuantity" label="数量" rules={[{ required: true, type: 'number', min: 0.000001 }]}>
                    <InputNumber min={0.000001} precision={6} className="inventory-full" />
                  </Form.Item>
                </Col>
              </Row>
              {!canCreateCustomer && !selectedCustomerId && <Alert type="warning" showIcon style={{ marginBottom: 16 }} message="自定义客户名称需要新建客户权限" />}
              <Row gutter={16}>
                <Col span={12}>
                  <Form.Item label="总数量 KG">
                    <InputNumber value={sampleTotalKg} precision={6} className="inventory-full" disabled />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item name="newCustomerName" label="客户名称" rules={[{ required: true }]}>
                    <AutoComplete filterOption={false} maxLength={20}
                      placeholder="搜索客户或输入新客户名称"
                      onSearch={(value) => void searchCustomers(value)}
                      onChange={(value) => {
                        const id = form.getFieldValue('customerId');
                        const selectedName = customers.find((item) => item.id === id)?.name
                          ?? (editing && editing.customerId === id ? editing.customerName : undefined);
                        if (value !== selectedName) form.setFieldValue('customerId', undefined);
                      }}
                      onSelect={(_name, option) => {
                        form.setFieldValue('customerId', option.customerId);
                        void selectCustomer(option.customerId);
                      }}
                      options={[
                        ...(editing?.customerId && !customers.some((customer) => customer.id === editing.customerId)
                          ? [{ value: editing.customerName, label: editing.customerName, customerId: editing.customerId }]
                          : []),
                        ...customers.map((customer) => ({
                          value: customer.name, label: `${customer.partnerCode} · ${customer.name}`,
                          customerId: customer.id,
                        })),
                      ]} />
                  </Form.Item>
                </Col>
              </Row>
              {!editing && <Form.Item label="收件信息">
                <Input.TextArea rows={3} value={recipientText} onChange={(event) => setRecipientText(event.target.value)}
                  onPaste={(event) => {
                    const text = event.clipboardData.getData('text');
                    if (!text) return;
                    event.preventDefault();
                    const { value, selectionStart, selectionEnd } = event.currentTarget;
                    recognizeRecipient(value.slice(0, selectionStart) + text + value.slice(selectionEnd));
                  }}
                  placeholder="粘贴收件人、手机号和地址，自动识别并填入" />
              </Form.Item>}
              <Row gutter={16}>
                <Col span={12}>
                  <Form.Item name="recipientName" label="收件人" rules={[{ required: true }]}>
                    <Input />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item name="recipientPhone" label="收件人电话" rules={[{ required: true }]}>
                    <Input />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item name="sampleAddress" label="发样地址" rules={[{ required: true, whitespace: true }]}>
                <Input />
              </Form.Item>
              <Row gutter={16}>
                <Col span={12}>
                  <Form.Item name="courierCompany" label="快递公司">
                    <Input />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item name="trackingNo" label="快递单号">
                    <Input />
                  </Form.Item>
                </Col>
              </Row>
              <Row gutter={16}>
                <Col span={12}>
                  <Form.Item name="clientContact" label="客户联系人">
                    <Input />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item name="clientContactPhone" label="客户联系人电话">
                    <Input />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item name="senderName" label="本公司发样人">
                <Input />
              </Form.Item>
              <Row gutter={16}>
                <Col span={12}>
                  <Form.Item name="customerRequirement" label="客户要求">
                    <Input.TextArea />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item name="customerFeedback" label="客户测试反馈">
                    <Input.TextArea />
                  </Form.Item>
                </Col>
              </Row>
            </>
          )}
          {!sample && (
            <>
              <Row gutter={16}>
                <Col span={12}>
                  <Form.Item name="scope" label="库存类型" rules={[{ required: true }]}>
                    <Select
                      disabled={!!editing}
                      options={[
                        { value: 'RND', label: '研发库存' },
                        { value: 'PRODUCTION', label: '生产库存' },
                      ]}
                      onChange={(value: InventoryScope) => {
                        setFormScope(value);
                        setProductSearch('');
                        form.setFieldValue('productId', undefined);
                      }}
                    />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item name="productId" label="产品名称" rules={[{ required: true }]}>
                    <Select
                      disabled={!!editing}
                      labelInValue
                      showSearch
                      filterOption
                      optionFilterProp="label"
                      placeholder="选择或输入自定义产品名称"
                      searchValue={productSearch}
                      onSearch={setProductSearch}
                      onChange={() => setProductSearch('')}
                      options={productOptions}
                      notFoundContent={productSearch ? null : '当前类型暂无库存产品，可输入名称创建'}
                    />
                  </Form.Item>
                </Col>
              </Row>
              <Row gutter={16}>
                <Col span={12}>
                  <Form.Item name="specification" label="规格 KG" rules={[{ required: !editing }, { type: 'number', min: 0.000001 }]}>
                    <InputNumber min={0.000001} precision={6} className="inventory-full" disabled={!!editing} />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item name="packageQuantity" label="数量" rules={[{ required: !editing }, { type: 'number', min: 0.000001 }]}>
                    <InputNumber
                      min={0.000001}
                      precision={6}
                      className="inventory-full"
                      disabled={!!editing}
                    />
                  </Form.Item>
                </Col>
              </Row>
              <Row gutter={16}>
                <Col span={12}>
                  <Form.Item name="totalQuantityKg" label="总数量 KG">
                    <InputNumber disabled precision={6} className="inventory-full" />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item name="newCustomerName" label="客户名称" rules={[{ required: true }]}>
                    <AutoComplete filterOption={false} maxLength={20}
                      placeholder="搜索客户或输入新客户名称"
                      onSearch={(value) => void searchCustomers(value)}
                      onChange={(value) => {
                        const id = form.getFieldValue('customerId');
                        const selectedName = customers.find((item) => item.id === id)?.name
                          ?? (editing && editing.customerId === id ? editing.customerName : undefined);
                        if (value !== selectedName) form.setFieldValue('customerId', undefined);
                      }}
                      onSelect={(_name, option) => {
                        form.setFieldValue('customerId', option.customerId);
                        void selectCustomer(option.customerId);
                      }}
                      options={[
                        ...(editing?.customerId && !customers.some((customer) => customer.id === editing.customerId)
                          ? [{ value: editing.customerName, label: editing.customerName, customerId: editing.customerId }]
                          : []),
                        ...customers.map((customer) => ({
                          value: customer.name, label: `${customer.partnerCode} · ${customer.name}`,
                          customerId: customer.id,
                        })),
                      ]} />
                  </Form.Item>
                </Col>
              </Row>
              {!canCreateCustomer && !selectedCustomerId && <Alert type="warning" showIcon style={{ marginBottom: 16 }} message="自定义客户名称需要新建客户权限" />}
              {!editing && <Form.Item label="收件信息">
                <Input.TextArea rows={3} value={recipientText} onChange={(event) => setRecipientText(event.target.value)}
                  onPaste={(event) => {
                    const text = event.clipboardData.getData('text');
                    if (!text) return;
                    event.preventDefault();
                    const { value, selectionStart, selectionEnd } = event.currentTarget;
                    recognizeRecipient(value.slice(0, selectionStart) + text + value.slice(selectionEnd));
                  }}
                  placeholder="粘贴收件人、手机号和地址，自动识别并填入" />
              </Form.Item>}
              <Row gutter={16}>
                <Col span={12}><Form.Item name="recipientName" label="收件人" rules={[{ required: true }]}><Input /></Form.Item></Col>
                <Col span={12}><Form.Item name="recipientPhone" label="收件人电话" rules={[{ required: true }]}><Input /></Form.Item></Col>
              </Row>
              <Row gutter={16}>
                <Col span={24}><Form.Item name="shippingAddress" label="收货地址" rules={[{ required: true, whitespace: true }]}><Input /></Form.Item></Col>
              </Row>
              <Row gutter={16}>
                <Col span={12}><Form.Item name="courierCompany" label="快递公司"><Input /></Form.Item></Col>
                <Col span={12}><Form.Item name="trackingNo" label="快递单号"><Input /></Form.Item></Col>
              </Row>
              <Row gutter={16}>
                <Col span={12}>
                  <Form.Item name="senderName" label="出货人">
                    <Input />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item name="batchNo" label="批次号">
                    <Input placeholder="可选，用于批次追溯" />
                  </Form.Item>
                </Col>
              </Row>
            </>
          )}
          {!sample && (
            <>
              <Row gutter={16}>
                <Col span={12}>
                  <Form.Item name="coaNo" label="已签发 COA 编号">
                    <Select
                      allowClear showSearch filterOption={false} loading={coaLoading}
                      placeholder="搜索已签发 COA 编号或名称"
                      notFoundContent={coaLoading ? '加载中' : '暂无可选 COA'}
                      onOpenChange={(visible) => { setCoaOpen(visible); if (visible) setCoaSearch(''); }}
                      onSearch={(value) => {
                        coaRequest.current += 1;
                        setCoaOptions([]);
                        setCoaHasMore(false);
                        setCoaSearch(value);
                      }}
                      onChange={(value) => setCoaVerified(!value || coaOptions.some((item) => item.businessNo === value))}
                      onPopupScroll={(event) => {
                        const target = event.currentTarget;
                        if (coaHasMore && !coaLoading && target.scrollTop + target.clientHeight >= target.scrollHeight - 24)
                          void loadCoaOptions(coaSearch, coaPage + 1);
                      }}
                      options={[
                        ...(form.getFieldValue('coaNo') && !coaOptions.some((item) => item.businessNo === form.getFieldValue('coaNo'))
                          ? [{ value: form.getFieldValue('coaNo') as string, label: coaVerified ? form.getFieldValue('coaNo') as string : `${form.getFieldValue('coaNo')}（已不可选）`, disabled: !coaVerified }]
                          : []),
                        ...coaOptions.map((item) => ({ value: item.businessNo, label: item.displayName ? `${item.businessNo} · ${item.displayName}` : item.businessNo })),
                      ]}
                    />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item name="note" label="备注">
                    <Input.TextArea />
                  </Form.Item>
                </Col>
              </Row>
            </>
          )}
        </Form>
      </Modal>
    </InventoryShell>
  );
}
type PageData = {
  items: Array<SampleDispatch | Shipment>;
  page: number;
  size: number;
  total: number;
  totalPages: number;
};

function writeWorkbook(fileName: string, sheetName: string, rows: unknown[][]) {
  const workbook = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(workbook, XLSX.utils.aoa_to_sheet(rows), sheetName);
  XLSX.writeFile(workbook, fileName);
}

async function exportTransactions(scope: InventoryScope) {
  const first = await inventoryApi.transactions({ scope, page: 1, size: 100 });
  const rows = [...first.items];
  for (let page = 2; page <= first.totalPages; page += 1) {
    rows.push(...(await inventoryApi.transactions({ scope, page, size: 100 })).items);
  }
  writeWorkbook(`${scopeLabels[scope]}流水.xlsx`, scopeLabels[scope], [
    [
      '日期',
      '单号',
      '产品编码',
      '产品名称',
      '方向',
      '原因',
      '数量KG',
      '变动前KG',
      '变动后KG',
      '经办人',
      '备注',
    ],
    ...rows.map((row) => [
      row.businessDate,
      row.documentNo,
      row.productCode,
      row.productName,
      row.direction === 'INBOUND' ? '入库' : '扣减',
      reasonLabels[row.reason] || row.reason,
      row.quantityKg,
      row.beforeKg,
      row.afterKg,
      row.actorName,
      row.note || '',
    ]),
  ]);
}

function BusinessImportExport({
  type,
  shipmentNo,
  hideImport = false,
  hideExport = false,
  productsByScope,
  customers,
  onDone,
  disabled = false,
}: {
  type: 'sample' | 'shipment';
  shipmentNo?: string;
  hideImport?: boolean;
  hideExport?: boolean;
  productsByScope: Record<InventoryScope, InventoryProductOption[]>;
  customers: BusinessPartner[];
  onDone: () => void | Promise<void>;
  disabled?: boolean;
}) {
  const sample = type === 'sample';
  const [importing, setImporting] = useState(false);
  const importQueue = useRef<Promise<void>>(Promise.resolve());
  const queuedImports = useRef(0);
  const template = () => {
    if (sample) {
      const workbook = XLSX.utils.book_new();
      const sheet = XLSX.utils.aoa_to_sheet([[
        '日期', '发样单号', '状态', '库存类型', '产品名称', '规格 KG', '数量', '总数量 KG',
        '本公司发样人', '客户名称', '收件人', '收件人电话', '发样地址',
        '快递公司', '快递单号', '客户联系人', '客户联系人电话', '客户要求', '客户测试反馈',
      ]]);
      sheet['!cols'] = Array.from({ length: 19 }, (_, index) => ({ wch: [14, 24, 12, 14, 24, 14, 14, 16, 18, 28, 16, 18, 42][index] ?? 22 }));
      XLSX.utils.book_append_sheet(workbook, sheet, '发样记录');
      XLSX.utils.book_append_sheet(workbook, XLSX.utils.aoa_to_sheet([
        ['字段', '填写要求'],
        ['日期', '必填，格式 YYYY-MM-DD'],
        ['发样单号', '必填，不可与已有发样单号重复'],
        ['状态', '未完成或已完成；不填默认为未完成'],
        ['库存类型', '研发库存或生产库存；不填默认为研发库存'],
        ['产品名称', '必填，填写所选库存类型中已有的产品名称或编码'],
        ['规格 KG', '必填，大于 0'],
        ['数量', '必填，大于 0'],
        ['总数量 KG', '选填，必须等于规格 KG × 数量；实际由系统计算'],
        ['客户名称', '必填，填写客户管理中的名称或编码；新名称会创建客户'],
        ['收件人、收件人电话、发样地址', '必填'],
      ]), '填写说明');
      XLSX.writeFile(workbook, '发样记录导入模板.xlsx');
      return;
    }
    XLSX.writeFile(shipmentWorkbookTemplate(), '出货记录表导入模板.xlsx');
  };
  const exportRows = async () => {
    const first = sample
      ? await inventoryApi.samples({ page: 1, size: 100 })
      : shipmentNo ? await inventoryApi.shipmentDetails(shipmentNo, { page: 1, size: 100 })
        : await inventoryApi.shipments({ page: 1, size: 100 });
    const rows: Array<SampleDispatch | Shipment> = [...first.items];
    for (let page = 2; page <= first.totalPages; page += 1) {
      const next = sample
        ? await inventoryApi.samples({ page, size: 100 })
        : shipmentNo ? await inventoryApi.shipmentDetails(shipmentNo, { page, size: 100 })
          : await inventoryApi.shipments({ page, size: 100 });
      rows.push(...next.items);
    }
    if (sample) {
      writeWorkbook('发样记录.xlsx', '发样记录', [
        [
          '日期',
          '发样单号',
          '状态',
          '库存类型',
          '产品名称',
          '规格 KG',
          '数量',
          '总数量 KG',
          '本公司发样人',
          '客户名称',
          '收件人',
          '收件人电话',
          '发样地址',
          '快递公司',
          '快递单号',
          '客户联系人',
          '客户联系人电话',
          '客户要求',
          '客户测试反馈',
        ],
        ...(rows as SampleDispatch[]).map((row) => [
          row.businessDate,
          row.dispatchNo,
          row.status === 'COMPLETED' ? '已完成' : '未完成',
          scopeLabels[row.scope ?? 'RND'],
          row.productName,
          row.specification || '',
          row.packageQuantity ?? '',
          row.quantityKg,
          row.senderName || row.operator,
          row.customerName,
          row.recipientName,
          row.recipientPhone || '',
          row.sampleAddress || '',
          row.courierCompany || '',
          row.trackingNo || '',
          row.clientContact || '',
          row.clientContactPhone || '',
          row.customerRequirement || '',
          row.customerFeedback || '',
        ]),
      ]);
    } else if (shipmentNo) {
      const header = await inventoryApi.shipmentHeader(shipmentNo);
      const { exportShipmentSheet } = await import('./shipment-sheet-export');
      await exportShipmentSheet(shipmentNo, header.businessDate, rows as Shipment[]);
    }
  };
  const importFile = async (file: File) => {
    const workbook = XLSX.read(await file.arrayBuffer(), { type: 'array' });
    const firstSheetName = workbook.SheetNames[0];
    if (!firstSheetName) throw new Error('导入文件没有工作表');
    const sheet = workbook.Sheets[firstSheetName];
    if (!sheet) throw new Error('无法读取导入工作表');
    if (!sample && isShipmentWorkbook(sheet)) {
      const { shipmentNo: number, details } = parseShipmentWorkbook(
        sheet, shipmentNo,
      );
      if (shipmentNo && shipmentNo !== number) throw new Error(`文件包含其他出货单号：${number}`);
      await inventoryApi.importShipment(number, details);
      message.success(`成功导入出货单 ${number}，共 ${details.length} 条明细`);
      await onDone();
      return;
    }
    const records = XLSX.utils.sheet_to_json<Record<string, string | number>>(sheet, {
      defval: '',
    });
    if (!records.length) throw new Error('导入文件没有数据');
    if (sample) {
      const requiredColumns = ['日期', '发样单号', '产品名称', '规格 KG', '数量', '客户名称', '收件人', '收件人电话', '发样地址'];
      const columns = Object.keys(records[0] ?? {});
      const missing = requiredColumns.filter((column) => !columns.includes(column));
      if (missing.length) throw new Error(`缺少必填列：${missing.join('、')}`);
      const firstCustomers = await getPartners({ status: 'ACTIVE', page: 1, size: 100 });
      const availableCustomers = [...firstCustomers.items];
      for (let page = 2; page <= firstCustomers.totalPages; page += 1) {
        availableCustomers.push(...(await getPartners({ status: 'ACTIVE', page, size: 100 })).items);
      }
      const documentNumbers = new Set<string>();
      const entries = records.map((row, index) => {
        const rowNo = index + 2;
        const required = (column: string) => {
          const value = String(row[column] ?? '').trim();
          if (!value) throw new Error(`第 ${rowNo} 行：${column}不能为空`);
          return value;
        };
        const rawDate = row['日期'];
        const businessDate = typeof rawDate === 'number'
          ? dayjs('1899-12-30').add(Math.floor(rawDate), 'day').format('YYYY-MM-DD')
          : required('日期');
        if (!/^\d{4}-\d{2}-\d{2}$/.test(businessDate) || dayjs(businessDate).format('YYYY-MM-DD') !== businessDate)
          throw new Error(`第 ${rowNo} 行：日期格式应为 YYYY-MM-DD`);
        const dispatchNo = required('发样单号');
        if (documentNumbers.has(dispatchNo)) throw new Error(`第 ${rowNo} 行：发样单号在文件中重复`);
        documentNumbers.add(dispatchNo);
        const rawScope = String(row['库存类型'] ?? '').trim();
        const normalizedScope = rawScope.toUpperCase();
        if (rawScope && !['研发库存', '生产库存'].includes(rawScope) && !['RND', 'PRODUCTION'].includes(normalizedScope))
          throw new Error(`第 ${rowNo} 行：库存类型只能填写研发库存或生产库存`);
        const scope: InventoryScope = rawScope === '生产库存' || normalizedScope === 'PRODUCTION' ? 'PRODUCTION' : 'RND';
        const rawProduct = required('产品名称');
        const product = productsByScope[scope].find((item) => item.name === rawProduct || item.code === rawProduct || item.id === rawProduct);
        if (!product) throw new Error(`第 ${rowNo} 行：${scopeLabels[scope]}中不存在产品“${rawProduct}”`);
        const specification = Number(required('规格 KG'));
        const packageQuantity = Number(required('数量'));
        if (!Number.isFinite(specification) || specification <= 0 || !Number.isFinite(packageQuantity) || packageQuantity <= 0)
          throw new Error(`第 ${rowNo} 行：规格 KG 和数量必须大于 0`);
        const totalKg = Math.round(specification * packageQuantity * 1_000_000) / 1_000_000;
        if (totalKg <= 0) throw new Error(`第 ${rowNo} 行：总数量 KG 必须大于 0`);
        const importedTotal = String(row['总数量 KG'] ?? '').trim();
        if (importedTotal && (!Number.isFinite(Number(importedTotal)) || Math.abs(Number(importedTotal) - totalKg) > 0.000001))
          throw new Error(`第 ${rowNo} 行：总数量 KG 与规格 KG × 数量不一致`);
        const customerName = required('客户名称');
        const byCode = availableCustomers.find((item) => item.partnerCode === customerName || item.id === customerName);
        const byName = availableCustomers.filter((item) => item.name === customerName);
        if (!byCode && byName.length > 1) throw new Error(`第 ${rowNo} 行：客户名称重复，请填写客户编码`);
        const customer = byCode ?? byName[0];
        const rawStatus = String(row['状态'] ?? '').trim();
        if (rawStatus && !['未完成', '已完成'].includes(rawStatus)) throw new Error(`第 ${rowNo} 行：状态只能填写未完成或已完成`);
        return {
          customerName,
          customerId: customer?.id,
          input: {
            businessDate, dispatchNo, status: rawStatus === '已完成' ? 'COMPLETED' : 'PENDING',
            productId: product.id, scope, packageQuantity, specification: String(specification),
            recipientName: required('收件人'), recipientPhone: required('收件人电话'),
            sampleAddress: required('发样地址'), customerId: customer?.id,
            courierCompany: String(row['快递公司'] ?? '').trim(),
            trackingNo: String(row['快递单号'] ?? '').trim(),
            senderName: String(row['本公司发样人'] ?? '').trim(),
            clientContact: String(row['客户联系人'] ?? row['客户端联系人'] ?? '').trim(),
            clientContactPhone: String(row['客户联系人电话'] ?? row['客户端联系人电话'] ?? '').trim(),
            customerRequirement: String(row['客户要求'] ?? '').trim(),
            customerFeedback: String(row['客户测试反馈'] ?? '').trim(),
          },
        };
      });
      const createdCustomers = new Map<string, string>();
      let completed = 0;
      for (const entry of entries) {
        try {
          const customerId = entry.customerId ?? createdCustomers.get(entry.customerName);
          const result = customerId
            ? await inventoryApi.createSample({ ...entry.input, customerId })
            : await inventoryApi.createSampleForNewCustomer(entry.input, entry.customerName);
          if (!customerId && result.customerId) createdCustomers.set(entry.customerName, result.customerId);
          completed += 1;
        } catch (error) {
          if (completed) await onDone();
          throw new Error(`第 ${completed + 2} 行导入失败，前 ${completed} 行已保存：${errorMessage(error, '保存失败')}`, { cause: error });
        }
      }
      message.success(`成功导入 ${completed} 条发样记录`);
      await onDone();
      return;
    }
    const inputs = records.map((row, index) => {
      const rawProduct = String(row['产品名称'] ?? row['产品'] ?? row['产品编码'] ?? '').trim();
      const rawCustomer = String(row['客户名称'] ?? row['客户'] ?? row['客户编码'] ?? '').trim();
      const rawScope = String(row['库存类型'] ?? '').trim();
      const scope: InventoryScope =
        rawScope === '研发库存' || rawScope.toUpperCase() === 'RND'
          ? 'RND'
          : rawScope === '生产库存' || rawScope.toUpperCase() === 'PRODUCTION'
            ? 'PRODUCTION'
            : sample
              ? 'RND'
              : 'PRODUCTION';
      const productCandidates = productsByScope[scope];
      const product = productCandidates.find(
        (item) =>
          item.code === rawProduct ||
          item.name === rawProduct ||
          item.id === rawProduct ||
          rawProduct.startsWith(`${item.code} · `),
      );
      const customer = customers.find(
        (item) =>
          item.partnerCode === rawCustomer ||
          item.name === rawCustomer ||
          item.id === rawCustomer ||
          rawCustomer.startsWith(`${item.partnerCode} · `),
      );
      const quantityKg = Number(row[sample ? '数量 KG' : '数量'] ?? row['数量KG']);
      const rawDate = row['日期'];
      const parsedDate =
        typeof rawDate === 'number' ? XLSX.SSF.parse_date_code(rawDate) : undefined;
      const businessDate = parsedDate
        ? dayjs(`${parsedDate.y}-${parsedDate.m}-${parsedDate.d}`).format('YYYY-MM-DD')
        : String(rawDate ?? '').trim();
      const documentNo = String(row[sample ? '发样单号' : '出货单号'] ?? '').trim();
      if (!product) throw new Error(`第 ${index + 2} 行：产品不存在`);
      if (!customer) throw new Error(`第 ${index + 2} 行：客户不存在或未启用`);
      if (!businessDate) throw new Error(`第 ${index + 2} 行：日期不能为空`);
      if (!documentNo)
        throw new Error(`第 ${index + 2} 行：${sample ? '发样' : '出货'}单号不能为空`);
      if (!Number.isFinite(quantityKg) || quantityKg <= 0)
        throw new Error(`第 ${index + 2} 行：数量必须大于 0`);
      if (sample && !String(row['收件人'] ?? '').trim())
        throw new Error(`第 ${index + 2} 行：收件人不能为空`);
      if (sample && !String(row['收件人电话'] ?? '').trim())
        throw new Error(`第 ${index + 2} 行：收件人电话不能为空`);
      if (sample && !String(row['发样地址'] ?? '').trim())
        throw new Error(`第 ${index + 2} 行：发样地址不能为空`);
      if (!sample && !String(row['收件人'] ?? '').trim())
        throw new Error(`第 ${index + 2} 行：收件人不能为空`);
      if (!sample && !String(row['收件人电话'] ?? '').trim())
        throw new Error(`第 ${index + 2} 行：收件人电话不能为空`);
      if (!sample && !String(row['收货地址'] ?? '').trim())
        throw new Error(`第 ${index + 2} 行：收货地址不能为空`);
      const rawStatus = String(row['状态'] ?? '').trim();
      if (rawStatus && !['未完成', '已完成'].includes(rawStatus))
        throw new Error(`第 ${index + 2} 行：状态只能填写未完成或已完成`);
      const batchNo = String(row['批次号'] ?? row['批号'] ?? '').trim();
      const coaNo = String(row['已签发 COA 编号'] ?? '').trim();
      const specification = String(row['规格 KG'] ?? row['规格'] ?? '').trim();
      if (!sample && (!Number.isFinite(Number(specification)) || Number(specification) <= 0))
        throw new Error(`第 ${index + 2} 行：规格必须大于 0`);
      const rawTotalQuantity = row['总数量 KG'];
      if (!sample && rawTotalQuantity != null && rawTotalQuantity !== '' &&
          (!Number.isFinite(Number(rawTotalQuantity)) ||
          Math.abs(Number(rawTotalQuantity) - Number((Number(specification) * quantityKg).toFixed(6))) > 0.000001))
        throw new Error(`第 ${index + 2} 行：总数量 KG 与规格乘数量不一致`);
      return sample
        ? {
            businessDate,
            dispatchNo: documentNo,
            status: rawStatus === '已完成' ? 'COMPLETED' : 'PENDING',
            productId: product.id,
            scope,
            quantityKg,
            customerId: customer.id,
            recipientName: String(row['收件人'] ?? '').trim(),
            recipientPhone: String(row['收件人电话'] ?? '').trim(),
            sampleAddress: String(row['发样地址'] ?? '').trim(),
            courierCompany: String(row['快递公司'] ?? '').trim(),
            trackingNo: String(row['快递单号'] ?? '').trim(),
            senderName: String(row['本公司发样人'] ?? '').trim(),
            clientContact: String(row['客户联系人'] ?? row['客户端联系人'] ?? '').trim(),
            clientContactPhone: String(row['客户联系人电话'] ?? row['客户端联系人电话'] ?? '').trim(),
            customerRequirement: String(row['客户要求'] ?? '').trim(),
            customerFeedback: String(row['客户测试反馈'] ?? '').trim(),
          }
        : {
            businessDate,
            shipmentNo: documentNo,
            status: rawStatus === '已完成' ? 'COMPLETED' : 'PENDING',
            productId: product.id,
            scope,
            packageQuantity: quantityKg,
            customerId: customer.id,
            senderName: String(row['出货人'] ?? '').trim(),
            specification,
            recipientName: String(row['收件人'] ?? '').trim(),
            recipientPhone: String(row['收件人电话'] ?? '').trim(),
            shippingAddress: String(row['收货地址'] ?? '').trim(),
            courierCompany: String(row['快递公司'] ?? '').trim(),
            trackingNo: String(row['快递单号'] ?? '').trim(),
            note: String(row['备注'] ?? '').trim(),
            batchNo: batchNo || undefined,
            coaNo: coaNo || undefined,
          };
    });
    if (sample) {
      for (const input of inputs) await inventoryApi.createSample(input);
    } else {
      const groups = new Map<string, typeof inputs>();
      for (const input of inputs) {
        const row = input as Record<string, unknown>;
        const number = String(row.shipmentNo);
        if (shipmentNo && number !== shipmentNo) throw new Error(`文件包含其他出货单号：${number}`);
        const group = groups.get(number) ?? [];
        if (group.length && (group[0] as Record<string, unknown>).businessDate !== row.businessDate)
          throw new Error(`出货单号 ${number} 的日期不一致`);
        group.push(input);
        groups.set(number, group);
      }
      for (const [number, group] of groups)
        await inventoryApi.createShipmentBatch(number, group);
    }
    message.success(`成功导入 ${inputs.length} 条${sample ? '发样' : '出货'}记录`);
    await onDone();
  };
  return (
    <Space wrap>
      {!hideImport && (
        <>
          <Button icon={<DownloadOutlined />} onClick={template}>模板下载</Button>
          <Upload
            showUploadList={false}
            accept=".xlsx"
            multiple={!sample}
            beforeUpload={(file) => {
              queuedImports.current += 1;
              setImporting(true);
              importQueue.current = importQueue.current
                .then(() => importFile(file))
                .catch((error: unknown) => {
                  message.error(`${file.name}：${error instanceof Error ? error.message : '导入失败'}`);
                })
                .finally(() => {
                  queuedImports.current -= 1;
                  if (queuedImports.current === 0) setImporting(false);
                });
              return false;
            }}
          >
            <Button icon={<UploadOutlined />} disabled={disabled || importing} loading={importing}>导入</Button>
          </Upload>
        </>
      )}
      {!hideExport && <Button icon={<DownloadOutlined />} onClick={() => {
        void exportRows().catch((error: unknown) => message.error(errorMessage(error, '导出失败')));
      }}>
        导出
      </Button>}
    </Space>
  );
}

function LedgerImportExport({
  scope,
  products,
  onDone,
  disabled = false,
}: {
  scope: InventoryScope;
  products: InventoryProductOption[];
  onDone: () => void | Promise<void>;
  disabled?: boolean;
}) {
  const download = () => {
    const direction = '入库';
    const reasonCode = reasonOptions[scope][0] ?? '';
    const reason = reasonLabels[reasonCode] ?? reasonCode;
    writeWorkbook(`${scopeLabels[scope]}流水导入模板.xlsx`, scopeLabels[scope], [
      ['日期', '单号', '产品名称', '变动方向', '业务原因', '数量 KG', '经办人', '备注'],
      [
        dayjs().format('YYYY/MM/DD'),
        `${scope === 'RND' ? 'RD' : 'PD'}-示例`,
        '',
        direction,
        reason,
        '',
        '',
        '',
      ],
    ]);
  };
  const exportRows = async () => exportTransactions(scope);
  const importFile = async (file: File) => {
    const workbook = XLSX.read(await file.arrayBuffer(), { type: 'array' });
    const firstSheetName = workbook.SheetNames[0];
    if (!firstSheetName) throw new Error('导入文件没有工作表');
    const sheet = workbook.Sheets[firstSheetName];
    if (!sheet) throw new Error('无法读取导入工作表');
    const records = XLSX.utils.sheet_to_json<Record<string, unknown>>(sheet, { defval: '' });
    if (!records.length) throw new Error('导入文件没有数据');
    const inputs = records.map((row, index) => {
      const rawProduct = String(row['产品名称'] ?? row['产品'] ?? row['产品编码'] ?? '').trim();
      const product = products.find(
        (item) =>
          item.code === rawProduct ||
          item.name === rawProduct ||
          rawProduct.startsWith(`${item.code} · `),
      );
      const directionValue = String(row['变动方向']).trim();
      const direction =
        directionValue === '入库'
          ? 'INBOUND'
          : directionValue === '扣减'
            ? 'OUTBOUND'
            : directionValue;
      const reasons = reasonOptions[scope];
      const reasonValue = String(row['业务原因']).trim();
      const reason = reasons.find(
        (value) => value === reasonValue || reasonLabels[value] === reasonValue,
      );
      const quantityKg = Number(row['数量 KG'] ?? row['数量KG']);
      const dateValue = row['日期'];
      const parsedDate =
        typeof dateValue === 'number' ? XLSX.SSF.parse_date_code(dateValue) : undefined;
      const businessDate = parsedDate
        ? dayjs(`${parsedDate.y}-${parsedDate.m}-${parsedDate.d}`).format('YYYY-MM-DD')
        : String(dateValue ?? '').trim();
      const documentNo = String(row['单号'] ?? '').trim();
      const actorName = String(row['经办人'] ?? '').trim();
      if (!rawProduct) throw new Error(`第 ${index + 2} 行：产品不能为空`);
      if (!businessDate) throw new Error(`第 ${index + 2} 行：日期不能为空`);
      if (!documentNo) throw new Error(`第 ${index + 2} 行：单号不能为空`);
      if (!['INBOUND', 'OUTBOUND'].includes(direction))
        throw new Error(`第 ${index + 2} 行：变动方向必须为入库或扣减`);
      if (!reason) throw new Error(`第 ${index + 2} 行：业务原因无效`);
      if (!actorName) throw new Error(`第 ${index + 2} 行：经办人不能为空`);
      if (!Number.isFinite(quantityKg) || quantityKg <= 0)
        throw new Error(`第 ${index + 2} 行：数量必须大于 0`);
      return {
        businessDate,
        documentNo,
        rawProduct,
        productId: product?.id,
        direction,
        reason,
        quantityKg,
        actorName,
        note: String(row['备注'] ?? '').trim(),
      };
    });
    const createdProducts = new Map<string, string>();
    for (const input of inputs) {
      const productKey = input.rawProduct.toLocaleLowerCase();
      const productId =
        input.productId ??
        createdProducts.get(productKey) ??
        (await inventoryApi.createCustomProduct(input.rawProduct, scope));
      if (!input.productId) createdProducts.set(productKey, productId);
      const movement = { ...input, productId, scope };
      delete (movement as Record<string, unknown>).rawProduct;
      await inventoryApi.move(movement);
    }
    message.success(`成功导入 ${inputs.length} 条库存变动`);
    await onDone();
  };
  return (
    <Space>
      <Button icon={<DownloadOutlined />} onClick={download}>
        模板下载
      </Button>
      <Upload
        showUploadList={false}
        accept=".xlsx"
        beforeUpload={(file) => {
          void importFile(file).catch((error: unknown) =>
            message.error(error instanceof Error ? error.message : '导入失败'),
          );
          return false;
        }}
      >
        <Button icon={<UploadOutlined />} disabled={disabled}>
          导入
        </Button>
      </Upload>
      <Button icon={<DownloadOutlined />} onClick={() => void exportRows()}>
        导出
      </Button>
    </Space>
  );
}
function InventoryShell({
  title,
  description = '库存流水为唯一事实，所有修正均通过冲销或补偿记录完成。',
  extra,
  children,
}: {
  title: string;
  description?: string;
  extra?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <div className="inventory-page pm-unified-list-page">
      <div className="page-heading inventory-heading">
        <div>
          <h1>{title}</h1>
          <p>{description}</p>
        </div>
        <div className="pm-page-actions">{extra}</div>
      </div>
      {children}
    </div>
  );
}
