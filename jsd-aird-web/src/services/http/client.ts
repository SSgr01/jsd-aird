import axios from 'axios';
import type { AxiosResponse } from 'axios';

import { appEnv } from '@/app/config/env';
import { HttpError } from '@/services/http/errors';
import type { ApiErrorResponse } from '@/services/http/types';
import { generateUUID } from '@/utils/uuid';

export const httpClient = axios.create({
  baseURL: appEnv.apiBaseUrl,
  timeout: 15_000,
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  headers: {
    Accept: 'application/json',
  },
});

let csrfRefresh: Promise<unknown> | null = null;
let csrfToken: string | null = null;

interface CsrfResponse {
  data?: { token?: string };
}

httpClient.interceptors.request.use(async (config) => {
  config.headers.set('X-Request-Id', generateUUID());
  const method = (config.method || 'get').toLowerCase();
  const isUnsafe = ['post', 'put', 'patch', 'delete'].includes(method);
  const isCsrfRequest = config.url?.endsWith('/api/v1/auth/csrf') ?? false;
  const hasExplicitCsrf = Boolean(config.headers.get('X-XSRF-TOKEN'));
  if (isUnsafe && !isCsrfRequest && !hasExplicitCsrf) {
    csrfRefresh ??= httpClient.get<CsrfResponse>('/api/v1/auth/csrf').then((response) => {
      csrfToken = response.data.data?.token || csrfToken;
    }).finally(() => { csrfRefresh = null; });
    await csrfRefresh;
  }
  if (isUnsafe && !isCsrfRequest && csrfToken && !hasExplicitCsrf) {
    config.headers.set('X-XSRF-TOKEN', csrfToken);
  }
  return config;
});

httpClient.interceptors.response.use(
  (response: AxiosResponse) => {
    if (response.config.url?.endsWith('/api/v1/auth/csrf')) {
      const payload = response.data as CsrfResponse;
      csrfToken = payload.data?.token || csrfToken;
    }
    return response;
  },
  (error: unknown) => {
    if (axios.isAxiosError<ApiErrorResponse>(error)) {
      const response = error.response;
      throw new HttpError(
        response?.data?.message || error.message || '请求失败',
        response?.data?.code || 'HTTP_REQUEST_FAILED',
        response?.status,
        response?.data?.traceId,
      );
    }
    throw error;
  },
);

/**
 * Raw fetch calls (for example SSE) do not pass through Axios' request
 * interceptor. Keep CSRF acquisition in one place so those calls use the
 * same server-issued token as the rest of the application.
 */
export async function ensureCsrfToken(): Promise<string> {
  if (csrfToken) return csrfToken;
  csrfRefresh ??= httpClient.get<CsrfResponse>('/api/v1/auth/csrf').then((response) => {
    csrfToken = response.data.data?.token || csrfToken;
  }).finally(() => { csrfRefresh = null; });
  await csrfRefresh;
  if (!csrfToken) throw new Error('CSRF 安全令牌获取失败，请刷新页面后重试');
  return csrfToken;
}

export async function refreshCsrfToken(): Promise<string> {
  csrfToken = null;
  csrfRefresh = null;
  return ensureCsrfToken();
}
