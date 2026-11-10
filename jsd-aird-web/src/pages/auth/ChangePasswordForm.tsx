import { LockOutlined } from '@ant-design/icons';
import { Button, Form, Input } from 'antd';

export interface PasswordValues {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

interface Props {
  onSubmit: (values: PasswordValues) => Promise<void>;
  submitting: boolean;
  onCancel?: () => void;
}

export function ChangePasswordForm({ onSubmit, submitting, onCancel }: Props) {
  return (
    <Form<PasswordValues> layout="vertical" onFinish={(values) => void onSubmit(values)} requiredMark={false}>
      <Form.Item name="currentPassword" label="当前密码" rules={[{ required: true, message: '请输入当前密码' }]}>
        <Input.Password prefix={<LockOutlined />} size="large" autoComplete="current-password" />
      </Form.Item>
      <Form.Item name="newPassword" label="新密码" rules={[{ required: true, min: 6, message: '新密码至少 6 位' }]}>
        <Input.Password prefix={<LockOutlined />} size="large" autoComplete="new-password" />
      </Form.Item>
      <Form.Item name="confirmPassword" label="确认新密码" dependencies={['newPassword']} rules={[{ required: true, message: '请再次输入新密码' }, ({ getFieldValue }) => ({ validator(_, value) { return !value || getFieldValue('newPassword') === value ? Promise.resolve() : Promise.reject(new Error('两次密码输入不一致')); } })]}>
        <Input.Password prefix={<LockOutlined />} size="large" autoComplete="new-password" />
      </Form.Item>
      <Form.Item style={{ marginBottom: 0 }}>
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          {onCancel && <Button onClick={onCancel} disabled={submitting}>稍后再说</Button>}
          <Button type="primary" htmlType="submit" loading={submitting}>保存新密码</Button>
        </div>
      </Form.Item>
    </Form>
  );
}
