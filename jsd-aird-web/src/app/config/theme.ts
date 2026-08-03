import type { ThemeConfig } from 'antd';

export const appTheme: ThemeConfig = {
  token: {
    colorPrimary: '#2563eb',
    colorInfo: '#2563eb',
    colorSuccess: '#047857',
    colorWarning: '#b45309',
    colorError: '#dc2626',
    colorText: '#0f172a',
    colorTextSecondary: '#475569',
    colorBorder: '#e2e8f0',
    borderRadius: 6,
    colorBgLayout: '#f8fafc',
    fontFamily:
      '"Source Sans 3", "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif',
  },
  components: {
    Layout: {
      headerBg: '#ffffff',
    },
    Button: {
      controlHeight: 36,
    },
    Table: {
      headerBg: '#f8fafc',
    },
  },
};
