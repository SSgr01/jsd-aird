import { SafetyCertificateOutlined } from '@ant-design/icons';
import { App, Card, Typography } from 'antd';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { ChangePasswordForm, type PasswordValues } from './ChangePasswordForm';
import { useAuthStore } from '@/stores/auth-store';
import { HttpError } from '@/services/http/errors';
import './auth.css';

export function ChangePasswordPage() {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const changePassword = useAuthStore((state) => state.changePassword);
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async (values: PasswordValues) => {
    setSubmitting(true);
    try {
      await changePassword(values.currentPassword, values.newPassword);
      message.success('密码修改成功');
      navigate('/assistant', { replace: true });
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '密码修改失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="auth-page">
      <div className="auth-brand-mark"><SafetyCertificateOutlined /></div>
      <Typography.Title className="auth-brand-title">修改密码</Typography.Title>
      <Typography.Text className="auth-brand-subtitle">请输入当前密码并设置新的登录密码</Typography.Text>
      <Card className="auth-card" variant="borderless">
        <ChangePasswordForm onSubmit={onSubmit} submitting={submitting} />
      </Card>
    </div>
  );
}
