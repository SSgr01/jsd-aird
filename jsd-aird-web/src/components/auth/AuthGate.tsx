import type { PropsWithChildren } from 'react';
import { Fragment, useEffect } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { StartupSkeleton } from '@/components/loading/StartupSkeleton';

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
    return <StartupSkeleton />;
  }
  if (status === 'error') {
    return <StartupSkeleton error onRetry={() => void load()} />;
  }
  if (!user) {
    return <Navigate to="/login" replace state={{ from: `${location.pathname}${location.search}` }} />;
  }
  return <Fragment key={JSON.stringify([user.organizationId,user.userId,user.authVersion,[...user.permissions].sort(),[...(user.menuKeys ?? [])].sort()])}>{children}</Fragment>;
}
