import type { ThemeConfig } from 'antd';

export const appTheme: ThemeConfig = {
  token: {
    colorPrimary: '#1677ff',
    borderRadius: 6,
    colorBgLayout: '#f5f7fa',
    fontFamily:
      '"Inter", "PingFang SC", "Microsoft YaHei", -apple-system, BlinkMacSystemFont, sans-serif',
  },
  components: {
    Layout: {
      headerBg: '#ffffff',
    },
  },
};
