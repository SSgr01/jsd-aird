import {
  DatabaseOutlined,
  EyeOutlined,
  FileSearchOutlined,
  FileTextOutlined,
  FolderOpenOutlined,
  InboxOutlined,
  OrderedListOutlined,
  RobotOutlined,
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
