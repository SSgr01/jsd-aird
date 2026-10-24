import type { RouteObject } from 'react-router-dom';
import { Navigate } from 'react-router-dom';
import { routePage, registerRoutePreload, preloadRoute } from './route-preload';

import { AuthenticatedAssistantLayout } from '@/layouts/AuthenticatedAssistantLayout';
import { AuthGate } from '@/components/auth/AuthGate';
import { NotFoundPage } from '@/pages/not-found';
import { AuthorizedHomeRedirect, PageAnyPermissionGate, PagePermissionGate } from '@/routes/route-guards';

const BasicLayout = routePage(async () => ({ default: (await import('@/layouts/BasicLayout')).BasicLayout }));

const AssistantPage = routePage(async () => ({ default: (await import('@/pages/assistant/AssistantPage')).AssistantPage }));
const FormulaPredictionPage = routePage(async () => ({ default: (await import('@/pages/formula-research/FormulaPredictionPage')).FormulaPredictionPage }));
const ExperimentOptimizationPage = routePage(async () => ({ default: (await import('@/pages/formula-research/HistoricalOptimizationWorkbench')).HistoricalOptimizationWorkbench }));
const ModelCenterPage = routePage(async () => ({ default: (await import('@/pages/model-center/ModelCenterPage')).ModelCenterPage }));
const PerformancePredictionPage = routePage(async () => ({ default: (await import('@/pages/performance-prediction/PerformancePredictionPage')).PerformancePredictionPage }));
const DashboardPage = routePage(async () => ({ default: (await import('@/pages/dashboard/DashboardPage')).DashboardPage }));
const DataImportJobPage = routePage(async () => ({ default: (await import('@/pages/data/DataImportJobPage')).DataImportJobPage }));
const DataUploadPage = routePage(async () => ({ default: (await import('@/pages/data/DataUploadPage')).DataUploadPage }));
const DataViewPage = routePage(async () => ({ default: (await import('@/pages/data/DataViewPage')).DataViewPage }));
const QualityUploadPage = routePage(async () => ({ default: (await import('@/pages/quality/QualityUploadPage')).QualityUploadPage }));
const QualityDataPage = routePage(async () => ({ default: (await import('@/pages/quality/QualityDataPage')).QualityDataPage }));
const QualityRecordWorkspacePage = routePage(async () => ({ default: (await import('@/pages/quality/QualityRecordWorkspacePage')).QualityRecordWorkspacePage }));
const InventoryQueryPage = routePage(async () => ({ default: (await import('@/pages/inventory/InventoryPages')).InventoryQueryPage }));
const InventoryLedgerPage = routePage(async () => ({ default: (await import('@/pages/inventory/InventoryPages')).InventoryLedgerPage }));
const SampleRecordsPage = routePage(async () => ({ default: (await import('@/pages/inventory/InventoryPages')).SampleRecordsPage }));
const ShipmentRecordsPage = routePage(async () => ({ default: (await import('@/pages/inventory/InventoryPages')).ShipmentRecordsPage }));
const InventoryControlPage = routePage(async () => ({ default: (await import('@/pages/inventory/InventoryControlPage')).InventoryControlPage }));
const KnowledgeDocumentPage = routePage(async () => ({ default: (await import('@/pages/knowledge/KnowledgeDocumentPage')).KnowledgeDocumentPage }));
const KnowledgeUploadTaskDetailPage = routePage(async () => ({ default: (await import('@/pages/knowledge/KnowledgeUploadTaskDetailPage')).KnowledgeUploadTaskDetailPage }));
const KnowledgeLibraryPage = routePage(async () => ({ default: (await import('@/pages/knowledge/KnowledgeLibraryPage')).KnowledgeLibraryPage }));
const KnowledgeReviewPage = routePage(async () => ({ default: (await import('@/pages/knowledge/KnowledgeReviewPage')).KnowledgeReviewPage }));
const KnowledgeSearchPage = routePage(async () => ({ default: (await import('@/pages/knowledge/KnowledgeSearchPage')).KnowledgeSearchPage }));
const KnowledgeViewPage = routePage(async () => ({ default: (await import('@/pages/knowledge/KnowledgeViewPage')).KnowledgeViewPage }));
const ProductionOrderListPage = routePage(async () => ({ default: (await import('@/pages/production-orders/ProductionOrderListPage')).ProductionOrderListPage }));
const ProductionOrderUploadPage = routePage(async () => ({ default: (await import('@/pages/production-orders/ProductionOrderUploadPage')).ProductionOrderUploadPage }));
const ProductionUploadWorkspacePage = routePage(async () => ({ default: (await import('@/pages/production-orders/ProductionUploadWorkspacePage')).ProductionUploadWorkspacePage }));
const ProductionWorkspacePage = routePage(async () => ({ default: (await import('@/pages/production-orders/ProductionWorkspacePage')).ProductionWorkspacePage }));
const TemplateUploadPage = routePage(async () => ({ default: (await import('@/pages/template-upload/TemplateUploadPage')).TemplateUploadPage }));
const TemplateWorkspacePage = routePage(async () => ({ default: (await import('@/pages/template-workspace/TemplateWorkspacePage')).TemplateWorkspacePage }));
const TemplatesPage = routePage(async () => ({ default: (await import('@/pages/templates/TemplatesPage')).TemplatesPage }));
const TemplateImportRenderPage = routePage(async () => ({ default: (await import('@/pages/template-render/TemplateImportRenderPage')).TemplateImportRenderPage }));
const PartnerDetailPage = routePage(async () => ({ default: (await import('@/pages/partners/PartnerDetailPage')).PartnerDetailPage }));
const PartnerListPage = routePage(async () => ({ default: (await import('@/pages/partners/PartnerListPage')).PartnerListPage }));
const ProjectDetailPage = routePage(async () => ({ default: (await import('@/pages/project/ProjectDetailPage')).ProjectDetailPage }));
const ProjectListPage = routePage(async () => ({ default: (await import('@/pages/project/ProjectListPage')).ProjectListPage }));
const PhasePage = routePage(async () => ({ default: (await import('@/pages/project/PhasePage')).PhasePage }));
const TaskPage = routePage(async () => ({ default: (await import('@/pages/project/TaskPage')).default }));
const ProjectDocumentWorkspacePage = routePage(async () => ({ default: (await import('@/pages/project/ProjectDocumentWorkspacePage')).ProjectDocumentWorkspacePage }));
const ExperimentListPage = routePage(async () => ({ default: (await import('@/pages/experiments/ExperimentListPage')).ExperimentListPage }));
const ExperimentUploadPage = routePage(async () => ({ default: (await import('@/pages/experiments/ExperimentUploadPage')).ExperimentUploadPage }));
const NewExperimentPage = routePage(async () => ({ default: (await import('@/pages/experiments/NewExperimentPage')).NewExperimentPage }));
const ExperimentImportWorkbenchPage = routePage(async () => ({ default: (await import('@/pages/experiments/ExperimentImportWorkbenchPage')).ExperimentImportWorkbenchPage }));
const ExperimentWorkspacePage = routePage(async () => ({ default: (await import('@/pages/experiments/ExperimentWorkspacePage')).ExperimentWorkspacePage }));
const DataRecognitionPage = routePage(async () => ({ default: (await import('@/pages/recognition/SourceRecognitionPage')).DataRecognitionPage }));
const ResearchTestListPage = routePage(async () => ({ default: (await import('@/pages/research-test/ResearchTestListPage')).ResearchTestListPage }));
const ResearchTestUploadPage = routePage(async () => ({ default: (await import('@/pages/research-test/ResearchTestUploadPage')).ResearchTestUploadPage }));
const ResearchTestWorkspacePage = routePage(async () => ({ default: (await import('@/pages/research-test/ResearchTestWorkspacePage')).ResearchTestWorkspacePage }));
const SpectrumChatPage = routePage(async () => ({ default: (await import('@/pages/spectrum/SpectrumChatPage')).SpectrumChatPage }));
const SpectrumUploadPage = routePage(async () => ({ default: (await import('@/pages/spectrum/SpectrumUploadPage')).SpectrumUploadPage }));
const SpectrumViewPage = routePage(async () => ({ default: (await import('@/pages/spectrum/SpectrumViewPage')).SpectrumViewPage }));
const LoginPage = routePage(async () => ({ default: (await import('@/pages/auth/LoginPage')).LoginPage }));
const ChangePasswordPage = routePage(async () => ({ default: (await import('@/pages/auth/ChangePasswordPage')).ChangePasswordPage }));
const UserManagementPage = routePage(async () => ({ default: (await import('@/pages/iam/UserManagementPage')).UserManagementPage }));
const RolePermissionsPage = routePage(async () => ({ default: (await import('@/pages/iam/RolePermissionsPage')).RolePermissionsPage }));
const UserPermissionsPage = routePage(async () => ({ default: (await import('@/pages/iam/UserPermissionsPage')).UserPermissionsPage }));
const AuditLogsPage = routePage(async () => ({ default: (await import('@/pages/iam/AuditLogsPage')).AuditLogsPage }));
const StandardDictionaryPage = routePage(async () => ({ default: (await import('@/pages/iam/StandardDictionaryPage')).StandardDictionaryPage }));

const applicationRoutes: RouteObject[] = [
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
      { path: 'knowledge/upload-tasks/:id', element: <PagePermissionGate permission="knowledge.upload"><KnowledgeUploadTaskDetailPage /></PagePermissionGate> },
      { path: 'knowledge/view', element: <KnowledgeViewPage /> },
      { path: 'knowledge/search', element: <KnowledgeSearchPage /> },
      { path: 'knowledge/review/:documentId/:versionId', element: <PagePermissionGate permission="knowledge.review"><KnowledgeReviewPage /></PagePermissionGate> },
      { path: 'knowledge/documents/:id', element: <KnowledgeDocumentPage /> },
      { path: 'assistant', element: <AssistantPage /> },
      { path: 'assistant/formula-prediction', element: <PagePermissionGate permission="ai.formula.predict"><FormulaPredictionPage /></PagePermissionGate> },
      { path: 'assistant/performance-prediction', element: <PagePermissionGate permission="ai.performance.predict"><PerformancePredictionPage /></PagePermissionGate> },
      { path: 'assistant/experiment-optimization', element: <PagePermissionGate permission="ai.experiment.optimize"><ExperimentOptimizationPage /></PagePermissionGate> },
      { path: 'assistant/model-center', element: <PageAnyPermissionGate permissions={['ai.model.read', 'ai.modeling.read']}><ModelCenterPage /></PageAnyPermissionGate> },
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
      { path: 'experiments/template-imports/:id', element: <ExperimentImportWorkbenchPage /> },
      { path: 'data/recognition-jobs/:id', element: <PagePermissionGate permission="data.view"><DataRecognitionPage /></PagePermissionGate> },
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
      { path: 'experiments/new', element: <NewExperimentPage /> },
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

export const routeConfig: RouteObject[] = [
  ...applicationRoutes.filter((route) => route.path === '/login'),
  { element: <AuthenticatedAssistantLayout />, children: applicationRoutes.filter((route) => route.path !== '/login') },
];

registerRoutePreload('/login', LoginPage.preload);
registerRoutePreload('/change-password', ChangePasswordPage.preload);
registerRoutePreload('/render/import/:importJobId', TemplateImportRenderPage.preload);
registerRoutePreload('/', BasicLayout.preload);
registerRoutePreload('/dashboard', DashboardPage.preload);
registerRoutePreload('/knowledge/library', KnowledgeLibraryPage.preload);
registerRoutePreload('/knowledge/upload-tasks/:id', KnowledgeUploadTaskDetailPage.preload);
registerRoutePreload('/knowledge/view', KnowledgeViewPage.preload);
registerRoutePreload('/knowledge/search', KnowledgeSearchPage.preload);
registerRoutePreload('/knowledge/review/:documentId/:versionId', KnowledgeReviewPage.preload);
registerRoutePreload('/knowledge/documents/:id', KnowledgeDocumentPage.preload);
registerRoutePreload('/assistant', AssistantPage.preload);
registerRoutePreload('/assistant/formula-prediction', FormulaPredictionPage.preload);
registerRoutePreload('/assistant/performance-prediction', PerformancePredictionPage.preload);
registerRoutePreload('/assistant/experiment-optimization', ExperimentOptimizationPage.preload);
registerRoutePreload('/assistant/model-center', ModelCenterPage.preload);
registerRoutePreload('/templates/upload', TemplateUploadPage.preload);
registerRoutePreload('/templates/library', TemplatesPage.preload);
registerRoutePreload('/templates/:versionId/workspace', TemplateWorkspacePage.preload);
registerRoutePreload('/production-orders/upload', ProductionOrderUploadPage.preload);
registerRoutePreload('/production-orders/list', ProductionOrderListPage.preload);
registerRoutePreload('/production-orders/uploads/:uploadId/workspace', ProductionUploadWorkspacePage.preload);
registerRoutePreload('/data/upload', DataUploadPage.preload);
registerRoutePreload('/data/import-jobs/:id', DataImportJobPage.preload);
registerRoutePreload('/experiments/template-imports/:id', ExperimentImportWorkbenchPage.preload);
registerRoutePreload('/data/recognition-jobs/:id', DataRecognitionPage.preload);
registerRoutePreload('/data/view', DataViewPage.preload);
registerRoutePreload('/spectrum/upload', SpectrumUploadPage.preload);
registerRoutePreload('/spectrum/view', SpectrumViewPage.preload);
registerRoutePreload('/spectrum/chat', SpectrumChatPage.preload);
registerRoutePreload('/quality/upload', QualityUploadPage.preload);
registerRoutePreload('/quality/view', QualityDataPage.preload);
registerRoutePreload('/quality/records/:id', QualityRecordWorkspacePage.preload);
registerRoutePreload('/inventory/query', InventoryQueryPage.preload);
registerRoutePreload('/inventory/rnd', InventoryLedgerPage.preload);
registerRoutePreload('/inventory/production', InventoryLedgerPage.preload);
registerRoutePreload('/inventory/samples', SampleRecordsPage.preload);
registerRoutePreload('/inventory/shipments', ShipmentRecordsPage.preload);
registerRoutePreload('/inventory/products', InventoryControlPage.preload);
registerRoutePreload('/inventory/warnings', InventoryControlPage.preload);
registerRoutePreload('/inventory/batches', InventoryControlPage.preload);
registerRoutePreload('/inventory/expiry', InventoryControlPage.preload);
registerRoutePreload('/inventory/transfer', InventoryControlPage.preload);
registerRoutePreload('/inventory/production-links', InventoryControlPage.preload);
registerRoutePreload('/inventory/eln-links', InventoryControlPage.preload);
registerRoutePreload('/inventory/settings', InventoryControlPage.preload);
registerRoutePreload('/inventory/permissions', InventoryControlPage.preload);
registerRoutePreload('/inventory/audit', InventoryControlPage.preload);
registerRoutePreload('/partners', PartnerListPage.preload);
registerRoutePreload('/partners/:id', PartnerDetailPage.preload);
registerRoutePreload('/projects/list', ProjectListPage.preload);
registerRoutePreload('/projects/:id', ProjectDetailPage.preload);
registerRoutePreload('/projects/:id/documents/:documentId', ProjectDocumentWorkspacePage.preload);
registerRoutePreload('/projects/phases', PhasePage.preload);
registerRoutePreload('/projects/tasks', TaskPage.preload);
registerRoutePreload('/experiments/list', ExperimentListPage.preload);
registerRoutePreload('/experiments/new', NewExperimentPage.preload);
registerRoutePreload('/experiments/upload', ExperimentUploadPage.preload);
registerRoutePreload('/experiments/:id', ExperimentWorkspacePage.preload);
registerRoutePreload('/research-test/upload', ResearchTestUploadPage.preload);
registerRoutePreload('/research-test/standard-upload', ResearchTestUploadPage.preload);
registerRoutePreload('/research-test/reports', ResearchTestListPage.preload);
registerRoutePreload('/research-test/reports/:id', ResearchTestWorkspacePage.preload);
registerRoutePreload('/research-test/standards', ResearchTestListPage.preload);
registerRoutePreload('/research-test/standards/:id', ResearchTestWorkspacePage.preload);
registerRoutePreload('/system/users', UserManagementPage.preload);
registerRoutePreload('/system/roles', RolePermissionsPage.preload);
registerRoutePreload('/system/user-permissions', UserPermissionsPage.preload);
registerRoutePreload('/system/audit-logs', AuditLogsPage.preload);
registerRoutePreload('/system/dictionary', StandardDictionaryPage.preload);
registerRoutePreload('/system/parameters', InventoryControlPage.preload);
registerRoutePreload('/production-orders/:orderId/workspace', ProductionWorkspacePage.preload);
// Begin the current route chunk while AuthGate checks the session.
if (window.location.pathname !== '/login' && window.location.pathname !== '/change-password' && !window.location.pathname.startsWith('/render/') && !/^\/production-orders\/[^/]+\/workspace$/.test(window.location.pathname)) void BasicLayout.preload().catch(() => undefined);
void preloadRoute(window.location.pathname).catch(() => undefined);
