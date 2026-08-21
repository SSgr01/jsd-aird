import { LockOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { App, Button, Card, Form, Input, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';

import { useAuthStore } from '@/stores/auth-store';
import { HttpError } from '@/services/http/errors';
import './auth.css';

interface ChangePasswordForm { currentPassword: string; newPassword: string; confirmPassword: string; }

export function ChangePasswordPage() {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const changePassword = useAuthStore((state) => state.changePassword);

  const onFinish = async (values: ChangePasswordForm) => {
    try {
      await changePassword(values.currentPassword, values.newPassword);
      message.success('密码修改成功');
      navigate('/assistant', { replace: true });
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '密码修改失败');
    }
  };

  return (
    <div className="auth-page">
      <div className="auth-brand-mark"><SafetyCertificateOutlined /></div>
      <Typography.Title className="auth-brand-title">修改密码</Typography.Title>
      <Typography.Text className="auth-brand-subtitle">请输入当前密码并设置新的登录密码</Typography.Text>
      <Card className="auth-card" variant="borderless">
        <Form<ChangePasswordForm> layout="vertical" onFinish={(values) => void onFinish(values)} requiredMark={false}>
          <Form.Item name="currentPassword" label="当前密码" rules={[{ required: true, message: '请输入当前密码' }]}>
            <Input.Password prefix={<LockOutlined />} size="large" autoComplete="current-password" />
          </Form.Item>
          <Form.Item name="newPassword" label="新密码" rules={[{ required: true, min: 6, message: '新密码至少 6 位' }]}>
            <Input.Password prefix={<LockOutlined />} size="large" autoComplete="new-password" />
          </Form.Item>
          <Form.Item name="confirmPassword" label="确认新密码" dependencies={['newPassword']} rules={[{ required: true, message: '请再次输入新密码' }, ({ getFieldValue }) => ({ validator(_, value) { return !value || getFieldValue('newPassword') === value ? Promise.resolve() : Promise.reject(new Error('两次密码输入不一致')); } })]}>
            <Input.Password prefix={<LockOutlined />} size="large" autoComplete="new-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" size="large" block>保存新密码</Button>
        </Form>
      </Card>
    </div>
  );
}
