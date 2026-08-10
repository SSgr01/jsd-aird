import { CameraOutlined, DownloadOutlined, EditOutlined, FileExcelOutlined, RightOutlined } from '@ant-design/icons';
import { Alert, App, Button, Col, DatePicker, Form, Input, InputNumber, Radio, Row, Select, Space } from 'antd';
import type { UploadFile } from 'antd';
import type { Dayjs } from 'dayjs';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { UploadWorkspace, type UploadWorkspaceRecord } from '@/components/upload-workspace';
import type { ProductionOrderListItem, ProductionOrderStatus } from '@/features/production-orders/types';
import type { TemplateListItem } from '@/features/template-workspace/types';
import { productionOrderApi } from '@/services/production-orders/production-order-api';
import { templateApi } from '@/services/templates/template-api';

interface FormValues { orderNo: string; templateVersionId: string; quantity?: number; unitCode?: string; plannedDate?: Dayjs }
type EntryMode = 'ONLINE' | 'XLSX' | 'PHOTO';

const orderStatuses: Record<ProductionOrderStatus, { label: string; color: string }> = {
  DRAFT: { label: '填写中', color: 'processing' },
  SUBMITTED: { label: '已提交', color: 'success' },
  CANCELLED: { label: '已取消', color: 'default' },
};

export function ProductionOrderUploadPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [templates, setTemplates] = useState<TemplateListItem[]>([]);
  const [selected, setSelected] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [entryMode, setEntryMode] = useState<EntryMode>('ONLINE');
  const [sourceFiles, setSourceFiles] = useState<UploadFile[]>([]);
  const [orders, setOrders] = useState<ProductionOrderListItem[]>([]);
  const [ordersLoading, setOrdersLoading] = useState(false);
  const [orderStatus, setOrderStatus] = useState('ALL');
  const [orderKeyword, setOrderKeyword] = useState('');
  const [form] = Form.useForm<FormValues>();

  useEffect(() => {
    void templateApi.list({ status: 'PUBLISHED', format: 'XLSX' })
      .then((page) => setTemplates(page.items.filter((item) => item.format === 'XLSX')))
      .catch((error) => void message.error(error instanceof Error ? error.message : '可用模板加载失败'))
      .finally(() => setLoading(false));
  }, [message]);

  const loadOrders = useCallback(async () => {
    setOrdersLoading(true);
    try { setOrders(await productionOrderApi.list()); }
    catch (error) { void message.error(error instanceof Error ? error.message : '生产单列表加载失败'); }
    finally { setOrdersLoading(false); }
  }, [message]);

  useEffect(() => { void loadOrders(); }, [loadOrders]);

  const chosen = useMemo(() => templates.find((item) => item.versionId === selected), [selected, templates]);

  const create = async () => {
    const values = await form.validateFields();
    setCreating(true);
    try {
      const order = await productionOrderApi.create({ orderNo: values.orderNo, templateVersionId: values.templateVersionId, quantity: values.quantity, unitCode: values.unitCode, plannedDate: values.plannedDate?.format('YYYY-MM-DD') });
      if (entryMode === 'ONLINE') {
        navigate(`/production-orders/${order.id}/workspace`);
        return;
      }
      const files = sourceFiles.flatMap((item) => item.originFileObj ? [item.originFileObj] : []);
      if (!files.length) throw new Error(entryMode === 'XLSX' ? '请选择填写后的 Excel 文件' : '请选择照片或扫描件');
      const staged = await Promise.all(files.map((file) => productionOrderApi.stageInstanceSource(file, entryMode)));
      const job = await productionOrderApi.createIngestJob(order.id, { sourceType: entryMode, sourceFileIds: staged.map((item) => item.fileId), requestedTemplateVersionId: values.templateVersionId });
      await loadOrders();
      navigate(`/production-orders/${order.id}/workspace?ingestJobId=${job.id}`);
    } catch (error) { void message.error(error instanceof Error ? error.message : '生产单创建失败'); }
    finally { setCreating(false); }
  };

  const records = useMemo<UploadWorkspaceRecord[]>(() => orders
    .filter((item) => (orderStatus === 'ALL' || item.status === orderStatus) && (!orderKeyword || `${item.orderNo}${item.templateName}${item.templateCode}`.toLowerCase().includes(orderKeyword.toLowerCase())))
    .map((item) => ({
      id: item.id,
      name: item.orderNo,
      icon: <FileExcelOutlined />,
      meta: `${item.templateName} · ${item.templateCode}`,
      detail: `${item.quantity ? `${item.quantity} ${item.unitCode || ''} · ` : ''}${item.plannedDate || '未设置计划日期'} · ${new Date(item.updatedAt).toLocaleString('zh-CN')}`,
      status: orderStatuses[item.status],
      actions: <Button type="link" icon={<RightOutlined />} onClick={() => navigate(`/production-orders/${item.id}/workspace`)}>{item.status === 'DRAFT' ? '继续填写' : '查看详情'}</Button>,
    })), [navigate, orderKeyword, orderStatus, orders]);

  return (
    <UploadWorkspace
      breadcrumbs={[{ title: '生产单管理' }, { title: '生产单上传' }]}
      title="生产单上传"
      description="选择已发布模板，再在线填写、上传同模板 Excel，或上传打印件照片。"
      headerActions={<Button onClick={() => navigate('/production-orders/list')}>生产单查看</Button>}
      leftTitle="基础分类"
      classification={<Form form={form} layout="vertical">
        <Form.Item name="templateVersionId" hidden rules={[{ required: true, message: '请选择业务模板' }]}><Input /></Form.Item>
        <Form.Item label="业务模板" required>
          <Select loading={loading} placeholder="请选择已发布业务模板" value={selected} onChange={(value) => { setSelected(value); form.setFieldValue('templateVersionId', value); }} options={templates.map((item) => ({ value: item.versionId, label: `${item.name} · ${item.templateCode} · V${item.versionNo}` }))} />
        </Form.Item>
        <Form.Item name="orderNo" label="生产单号" rules={[{ required: true, message: '请输入生产单号' }]}><Input placeholder="例如：PO-20260731-001" /></Form.Item>
        <Row gutter={12}><Col span={14}><Form.Item name="quantity" label="计划数量"><InputNumber min={0.0001} precision={4} style={{ width: '100%' }} /></Form.Item></Col><Col span={10}><Form.Item name="unitCode" label="单位"><Select allowClear options={[{ value: 'kg', label: 'kg' }, { value: 't', label: 't' }, { value: 'L', label: 'L' }, { value: 'pcs', label: '件' }]} /></Form.Item></Col></Row>
        <Form.Item name="plannedDate" label="计划日期"><DatePicker style={{ width: '100%' }} /></Form.Item>
        <Form.Item label="录入方式">
          <Radio.Group value={entryMode} optionType="button" buttonStyle="solid" onChange={(event) => { setEntryMode(event.target.value as EntryMode); setSourceFiles([]); }} options={[{ value: 'ONLINE', label: <Space size={5}><EditOutlined />在线填写</Space> }, { value: 'XLSX', label: <Space size={5}><FileExcelOutlined />上传 Excel</Space> }, { value: 'PHOTO', label: <Space size={5}><CameraOutlined />拍照/扫描</Space> }]} />
        </Form.Item>
        {entryMode === 'XLSX' && <Alert type="info" showIcon message="推荐先下载当前模板，填写后原样上传；这种方式无需 AI，结果最稳定。" action={<Button size="small" icon={<DownloadOutlined />} disabled={!chosen} onClick={() => chosen && void productionOrderApi.downloadInstanceXlsx(chosen.versionId)}>下载模板</Button>} />}
        {entryMode === 'PHOTO' && <Alert type="warning" showIcon message="仅支持所选模板的打印件或扫描件；手写和低置信度内容必须在下一步复核。" />}
      </Form>}
      accept={entryMode === 'PHOTO' ? 'image/*' : '.xlsx'}
      showDropzone={entryMode !== 'ONLINE'}
      showPreview={entryMode !== 'ONLINE'}
      fileRequired={entryMode !== 'ONLINE'}
      multiple={entryMode === 'PHOTO'}
      maxCount={entryMode === 'PHOTO' ? undefined : 1}
      files={sourceFiles}
      onFilesChange={setSourceFiles}
      onRemoveFile={(file) => setSourceFiles((current) => current.filter((item) => item.uid !== file.uid))}
      onClearFiles={() => setSourceFiles([])}
      uploadMainText={entryMode === 'PHOTO' ? '拖拽或选择同一生产单的多页照片' : '拖拽或选择填写后的 .xlsx 文件'}
      uploadHint={entryMode === 'PHOTO' ? '支持 JPG / PNG / TIFF，支持多页上传。' : '仅支持当前业务模板对应的 XLSX 文件。'}
      submitLabel={entryMode === 'ONLINE' ? '创建并在线填写' : '创建并生成导入预览'}
      submitIcon={<RightOutlined />}
      onSubmit={() => void create()}
      submitting={creating}
      submitDisabled={!chosen}
      rightTitle="已上传生产单"
      rightCount={records.length}
      rightFilters={[{ key: 'ALL', label: '全部' }, { key: 'DRAFT', label: '填写中' }, { key: 'SUBMITTED', label: '已提交' }, { key: 'CANCELLED', label: '已取消' }]}
      activeFilter={orderStatus}
      onFilterChange={setOrderStatus}
      searchValue={orderKeyword}
      onSearchChange={setOrderKeyword}
      searchPlaceholder="搜索生产单号或模板"
      records={records}
      recordsLoading={ordersLoading}
    />
  );
}
