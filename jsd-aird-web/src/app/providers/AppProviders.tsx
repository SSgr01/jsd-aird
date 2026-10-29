import type { PropsWithChildren } from 'react';

import { App as AntdApp, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';

import { appTheme } from '@/app/config/theme';
import { QueryClientProvider } from '@tanstack/react-query';
import { queryClient } from '@/services/query/query-client';

export function AppProviders({ children }: PropsWithChildren) {
  return (
    <QueryClientProvider client={queryClient}><ConfigProvider locale={zhCN} theme={appTheme}>
      <AntdApp>{children}</AntdApp>
    </ConfigProvider></QueryClientProvider>
  );
}
