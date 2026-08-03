import { FileExcelOutlined, FileWordOutlined, RightOutlined } from '@ant-design/icons';
import { App, Button, Card, Col, DatePicker, Empty, Form, Input, InputNumber, Row, Select, Tag, Typography } from 'antd';
import type { Dayjs } from 'dayjs';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import type { TemplateListItem } from '@/features/template-workspace/types';
import { productionOrderApi } from '@/services/production-orders/production-order-api';
import { templateApi } from '@/services/templates/template-api';

interface FormValues { orderNo: string; templateVersionId: string; quantity?: number; unitCode?: string; plannedDate?: Dayjs }

export function ProductionOrderUploadPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [templates, setTemplates] = useState<TemplateListItem[]>([]);
  const [selected, setSelected] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [form] = Form.useForm<FormValues>();

  useEffect(() => { void templateApi.list({ status: 'PUBLISHED' }).then((page) => setTemplates(page.items)).catch((error) => void message.error(error instanceof Error ? error.message : '可用模板加载失败')).finally(() => setLoading(false)); }, [message]);
  const chosen = useMemo(() => templates.find((item) => item.versionId === selected), [selected, templates]);

  const create = async () => {
    const values = await form.validateFields();
    setCreating(true);
    try {
      const order = await productionOrderApi.create({ orderNo: values.orderNo, templateVersionId: values.templateVersionId, quantity: values.quantity, unitCode: values.unitCode, plannedDate: values.plannedDate?.format('YYYY-MM-DD') });
      navigate(`/production-orders/${order.id}/workspace`);
    } catch (error) { void message.error(error instanceof Error ? error.message : '生产单创建失败'); }
    finally { setCreating(false); }
  };

  return <div className="business-page">
    <div className="page-heading"><div><Typography.Title level={2}>生产单上传</Typography.Title><Typography.Text type="secondary">选择已发布模板，填写生产单基本信息后进入工作台录入内容。</Typography.Text></div><Button onClick={() => navigate('/production-orders/list')}>查看生产单</Button></div>
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
        <Card className="content-card order-form-card" title="2. 填写生产单信息">
          <Form form={form} layout="vertical"><Form.Item name="templateVersionId" hidden rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item name="orderNo" label="生产单号" rules={[{ required: true, message: '请输入生产单号' }]}><Input placeholder="例如：PO-20260731-001" /></Form.Item>
            <Form.Item label="当前模板"><Input value={chosen?.name || ''} placeholder="请从左侧选择模板" readOnly /></Form.Item>
            <Row gutter={12}><Col span={14}><Form.Item name="quantity" label="计划数量"><InputNumber min={0.0001} precision={4} style={{ width: '100%' }} /></Form.Item></Col><Col span={10}><Form.Item name="unitCode" label="单位"><Select allowClear options={[{ value: 'kg', label: 'kg' }, { value: 't', label: 't' }, { value: 'L', label: 'L' }, { value: 'pcs', label: '件' }]} /></Form.Item></Col></Row>
            <Form.Item name="plannedDate" label="计划日期"><DatePicker style={{ width: '100%' }} /></Form.Item>
            <Button type="primary" size="large" block icon={<RightOutlined />} disabled={!chosen} loading={creating} onClick={() => void create()}>创建并填写生产单</Button>
          </Form>
          <Typography.Paragraph type="secondary" className="form-footnote">创建后将复制当前已发布模板版本，后续模板调整不会改变这张生产单。</Typography.Paragraph>
        </Card>
      </Col>
    </Row>
  </div>;
}
