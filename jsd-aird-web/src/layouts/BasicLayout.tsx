import {
  EyeOutlined,
  FileTextOutlined,
  InboxOutlined,
  OrderedListOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import { ProLayout } from '@ant-design/pro-components';
import { Link, Outlet, useLocation } from 'react-router-dom';

import { appEnv } from '@/app/config/env';
import { useAppStore } from '@/stores/app-store';

const route = {
  path: '/',
  routes: [
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
  ],
};

export function BasicLayout() {
  const location = useLocation();
  const isTemplateWorkspace = /^\/templates\/[^/]+\/workspace$/.test(location.pathname);
  const collapsed = useAppStore((state) => state.sidebarCollapsed);
  const setCollapsed = useAppStore((state) => state.setSidebarCollapsed);

  return (
    <ProLayout
      title={appEnv.title}
      logo={false}
      route={route}
      location={{ pathname: location.pathname }}
      collapsed={collapsed}
      onCollapse={setCollapsed}
      layout="side"
      fixedHeader
      fixSiderbar
      contentStyle={{ minHeight: 'calc(100dvh - 64px)', padding: 0 }}
      menuItemRender={(item, dom) => (item.path ? <Link to={item.path}>{dom}</Link> : dom)}
    >
      <main
        className={`app-content${isTemplateWorkspace ? ' app-content-workspace' : ''}`}
        id="main-content"
      >
        <Outlet />
      </main>
    </ProLayout>
  );
}
