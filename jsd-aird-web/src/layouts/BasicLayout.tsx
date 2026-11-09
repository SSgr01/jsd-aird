import {
  DatabaseOutlined,
  ExperimentOutlined,
  EyeOutlined,
  FileSearchOutlined,
  FileTextOutlined,
  FolderOpenOutlined,
  InboxOutlined,
  OrderedListOutlined,
  RobotOutlined,
  AuditOutlined,
  UploadOutlined,
  BankOutlined,
  CheckSquareOutlined,
  SafetyCertificateOutlined,
  PartitionOutlined,
  ProjectOutlined,
  UnorderedListOutlined,
  LineChartOutlined,
  HomeOutlined,
  LockOutlined,
  LogoutOutlined,
  AppstoreOutlined,
  SettingOutlined,
  UserOutlined,
  SearchOutlined,
  PlusOutlined,
  AimOutlined,
} from '@ant-design/icons';
import { ProLayout, type MenuDataItem } from '@ant-design/pro-components';
import { App, Avatar, Button, Dropdown, Input, Modal, Result, Tooltip, Typography, type InputRef, type MenuProps } from 'antd';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useEffect, useMemo, useRef, useState } from 'react';

import { ContentBoundary } from '@/components/loading/ContentBoundary';
import { PrefetchLink } from '@/components/loading/PrefetchLink';
import { RouteScrollRestoration } from '@/components/loading/RouteScrollRestoration';
import { appEnv } from '@/app/config/env';
import { AuthGate } from '@/components/auth/AuthGate';
import { useAppStore } from '@/stores/app-store';
import { useAuthStore } from '@/stores/auth-store';
import { ChangePasswordForm, type PasswordValues } from '@/pages/auth/ChangePasswordForm';
import { HttpError } from '@/services/http/errors';
import { canViewPathWithMenus, filterMenuRoute, firstAccessiblePath, firstAccessibleSystemPath, requiredPermissionForPath } from '@/routes/route-permissions';

const route = {
  path: '/',
  routes: [
    {
      path: '/dashboard',
      name: '工作台',
      icon: <HomeOutlined />,
    },
    {
      path: '/partners',
      name: '客户管理',
      icon: <BankOutlined />,
    },
    {
      path: '/templates',
      name: '模板中心',
      icon: <FileTextOutlined />,
      routes: [
        { path: '/templates/upload', name: '模板上传', icon: <UploadOutlined /> },
        { path: '/templates/library', name: '模板查看', icon: <EyeOutlined /> },
      ],
    },
    {
      path: '/projects',
      name: '项目管理',
      icon: <ProjectOutlined />,
      routes: [
        { path: '/projects/list', name: '项目列表', icon: <UnorderedListOutlined /> },
        { path: '/projects/phases', name: '阶段', icon: <PartitionOutlined /> },
        { path: '/projects/tasks', name: '任务', icon: <CheckSquareOutlined /> },
      ],
    },
    {
      path: '/experiments-root',
      name: '电子实验记录本',
      icon: <ExperimentOutlined />,
      routes: [
        { path: '/experiments/new', name: '在线新建', icon: <PlusOutlined /> },
        { path: '/experiments/upload', name: '文件导入', icon: <UploadOutlined /> },
        { path: '/experiments/list', name: '实验查看', icon: <EyeOutlined /> },
      ],
    },
    {
      path: '/research-test-root',
      name: '研发测试中心',
      icon: <SafetyCertificateOutlined />,
      routes: [
        { path: '/research-test/upload', name: '报告上传', icon: <UploadOutlined /> },
        { path: '/research-test/reports', name: '报告查看', icon: <EyeOutlined /> },
        { path: '/research-test/standard-upload', name: '测试标准上传', icon: <UploadOutlined /> },
        { path: '/research-test/standards', name: '测试标准方法', icon: <FileTextOutlined /> },
      ],
    },
    {
      path: '/quality',
      name: '品管部数据',
      icon: <SafetyCertificateOutlined />,
      routes: [
        { path: '/quality/upload', name: '品管部数据上传', icon: <UploadOutlined /> },
        { path: '/quality/view', name: '品管部数据查看', icon: <EyeOutlined /> },
      ],
    },
    {
      path: '/spectrum',
      name: 'AI图谱中心',
      icon: <LineChartOutlined />,
      routes: [
        { path: '/spectrum/upload', name: '图谱上传', icon: <UploadOutlined /> },
        { path: '/spectrum/view', name: '图谱查看', icon: <EyeOutlined /> },
        { path: '/spectrum/chat', name: 'AI图谱分析', icon: <RobotOutlined /> },
      ],
    },
    {
      path: '/production-orders',
      name: '生产单管理',
      icon: <OrderedListOutlined />,
      routes: [
        { path: '/production-orders/upload', name: '生产单上传', icon: <InboxOutlined /> },
        { path: '/production-orders/list', name: '生产单查看', icon: <EyeOutlined /> },
      ],
    },
    {
      path: '/quotations-root',
      name: '报价记录',
      icon: <FileTextOutlined />,
      routes: [
        { path: '/quotations/upload', name: '报价上传', icon: <UploadOutlined /> },
        { path: '/quotations/list', name: '报价查看', icon: <EyeOutlined /> },
      ],
    },
    {
      path: '/ai-assistant',
      name: 'AI研发助手',
      icon: <RobotOutlined />,
      routes: [
        { path: '/knowledge/search', name: '文件检索', icon: <FileSearchOutlined /> },
        { path: '/assistant', name: 'AI问答', icon: <RobotOutlined /> },
        { path: '/assistant/formula-prediction', name: 'AI配方预测', icon: <AimOutlined /> },
        { path: '/assistant/performance-prediction', name: '性能预测', icon: <LineChartOutlined /> },
        { path: '/assistant/experiment-optimization', name: 'AI实验优化', icon: <ExperimentOutlined /> },
        { path: '/assistant/model-center', name: '模型中心', icon: <LineChartOutlined /> },
      ],
    },
    {
      path: '/knowledge',
      name: '研发知识库',
      icon: <FolderOpenOutlined />,
      routes: [
        { path: '/knowledge/library', name: '资料上传', icon: <FolderOpenOutlined /> },
        { path: '/knowledge/view', name: '知识库查看', icon: <EyeOutlined /> },
      ],
    },
    {
      path: '/data',
      name: '数据中心',
      icon: <DatabaseOutlined />,
      routes: [
        { path: '/data/upload', name: '数据上传', icon: <UploadOutlined /> },
        { path: '/data/view', name: '数据查看', icon: <EyeOutlined /> },
      ],
    },
    {
      path: '/inventory',
      name: '库存管理',
      icon: <AppstoreOutlined />,
      routes: [
        { path: '/inventory/query', name: '库存查询', icon: <EyeOutlined /> },
        { path: '/inventory/rnd', name: '研发库存表', icon: <ExperimentOutlined /> },
        { path: '/inventory/production', name: '生产库存表', icon: <OrderedListOutlined /> },
        { path: '/inventory/samples', name: '发样记录表', icon: <InboxOutlined /> },
        { path: '/inventory/shipments', name: '出货记录表', icon: <UnorderedListOutlined /> },
      ],
    },
    {
      path: '/system',
      name: '系统设置',
      icon: <SettingOutlined />,
      routes: [
        { path: '/system/users', name: '用户管理', icon: <UserOutlined /> },
        { path: '/system/user-permissions', name: '用户权限配置', icon: <SettingOutlined /> },
        { path: '/system/dictionary', name: '基础字典', icon: <FileTextOutlined /> },
        { path: '/system/audit-logs', name: '操作日志', icon: <AuditOutlined /> },
        { path: '/system/parameters', name: '系统参数', icon: <SettingOutlined /> },
      ],
    },
  ],
};

function UserMenu() {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [submittingPassword, setSubmittingPassword] = useState(false);
  const user = useAuthStore((state) => state.user);
  const logout = useAuthStore((state) => state.logout);
  const changePassword = useAuthStore((state) => state.changePassword);
  const submitPassword = async (values: PasswordValues) => {
    setSubmittingPassword(true);
    try {
      await changePassword(values.currentPassword, values.newPassword);
      setPasswordOpen(false);
      message.success('密码修改成功');
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '密码修改失败');
    } finally {
      setSubmittingPassword(false);
    }
  };
  if (!user) return null;
  const initial = (user.displayName || user.username || '用').slice(0, 1);
  const systemSettingsPath = firstAccessibleSystemPath(user.permissions ?? [], user.menuKeys ?? []);
  const items: MenuProps['items'] = [
    { key: 'workbench', icon: <HomeOutlined />, label: '工作台' },
    ...(systemSettingsPath ? [{ key: 'system-settings', icon: <SettingOutlined />, label: '系统设置' }] : []),
    { key: 'change-password', icon: <LockOutlined />, label: '修改密码' },
    { type: 'divider' },
    { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', danger: true },
  ];
  return <><Dropdown
    trigger={['click']}
    menu={{ items, onClick: ({ key }: { key: string }) => {
      if (key === 'workbench') navigate('/dashboard');
      if (key === 'system-settings' && systemSettingsPath) navigate(systemSettingsPath);
      if (key === 'change-password') setPasswordOpen(true);
      if (key === 'logout') void logout();
    } }}
    popupRender={(menu) => <div className="app-user-dropdown">
      <div className="app-user-profile"><Typography.Text strong>{user.displayName || user.username}</Typography.Text><Typography.Text type="secondary">{user.roleName || '未分配角色'}</Typography.Text>{user.email && <Typography.Text type="secondary">{user.email}</Typography.Text>}</div>
      {menu}
    </div>}
  >
    <button type="button" className="app-user-trigger" aria-label="打开用户菜单"><Avatar size={34}>{initial}</Avatar></button>
  </Dropdown>
    <Modal title="修改密码" open={passwordOpen} onCancel={() => { if (!submittingPassword) setPasswordOpen(false); }} footer={null} destroyOnHidden>
      <ChangePasswordForm onSubmit={submitPassword} submitting={submittingPassword} onCancel={() => setPasswordOpen(false)} />
    </Modal></>;
}

function AppHeaderTools({ permissions, menuKeys }: { permissions: string[]; menuKeys: string[] }) {
  const navigate = useNavigate();
  const searchRef = useRef<InputRef>(null);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        searchRef.current?.focus();
        searchRef.current?.select();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  const search = (value: string) => {
    const query = value.trim();
    if (query) navigate(`/knowledge/search?q=${encodeURIComponent(query)}`);
  };

  return (
    <div className="app-header-tools">
      {canViewPathWithMenus('/knowledge/search', permissions, menuKeys) && <Input
        ref={searchRef}
        className="app-command-search"
        aria-label="全局搜索"
        aria-keyshortcuts="Control+K Meta+K"
        prefix={<SearchOutlined />}
        placeholder="搜索知识库、文档、问题…"
        suffix={<kbd>Ctrl K</kbd>}
        onPressEnter={(event) => search(event.currentTarget.value)}
      />}
      {appEnv.version && <span className="app-version" title="系统版本" aria-label={`系统版本 ${appEnv.version}`}>{appEnv.version}</span>}
      <UserMenu />
    </div>
  );
}

function AccessDenied({ permissions, menuKeys }: { permissions: string[]; menuKeys?: string[] }) {
  const navigate = useNavigate();
  const home = firstAccessiblePath(permissions, menuKeys);
  return <Result status="403" title="暂无权限访问此页面" subTitle="请联系系统管理员开通查看权限" extra={<Button type="primary" onClick={() => navigate(home || '/login', { replace: true })}>{home ? '返回工作台' : '返回登录'}</Button>} />;
}

export function BasicLayout() {
  const location = useLocation();
  const isTemplateWorkspace = /^\/templates\/[^/]+\/workspace$/.test(location.pathname);
  const isExperimentPage = ['/experiments/new', '/experiments/upload', '/experiments/list'].includes(location.pathname);
  const isExperimentWorkspace = /^\/experiments\/template-imports\/[^/]+$/.test(location.pathname)
    || /^\/experiments\/(?!imports\/)[^/]+$/.test(location.pathname) && !isExperimentPage;
  const isDataWorkspace = /^\/data\/(?:import-jobs|assets)\/[^/]+$/.test(location.pathname);
  const isQuotationWorkspace = /^\/quotations\/[^/]+$/.test(location.pathname)
    && !['/quotations/list', '/quotations/upload'].includes(location.pathname);
  const isProjectDocumentWorkspace = /^\/projects\/[^/]+\/documents\/[^/]+$/.test(location.pathname);
  const isKnowledgeWorkbench = location.pathname.startsWith('/knowledge/review/') || location.pathname.startsWith('/knowledge/documents/');
  const isPermissionManagement = location.pathname.startsWith('/system/user-permissions');
  const isWorkspace = isTemplateWorkspace || isExperimentWorkspace || isDataWorkspace || isQuotationWorkspace;
  const collapsed = useAppStore((state) => state.sidebarCollapsed);
  const setCollapsed = useAppStore((state) => state.setSidebarCollapsed);
  const isAssistant = location.pathname === '/assistant';
  const [assistantNavExpanded, setAssistantNavExpanded] = useState(false);
  const layoutCollapsed = isAssistant ? !assistantNavExpanded : collapsed;
  const user = useAuthStore((state) => state.user);
  const permissions = user?.permissions ?? [];
  const menuKeys = user?.menuKeys;
  const visibleRoute = useMemo(() => filterMenuRoute(route, permissions, menuKeys) ?? { path: '/', routes: [] }, [permissions, menuKeys]);
  const requiredPermission = requiredPermissionForPath(location.pathname);

  return (
    <AuthGate>
      <div className={`app-shell${layoutCollapsed ? ' app-shell-sidebar-collapsed' : ''}${isAssistant ? ' app-shell-assistant' : ''}${isPermissionManagement ? ' permission-management-shell' : ''}`}>
      <div className="app-topbar" aria-label="全局工具区">
        <AppHeaderTools permissions={permissions} menuKeys={menuKeys ?? []} />
      </div>
      <ProLayout
        title={appEnv.title}
        logo={<AppstoreOutlined />}
        route={visibleRoute}
        location={{ pathname: location.pathname }}
        selectedKeys={[location.pathname.startsWith('/assistant/model-center') ? '/assistant/model-center' : location.pathname]}
        collapsed={layoutCollapsed}
        onCollapse={isAssistant ? () => undefined : setCollapsed}
        collapsedButtonRender={isAssistant ? () => <Tooltip title={layoutCollapsed ? '展开导航' : '收起导航'}><Button type="text" aria-label={layoutCollapsed ? '展开导航' : '收起导航'} icon={<UnorderedListOutlined />} onClick={(event) => { event.stopPropagation(); setAssistantNavExpanded((value) => !value); }} /></Tooltip> : undefined}
        layout="side"
        fixedHeader
        fixSiderbar
        siderWidth={256}
        avatarProps={false}
        actionsRender={false}
        rightContentRender={() => null}
        contentStyle={{ minHeight: 'calc(var(--app-viewport-height, 100vh) - 64px)', padding: 0 }}
        menuItemRender={(item: MenuDataItem & { isUrl: boolean; onClick: () => void }, dom: React.ReactNode, menuProps: { isMobile?: boolean }) => (item.path ? <PrefetchLink to={item.path} onClick={menuProps.isMobile ? item.onClick : undefined}>{dom}</PrefetchLink> : dom)}
      >
        <main
          className={`app-content${isAssistant ? ' app-content-assistant' : ''}${isWorkspace ? ' app-content-workspace' : ''}${isProjectDocumentWorkspace ? ' app-content-project-document-workspace' : ''}${isKnowledgeWorkbench ? ' app-content-knowledge-workbench' : ''}${isPermissionManagement ? ' app-content-permission-management' : ''}`}
          id="main-content"
        >
          {requiredPermission && !canViewPathWithMenus(location.pathname, permissions, menuKeys) ? <AccessDenied permissions={permissions} menuKeys={menuKeys} /> : <ContentBoundary><Outlet /><RouteScrollRestoration /></ContentBoundary>}
        </main>
      </ProLayout>
      </div>
    </AuthGate>
  );
}
