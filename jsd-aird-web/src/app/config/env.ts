export const appEnv = {
  title: import.meta.env.VITE_APP_TITLE || '杰事达研发数字化与AI平台',
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL || '',
} as const;
