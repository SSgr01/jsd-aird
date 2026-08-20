import type { ThemeConfig } from 'antd';

export const appTheme: ThemeConfig = {
  token: {
    colorPrimary: '#2f66e8',
    colorInfo: '#2f66e8',
    colorSuccess: '#16a34a',
    colorWarning: '#f59e0b',
    colorError: '#dc2626',
    colorText: '#17233a',
    colorTextSecondary: '#66758b',
    colorBorder: '#dfe7f2',
    colorBgContainer: '#ffffff',
    colorBgLayout: '#f3f6fb',
    borderRadius: 10,
    controlOutline: '#2f66e8',
    fontFamily:
      '"Segoe UI Variable", "Segoe UI", "Microsoft YaHei UI", "PingFang SC", "Noto Sans CJK SC", sans-serif',
  },
  components: {
    Layout: {
      headerBg: '#ffffff',
      siderBg: '#2f63d9',
      bodyBg: '#f3f6fb',
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
      headerBg: '#f7f9fc',
    },
  },
};
