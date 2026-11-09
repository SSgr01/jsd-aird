import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, DatePicker, Form, Input, InputNumber, Space, Typography } from 'antd';
import dayjs from 'dayjs';
import { useEffect } from 'react';

import type { QuotationDetail, QuotationEdit } from '@/services/quotations/quotation-api';

type FormValues = Omit<QuotationEdit, 'items' | 'versionNo' | 'workbookSnapshot'> & {
  items: Array<Omit<QuotationEdit['items'][number], 'quoteDate'> & { quoteDate?: dayjs.Dayjs | null }>;
};

export function QuotationEditForm({ detail, saving, onCancel, onSave, cellChange }: {
  detail: QuotationDetail;
  saving: boolean;
  onCancel: () => void;
  onSave: (value: QuotationEdit) => Promise<void>;
  cellChange?: { key: string; value: string; revision: number };
}) {
  const [form] = Form.useForm<FormValues>();
  useEffect(() => {
    if (!cellChange) return;
    const [section, index, field] = cellChange.key.split(':');
    if (!field) return;
    const customerPath: Record<string, keyof FormValues> = {
      '客户名称': 'customerName', '关联公司': 'relatedCompany', '主联系人': 'contactName',
      '职位': 'contactTitle', '电话': 'phone', '传真': 'fax', '邮箱': 'email',
      '网站': 'website', '地址1': 'addressLine1', '地址2': 'addressLine2', '备注': 'note',
    };
    const itemPath: Record<string, keyof FormValues['items'][number]> = {
      '产品': 'productName', '单价': 'unitPrice', '规格': 'specification',
      '报价日期': 'quoteDate', '客户编码': 'customerCode', '备注': 'note',
    };
    if (section === 'customer' && customerPath[field]) form.setFieldValue(customerPath[field], cellChange.value);
    if (section === 'items' && itemPath[field]) {
      const value = field === '报价日期' ? dayjs(cellChange.value) : cellChange.value;
      form.setFieldValue(['items', Number(index), itemPath[field]], value);
    }
  }, [cellChange, form]);
  const initialValues: FormValues = {
    customerName: detail.summary.customerName,
    relatedCompany: detail.summary.relatedCompany,
    contactName: detail.summary.contactName,
    contactTitle: detail.contactTitle,
    phone: detail.summary.phone,
    fax: detail.fax,
    email: detail.email,
    addressLine1: detail.addressLine1,
    addressLine2: detail.addressLine2,
    website: detail.website,
    note: detail.note,
    contacts: detail.contacts.map(({ sourceColumn, name, title, phone }) => ({ sourceColumn, name, title, phone })),
    items: detail.items.map(({ sourceRowNo, productName, unitPrice, specification, quoteDate,
      customerCode, note, priceOptions }) => ({
      sourceRowNo: sourceRowNo || 1, productName, unitPrice, specification,
      quoteDate: quoteDate ? dayjs(quoteDate) : null, customerCode, note,
      priceOptions: priceOptions.map(({ unitPrice: price, specification: spec }) => ({ unitPrice: price, specification: spec })),
    })),
  };

  return <Form id="quotation-edit-form" form={form} layout="vertical" initialValues={initialValues}
    onFinish={(values) => void onSave({
      ...values,
      versionNo: detail.versionNo,
      items: values.items.map((item) => ({ ...item, quoteDate: item.quoteDate?.format('YYYY-MM-DD') ?? null })),
    })}>
    <Typography.Title level={5}>客户资料</Typography.Title>
    <Space wrap align="start">
      <Form.Item name="customerName" label="客户名称" rules={[{ required: true, whitespace: true }]}><Input maxLength={300} /></Form.Item>
      <Form.Item name="relatedCompany" label="关联公司"><Input maxLength={300} /></Form.Item>
      <Form.Item name="contactName" label="主联系人"><Input maxLength={300} /></Form.Item>
      <Form.Item name="contactTitle" label="职位"><Input maxLength={160} /></Form.Item>
      <Form.Item name="phone" label="电话"><Input maxLength={300} /></Form.Item>
      <Form.Item name="fax" label="传真"><Input maxLength={120} /></Form.Item>
      <Form.Item name="email" label="邮箱"><Input maxLength={300} /></Form.Item>
      <Form.Item name="website" label="网站"><Input maxLength={300} /></Form.Item>
      <Form.Item name="addressLine1" label="地址1"><Input maxLength={600} /></Form.Item>
      <Form.Item name="addressLine2" label="地址2"><Input maxLength={600} /></Form.Item>
    </Space>
    <Form.Item name="note" label="备注"><Input.TextArea rows={2} /></Form.Item>
    <Typography.Title level={5}>联系人</Typography.Title>
    <Form.List name="contacts">{(fields, { add, remove }) => <>
      {fields.map((field) => <Space key={field.key} wrap align="start">
        <Form.Item name={[field.name, 'name']} label="姓名"><Input maxLength={300} /></Form.Item>
        <Form.Item name={[field.name, 'title']} label="职位"><Input maxLength={160} /></Form.Item>
        <Form.Item name={[field.name, 'phone']} label="电话"><Input maxLength={300} /></Form.Item>
        <Form.Item name={[field.name, 'sourceColumn']} hidden><InputNumber /></Form.Item>
        <Button icon={<DeleteOutlined />} aria-label="删除联系人" onClick={() => remove(field.name)} />
      </Space>)}
      <Button icon={<PlusOutlined />} onClick={() => add({ sourceColumn: 1 })}>添加联系人</Button>
    </>}</Form.List>
    <Typography.Title level={5} style={{ marginTop: 24 }}>报价明细</Typography.Title>
    <Form.List name="items" rules={[{ validator: (_, value: unknown) =>
      Array.isArray(value) && value.length > 0
        ? Promise.resolve()
        : Promise.reject(new Error('至少保留一条报价明细')) }]}>{(fields, { add, remove }, { errors }) => <>
      {fields.map((field, index) => <div key={field.key} style={{ borderTop: '1px solid #ddd', paddingTop: 12, marginTop: 12 }}>
        <Space style={{ width: '100%', justifyContent: 'space-between' }}>
          <Typography.Text strong>第 {index + 1} 条</Typography.Text>
          <Button icon={<DeleteOutlined />} aria-label="删除明细" onClick={() => remove(field.name)} />
        </Space>
        <Space wrap align="start">
          <Form.Item name={[field.name, 'productName']} label="产品" rules={[{ required: true, whitespace: true }]}><Input maxLength={300} /></Form.Item>
          <Form.Item name={[field.name, 'unitPrice']} label="原始单价"><Input maxLength={160} /></Form.Item>
          <Form.Item name={[field.name, 'specification']} label="原始规格"><Input maxLength={200} /></Form.Item>
          <Form.Item name={[field.name, 'quoteDate']} label="报价日期"><DatePicker /></Form.Item>
          <Form.Item name={[field.name, 'customerCode']} label="客户编码"><Input maxLength={120} /></Form.Item>
          <Form.Item name={[field.name, 'sourceRowNo']} hidden><InputNumber /></Form.Item>
        </Space>
        <Form.Item name={[field.name, 'note']} label="备注"><Input.TextArea rows={1} /></Form.Item>
        <Form.List name={[field.name, 'priceOptions']}>{(prices, actions) => <>
          {prices.map((price) => <Space key={price.key} align="start">
            <Form.Item name={[price.name, 'unitPrice']} label="单价" rules={[{ required: true }]}><InputNumber min={0} precision={6} /></Form.Item>
            <Form.Item name={[price.name, 'specification']} label="规格"><Input maxLength={200} /></Form.Item>
            <Button icon={<DeleteOutlined />} aria-label="删除价格" onClick={() => actions.remove(price.name)} />
          </Space>)}
          <Button icon={<PlusOutlined />} onClick={() => actions.add()}>添加价格</Button>
        </>}</Form.List>
      </div>)}
      <Form.ErrorList errors={errors} />
      <Button icon={<PlusOutlined />} style={{ marginTop: 16 }} onClick={() => add({ sourceRowNo: 1, priceOptions: [] })}>添加明细</Button>
    </>}</Form.List>
    <Space style={{ marginTop: 24 }}><Button onClick={onCancel}>取消</Button><Button type="primary" htmlType="submit" loading={saving}>保存</Button></Space>
  </Form>;
}
