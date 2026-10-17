import { lazy, Suspense } from 'react';
import { Outlet } from 'react-router-dom';
import { AuthGate } from '@/components/auth/AuthGate';

const OperationAssistant = lazy(async () => ({
  default: (await import('@/components/operation-assistant/OperationAssistant')).OperationAssistant,
}));

/** Stays mounted across both the regular shell and standalone workspaces. */
export function AuthenticatedAssistantLayout() {
  return (
    <AuthGate>
      <Outlet />
      <Suspense fallback={null}>
        <OperationAssistant />
      </Suspense>
    </AuthGate>
  );
}
