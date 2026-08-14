import type { RouteObject } from 'react-router-dom';
import { Navigate } from 'react-router-dom';

import { BasicLayout } from '@/layouts';
import { AssistantPage } from '@/pages/assistant';
import { DataAssetPage, DataImportJobPage, DataUploadPage, DataViewPage } from '@/pages/data';
import { KnowledgeDocumentPage, KnowledgeLibraryPage, KnowledgeReviewPage, KnowledgeReviewQueuePage, KnowledgeSearchPage, KnowledgeViewPage } from '@/pages/knowledge';
import { NotFoundPage } from '@/pages/not-found';
import { ProductionOrderListPage, ProductionOrderUploadPage, ProductionWorkspacePage } from '@/pages/production-orders';
import { TemplateUploadPage } from '@/pages/template-upload';
import { TemplateWorkspacePage } from '@/pages/template-workspace';
import { TemplatesPage } from '@/pages/templates';
import { TemplateImportRenderPage } from '@/pages/template-render/TemplateImportRenderPage';
import { PartnerDetailPage, PartnerListPage } from '@/pages/partners';
import { ProjectDetailPage, ProjectListPage, PhasePage, TaskPage } from '@/pages/project';
import { ProjectDocumentWorkspacePage } from '@/pages/project/ProjectDocumentWorkspacePage';
import { ExperimentListPage, ExperimentUploadPage, ExperimentWorkspacePage } from '@/pages/experiments';

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
      { path: 'partners', element: <PartnerListPage /> },
      { path: 'partners/:id', element: <PartnerDetailPage /> },
      { path: 'projects', element: <Navigate to="/projects/list" replace /> },
      { path: 'projects/list', element: <ProjectListPage /> },
      { path: 'projects/:id', element: <ProjectDetailPage /> },
      { path: 'projects/:id/documents/:documentId', element: <ProjectDocumentWorkspacePage /> },
      { path: 'projects/phases', element: <PhasePage /> },
      { path: 'projects/tasks', element: <TaskPage /> },
      { path: 'experiments', element: <Navigate to="/experiments/list" replace /> },
      { path: 'experiments/list', element: <ExperimentListPage /> },
      { path: 'experiments/upload', element: <ExperimentUploadPage /> },
      { path: 'experiments/:id', element: <ExperimentWorkspacePage /> },
    ],
  },
  { path: '/production-orders/:orderId/workspace', element: <ProductionWorkspacePage /> },
];
