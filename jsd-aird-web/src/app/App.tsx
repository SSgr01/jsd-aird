import { RouterProvider } from 'react-router-dom';

import { AppProviders } from '@/app/providers/AppProviders';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { router } from '@/routes/router';

export function App() {
  return (
    <ErrorBoundary>
      <AppProviders>
        <RouterProvider router={router} future={{ v7_startTransition: true }} />
      </AppProviders>
    </ErrorBoundary>
  );
}
