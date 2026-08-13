import { Form, Input, Modal, Select } from 'antd';
import { useEffect } from 'react';

export interface CategoryEditorValue {
  name: string;
  description?: string;
  scope?: string;
  targetDataType?: string;
}

interface CategoryEditorModalProps {
  open: boolean;
  title: string;
  initialValue?: CategoryEditorValue;
  scopeOptions?: Array<{ value: string; label: string }>;
  targetDataTypeOptions?: Array<{ value: string; label: string }>;
  confirmLoading?: boolean;
  onCancel: () => void;
  onSubmit: (value: CategoryEditorValue) => void;
}

export function CategoryEditorModal({ open, title, initialValue, scopeOptions, targetDataTypeOptions, confirmLoading, onCancel, onSubmit }: CategoryEditorModalProps) {
  const [form] = Form.useForm<CategoryEditorValue>();
  useEffect(() => {
    if (open) form.setFieldsValue(initialValue || { name: '' });
    else form.resetFields();
  }, [form, initialValue, open]);
  return (
    <Modal open={open} title={title} okText="保存" cancelText="取消" confirmLoading={confirmLoading} destroyOnHidden onCancel={onCancel} onOk={() => void form.validateFields().then(onSubmit)}>
      <Form form={form} layout="vertical">
        <Form.Item name="name" label="分类名称" rules={[{ required: true, whitespace: true, message: '请输入分类名称' }]}>
          <Input autoFocus maxLength={120} />
        </Form.Item>
        <Form.Item name="description" label="分类简介">
          <Input.TextArea rows={3} maxLength={240} showCount placeholder="可选，简要说明该分类的用途或内容范围" />
        </Form.Item>
        {scopeOptions && <Form.Item name="scope" label="资料范围" rules={[{ required: true, message: '请选择资料范围' }]}><Select options={scopeOptions} /></Form.Item>}
        {targetDataTypeOptions && <Form.Item name="targetDataType" label="绑定数据类型"><Select allowClear placeholder="不绑定固定类型" options={targetDataTypeOptions} /></Form.Item>}
      </Form>
    </Modal>
  );
}
