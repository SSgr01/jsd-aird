import { lazy, Suspense } from 'react';
import { Outlet } from 'react-router-dom';
import { ContentBoundary } from '@/components/loading/ContentBoundary';
import { AuthGate } from '@/components/auth/AuthGate';
import { useAuthStore } from '@/stores/auth-store';

const FirstLoginPasswordDialog = lazy(async () => ({
  default: (await import('@/app/providers/FirstLoginPasswordDialog')).FirstLoginPasswordDialog,
}));

const OperationAssistant = lazy(async () => ({
  default: (await import('@/components/operation-assistant/OperationAssistant')).OperationAssistant,
}));

/** Stays mounted across both the regular shell and standalone workspaces. */
export function AuthenticatedAssistantLayout() {
  const firstLogin = useAuthStore((state) => state.firstLoginPrompt && state.status === 'authenticated');
  return (
    <AuthGate>
      <ContentBoundary startup><Outlet /></ContentBoundary>
      {firstLogin && <Suspense fallback={null}><FirstLoginPasswordDialog /></Suspense>}
      <Suspense fallback={null}>
        <OperationAssistant />
      </Suspense>
    </AuthGate>
  );
}
