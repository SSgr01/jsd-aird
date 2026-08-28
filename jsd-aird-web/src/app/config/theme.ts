import type { ThemeConfig } from 'antd';

export const appTheme: ThemeConfig = {
  token: {
    colorPrimary: '#315cf6',
    colorInfo: '#315cf6',
    colorSuccess: '#18a66a',
    colorWarning: '#f59e0b',
    colorError: '#dc2626',
    colorText: '#14213d',
    colorTextSecondary: '#66738a',
    colorBorder: '#dce4ef',
    colorBorderSecondary: '#e8edf4',
    colorBgContainer: '#fefeff',
    colorBgLayout: '#f8fafd',
    colorBgElevated: '#ffffff',
    borderRadius: 8,
    controlOutline: '#315cf6',
    fontSize: 15,
    fontSizeSM: 13,
    fontSizeLG: 17,
    lineHeight: 1.55,
    fontFamily:
      '"Segoe UI Variable", "Segoe UI", "Microsoft YaHei UI", "PingFang SC", "Noto Sans CJK SC", sans-serif',
  },
  components: {
    Layout: {
      headerBg: '#fbfcfe',
      siderBg: '#fbfcfe',
      bodyBg: '#f8fafd',
    },
    Button: {
      controlHeight: 36,
      borderRadius: 8,
      primaryShadow: '0 6px 16px rgb(49 92 246 / 18%)',
    },
    Card: {
      borderRadiusLG: 12,
      borderRadiusSM: 8,
    },
    Table: {
      headerBg: '#f6f8fb',
      headerColor: '#14213d',
      rowHoverBg: '#f7f9ff',
      borderColor: '#e8edf4',
    },
    Input: {
      activeBorderColor: '#315cf6',
      hoverBorderColor: '#a8b9e8',
      activeShadow: '0 0 0 3px rgb(49 92 246 / 10%)',
    },
    Select: {
      activeBorderColor: '#315cf6',
      hoverBorderColor: '#a8b9e8',
    },
    Menu: {
      itemBorderRadius: 8,
      itemSelectedBg: '#eef3ff',
      itemSelectedColor: '#315cf6',
      itemHoverBg: '#f3f6fc',
      itemHoverColor: '#2448d8',
    },
  },
};
