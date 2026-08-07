import type { RouteObject } from 'react-router-dom';
import { Navigate } from 'react-router-dom';

import { BasicLayout } from '@/layouts';
import { NotFoundPage } from '@/pages/not-found';
import { ProductionOrderListPage, ProductionOrderUploadPage, ProductionWorkspacePage } from '@/pages/production-orders';
import { TemplateUploadPage } from '@/pages/template-upload';
import { TemplateWorkspacePage } from '@/pages/template-workspace';
import { TemplatesPage } from '@/pages/templates';
import { TemplateImportRenderPage } from '@/pages/template-render/TemplateImportRenderPage';

export const routeConfig: RouteObject[] = [
  {
    path: '/render/import/:importJobId',
    element: <TemplateImportRenderPage />,
  },
  {
    path: '/',
    element: <BasicLayout />,
    children: [
      {
        index: true,
        element: <Navigate to="/templates/upload" replace />,
      },
      {
        path: 'templates',
        element: <Navigate to="/templates/upload" replace />,
      },
      {
        path: 'templates/upload',
        element: <TemplateUploadPage />,
      },
      {
        path: 'templates/library',
        element: <TemplatesPage />,
      },
      {
        path: 'templates/:versionId/workspace',
        element: <TemplateWorkspacePage />,
      },
      {
        path: 'production-orders',
        element: <Navigate to="/production-orders/upload" replace />,
      },
      {
        path: 'production-orders/upload',
        element: <ProductionOrderUploadPage />,
      },
      {
        path: 'production-orders/list',
        element: <ProductionOrderListPage />,
      },
      {
        path: '*',
        element: <NotFoundPage />,
      },
    ],
  },
  { path: '/production-orders/:orderId/workspace', element: <ProductionWorkspacePage /> },
];
