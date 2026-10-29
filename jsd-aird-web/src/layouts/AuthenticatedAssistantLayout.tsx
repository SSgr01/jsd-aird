import { lazy, Suspense } from 'react';
import { Outlet } from 'react-router-dom';
import { ContentBoundary } from '@/components/loading/ContentBoundary';
import { AuthGate } from '@/components/auth/AuthGate';

const OperationAssistant = lazy(async () => ({
  default: (await import('@/components/operation-assistant/OperationAssistant')).OperationAssistant,
}));

/** Stays mounted across both the regular shell and standalone workspaces. */
export function AuthenticatedAssistantLayout() {
  return (
    <AuthGate>
      <ContentBoundary startup><Outlet /></ContentBoundary>
      <Suspense fallback={null}>
        <OperationAssistant />
      </Suspense>
    </AuthGate>
  );
}
