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
  PartitionOutlined,
  ProjectOutlined,
  UnorderedListOutlined,
  LineChartOutlined,
  HomeOutlined,
  LockOutlined,
  LogoutOutlined,
  SettingOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { ProLayout } from '@ant-design/pro-components';
import { Avatar, Button, Dropdown, Result, Typography } from 'antd';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useMemo } from 'react';

import { appEnv } from '@/app/config/env';
import { AuthGate } from '@/components/auth/AuthGate';
import { useAppStore } from '@/stores/app-store';
import { useAuthStore } from '@/stores/auth-store';
import { canViewPath, filterMenuRoute, firstAccessiblePath, requiredPermissionForPath } from '@/routes/route-permissions';

const route = {
  path: '/',
  routes: [
    {
      path: '/partners',
      name: '客户管理',
      icon: <BankOutlined />,
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
        { path: '/experiments/upload', name: '实验上传', icon: <UploadOutlined /> },
        { path: '/experiments/list', name: '实验查看', icon: <EyeOutlined /> },
      ],
    },
    {
      path: '/ai-assistant',
      name: 'AI研发助手',
      icon: <RobotOutlined />,
      routes: [
        { path: '/assistant', name: 'AI问答', icon: <RobotOutlined /> },
        { path: '/knowledge/search', name: '文件检索', icon: <FileSearchOutlined /> },
      ],
    },
    {
      path: '/knowledge',
      name: '研发知识库',
      icon: <FolderOpenOutlined />,
      routes: [
        { path: '/knowledge/library', name: '资料上传', icon: <FolderOpenOutlined /> },
        { path: '/knowledge/view', name: '知识库查看', icon: <EyeOutlined /> },
        { path: '/knowledge/review', name: '审核工作台', icon: <AuditOutlined /> },
      ],
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
      path: '/production-orders',
      name: '生产单管理',
      icon: <OrderedListOutlined />,
      routes: [
        { path: '/production-orders/upload', name: '生产单上传', icon: <InboxOutlined /> },
        { path: '/production-orders/list', name: '生产单查看', icon: <EyeOutlined /> },
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
      path: '/system',
      name: '系统设置',
      icon: <SettingOutlined />,
      routes: [
        { path: '/system/users', name: '用户管理', icon: <UserOutlined /> },
        { path: '/system/roles', name: '角色权限配置', icon: <SettingOutlined /> },
        { path: '/system/user-permissions', name: '用户权限配置', icon: <SettingOutlined /> },
        { path: '/system/audit-logs', name: '操作日志', icon: <AuditOutlined /> },
      ],
    },
  ],
};

function UserMenu() {
  const navigate = useNavigate();
  const user = useAuthStore((state) => state.user);
  const logout = useAuthStore((state) => state.logout);
  if (!user) return null;
  const initial = (user.displayName || user.username || '用').slice(0, 1);
  const items = [
    { key: 'workbench', icon: <HomeOutlined />, label: '工作台' },
    { key: 'change-password', icon: <LockOutlined />, label: '修改密码' },
    { type: 'divider' as const },
    { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', danger: true },
  ];
  return <Dropdown
    trigger={['click']}
    menu={{ items, onClick: ({ key }) => {
      if (key === 'workbench') navigate(firstAccessiblePath(user.permissions) || '/');
      if (key === 'change-password') navigate('/change-password');
      if (key === 'logout') void logout();
    } }}
    popupRender={(menu) => <div className="app-user-dropdown">
      <div className="app-user-profile"><Typography.Text strong>{user.displayName || user.username}</Typography.Text><Typography.Text type="secondary">{user.roleName || '未分配角色'}</Typography.Text>{user.email && <Typography.Text type="secondary">{user.email}</Typography.Text>}</div>
      {menu}
    </div>}
  >
    <button type="button" className="app-user-trigger" aria-label="打开用户菜单"><Avatar size={34}>{initial}</Avatar></button>
  </Dropdown>;
}

function AccessDenied({ permissions }: { permissions: string[] }) {
  const navigate = useNavigate();
  const home = firstAccessiblePath(permissions);
  return <Result status="403" title="暂无权限访问此页面" subTitle="请联系系统管理员开通查看权限" extra={<Button type="primary" onClick={() => navigate(home || '/login', { replace: true })}>{home ? '返回工作台' : '返回登录'}</Button>} />;
}

export function BasicLayout() {
  const location = useLocation();
  const isTemplateWorkspace = /^\/templates\/[^/]+\/workspace$/.test(location.pathname);
  const isDataWorkspace = /^\/data\/(?:import-jobs|assets)\/[^/]+$/.test(location.pathname);
  const isWorkspace = isTemplateWorkspace || isDataWorkspace;
  const collapsed = useAppStore((state) => state.sidebarCollapsed);
  const setCollapsed = useAppStore((state) => state.setSidebarCollapsed);
  const user = useAuthStore((state) => state.user);
  const permissions = user?.permissions ?? [];
  const visibleRoute = useMemo(() => filterMenuRoute(route, permissions) ?? { path: '/', routes: [] }, [permissions]);
  const requiredPermission = requiredPermissionForPath(location.pathname);

  return (
    <AuthGate>
      <div className="app-shell">
      <ProLayout
        title={appEnv.title}
        logo={false}
        route={visibleRoute}
        location={{ pathname: location.pathname }}
        collapsed={collapsed}
        onCollapse={setCollapsed}
        layout="side"
        fixedHeader
        fixSiderbar
        avatarProps={false}
        actionsRender={() => null}
        rightContentRender={() => null}
        contentStyle={{ minHeight: 'calc(100dvh - 64px)', padding: 0 }}
        menuItemRender={(item, dom) => (item.path ? <Link to={item.path}>{dom}</Link> : dom)}
      >
        <main
          className={`app-content${isWorkspace ? ' app-content-workspace' : ''}`}
          id="main-content"
        >
          {requiredPermission && !canViewPath(location.pathname, permissions) ? <AccessDenied permissions={permissions} /> : <Outlet />}
        </main>
      </ProLayout>
      <div className="app-header-user"><UserMenu /></div>
      </div>
    </AuthGate>
  );
}
