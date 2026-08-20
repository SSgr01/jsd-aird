import { Result } from 'antd';
import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';

import { firstAccessiblePath } from './route-permissions';
import { useAuthStore } from '@/stores/auth-store';

export function AuthorizedHomeRedirect() {
  const user = useAuthStore((state) => state.user);
  const target = firstAccessiblePath(user?.permissions ?? []);
  return target ? <Navigate to={target} replace /> : <Result status="403" title="暂无可访问功能" subTitle="请联系系统管理员配置查看权限" />;
}

export function PagePermissionGate({ permission, children }: { permission: string; children: ReactNode }) {
  const permissions = useAuthStore((state) => state.user?.permissions ?? []);
  if (!permissions.includes(permission)) {
    return <Result status="403" title="暂无权限访问此页面" subTitle="请联系系统管理员开通查看权限" />;
  }
  return <>{children}</>;
}
