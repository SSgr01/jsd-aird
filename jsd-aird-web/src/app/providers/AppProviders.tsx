import type { PropsWithChildren } from 'react';

import { App as AntdApp, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';

import { appTheme } from '@/app/config/theme';

export function AppProviders({ children }: PropsWithChildren) {
  return (
    <ConfigProvider locale={zhCN} theme={appTheme}>
      <AntdApp>{children}</AntdApp>
    </ConfigProvider>
  );
}
