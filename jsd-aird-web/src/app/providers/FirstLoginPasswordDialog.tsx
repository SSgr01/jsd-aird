import { App, Modal, Typography } from 'antd';
import { useState } from 'react';

import { ChangePasswordForm, type PasswordValues } from '@/pages/auth/ChangePasswordForm';
import { HttpError } from '@/services/http/errors';
import { useAuthStore } from '@/stores/auth-store';

export function FirstLoginPasswordDialog() {
  const open = useAuthStore((state) => state.firstLoginPrompt && state.status === 'authenticated');
  const dismiss = useAuthStore((state) => state.dismissFirstLoginPrompt);
  const changePassword = useAuthStore((state) => state.changePassword);
  const [submitting, setSubmitting] = useState(false);
  const { message } = App.useApp();

  const onSubmit = async (values: PasswordValues) => {
    setSubmitting(true);
    try {
      await changePassword(values.currentPassword, values.newPassword);
      message.success('密码修改成功');
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '密码修改失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal open={open} title="首次登录，请修改密码" onCancel={() => { if (!submitting) dismiss(); }} footer={null} destroyOnHidden>
      <Typography.Paragraph type="secondary">建议设置新的登录密码，以保护账号安全。</Typography.Paragraph>
      <ChangePasswordForm onSubmit={onSubmit} submitting={submitting} onCancel={dismiss} />
    </Modal>
  );
}
