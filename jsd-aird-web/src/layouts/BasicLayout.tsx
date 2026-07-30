import { HomeOutlined } from '@ant-design/icons';
import { ProLayout } from '@ant-design/pro-components';
import { Link, Outlet, useLocation } from 'react-router-dom';

import { appEnv } from '@/app/config/env';
import { useAppStore } from '@/stores/app-store';

const route = {
  path: '/',
  routes: [
    {
      path: '/',
      name: '首页',
      icon: <HomeOutlined />,
    },
  ],
};

export function BasicLayout() {
  const location = useLocation();
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
      layout="mix"
      fixedHeader
      fixSiderbar
      contentStyle={{ minHeight: 'calc(100vh - 64px)' }}
      menuItemRender={(item, dom) => (item.path ? <Link to={item.path}>{dom}</Link> : dom)}
    >
      <Outlet />
    </ProLayout>
  );
}
