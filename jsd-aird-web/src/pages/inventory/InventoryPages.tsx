import { DownloadOutlined, EditOutlined, PlusOutlined, UploadOutlined } from '@ant-design/icons';
import {
  Button,
  Card,
  Col,
  DatePicker,
  Descriptions,
  Drawer,
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
  Upload,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { useCallback, useEffect, useMemo, useState } from 'react';
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
} from '@/services/inventory/inventory-api';
import type { InventoryProductOption } from '@/services/inventory/inventory-api';
import { getPartners, type BusinessPartner } from '@/services/partners/partner-api';
import { errorMessage } from '@/services/http/errors';
import './inventory-pages.css';

const scopeLabels: Record<InventoryScope, string> = { RND: '研发库存', PRODUCTION: '生产库存' };
const alertLabels: Record<AlertStatus, string> = {
  NORMAL: '正常',
  LOW_STOCK: '低库存',
  OUT_OF_STOCK: '无库存',
};
const reasonOptions: Record<InventoryScope, Record<InventoryDirection, string[]>> = {
  RND: {
    INBOUND: [
      'PURCHASE',
      'RND_SAMPLE_IN',
      'SUPPLIER_SAMPLE',
      'EXPERIMENT_PRODUCT',
      'OPENING_BALANCE',
      'COMPENSATION',
    ],
    OUTBOUND: ['EXPERIMENT', 'SAMPLE_DISPATCH', 'STOCKTAKE', 'COMPENSATION'],
  },
  PRODUCTION: {
    INBOUND: [
      'PURCHASE',
      'FINISHED_PRODUCT',
      'INTERMEDIATE_PRODUCT',
      'OPENING_BALANCE',
      'COMPENSATION',
    ],
    OUTBOUND: ['PRODUCTION_ORDER', 'RND_SAMPLE', 'STOCKTAKE', 'SHIPMENT', 'COMPENSATION'],
  },
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
    <Row gutter={16} className="inventory-summary">
      <Col span={6}>
        <Card>
          <Statistic title="库存总量" value={summary?.totalKg || 0} suffix="KG" />
        </Card>
      </Col>
      <Col span={6}>
        <Card>
          <Statistic title="库存项" value={summary?.productCount || 0} />
        </Card>
      </Col>
      <Col span={6}>
        <Card>
          <Statistic
            title="低库存"
            value={summary?.lowStockCount || 0}
            valueStyle={{ color: '#d97706' }}
          />
        </Card>
      </Col>
      <Col span={6}>
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
  const [filters, setFilters] = useState<Record<string, unknown>>({ page: 1, size: 20 });
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
      <Card>
        <Space className="inventory-filters">
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
        </Space>
        <Table
          rowKey={(r) => `${r.productId}-${r.scope}`}
          loading={loading}
          columns={columns}
          dataSource={data?.items}
          pagination={{
            current: data?.page,
            total: data?.total,
            pageSize: data?.size,
            onChange: (page, size) => setFilters((x) => ({ ...x, page, size })),
          }}
        />
      </Card>
      <Drawer
        open={!!selected}
        onClose={() => setSelected(undefined)}
        title="库存详情"
        extra={
          <Button
            type="primary"
            onClick={async () => {
              if (!selected) return;
              if (inventoryIsReadOnly()) {
                message.error('当前角色为只读用户，不能修改库存策略');
                return;
              }
              const values = await policyForm.validateFields();
              const updated = await inventoryApi.updatePolicy(
                selected.productId,
                selected.scope,
                values,
              );
              setSelected(updated);
              message.success('库存策略已保存');
              await load();
            }}
          >
            保存策略
          </Button>
        }
      >
        <Descriptions
          column={1}
          bordered
          items={
            selected
              ? [
                  { label: '产品', children: `${selected.productCode} · ${selected.productName}` },
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
      </Drawer>
    </InventoryShell>
  );
}

export function InventoryLedgerPage({ scope }: { scope: InventoryScope }) {
  const [data, setData] = useState<Awaited<ReturnType<typeof inventoryApi.transactions>>>();
  const [filters, setFilters] = useState<Record<string, unknown>>({ scope, page: 1, size: 20 });
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm();
  const [products, setProducts] = useState<InventoryProductOption[]>([]);
  const [productSearch, setProductSearch] = useState('');
  const [saving, setSaving] = useState(false);
  const [detail, setDetail] = useState<InventoryTransaction>();
  const [editing, setEditing] = useState<InventoryTransaction>();
  const load = useCallback(
    async () => setData(await inventoryApi.transactions({ ...filters, scope })),
    [filters, scope],
  );
  useEffect(() => {
    setFilters({ scope, page: 1, size: 20 });
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
  const direction = Form.useWatch('direction', form) || 'INBOUND';
  const save = async () => {
    const v = await form.validateFields();
    if (inventoryIsReadOnly()) {
      message.error('当前角色为只读用户，不能修改库存流水');
      return;
    }
    setSaving(true);
    try {
      if (editing) {
        await inventoryApi.updateTransaction(editing.id, {
          businessDate: v.businessDate.format('YYYY-MM-DD'),
          documentNo: v.documentNo,
          reason: v.reason,
          note: v.note,
        });
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
  const columns: ColumnsType<InventoryTransaction> = [
    { title: '日期', dataIndex: 'businessDate' },
    { title: '单号', dataIndex: 'documentNo' },
    { title: '产品', render: (_, r) => `${r.productCode} · ${r.productName}` },
    {
      title: '方向',
      dataIndex: 'direction',
      render: (v) => (
        <Tag color={v === 'INBOUND' ? 'green' : 'orange'}>{v === 'INBOUND' ? '入库' : '扣减'}</Tag>
      ),
    },
    { title: '原因', dataIndex: 'reason', render: (v) => reasonLabels[v] || v },
    { title: '数量', dataIndex: 'quantityKg', align: 'right', render: kg },
    { title: '变动前', dataIndex: 'beforeKg', render: kg },
    { title: '变动后', dataIndex: 'afterKg', render: kg },
    { title: '经办人', dataIndex: 'actorName' },
    {
      title: '操作',
      render: (_, r) => (
        <Space>
          <Button type="link" onClick={() => setDetail(r)}>
            查看
          </Button>
          <Button
            type="link"
            icon={<EditOutlined />}
            disabled={inventoryIsReadOnly() || r.reversed || !!r.reversalOf}
            onClick={() => {
              form.setFieldsValue({
                businessDate: dayjs(r.businessDate),
                documentNo: r.documentNo,
                productId: { value: r.productId, label: `${r.productCode} · ${r.productName}` },
                direction: r.direction,
                reason: r.reason,
                quantityKg: r.quantityKg,
                note: r.note,
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
                await inventoryApi.reverse(r.id);
                message.success('已冲销');
                await load();
              }}
            >
              <Button type="link" danger>
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
          <InitialStockImport scope={scope} onDone={load} />
          <Button icon={<DownloadOutlined />} onClick={() => void exportTransactions(scope)}>
            导出
          </Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              setEditing(undefined);
              form.setFieldsValue({
                direction: 'INBOUND',
                businessDate: dayjs(),
                documentNo: `${scope === 'RND' ? 'RD' : 'PD'}-${dayjs().format('YYYYMMDD-HHmmss')}`,
              });
              setOpen(true);
            }}
          >
            新增库存变动
          </Button>
        </Space>
      }
    >
      <Card>
        <Space className="inventory-filters">
          <Input.Search
            allowClear
            placeholder="搜索单号、产品或备注"
            onSearch={(keyword) => setFilters((x) => ({ ...x, keyword, page: 1 }))}
          />
          <Select
            allowClear
            placeholder="变动方向"
            options={[
              { value: 'INBOUND', label: '入库' },
              { value: 'OUTBOUND', label: '扣减' },
            ]}
            onChange={(direction) => setFilters((x) => ({ ...x, direction, page: 1 }))}
          />
        </Space>
        <Table
          rowKey="id"
          columns={columns}
          dataSource={data?.items}
          scroll={{ x: 1200 }}
          pagination={{
            current: data?.page,
            total: data?.total,
            pageSize: data?.size,
            onChange: (page, size) => setFilters((x) => ({ ...x, page, size })),
          }}
        />
      </Card>
      <Modal
        title={`${editing ? '编辑' : '新增'}${scopeLabels[scope]}变动`}
        open={open}
        width={640}
        okText="保存"
        cancelText="取消"
        confirmLoading={saving}
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
              <Form.Item name="productId" label="产品" rules={[{ required: true }]}>
                  <Select
                    disabled={!!editing}
                  labelInValue
                  showSearch
                  filterOption
                  optionFilterProp="label"
                  placeholder="选择或输入自定义产品"
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
              <Form.Item name="reason" label="业务原因" rules={[{ required: true }]}>
                <Select
                  options={reasonOptions[scope][direction as InventoryDirection].map((value) => ({
                    value,
                    label: reasonLabels[value] || value,
                  }))}
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
              <Form.Item label="经办人">
                <Input value="当前用户" disabled />
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
                  产品: detail.productName,
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
  return <BusinessRecords type="shipment" />;
}
function BusinessRecords({ type }: { type: 'sample' | 'shipment' }) {
  const sample = type === 'sample';
  const [data, setData] = useState<PageData>();
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm();
  const [products, setProducts] = useState<InventoryProductOption[]>([]);
  const [customers, setCustomers] = useState<BusinessPartner[]>([]);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState<SampleDispatch | Shipment>();
  const load = useCallback(
    async () => setData(sample ? await inventoryApi.samples() : await inventoryApi.shipments()),
    [sample],
  );
  useEffect(() => {
    void load();
    void inventoryApi.productOptions(type === 'sample' ? 'RND' : 'PRODUCTION').then(setProducts);
    void getPartners({ status: 'ACTIVE', page: 1, size: 100 })
      .then((result) => setCustomers(result.items))
      .catch(() => message.error('客户列表加载失败'));
  }, [load, type]);
  const openEdit = (record: SampleDispatch | Shipment) => {
    setEditing(record);
    const values: Record<string, unknown> = {
      businessDate: dayjs(record.businessDate),
      productId: record.productId,
      quantityKg: record.quantityKg,
      customerId: record.customerId,
    };
    if ('dispatchNo' in record) {
      Object.assign(values, {
        dispatchNo: record.dispatchNo,
        recipientName: record.recipientName,
        sampleAddress: record.sampleAddress,
        courierCompany: record.courierCompany,
        trackingNo: record.trackingNo,
        clientContact: record.clientContact,
        customerRequirement: record.customerRequirement,
        customerFeedback: record.customerFeedback,
      });
    } else {
      values.shipmentNo = record.shipmentNo;
      values.note = record.note;
    }
    form.setFieldsValue(values);
    setOpen(true);
  };
  const save = async () => {
    const v = await form.validateFields();
    if (inventoryIsReadOnly()) {
      message.error(`当前角色为只读用户，不能修改${sample ? '发样' : '出货'}记录`);
      return;
    }
    setSaving(true);
    try {
      if (editing) {
        if (sample && 'dispatchNo' in editing) {
          await inventoryApi.updateSample(editing.id, {
            dispatchNo: v.dispatchNo,
            businessDate: v.businessDate.format('YYYY-MM-DD'),
            customerId: v.customerId,
            recipientName: v.recipientName,
            sampleAddress: v.sampleAddress,
            courierCompany: v.courierCompany,
            trackingNo: v.trackingNo,
            clientContact: v.clientContact,
            customerRequirement: v.customerRequirement,
            customerFeedback: v.customerFeedback,
          });
        } else {
          await inventoryApi.updateShipment(editing.id, {
            shipmentNo: v.shipmentNo,
            businessDate: v.businessDate.format('YYYY-MM-DD'),
            customerId: v.customerId,
            note: v.note,
          });
        }
        message.success(sample ? '发样记录已更新' : '出货记录已更新');
        setOpen(false);
        setEditing(undefined);
        form.resetFields();
        await load();
        return;
      }
      const input = { ...v, businessDate: v.businessDate.format('YYYY-MM-DD') };
      if (sample) await inventoryApi.createSample(input);
      else await inventoryApi.createShipment(input);
      message.success(sample ? '发样记录已保存并扣减研发库存' : '出货记录已保存并扣减生产库存');
      setOpen(false);
      form.resetFields();
      await load();
    } catch (error) {
      message.error(errorMessage(error, `${sample ? '发样' : '出货'}记录保存失败，请稍后重试`));
    } finally {
      setSaving(false);
    }
  };
  const columns = useMemo<ColumnsType<SampleDispatch | Shipment>>(
    () =>
      sample
        ? [
            { title: '日期', dataIndex: 'businessDate' },
            { title: '发样单号', dataIndex: 'dispatchNo' },
            { title: '客户', dataIndex: 'customerName' },
            { title: '收件人', dataIndex: 'recipientName' },
            { title: '发样产品', dataIndex: 'productName' },
            { title: '数量', dataIndex: 'quantityKg', render: kg },
            { title: '快递公司', dataIndex: 'courierCompany' },
            { title: '快递单号', dataIndex: 'trackingNo' },
            { title: '发样人', dataIndex: 'operator' },
            {
              title: '操作',
              render: (_: unknown, record: SampleDispatch | Shipment) => (
                <Button
                  type="link"
                  icon={<EditOutlined />}
                  disabled={inventoryIsReadOnly()}
                  onClick={() => openEdit(record)}
                >
                  编辑
                </Button>
              ),
            },
          ]
        : [
            { title: '日期', dataIndex: 'businessDate' },
            { title: '出货单号', dataIndex: 'shipmentNo' },
            { title: '产品', dataIndex: 'productName' },
            { title: '客户', dataIndex: 'customerName' },
            { title: '数量', dataIndex: 'quantityKg', render: kg },
            { title: '经办人', dataIndex: 'operator' },
            { title: '备注', dataIndex: 'note' },
            {
              title: '操作',
              render: (_: unknown, record: SampleDispatch | Shipment) => (
                <Button
                  type="link"
                  icon={<EditOutlined />}
                  disabled={inventoryIsReadOnly()}
                  onClick={() => openEdit(record)}
                >
                  编辑
                </Button>
              ),
            },
          ],
    [openEdit, sample],
  );
  return (
    <InventoryShell
      title={sample ? '发样记录表' : '出货记录表'}
      description={
        sample
          ? '新增发样后自动扣减研发库存并生成研发库存流水。'
          : '新增出货后自动扣减生产库存并生成生产库存流水。'
      }
      extra={
        <Space>
          <BusinessImportExport
            type={type}
            products={products}
            customers={customers}
            onDone={load}
          />
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              setEditing(undefined);
              form.setFieldsValue({ businessDate: dayjs() });
              setOpen(true);
            }}
          >
            {sample ? '新增发样' : '新增出货'}
          </Button>
        </Space>
      }
    >
      <Card>
        <Table rowKey="id" columns={columns} dataSource={data?.items} />
      </Card>
      <Modal
        width={650}
        title={`${editing ? '编辑' : '新增'}${sample ? '发样' : '出货'}`}
        open={open}
        okText="保存"
        cancelText="取消"
        confirmLoading={saving}
        onCancel={() => {
          setOpen(false);
          setEditing(undefined);
          form.resetFields();
        }}
        onOk={save}
      >
        <Form form={form} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="businessDate" label="日期" rules={[{ required: true }]}>
                <DatePicker className="inventory-full" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name={sample ? 'dispatchNo' : 'shipmentNo'}
                label={sample ? '发样单号' : '出货单号'}
                rules={[{ required: true }]}
              >
                <Input />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="productId" label="产品" rules={[{ required: true }]}>
            <Select
              disabled={!!editing}
              showSearch
              optionFilterProp="label"
              options={products.map((p) => ({
                value: p.id,
                label: `${p.code} · ${p.name} · ${p.category}`,
              }))}
            />
          </Form.Item>
          <Form.Item name="quantityKg" label="数量 KG" rules={[{ required: true }]}>
            <InputNumber
              min={0.000001}
              precision={6}
              className="inventory-full"
              disabled={!!editing}
            />
          </Form.Item>
          <Form.Item name="customerId" label="客户名称" rules={[{ required: true }]}>
            <Select
              showSearch
              allowClear
              optionFilterProp="label"
              placeholder="请选择客户"
              options={customers.map((customer) => ({
                value: customer.id,
                label: `${customer.partnerCode} · ${customer.name}`,
              }))}
            />
          </Form.Item>
          {sample && (
            <>
              <Form.Item name="recipientName" label="收件人" rules={[{ required: true }]}>
                <Input />
              </Form.Item>
              <Form.Item name="sampleAddress" label="发样地址">
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
              <Form.Item name="clientContact" label="客户端联系人">
                <Input />
              </Form.Item>
              <Form.Item name="customerRequirement" label="客户要求">
                <Input.TextArea />
              </Form.Item>
              <Form.Item name="customerFeedback" label="客户测试反馈">
                <Input.TextArea />
              </Form.Item>
            </>
          )}
          {!sample && (
            <Form.Item name="note" label="备注">
              <Input.TextArea />
            </Form.Item>
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
  products,
  customers,
  onDone,
}: {
  type: 'sample' | 'shipment';
  products: InventoryProductOption[];
  customers: BusinessPartner[];
  onDone: () => void | Promise<void>;
}) {
  const sample = type === 'sample';
  const template = () => {
    const header = sample
      ? [
          '日期',
          '发样单号',
          '产品编码',
          '数量KG',
          '客户编码',
          '收件人',
          '发样地址',
          '快递公司',
          '快递单号',
          '客户端联系人',
          '客户要求',
          '客户测试反馈',
        ]
      : ['日期', '出货单号', '产品编码', '数量KG', '客户编码', '备注'];
    writeWorkbook(`${sample ? '发样' : '出货'}导入模板.xlsx`, sample ? '发样记录' : '出货记录', [
      header,
    ]);
  };
  const exportRows = async () => {
    const first = sample
      ? await inventoryApi.samples({ page: 1, size: 100 })
      : await inventoryApi.shipments({ page: 1, size: 100 });
    const rows: Array<SampleDispatch | Shipment> = [...first.items];
    for (let page = 2; page <= first.totalPages; page += 1) {
      const next = sample
        ? await inventoryApi.samples({ page, size: 100 })
        : await inventoryApi.shipments({ page, size: 100 });
      rows.push(...next.items);
    }
    if (sample) {
      writeWorkbook('发样记录.xlsx', '发样记录', [
        [
          '日期',
          '发样单号',
          '产品',
          '客户',
          '数量KG',
          '收件人',
          '发样地址',
          '快递公司',
          '快递单号',
          '经办人',
        ],
        ...(rows as SampleDispatch[]).map((row) => [
          row.businessDate,
          row.dispatchNo,
          row.productName,
          row.customerName,
          row.quantityKg,
          row.recipientName,
          row.sampleAddress || '',
          row.courierCompany || '',
          row.trackingNo || '',
          row.operator,
        ]),
      ]);
    } else {
      writeWorkbook('出货记录.xlsx', '出货记录', [
        ['日期', '出货单号', '产品', '客户', '数量KG', '经办人', '备注'],
        ...(rows as Shipment[]).map((row) => [
          row.businessDate,
          row.shipmentNo,
          row.productName,
          row.customerName,
          row.quantityKg,
          row.operator,
          row.note || '',
        ]),
      ]);
    }
  };
  const importFile = async (file: File) => {
    const workbook = XLSX.read(await file.arrayBuffer(), { type: 'array' });
    const firstSheetName = workbook.SheetNames[0];
    if (!firstSheetName) throw new Error('导入文件没有工作表');
    const sheet = workbook.Sheets[firstSheetName];
    if (!sheet) throw new Error('无法读取导入工作表');
    const records = XLSX.utils.sheet_to_json<Record<string, unknown>>(sheet, { defval: '' });
    if (!records.length) throw new Error('导入文件没有数据');
    const inputs = records.map((row, index) => {
      const product = products.find((item) => item.code === String(row['产品编码']).trim());
      const customer = customers.find(
        (item) => item.partnerCode === String(row['客户编码']).trim(),
      );
      const quantityKg = Number(row['数量KG']);
      const rawDate = row['日期'];
      const parsedDate =
        typeof rawDate === 'number' ? XLSX.SSF.parse_date_code(rawDate) : undefined;
      const businessDate = parsedDate
        ? dayjs(`${parsedDate.y}-${parsedDate.m}-${parsedDate.d}`).format('YYYY-MM-DD')
        : String(rawDate).trim();
      const documentNo = String(row[sample ? '发样单号' : '出货单号']).trim();
      if (!product) throw new Error(`第 ${index + 2} 行：产品编码不存在`);
      if (!customer) throw new Error(`第 ${index + 2} 行：客户编码不存在或未启用`);
      if (!businessDate) throw new Error(`第 ${index + 2} 行：日期不能为空`);
      if (!documentNo)
        throw new Error(`第 ${index + 2} 行：${sample ? '发样' : '出货'}单号不能为空`);
      if (!Number.isFinite(quantityKg) || quantityKg <= 0)
        throw new Error(`第 ${index + 2} 行：数量必须大于 0`);
      if (sample && !String(row['收件人']).trim())
        throw new Error(`第 ${index + 2} 行：收件人不能为空`);
      return sample
        ? {
            businessDate,
            dispatchNo: documentNo,
            productId: product.id,
            quantityKg,
            customerId: customer.id,
            recipientName: String(row['收件人']).trim(),
            sampleAddress: String(row['发样地址']).trim(),
            courierCompany: String(row['快递公司']).trim(),
            trackingNo: String(row['快递单号']).trim(),
            clientContact: String(row['客户端联系人']).trim(),
            customerRequirement: String(row['客户要求']).trim(),
            customerFeedback: String(row['客户测试反馈']).trim(),
          }
        : {
            businessDate,
            shipmentNo: documentNo,
            productId: product.id,
            quantityKg,
            customerId: customer.id,
            note: String(row['备注']).trim(),
          };
    });
    for (const input of inputs) {
      if (sample) await inventoryApi.createSample(input);
      else await inventoryApi.createShipment(input);
    }
    message.success(`成功导入 ${inputs.length} 条${sample ? '发样' : '出货'}记录`);
    await onDone();
  };
  return (
    <Space>
      <Button icon={<DownloadOutlined />} onClick={template}>
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
        <Button icon={<UploadOutlined />}>导入</Button>
      </Upload>
      <Button icon={<DownloadOutlined />} onClick={() => void exportRows()}>
        导出
      </Button>
    </Space>
  );
}

function InitialStockImport({
  scope,
  onDone,
}: {
  scope: InventoryScope;
  onDone: () => void | Promise<void>;
}) {
  const [current, setCurrent] = useState<Awaited<ReturnType<typeof inventoryApi.uploadInitial>>>();
  const download = () => {
    const ws = XLSX.utils.aoa_to_sheet([
      ['产品编码', '库存域', '数量KG', '业务日期'],
      [scope === 'RND' ? 'RD-示例' : 'PD-示例', scope, 100, dayjs().format('YYYY-MM-DD')],
    ]);
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, scopeLabels[scope]);
    XLSX.writeFile(wb, `${scopeLabels[scope]}导入模板.xlsx`);
  };
  return (
    <Space>
      <Button icon={<DownloadOutlined />} onClick={download}>
        模板下载
      </Button>
      <Upload
        showUploadList={false}
        accept=".xlsx"
        beforeUpload={async (file) => {
          try {
            const workbook = XLSX.read(await file.arrayBuffer(), { type: 'array' });
            const firstSheetName = workbook.SheetNames[0];
            const sheet = firstSheetName ? workbook.Sheets[firstSheetName] : undefined;
            if (!sheet) throw new Error('无法读取导入工作表');
            const rows = XLSX.utils.sheet_to_json<Record<string, unknown>>(sheet, { defval: '' });
            const wrongScopeRow = rows.findIndex(
              (row) => String(row['库存域']).trim().toUpperCase() !== scope,
            );
            if (wrongScopeRow >= 0)
              throw new Error(`第 ${wrongScopeRow + 2} 行库存域必须为 ${scope}`);
            setCurrent(await inventoryApi.uploadInitial(file));
          } catch (e) {
            message.error(e instanceof Error ? e.message : '导入失败');
          }
          return false;
        }}
      >
        <Button icon={<UploadOutlined />}>导入</Button>
      </Upload>
      <Modal
        open={!!current}
        title="期初库存校验结果"
        onCancel={() => setCurrent(undefined)}
        okButtonProps={{ disabled: current?.status !== 'VALIDATED' }}
        okText="确认入账"
        onOk={async () => {
          if (!current) return;
          await inventoryApi.commitInitial(current.id);
          message.success('期初库存已入账');
          setCurrent(undefined);
          await onDone();
        }}
      >
        <Tag color={current?.status === 'VALIDATED' ? 'green' : 'red'}>
          {current?.status === 'VALIDATED' ? '校验通过' : '校验失败'}
        </Tag>
        <p>
          数据行：{current?.rows.length || 0}，错误：{current?.errors.length || 0}
        </p>
        {current?.errors.map((e) => (
          <p key={`${e.rowNumber}-${e.field}`} className="inventory-error">
            第 {e.rowNumber} 行：{e.message}
          </p>
        ))}
      </Modal>
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
    <div className="inventory-page">
      <div className="inventory-heading">
        <div>
          <h1>{title}</h1>
          <p>{description}</p>
        </div>
        {extra}
      </div>
      {children}
    </div>
  );
}
