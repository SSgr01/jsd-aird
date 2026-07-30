import type { RouteObject } from 'react-router-dom';

import { BasicLayout } from '@/layouts';
import { HomePage } from '@/pages/home';
import { NotFoundPage } from '@/pages/not-found';

export const routeConfig: RouteObject[] = [
  {
    path: '/',
    element: <BasicLayout />,
    children: [
      {
        index: true,
        element: <HomePage />,
      },
      {
        path: '*',
        element: <NotFoundPage />,
      },
    ],
  },
];
