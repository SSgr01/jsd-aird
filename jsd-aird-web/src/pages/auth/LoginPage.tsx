import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { App, Button, Card, Checkbox, Form, Input, Typography } from 'antd';
import { useLocation, useNavigate } from 'react-router-dom';
import { useEffect } from 'react';
import { markPerformance, measurePerformance } from '@/services/performance/metrics';

import { useAuthStore } from '@/stores/auth-store';
import { HttpError } from '@/services/http/errors';
import { workbenchReturn } from './workbench-return';
import { loginDestination } from './login-redirect';
import { BrandMark } from '@/components/brand/BrandMark';
import { appEnv } from '@/app/config/env';
import './auth.css';

interface LoginForm {
  username: string;
  password: string;
  rememberMe: boolean;
}

export function LoginPage() {
  useEffect(() => { markPerformance('login-ready'); measurePerformance('login', 'boot-start', 'login-ready'); }, []);
  const navigate = useNavigate();
  const location = useLocation();
  const { message } = App.useApp();
  const login = useAuthStore((state) => state.login);
  const [form] = Form.useForm<LoginForm>();

  const onFinish = async (values: LoginForm) => {
    try {
      await login(values);
      const workbench = workbenchReturn(location.search, location.hash);
      if (workbench) {
        window.location.replace(workbench);
        return;
      }
      const requestedFrom = (location.state as { from?: string } | null)?.from;
      navigate(loginDestination(requestedFrom), { replace: true });
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '登录失败，请稍后重试');
    }
  };

  return (
    <div className="auth-page">
      <Card className="auth-card" variant="borderless">
        <div className="auth-product-brand">
          <BrandMark size={40} />
          <div><strong>{appEnv.title || 'AI 研发平台'}</strong><span>研发数据与 AI 协同工作台</span></div>
        </div>
        <div className="auth-card-heading">
          <Typography.Title level={1}>欢迎回来</Typography.Title>
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
