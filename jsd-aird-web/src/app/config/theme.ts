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
    colorBgContainer: '#ffffff',
    colorBgLayout: '#f8fafc',
    borderRadius: 8,
    controlOutline: '#2563eb',
    fontFamily:
      '"Segoe UI Variable", "Segoe UI", "Microsoft YaHei UI", "PingFang SC", "Noto Sans CJK SC", sans-serif',
  },
  components: {
    Layout: {
      headerBg: '#ffffff',
      siderBg: '#ffffff',
      bodyBg: '#f8fafc',
    },
    Button: {
      controlHeight: 36,
      borderRadius: 8,
    },
    Card: {
      borderRadiusLG: 12,
      borderRadiusSM: 8,
    },
    Table: {
      headerBg: '#f8fafc',
    },
  },
};
