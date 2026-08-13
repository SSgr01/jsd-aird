import type { RouteObject } from 'react-router-dom';
import { Navigate } from 'react-router-dom';

import { BasicLayout } from '@/layouts';
import { AssistantPage } from '@/pages/assistant';
import { DataAssetPage, DataImportJobPage, DataUploadPage, DataViewPage } from '@/pages/data';
import { KnowledgeDocumentPage, KnowledgeLibraryPage, KnowledgePageDetailPage, KnowledgePagesPage, KnowledgeReviewPage, KnowledgeReviewQueuePage, KnowledgeSearchPage, KnowledgeViewPage } from '@/pages/knowledge';
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
        element: <Navigate to="/assistant" replace />,
      },
      { path: 'knowledge/library', element: <KnowledgeLibraryPage /> },
      { path: 'knowledge/view', element: <KnowledgeViewPage /> },
      { path: 'knowledge/search', element: <KnowledgeSearchPage /> },
      { path: 'knowledge/review', element: <KnowledgeReviewQueuePage /> },
      { path: 'knowledge/review/:documentId/:versionId', element: <KnowledgeReviewPage /> },
      { path: 'knowledge/pages', element: <KnowledgePagesPage /> },
      { path: 'knowledge/pages/:id', element: <KnowledgePageDetailPage /> },
      { path: 'knowledge/documents/:id', element: <KnowledgeDocumentPage /> },
      { path: 'assistant', element: <AssistantPage /> },
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
      { path: 'data', element: <Navigate to="/data/upload" replace /> },
      { path: 'data/upload', element: <DataUploadPage /> },
      { path: 'data/import-jobs/:id', element: <DataImportJobPage /> },
      { path: 'data/view', element: <DataViewPage /> },
      { path: 'data/assets/:id', element: <DataAssetPage /> },
      {
        path: '*',
        element: <NotFoundPage />,
      },
    ],
  },
  { path: '/production-orders/:orderId/workspace', element: <ProductionWorkspacePage /> },
];
