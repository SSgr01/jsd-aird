import {
  CameraOutlined,
  DownloadOutlined,
  EditOutlined,
  FileExcelOutlined,
  FileWordOutlined,
  InboxOutlined,
  RightOutlined,
} from '@ant-design/icons';
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  DatePicker,
  Empty,
  Form,
  Input,
  InputNumber,
  Radio,
  Row,
  Select,
  Space,
  Tag,
  Typography,
  Upload,
} from 'antd';
import type { UploadFile } from 'antd';
import type { Dayjs } from 'dayjs';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import type { TemplateListItem } from '@/features/template-workspace/types';
import { productionOrderApi } from '@/services/production-orders/production-order-api';
import { templateApi } from '@/services/templates/template-api';

interface FormValues { orderNo: string; templateVersionId: string; quantity?: number; unitCode?: string; plannedDate?: Dayjs }
type EntryMode = 'ONLINE' | 'XLSX' | 'PHOTO';

export function ProductionOrderUploadPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [templates, setTemplates] = useState<TemplateListItem[]>([]);
  const [selected, setSelected] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [entryMode, setEntryMode] = useState<EntryMode>('ONLINE');
  const [sourceFiles, setSourceFiles] = useState<UploadFile[]>([]);
  const [form] = Form.useForm<FormValues>();

  useEffect(() => {
    void templateApi
      .list({ status: 'PUBLISHED', format: 'XLSX' })
      .then((page) => setTemplates(page.items.filter((item) => item.format === 'XLSX')))
      .catch((error) => void message.error(error instanceof Error ? error.message : '可用模板加载失败'))
      .finally(() => setLoading(false));
  }, [message]);
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
      const sourceType = entryMode;
      const staged = await Promise.all(files.map((file) => productionOrderApi.stageInstanceSource(file, sourceType)));
      const job = await productionOrderApi.createIngestJob(order.id, {
        sourceType,
        sourceFileIds: staged.map((item) => item.fileId),
        requestedTemplateVersionId: values.templateVersionId,
      });
      navigate(`/production-orders/${order.id}/workspace?ingestJobId=${job.id}`);
    } catch (error) { void message.error(error instanceof Error ? error.message : '生产单创建失败'); }
    finally { setCreating(false); }
  };

  return <div className="business-page">
    <div className="page-heading"><div><Typography.Title level={2}>新建生产单</Typography.Title><Typography.Text type="secondary">先选择已发布模板，再在线填写、上传同模板 Excel，或上传打印件照片。</Typography.Text></div><Button onClick={() => navigate('/production-orders/list')}>查看生产单</Button></div>
    <Row gutter={16} align="stretch">
      <Col xs={24} xl={15}>
        <Card className="content-card" title="1. 选择业务模板" loading={loading}>
          {templates.length ? <div className="template-card-grid">{templates.map((item) => <button type="button" key={item.versionId} className="template-choice" aria-current={selected === item.versionId} onClick={() => { setSelected(item.versionId); form.setFieldValue('templateVersionId', item.versionId); }}>
            <span className={item.format === 'XLSX' ? 'format-badge excel' : 'format-badge word'}>{item.format === 'XLSX' ? <FileExcelOutlined /> : <FileWordOutlined />}</span>
            <span className="template-choice-content"><strong>{item.name}</strong><small>{item.templateCode} · V{item.versionNo}</small><span>{item.purpose || '通用业务模板'}</span></span>
            <Tag color={item.format === 'XLSX' ? 'green' : 'blue'}>{item.format === 'XLSX' ? 'Excel' : 'Word'}</Tag>
          </button>)}</div> : <Empty description="暂无已发布模板，请先在模板中心发布模板" />}
        </Card>
      </Col>
      <Col xs={24} xl={9}>
        <Card className="content-card order-form-card" title="2. 选择录入方式并填写基本信息">
          <Form form={form} layout="vertical"><Form.Item name="templateVersionId" hidden rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item name="orderNo" label="生产单号" rules={[{ required: true, message: '请输入生产单号' }]}><Input placeholder="例如：PO-20260731-001" /></Form.Item>
            <Form.Item label="当前模板"><Input value={chosen?.name || ''} placeholder="请从左侧选择模板" readOnly /></Form.Item>
            <Row gutter={12}><Col span={14}><Form.Item name="quantity" label="计划数量"><InputNumber min={0.0001} precision={4} style={{ width: '100%' }} /></Form.Item></Col><Col span={10}><Form.Item name="unitCode" label="单位"><Select allowClear options={[{ value: 'kg', label: 'kg' }, { value: 't', label: 't' }, { value: 'L', label: 'L' }, { value: 'pcs', label: '件' }]} /></Form.Item></Col></Row>
            <Form.Item name="plannedDate" label="计划日期"><DatePicker style={{ width: '100%' }} /></Form.Item>
            <Form.Item label="录入方式">
              <Radio.Group
                value={entryMode}
                optionType="button"
                buttonStyle="solid"
                onChange={(event) => {
                  setEntryMode(event.target.value as EntryMode);
                  setSourceFiles([]);
                }}
                options={[
                  { value: 'ONLINE', label: <Space size={5}><EditOutlined />在线填写</Space> },
                  { value: 'XLSX', label: <Space size={5}><FileExcelOutlined />上传 Excel</Space> },
                  { value: 'PHOTO', label: <Space size={5}><CameraOutlined />拍照/扫描</Space> },
                ]}
              />
            </Form.Item>
            {entryMode === 'XLSX' && <Space direction="vertical" style={{ width: '100%', marginBottom: 18 }}>
              <Alert type="info" showIcon message="推荐先下载当前模板，填写后原样上传；这种方式无需 AI，结果最稳定。" />
              <Button
                icon={<DownloadOutlined />}
                disabled={!chosen || chosen.format !== 'XLSX'}
                onClick={() => chosen && void productionOrderApi.downloadInstanceXlsx(chosen.versionId)}
              >下载当前生产单 Excel 模板</Button>
              <Upload.Dragger
                accept=".xlsx"
                maxCount={1}
                beforeUpload={() => false}
                fileList={sourceFiles}
                onChange={({ fileList }) => setSourceFiles(fileList)}
              ><p className="ant-upload-drag-icon"><InboxOutlined /></p><p>拖入或选择填写后的 .xlsx 文件</p></Upload.Dragger>
            </Space>}
            {entryMode === 'PHOTO' && <Space direction="vertical" style={{ width: '100%', marginBottom: 18 }}>
              <Alert type="warning" showIcon message="仅支持所选模板的打印件或扫描件；手写和低置信度内容必须在下一步复核。" />
              <Upload.Dragger
                accept="image/*"
                multiple
                beforeUpload={() => false}
                fileList={sourceFiles}
                onChange={({ fileList }) => setSourceFiles(fileList)}
              ><p className="ant-upload-drag-icon"><CameraOutlined /></p><p>可上传同一生产单的多页照片</p></Upload.Dragger>
            </Space>}
            <Button type="primary" size="large" block icon={<RightOutlined />} disabled={!chosen} loading={creating} onClick={() => void create()}>
              {entryMode === 'ONLINE' ? '创建并在线填写' : '创建并生成导入预览'}
            </Button>
          </Form>
          <Typography.Paragraph type="secondary" className="form-footnote">所有上传结果都先进入预览，确认前不会改写生产单；提交生产单后才生成不可变实例记录。</Typography.Paragraph>
        </Card>
      </Col>
    </Row>
  </div>;
}
