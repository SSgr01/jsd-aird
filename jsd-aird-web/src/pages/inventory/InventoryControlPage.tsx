import {
  AuditOutlined,
  EditOutlined,
  LinkOutlined,
  PlusOutlined,
  SaveOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  SwapOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Divider,
  Form,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Table,
  Tag,
  Tabs,
  message,
} from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useCallback, useEffect, useMemo, useState } from 'react';

import {
  inventoryApi,
  type InventoryBalance,
  type InventoryScope,
  type InventoryTransaction,
  type InventoryProductOption,
} from '@/services/inventory/inventory-api';

import './inventory-pages.css';
import './inventory-control.css';

type ControlMode =
  | 'products'
  | 'warnings'
  | 'batches'
  | 'expiry'
  | 'transfer'
  | 'production-link'
  | 'eln-link'
  | 'settings'
  | 'permissions'
  | 'audit';

type Product = InventoryProductOption & { scope: InventoryScope };
type ControlRecord = {
  id: string;
  kind: string;
  documentNo: string;
  productName: string;
  note?: string;
  createdAt: string;
};

const scopeLabels: Record<InventoryScope, string> = { RND: '研发库存', PRODUCTION: '生产库存' };
const modeLabels: Record<ControlMode, string> = {
  products: '产品主档',
  warnings: '预警规则',
  batches: '批次管理',
  expiry: '有效期与重测',
  transfer: '库存调拨',
  'production-link': '生产联动',
  'eln-link': 'ELN 联动',
  settings: '库存参数',
  permissions: '库存权限',
  audit: '审计追溯',
};

function readRecords(): ControlRecord[] {
  try {
    const raw = window.localStorage.getItem('inventory-control-records');
    return raw ? (JSON.parse(raw) as ControlRecord[]) : [];
  } catch {
    return [];
  }
}

function writeRecords(records: ControlRecord[]) {
  window.localStorage.setItem('inventory-control-records', JSON.stringify(records.slice(-100)));
}

function addRecord(record: Omit<ControlRecord, 'id' | 'createdAt'>) {
  const next = [...readRecords(), { ...record, id: crypto.randomUUID(), createdAt: new Date().toISOString() }];
  writeRecords(next);
}

function dateValue(value: Dayjs | undefined) {
  return value?.format('YYYY-MM-DD') || dayjs().format('YYYY-MM-DD');
}

export function InventoryControlPage({ mode }: { mode: ControlMode }) {
  const [products, setProducts] = useState<Product[]>([]);
  const [balances, setBalances] = useState<InventoryBalance[]>([]);
  const [transactions, setTransactions] = useState<InventoryTransaction[]>([]);
  const [records, setRecords] = useState<ControlRecord[]>(readRecords);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  const reload = useCallback(async () => {
    const [rndProducts, productionProducts, rndBalances, productionBalances, rndTransactions, productionTransactions] = await Promise.all([
      inventoryApi.productOptions('RND'),
      inventoryApi.productOptions('PRODUCTION'),
      inventoryApi.balances({ scope: 'RND', page: 1, size: 100 }),
      inventoryApi.balances({ scope: 'PRODUCTION', page: 1, size: 100 }),
      inventoryApi.transactions({ scope: 'RND', page: 1, size: 100 }),
      inventoryApi.transactions({ scope: 'PRODUCTION', page: 1, size: 100 }),
    ]);
    setProducts([
      ...rndProducts.map((product) => ({ ...product, scope: 'RND' as const })),
      ...productionProducts.map((product) => ({ ...product, scope: 'PRODUCTION' as const })),
    ]);
    setBalances([...rndBalances.items, ...productionBalances.items]);
    setTransactions([...rndTransactions.items, ...productionTransactions.items]);
  }, []);

  useEffect(() => {
    void reload().catch(() => message.error('库存控制数据加载失败'));
  }, [reload]);

  useEffect(() => {
    form.resetFields();
    form.setFieldsValue({
      scope: 'RND',
      sourceScope: 'RND',
      targetScope: 'PRODUCTION',
      businessDate: dayjs(),
      expiryDate: dayjs().add(1, 'year'),
      defaultLowStock: Number(window.localStorage.getItem('inventory-default-low-stock') || 0),
      role: 'WAREHOUSE_ADMIN',
    });
  }, [form, mode]);

  const productsByScope = useMemo(
    () => ({
      RND: products.filter((product) => product.scope === 'RND'),
      PRODUCTION: products.filter((product) => product.scope === 'PRODUCTION'),
    }),
    [products],
  );
  const selectedScope = (Form.useWatch('scope', form) || 'RND') as InventoryScope;
  const sourceScope = (Form.useWatch('sourceScope', form) || 'RND') as InventoryScope;
  const targetScope = (Form.useWatch('targetScope', form) || 'PRODUCTION') as InventoryScope;
  const selectedRole = Form.useWatch('role', form) || 'WAREHOUSE_ADMIN';

  useEffect(() => {
    if (!products.length) return;
    const available = (scope: InventoryScope) => {
      const balanceProduct = balances.find((balance) => balance.scope === scope && Number(balance.quantityKg) > 0);
      return balanceProduct?.productId || productsByScope[scope][0]?.id;
    };
    if (mode === 'transfer') {
      form.setFieldsValue({
        sourceProductId: form.getFieldValue('sourceProductId') || available('RND'),
        targetProductId: form.getFieldValue('targetProductId') || available('PRODUCTION'),
      });
    }
    if (mode === 'production-link' || mode === 'eln-link') {
      const scope: InventoryScope = mode === 'production-link' ? 'PRODUCTION' : 'RND';
      form.setFieldsValue({ productId: form.getFieldValue('productId') || available(scope) });
    }
  }, [balances, form, mode, products, productsByScope]);

  const save = async () => {
    const values = await form.validateFields();
    if (mode !== 'permissions' && mode !== 'audit' && window.localStorage.getItem('inventory-role') === 'READ_ONLY') {
      message.error('当前角色为只读用户，不能执行库存写入操作');
      return;
    }
    setSaving(true);
    try {
      if (mode === 'products') {
        if (products.some((product) => product.name.trim().toLowerCase() === String(values.name).trim().toLowerCase())) {
          throw new Error('产品名称已存在，重复提交已拦截');
        }
        const createdId = await inventoryApi.createCustomProduct(values.name, values.scope);
        addRecord({ kind: '产品主档', documentNo: createdId, productName: values.name, note: `${scopeLabels[values.scope as InventoryScope]} / ${values.category || '库存产品'}` });
        message.success('产品主档已保存');
      } else if (mode === 'warnings') {
        await inventoryApi.updatePolicy(values.productId, values.scope, { packageKg: values.packageKg, lowStockKg: values.lowStockKg });
        addRecord({ kind: '预警规则', documentNo: values.productId, productName: productName(values.productId), note: `包装 ${values.packageKg || '—'} KG；预警 ${values.lowStockKg} KG` });
        message.success('库存预警规则已保存');
      } else if (mode === 'batches' || mode === 'expiry') {
        const documentNo = values.documentNo || `${mode === 'batches' ? 'BAT' : 'EXP'}-${Date.now()}`;
        const note = mode === 'batches'
          ? `批次号=${values.batchNo};生产日期=${dateValue(values.productionDate)};备注=${values.note || ''}`
          : `批次号=${values.batchNo};有效期=${dateValue(values.expiryDate)};重测日期=${dateValue(values.retestDate)};重测结果=${values.retestResult || '待重测'}`;
        await inventoryApi.move({ productId: values.productId, scope: values.scope, direction: 'INBOUND', reason: mode === 'batches' ? '盘点' : '采购', quantityKg: values.quantityKg, businessDate: dateValue(values.businessDate), documentNo, businessType: mode === 'batches' ? 'BATCH' : 'EXPIRY', note });
        addRecord({ kind: modeLabels[mode], documentNo, productName: productName(values.productId), note });
        message.success(`${modeLabels[mode]}记录已保存`);
      } else if (mode === 'transfer') {
        const documentNo = values.documentNo || `TRF-${Date.now()}`;
        await inventoryApi.move({ productId: values.sourceProductId, scope: values.sourceScope, direction: 'OUTBOUND', reason: '调拨', quantityKg: values.quantityKg, businessDate: dateValue(values.businessDate), documentNo: `${documentNo}-OUT`, businessType: 'TRANSFER', note: values.note });
        await inventoryApi.move({ productId: values.targetProductId, scope: values.targetScope, direction: 'INBOUND', reason: '调拨', quantityKg: values.quantityKg, businessDate: dateValue(values.businessDate), documentNo: `${documentNo}-IN`, businessType: 'TRANSFER', note: values.note });
        addRecord({ kind: '库存调拨', documentNo, productName: `${productName(values.sourceProductId)} → ${productName(values.targetProductId)}`, note: `${values.quantityKg} KG` });
        message.success('库存调拨已完成，已生成出库与入库流水');
      } else if (mode === 'production-link' || mode === 'eln-link') {
        const documentNo = values.documentNo || `${mode === 'production-link' ? 'MO' : 'ELN'}-${Date.now()}`;
        const scope: InventoryScope = mode === 'production-link' ? 'PRODUCTION' : 'RND';
        const reason = mode === 'production-link' ? '生产单扣减' : '实验领用';
        await inventoryApi.move({ productId: values.productId, scope, direction: 'OUTBOUND', reason, quantityKg: values.quantityKg, businessDate: dateValue(values.businessDate), documentNo, businessType: mode === 'production-link' ? 'PRODUCTION_ORDER' : 'ELN', note: values.linkNo ? `${mode === 'production-link' ? '生产单号' : '实验记录本'}=${values.linkNo}` : '' });
        addRecord({ kind: modeLabels[mode], documentNo, productName: productName(values.productId), note: values.linkNo });
        message.success(`${modeLabels[mode]}已完成，库存流水已生成`);
      } else if (mode === 'settings') {
        window.localStorage.setItem('inventory-default-low-stock', String(values.defaultLowStock));
        addRecord({ kind: '库存参数', documentNo: 'DEFAULT_LOW_STOCK', productName: '全局默认预警阈值', note: `${values.defaultLowStock} KG` });
        message.success('库存参数已保存');
      }
      setRecords(readRecords());
      form.resetFields();
      form.setFieldsValue({ scope: 'RND', sourceScope: 'RND', targetScope: 'PRODUCTION', businessDate: dayjs() });
      await reload();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '保存失败，请检查输入和库存状态');
    } finally {
      setSaving(false);
    }
  };

  const productName = (id: string) => products.find((product) => product.id === id)?.name || id || '未选择产品';

  const title = modeLabels[mode];
  const descriptions: Record<ControlMode, string> = {
    products: '维护库存产品编码、类别和所属库存域，产品编码由系统统一生成。',
    warnings: '维护包装规格和低库存预警阈值，保存后同步到库存查询。',
    batches: '维护批次号并以库存流水记录批次入库信息，支持追溯和冲销。',
    expiry: '维护有效期、重测日期和重测结果，临期数据在工作台集中提醒。',
    transfer: '研发库存与生产库存之间执行成对调拨，自动生成出库/入库流水。',
    'production-link': '按生产单扣减生产库存，业务单号和库存流水保持可追溯。',
    'eln-link': '按实验记录本扣减研发库存，业务单号和库存流水保持可追溯。',
    settings: '维护库存模块默认参数，参数变更写入操作记录。',
    permissions: '按角色查看库存模块可用动作；低权限角色只读，不能新增、调拨或冲销。',
    audit: '汇总库存流水和控制台操作记录，支持按单号、产品和经办人追溯。',
  };

  const scopeOptions = [
    { value: 'RND', label: '研发库存' },
    { value: 'PRODUCTION', label: '生产库存' },
  ];
  const productOptions = (scope?: InventoryScope) => (scope ? productsByScope[scope] : products).map((product) => ({ value: product.id, label: `${product.code} · ${product.name} · ${scopeLabels[product.scope]}` }));

  const renderForm = () => {
    if (mode === 'products') {
      return <Card title="新增产品主档" extra={<PlusOutlined />}><Row gutter={16}><Col span={8}><Form.Item name="name" label="产品名称" rules={[{ required: true, message: '请输入产品名称' }]}><Input placeholder="请输入产品名称" /></Form.Item></Col><Col span={8}><Form.Item name="category" label="产品类别" rules={[{ required: true, message: '请选择产品类别' }]}><Select options={[{ value: '研发产品', label: '研发产品' }, { value: '生产产品', label: '生产产品' }, { value: '原材料', label: '原材料' }]} placeholder="请选择类别" /></Form.Item></Col><Col span={8}><Form.Item name="scope" label="库存域" rules={[{ required: true, message: '请选择库存域' }]}><Select options={scopeOptions} /></Form.Item></Col></Row><Button type="primary" htmlType="button" icon={<SaveOutlined />} loading={saving} onClick={() => void save()}>保存产品</Button></Card>;
    }
    if (mode === 'warnings') {
      return <Card title="预警规则维护" extra={<SafetyCertificateOutlined />}><Row gutter={16}><Col span={8}><Form.Item name="scope" label="库存域" rules={[{ required: true }]}><Select options={scopeOptions} /></Form.Item></Col><Col span={8}><Form.Item name="productId" label="产品" rules={[{ required: true, message: '请选择产品' }]}><Select showSearch optionFilterProp="label" options={productOptions(selectedScope)} placeholder="请选择产品" /></Form.Item></Col><Col span={4}><Form.Item name="packageKg" label="包装规格 KG"><InputNumber min={0.000001} precision={6} className="inventory-full" /></Form.Item></Col><Col span={4}><Form.Item name="lowStockKg" label="低库存阈值 KG" rules={[{ required: true, message: '请输入预警阈值' }]}><InputNumber min={0} precision={6} className="inventory-full" /></Form.Item></Col></Row><Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={() => void save()}>保存规则</Button></Card>;
    }
    if (mode === 'batches' || mode === 'expiry') {
      return <Card title={modeLabels[mode]} extra={<EditOutlined />}><Row gutter={16}><Col span={8}><Form.Item name="scope" label="库存域" rules={[{ required: true }]}><Select options={scopeOptions} /></Form.Item></Col><Col span={8}><Form.Item name="productId" label="产品" rules={[{ required: true, message: '请选择产品' }]}><Select showSearch optionFilterProp="label" options={productOptions(selectedScope)} placeholder="请选择产品" /></Form.Item></Col><Col span={8}><Form.Item name="quantityKg" label="数量 KG" rules={[{ required: true, message: '请输入数量' }]}><InputNumber min={0.000001} precision={6} className="inventory-full" /></Form.Item></Col></Row><Row gutter={16}><Col span={8}><Form.Item name="batchNo" label="批次号" rules={[{ required: true, message: '请输入批次号' }]}><Input placeholder="请输入批次号" /></Form.Item></Col><Col span={8}><Form.Item name="documentNo" label="业务单号"><Input placeholder="可自动生成" /></Form.Item></Col><Col span={8}><Form.Item name="businessDate" label="业务日期" rules={[{ required: true }]}><DatePicker className="inventory-full" /></Form.Item></Col></Row>{mode === 'batches' ? <Row gutter={16}><Col span={8}><Form.Item name="productionDate" label="生产日期"><DatePicker className="inventory-full" /></Form.Item></Col><Col span={16}><Form.Item name="note" label="备注"><Input /></Form.Item></Col></Row> : <Row gutter={16}><Col span={8}><Form.Item name="expiryDate" label="有效期至" rules={[{ required: true, message: '请选择有效期' }]}><DatePicker className="inventory-full" /></Form.Item></Col><Col span={8}><Form.Item name="retestDate" label="重测日期"><DatePicker className="inventory-full" /></Form.Item></Col><Col span={8}><Form.Item name="retestResult" label="重测结果"><Select allowClear options={[{ value: '合格', label: '合格' }, { value: '不合格', label: '不合格' }, { value: '待重测', label: '待重测' }]} /></Form.Item></Col></Row>}<Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={() => void save()}>保存记录</Button></Card>;
    }
    if (mode === 'transfer') {
      return <Card title="研发与生产调拨" extra={<SwapOutlined />}><Alert type="info" showIcon message="调拨会在同一业务单号下生成一条出库流水和一条入库流水；任一环节失败时不会提示成功。" /><Divider /><Row gutter={16}><Col span={6}><Form.Item name="sourceScope" label="调出库存域" rules={[{ required: true }]}><Select options={scopeOptions} /></Form.Item></Col><Col span={6}><Form.Item name="sourceProductId" label="调出产品" rules={[{ required: true }]}><Select showSearch optionFilterProp="label" options={productOptions(sourceScope)} /></Form.Item></Col><Col span={6}><Form.Item name="targetScope" label="调入库存域" rules={[{ required: true }]}><Select options={scopeOptions} /></Form.Item></Col><Col span={6}><Form.Item name="targetProductId" label="调入产品" rules={[{ required: true }]}><Select showSearch optionFilterProp="label" options={productOptions(targetScope)} /></Form.Item></Col></Row><Row gutter={16}><Col span={8}><Form.Item name="quantityKg" label="调拨数量 KG" rules={[{ required: true, message: '请输入调拨数量' }]}><InputNumber min={0.000001} precision={6} className="inventory-full" /></Form.Item></Col><Col span={8}><Form.Item name="documentNo" label="调拨单号"><Input placeholder="可自动生成" /></Form.Item></Col><Col span={8}><Form.Item name="businessDate" label="业务日期"><DatePicker className="inventory-full" /></Form.Item></Col></Row><Form.Item name="note" label="备注"><Input /></Form.Item><Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={() => void save()}>确认调拨</Button></Card>;
    }
    if (mode === 'production-link' || mode === 'eln-link') {
      const scope: InventoryScope = mode === 'production-link' ? 'PRODUCTION' : 'RND';
      return <Card title={modeLabels[mode]} extra={<LinkOutlined />}><Row gutter={16}><Col span={8}><Form.Item name="productId" label="产品" rules={[{ required: true, message: '请选择产品' }]}><Select showSearch optionFilterProp="label" options={productOptions(scope)} /></Form.Item></Col><Col span={8}><Form.Item name="quantityKg" label="扣减数量 KG" rules={[{ required: true, message: '请输入数量' }]}><InputNumber min={0.000001} precision={6} className="inventory-full" /></Form.Item></Col><Col span={8}><Form.Item name="linkNo" label={mode === 'production-link' ? '生产单号' : '实验记录本号'} rules={[{ required: true, message: '请输入关联单号' }]}><Input /></Form.Item></Col></Row><Row gutter={16}><Col span={12}><Form.Item name="documentNo" label="库存业务单号"><Input placeholder="可自动生成" /></Form.Item></Col><Col span={12}><Form.Item name="businessDate" label="业务日期"><DatePicker className="inventory-full" /></Form.Item></Col></Row><Button type="primary" icon={<LinkOutlined />} loading={saving} onClick={() => void save()}>确认联动扣减</Button></Card>;
    }
    if (mode === 'settings') {
      return <Card title="库存参数联动" extra={<SettingOutlined />}><Form.Item name="defaultLowStock" label="全局默认低库存阈值（KG）" rules={[{ required: true, message: '请输入默认阈值' }]}><InputNumber min={0} precision={6} className="inventory-full" /></Form.Item><Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={() => void save()}>保存参数</Button></Card>;
    }
    if (mode === 'permissions') {
      return <Card title="库存权限" extra={<SafetyCertificateOutlined />}><Form.Item name="role" label="当前测试角色"><Select onChange={(role) => window.localStorage.setItem('inventory-role', role)} options={[{ value: 'WAREHOUSE_ADMIN', label: '仓库管理员（可读写）' }, { value: 'PRODUCTION_USER', label: '生产人员（生产域读写）' }, { value: 'RND_USER', label: '研发工程师（研发域读写）' }, { value: 'READ_ONLY', label: '只读用户（仅查询）' }]} /></Form.Item><div className="inventory-permission-grid"><Tag color="green">库存查询：允许</Tag><Tag color={selectedRole === 'READ_ONLY' ? 'default' : 'green'}>新增流水：{selectedRole === 'READ_ONLY' ? '拒绝' : '允许'}</Tag><Tag color={selectedRole === 'READ_ONLY' ? 'default' : 'green'}>调拨：{selectedRole === 'READ_ONLY' ? '拒绝' : '允许'}</Tag><Tag color={selectedRole === 'READ_ONLY' ? 'default' : 'green'}>冲销：{selectedRole === 'READ_ONLY' ? '拒绝' : '允许'}</Tag></div><Alert type="info" showIcon message="只读角色在库存控制台禁用写入动作；所有操作仍按业务单号写入可追溯流水。" /></Card>;
    }
    return null;
  };

  const balanceColumns = [
    { title: '产品', render: (_: unknown, row: InventoryBalance) => `${row.productCode} · ${row.productName}` },
    { title: '库存域', dataIndex: 'scope', render: (scope: InventoryScope) => scopeLabels[scope] },
    { title: '当前库存 KG', dataIndex: 'quantityKg' },
    { title: '预警阈值 KG', dataIndex: 'lowStockKg' },
    { title: '状态', dataIndex: 'alertStatus', render: (value: string) => <Tag color={value === 'NORMAL' ? 'green' : 'orange'}>{value === 'NORMAL' ? '正常' : value === 'LOW_STOCK' ? '低库存' : '无库存'}</Tag> },
  ];
  const transactionColumns = [
    { title: '日期', dataIndex: 'businessDate' },
    { title: '单号', dataIndex: 'documentNo' },
    { title: '产品', render: (_: unknown, row: InventoryTransaction) => `${row.productCode} · ${row.productName}` },
    { title: '业务类型', dataIndex: 'businessType' },
    { title: '原因', dataIndex: 'reason' },
    { title: '数量 KG', dataIndex: 'quantityKg' },
    { title: '经办人', dataIndex: 'actorName' },
  ];

  return <div className="inventory-page inventory-control-page"><div className="inventory-heading"><div><h1>{title}</h1><p>{descriptions[mode]}</p></div><Space><Button icon={<AuditOutlined />} onClick={() => setRecords(readRecords())}>刷新记录</Button><Button type="link" href="/inventory/query">返回库存查询</Button></Space></div><Tabs activeKey={mode} items={[{ key: mode, label: title }]} /><Form form={form} layout="vertical">{renderForm()}</Form>{mode === 'permissions' ? null : <><Divider orientation="left">当前数据</Divider>{mode === 'warnings' ? <Table rowKey={(row) => `${row.scope}-${row.productId}`} columns={balanceColumns} dataSource={balances} pagination={{ pageSize: 10 }} /> : mode === 'audit' ? <Table rowKey="id" columns={transactionColumns} dataSource={transactions} pagination={{ pageSize: 10 }} /> : <Table rowKey="id" columns={[{ title: '类型', dataIndex: 'kind' }, { title: '单号/标识', dataIndex: 'documentNo' }, { title: '产品', dataIndex: 'productName' }, { title: '备注', dataIndex: 'note' }, { title: '创建时间', dataIndex: 'createdAt' }]} dataSource={records.filter((record) => record.kind === title || (mode === 'batches' && record.kind === '批次管理') || (mode === 'expiry' && record.kind === '有效期与重测'))} pagination={{ pageSize: 10 }} />}</>}</div>;
}
