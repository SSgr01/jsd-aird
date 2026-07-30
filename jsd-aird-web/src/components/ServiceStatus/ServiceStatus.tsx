import { useEffect, useState } from 'react';

import { Badge, Space, Typography } from 'antd';

import { getHealth } from '@/services/health/health-api';

type HealthState = 'checking' | 'up' | 'down';

export function ServiceStatus() {
  const [state, setState] = useState<HealthState>('checking');

  useEffect(() => {
    let active = true;

    void getHealth()
      .then((result) => {
        if (active) {
          setState(result.status === 'UP' ? 'up' : 'down');
        }
      })
      .catch(() => {
        if (active) {
          setState('down');
        }
      });

    return () => {
      active = false;
    };
  }, []);

  const status = state === 'checking' ? 'processing' : state === 'up' ? 'success' : 'error';
  const label =
    state === 'checking' ? '正在检查服务' : state === 'up' ? '后端服务正常' : '后端服务未连接';

  return (
    <Space>
      <Badge status={status} />
      <Typography.Text type={state === 'down' ? 'secondary' : undefined}>{label}</Typography.Text>
    </Space>
  );
}
