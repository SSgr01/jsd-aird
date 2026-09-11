import type { RouteObject } from 'react-router-dom';
import { Navigate } from 'react-router-dom';
import { lazy } from 'react';

import { BasicLayout } from '@/layouts';
import { AuthGate } from '@/components/auth/AuthGate';
import { NotFoundPage } from '@/pages/not-found';
import { AuthorizedHomeRedirect, PagePermissionGate } from '@/routes/route-guards';

const AssistantPage = lazy(async () => ({ default: (await import('@/pages/assistant')).AssistantPage }));
const FormulaPredictionPage = lazy(async () => ({ default: (await import('@/pages/formula-research')).FormulaPredictionPage }));
const ExperimentOptimizationPage = lazy(async () => ({ default: (await import('@/pages/formula-research')).ExperimentOptimizationPage }));
const DashboardPage = lazy(async () => ({ default: (await import('@/pages/dashboard')).DashboardPage }));
const DataImportJobPage = lazy(async () => ({ default: (await import('@/pages/data')).DataImportJobPage }));
const DataUploadPage = lazy(async () => ({ default: (await import('@/pages/data')).DataUploadPage }));
const DataViewPage = lazy(async () => ({ default: (await import('@/pages/data')).DataViewPage }));
const QualityUploadPage = lazy(async () => ({ default: (await import('@/pages/quality/QualityUploadPage')).QualityUploadPage }));
const QualityDataPage = lazy(async () => ({ default: (await import('@/pages/quality/QualityDataPage')).QualityDataPage }));
const QualityRecordWorkspacePage = lazy(async () => ({ default: (await import('@/pages/quality/QualityRecordWorkspacePage')).QualityRecordWorkspacePage }));
const InventoryQueryPage = lazy(async () => ({ default: (await import('@/pages/inventory')).InventoryQueryPage }));
const InventoryLedgerPage = lazy(async () => ({ default: (await import('@/pages/inventory')).InventoryLedgerPage }));
const SampleRecordsPage = lazy(async () => ({ default: (await import('@/pages/inventory')).SampleRecordsPage }));
const ShipmentRecordsPage = lazy(async () => ({ default: (await import('@/pages/inventory')).ShipmentRecordsPage }));
const InventoryControlPage = lazy(async () => ({ default: (await import('@/pages/inventory')).InventoryControlPage }));
const KnowledgeDocumentPage = lazy(async () => ({ default: (await import('@/pages/knowledge')).KnowledgeDocumentPage }));
const KnowledgeLibraryPage = lazy(async () => ({ default: (await import('@/pages/knowledge')).KnowledgeLibraryPage }));
const KnowledgeReviewPage = lazy(async () => ({ default: (await import('@/pages/knowledge')).KnowledgeReviewPage }));
const KnowledgeReviewQueuePage = lazy(async () => ({ default: (await import('@/pages/knowledge')).KnowledgeReviewQueuePage }));
const KnowledgeSearchPage = lazy(async () => ({ default: (await import('@/pages/knowledge')).KnowledgeSearchPage }));
const KnowledgeViewPage = lazy(async () => ({ default: (await import('@/pages/knowledge')).KnowledgeViewPage }));
const ProductionOrderListPage = lazy(async () => ({ default: (await import('@/pages/production-orders')).ProductionOrderListPage }));
const ProductionOrderUploadPage = lazy(async () => ({ default: (await import('@/pages/production-orders')).ProductionOrderUploadPage }));
const ProductionUploadWorkspacePage = lazy(async () => ({ default: (await import('@/pages/production-orders')).ProductionUploadWorkspacePage }));
const ProductionWorkspacePage = lazy(async () => ({ default: (await import('@/pages/production-orders/ProductionWorkspacePage')).ProductionWorkspacePage }));
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
const ResearchTestListPage = lazy(async () => ({ default: (await import('@/pages/research-test')).ResearchTestListPage }));
const ResearchTestUploadPage = lazy(async () => ({ default: (await import('@/pages/research-test')).ResearchTestUploadPage }));
const ResearchTestWorkspacePage = lazy(async () => ({ default: (await import('@/pages/research-test')).ResearchTestWorkspacePage }));
const SpectrumChatPage = lazy(async () => ({ default: (await import('@/pages/spectrum')).SpectrumChatPage }));
const SpectrumUploadPage = lazy(async () => ({ default: (await import('@/pages/spectrum')).SpectrumUploadPage }));
const SpectrumViewPage = lazy(async () => ({ default: (await import('@/pages/spectrum')).SpectrumViewPage }));
const LoginPage = lazy(async () => ({ default: (await import('@/pages/auth/LoginPage')).LoginPage }));
const ChangePasswordPage = lazy(async () => ({ default: (await import('@/pages/auth/ChangePasswordPage')).ChangePasswordPage }));
const UserManagementPage = lazy(async () => ({ default: (await import('@/pages/iam/UserManagementPage')).UserManagementPage }));
const RolePermissionsPage = lazy(async () => ({ default: (await import('@/pages/iam/RolePermissionsPage')).RolePermissionsPage }));
const UserPermissionsPage = lazy(async () => ({ default: (await import('@/pages/iam/UserPermissionsPage')).UserPermissionsPage }));
const AuditLogsPage = lazy(async () => ({ default: (await import('@/pages/iam/AuditLogsPage')).AuditLogsPage }));
const StandardDictionaryPage = lazy(async () => ({ default: (await import('@/pages/iam/StandardDictionaryPage')).StandardDictionaryPage }));

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
      { path: 'dashboard', element: <DashboardPage /> },
      { path: 'knowledge/library', element: <PagePermissionGate permission="knowledge.upload"><KnowledgeLibraryPage /></PagePermissionGate> },
      { path: 'knowledge/view', element: <KnowledgeViewPage /> },
      { path: 'knowledge/search', element: <KnowledgeSearchPage /> },
      { path: 'knowledge/review', element: <PagePermissionGate permission="knowledge.review"><KnowledgeReviewQueuePage /></PagePermissionGate> },
      { path: 'knowledge/review/:documentId/:versionId', element: <PagePermissionGate permission="knowledge.review"><KnowledgeReviewPage /></PagePermissionGate> },
      { path: 'knowledge/documents/:id', element: <KnowledgeDocumentPage /> },
      { path: 'assistant', element: <AssistantPage /> },
      { path: 'assistant/formula-prediction', element: <PagePermissionGate permission="ai.use"><FormulaPredictionPage /></PagePermissionGate> },
      { path: 'assistant/experiment-optimization', element: <PagePermissionGate permission="ai.use"><ExperimentOptimizationPage /></PagePermissionGate> },
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
        path: 'production-orders/uploads/:uploadId/workspace',
        element: <ProductionUploadWorkspacePage />,
      },
      { path: 'data', element: <Navigate to="/data/upload" replace /> },
      { path: 'data/upload', element: <PagePermissionGate permission="data.create"><DataUploadPage /></PagePermissionGate> },
      { path: 'data/import-jobs/:id', element: <DataImportJobPage /> },
      { path: 'data/view', element: <DataViewPage /> },
      { path: 'spectrum', element: <Navigate to="/spectrum/upload" replace /> },
      { path: 'spectrum/upload', element: <SpectrumUploadPage /> },
      { path: 'spectrum/view', element: <SpectrumViewPage /> },
      { path: 'spectrum/chat', element: <SpectrumChatPage /> },
      { path: 'quality', element: <Navigate to="/quality/upload" replace /> },
      { path: 'quality/upload', element: <PagePermissionGate permission="quality.upload"><QualityUploadPage /></PagePermissionGate> },
      { path: 'quality/view', element: <PagePermissionGate permission="quality.view"><QualityDataPage /></PagePermissionGate> },
      { path: 'quality/records/:id', element: <PagePermissionGate permission="quality.view"><QualityRecordWorkspacePage /></PagePermissionGate> },
      { path: 'inventory', element: <Navigate to="/inventory/query" replace /> },
      { path: 'inventory/query', element: <InventoryQueryPage /> },
      { path: 'inventory/rnd', element: <InventoryLedgerPage key="inventory-rnd" scope="RND" /> },
      {
        path: 'inventory/production',
        element: <InventoryLedgerPage key="inventory-production" scope="PRODUCTION" />,
      },
      { path: 'inventory/samples', element: <SampleRecordsPage /> },
      { path: 'inventory/shipments', element: <ShipmentRecordsPage /> },
      { path: 'inventory/products', element: <InventoryControlPage mode="products" /> },
      { path: 'inventory/warnings', element: <InventoryControlPage mode="warnings" /> },
      { path: 'inventory/batches', element: <InventoryControlPage mode="batches" /> },
      { path: 'inventory/expiry', element: <InventoryControlPage mode="expiry" /> },
      { path: 'inventory/transfer', element: <InventoryControlPage mode="transfer" /> },
      { path: 'inventory/production-links', element: <InventoryControlPage mode="production-link" /> },
      { path: 'inventory/eln-links', element: <InventoryControlPage mode="eln-link" /> },
      { path: 'inventory/settings', element: <InventoryControlPage mode="settings" /> },
      { path: 'inventory/permissions', element: <InventoryControlPage mode="permissions" /> },
      { path: 'inventory/audit', element: <InventoryControlPage mode="audit" /> },
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
      { path: 'research-test', element: <Navigate to="/research-test/reports" replace /> },
      { path: 'research-test/upload', element: <PagePermissionGate permission="research-test.report.create"><ResearchTestUploadPage /></PagePermissionGate> },
      { path: 'research-test/standard-upload', element: <PagePermissionGate permission="research-test.standard.create"><ResearchTestUploadPage type="STANDARD" /></PagePermissionGate> },
      { path: 'research-test/reports', element: <PagePermissionGate permission="research-test.report.view"><ResearchTestListPage type="REPORT" /></PagePermissionGate> },
      { path: 'research-test/reports/:id', element: <PagePermissionGate permission="research-test.report.view"><ResearchTestWorkspacePage type="REPORT" /></PagePermissionGate> },
      { path: 'research-test/standards', element: <PagePermissionGate permission="research-test.standard.view"><ResearchTestListPage type="STANDARD" /></PagePermissionGate> },
      { path: 'research-test/standards/:id', element: <PagePermissionGate permission="research-test.standard.view"><ResearchTestWorkspacePage type="STANDARD" /></PagePermissionGate> },
      { path: 'system/users', element: <UserManagementPage /> },
      { path: 'system/roles', element: <RolePermissionsPage /> },
      { path: 'system/user-permissions', element: <UserPermissionsPage /> },
      { path: 'system/audit-logs', element: <AuditLogsPage /> },
      { path: 'system/dictionary', element: <PagePermissionGate permission="system.dictionary.manage"><StandardDictionaryPage /></PagePermissionGate> },
      { path: 'system/parameters', element: <PagePermissionGate permission="system.parameter.manage"><InventoryControlPage mode="settings" /></PagePermissionGate> },
    ],
  },
  {
    path: '/production-orders/:orderId/workspace',
    element: <AuthGate><PagePermissionGate permission="production.view"><ProductionWorkspacePage /></PagePermissionGate></AuthGate>,
  },
];
