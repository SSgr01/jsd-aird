import { useSyncExternalStore } from 'react';
import { Button, Modal } from 'antd';

import {
  confirmSessionReplacement,
  isSessionReplacementPending,
  subscribeSessionReplacement,
} from '@/services/http/auth-events';

export function SessionReplacementDialog() {
  const open = useSyncExternalStore(subscribeSessionReplacement, isSessionReplacementPending);

  return (
    <Modal
      open={open}
      title="登录状态已失效"
      closable={false}
      maskClosable={false}
      keyboard={false}
      footer={<Button type="primary" onClick={confirmSessionReplacement}>确定</Button>}
    >
      账号已在其他地方登录。请重新登录。
    </Modal>
  );
}
