import type { PropsWithChildren } from 'react';
import { useEffect } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { Spin } from 'antd';

import { useAuthStore } from '@/stores/auth-store';

export function AuthGate({ children }: PropsWithChildren) {
  const location = useLocation();
  const status = useAuthStore((state) => state.status);
  const user = useAuthStore((state) => state.user);
  const load = useAuthStore((state) => state.load);

  useEffect(() => {
    if (import.meta.env.MODE !== 'test' && status === 'unknown') void load();
  }, [load, status]);

  if (import.meta.env.MODE === 'test') return children;
  if (status === 'unknown' || status === 'loading') {
    return <div className="auth-loading"><Spin /><span>正在校验登录状态…</span></div>;
  }
  if (!user) {
    return <Navigate to="/login" replace state={{ from: `${location.pathname}${location.search}` }} />;
  }
  return children;
}
