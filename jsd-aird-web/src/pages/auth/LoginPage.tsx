import { LockOutlined, SafetyCertificateOutlined, UserOutlined } from '@ant-design/icons';
import { App, Button, Card, Checkbox, Form, Input, Typography } from 'antd';
import { useLocation, useNavigate } from 'react-router-dom';

import { useAuthStore } from '@/stores/auth-store';
import { HttpError } from '@/services/http/errors';
import './auth.css';

interface LoginForm {
  username: string;
  password: string;
  rememberMe: boolean;
}

export function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { message } = App.useApp();
  const login = useAuthStore((state) => state.login);
  const [form] = Form.useForm<LoginForm>();

  const onFinish = async (values: LoginForm) => {
    try {
      await login(values);
      const from = (location.state as { from?: string } | null)?.from || '/assistant';
      navigate(from, { replace: true });
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '登录失败，请稍后重试');
    }
  };

  return (
    <div className="auth-page">
      <div className="auth-brand-mark"><SafetyCertificateOutlined /></div>
      <Typography.Title className="auth-brand-title">杰事达材料研发系统</Typography.Title>
      <Typography.Text className="auth-brand-subtitle">研发数据与 AI 协同工作台</Typography.Text>
      <Card className="auth-card" variant="borderless">
        <div className="auth-card-heading">
          <Typography.Title level={3}>欢迎回来</Typography.Title>
          <Typography.Text type="secondary">使用系统账号登录工作台</Typography.Text>
        </div>
        <Form<LoginForm> form={form} layout="vertical" initialValues={{ rememberMe: false }} onFinish={(values) => void onFinish(values)} requiredMark={false}>
          <Form.Item name="username" label="账号" rules={[{ required: true, message: '请输入账号' }]}>
            <Input size="large" prefix={<UserOutlined />} placeholder="请输入账号" autoComplete="username" />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password size="large" prefix={<LockOutlined />} placeholder="请输入密码" autoComplete="current-password" />
          </Form.Item>
          <Form.Item name="rememberMe" valuePropName="checked" noStyle>
            <Checkbox>保持登录状态</Checkbox>
          </Form.Item>
          <Button className="auth-submit" type="primary" htmlType="submit" size="large" block>
            登录系统
          </Button>
        </Form>
      </Card>
      <Typography.Text className="auth-footer">账号由系统管理员统一创建和维护</Typography.Text>
    </div>
  );
}
