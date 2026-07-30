import { createBrowserRouter } from 'react-router-dom';

import { routeConfig } from '@/routes/route-config';

export const router = createBrowserRouter(routeConfig, {
  future: {
    v7_relativeSplatPath: true,
  },
});
