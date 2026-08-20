import type { RouteObject } from 'react-router-dom';
import { Navigate } from 'react-router-dom';
import { lazy } from 'react';

import { BasicLayout } from '@/layouts';
import { AuthGate } from '@/components/auth/AuthGate';
import { NotFoundPage } from '@/pages/not-found';
import { AuthorizedHomeRedirect, PagePermissionGate } from '@/routes/route-guards';

const AssistantPage = lazy(async () => ({ default: (await import('@/pages/assistant')).AssistantPage }));
const DataImportJobPage = lazy(async () => ({ default: (await import('@/pages/data')).DataImportJobPage }));
const DataUploadPage = lazy(async () => ({ default: (await import('@/pages/data')).DataUploadPage }));
const DataViewPage = lazy(async () => ({ default: (await import('@/pages/data')).DataViewPage }));
const KnowledgeDocumentPage = lazy(async () => ({ default: (await import('@/pages/knowledge')).KnowledgeDocumentPage }));
const KnowledgeLibraryPage = lazy(async () => ({ default: (await import('@/pages/knowledge')).KnowledgeLibraryPage }));
const KnowledgeReviewPage = lazy(async () => ({ default: (await import('@/pages/knowledge')).KnowledgeReviewPage }));
const KnowledgeReviewQueuePage = lazy(async () => ({ default: (await import('@/pages/knowledge')).KnowledgeReviewQueuePage }));
const KnowledgeSearchPage = lazy(async () => ({ default: (await import('@/pages/knowledge')).KnowledgeSearchPage }));
const KnowledgeViewPage = lazy(async () => ({ default: (await import('@/pages/knowledge')).KnowledgeViewPage }));
const ProductionOrderListPage = lazy(async () => ({ default: (await import('@/pages/production-orders')).ProductionOrderListPage }));
const ProductionOrderUploadPage = lazy(async () => ({ default: (await import('@/pages/production-orders')).ProductionOrderUploadPage }));
const ProductionWorkspacePage = lazy(async () => ({ default: (await import('@/pages/production-orders')).ProductionWorkspacePage }));
const TemplateUploadPage = lazy(async () => ({ default: (await import('@/pages/template-upload')).TemplateUploadPage }));
const TemplateWorkspacePage = lazy(async () => ({ default: (await import('@/pages/template-workspace')).TemplateWorkspacePage }));
const TemplatesPage = lazy(async () => ({ default: (await import('@/pages/templates')).TemplatesPage }));
const TemplateImportRenderPage = lazy(async () => ({ default: (await import('@/pages/template-render/TemplateImportRenderPage')).TemplateImportRenderPage }));
const PartnerDetailPage = lazy(async () => ({ default: (await import('@/pages/partners')).PartnerDetailPage }));
const PartnerListPage = lazy(async () => ({ default: (await import('@/pages/partners')).PartnerListPage }));
const ProjectDetailPage = lazy(async () => ({ default: (await import('@/pages/project')).ProjectDetailPage }));
const ProjectListPage = lazy(async () => ({ default: (await import('@/pages/project')).ProjectListPage }));
const PhasePage = lazy(async () => ({ default: (await import('@/pages/project')).PhasePage }));
const TaskPage = lazy(async () => ({ default: (await import('@/pages/project')).TaskPage }));
const ProjectDocumentWorkspacePage = lazy(async () => ({ default: (await import('@/pages/project/ProjectDocumentWorkspacePage')).ProjectDocumentWorkspacePage }));
const ExperimentListPage = lazy(async () => ({ default: (await import('@/pages/experiments')).ExperimentListPage }));
const ExperimentUploadPage = lazy(async () => ({ default: (await import('@/pages/experiments')).ExperimentUploadPage }));
const ExperimentWorkspacePage = lazy(async () => ({ default: (await import('@/pages/experiments')).ExperimentWorkspacePage }));
const SpectrumChatPage = lazy(async () => ({ default: (await import('@/pages/spectrum')).SpectrumChatPage }));
const SpectrumUploadPage = lazy(async () => ({ default: (await import('@/pages/spectrum')).SpectrumUploadPage }));
const SpectrumViewPage = lazy(async () => ({ default: (await import('@/pages/spectrum')).SpectrumViewPage }));
const LoginPage = lazy(async () => ({ default: (await import('@/pages/auth/LoginPage')).LoginPage }));
const ChangePasswordPage = lazy(async () => ({ default: (await import('@/pages/auth/ChangePasswordPage')).ChangePasswordPage }));
const UserManagementPage = lazy(async () => ({ default: (await import('@/pages/iam/UserManagementPage')).UserManagementPage }));
const RolePermissionsPage = lazy(async () => ({ default: (await import('@/pages/iam/RolePermissionsPage')).RolePermissionsPage }));
const UserPermissionsPage = lazy(async () => ({ default: (await import('@/pages/iam/UserPermissionsPage')).UserPermissionsPage }));
const AuditLogsPage = lazy(async () => ({ default: (await import('@/pages/iam/AuditLogsPage')).AuditLogsPage }));

export const routeConfig: RouteObject[] = [
  { path: '/login', element: <LoginPage /> },
  { path: '/change-password', element: <AuthGate><ChangePasswordPage /></AuthGate> },
  {
    path: '/render/import/:importJobId',
    element: <AuthGate><PagePermissionGate permission="template.view"><TemplateImportRenderPage /></PagePermissionGate></AuthGate>,
  },
  {
    path: '/',
    element: <BasicLayout />,
    children: [
      {
        index: true,
        element: <AuthorizedHomeRedirect />,
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
      { path: 'spectrum', element: <Navigate to="/spectrum/upload" replace /> },
      { path: 'spectrum/upload', element: <SpectrumUploadPage /> },
      { path: 'spectrum/view', element: <SpectrumViewPage /> },
      { path: 'spectrum/chat', element: <SpectrumChatPage /> },
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
      { path: 'system/users', element: <UserManagementPage /> },
      { path: 'system/roles', element: <RolePermissionsPage /> },
      { path: 'system/user-permissions', element: <UserPermissionsPage /> },
      { path: 'system/audit-logs', element: <AuditLogsPage /> },
    ],
  },
  {
    path: '/production-orders/:orderId/workspace',
    element: <AuthGate><PagePermissionGate permission="production.view"><ProductionWorkspacePage /></PagePermissionGate></AuthGate>,
  },
];
