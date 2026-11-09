export const appEnv = {
  title: import.meta.env.VITE_APP_TITLE || '',
  version: import.meta.env.VITE_APP_VERSION || '',
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL || '',
} as const;
