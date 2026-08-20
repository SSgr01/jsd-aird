import { RouterProvider } from 'react-router-dom';
import { Suspense } from 'react';

import { AppProviders } from '@/app/providers/AppProviders';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { router } from '@/routes/router';

export function App() {
  return (
    <ErrorBoundary>
      <AppProviders>
        <Suspense fallback={<div className="app-route-loading">页面加载中…</div>}>
          <RouterProvider router={router} future={{ v7_startTransition: true }} />
        </Suspense>
      </AppProviders>
    </ErrorBoundary>
  );
}
