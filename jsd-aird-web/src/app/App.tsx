import { RouterProvider } from 'react-router-dom';
import { Suspense, useEffect } from 'react';

import { AppProviders } from '@/app/providers/AppProviders';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { router } from '@/routes/router';
import { StartupSkeleton } from '@/components/loading/StartupSkeleton';

export function App() {
  useEffect(() => {
    // Serve Unicode subsets locally without blocking the usable system-font UI.
    void import('@/styles/fonts.css').catch(() => undefined);
  }, []);

  return (
    <ErrorBoundary>
      <AppProviders>
        <Suspense fallback={<StartupSkeleton />}>
          <RouterProvider router={router} future={{ v7_startTransition: true }} />
        </Suspense>
      </AppProviders>
    </ErrorBoundary>
  );
}
