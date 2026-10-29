import { RouterProvider } from 'react-router-dom';
import { Suspense } from 'react';

import { AppProviders } from '@/app/providers/AppProviders';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { router } from '@/routes/router';
import { StartupSkeleton } from '@/components/loading/StartupSkeleton';

export function App() {
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
