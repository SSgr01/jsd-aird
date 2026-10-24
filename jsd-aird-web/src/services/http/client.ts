import axios from 'axios';
import type { AxiosResponse } from 'axios';

import { appEnv } from '@/app/config/env';
import { HttpError, retryAfterMillis } from '@/services/http/errors';
import type { ApiErrorResponse } from '@/services/http/types';
import { getSessionEpoch, notifyAuthRequired } from '@/services/http/auth-events';
import { generateUUID } from '@/utils/uuid';
import { clearDomain, domainsForRequest, invalidateDomains, primaryDomainForRequest } from '@/services/query/query-client';
import { recordRequestMetric } from '@/services/performance/metrics';

type RetriableRequestConfig = AxiosResponse['config'] & { _csrfRetried?: boolean; _sessionEpoch?: number; _startedAt?: number; _sampled?: boolean };

function recordRequest(config: RetriableRequestConfig, status?: number, traceId?: string) {
  if (!config._sampled || config._startedAt === undefined) return;
  const requestId = traceId || String(config.headers.get('X-Request-Id') || '');
  recordRequestMetric(requestId, domainsForRequest(config.url || '')[0] || 'other', (config.method || 'get').toUpperCase(), config._startedAt, status);
}

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

/**
 * Requests whose payload scales with file size (upload/download of original
 * documents, published structured content, review snapshots) cannot share the
 * 15s interactive timeout: a knowledge file may be hundreds of megabytes, so
 * the transfer legitimately takes minutes. 0 disables the axios timeout — a
 * dropped connection still rejects the promise.
 */
export const LARGE_PAYLOAD_TIMEOUT = 0;

let csrfRefresh: Promise<unknown> | null = null;
let csrfToken: string | null = null;

interface CsrfResponse {
  data?: { token?: string };
}

httpClient.interceptors.request.use(async (config) => {
  (config as RetriableRequestConfig)._sessionEpoch ??= getSessionEpoch();
  (config as RetriableRequestConfig)._startedAt = performance.now();
  (config as RetriableRequestConfig)._sampled = Math.random() < 0.1;
  config.headers.set('X-Request-Id', generateUUID());
  const method = (config.method || 'get').toLowerCase();
  const isUnsafe = ['post', 'put', 'patch', 'delete'].includes(method);
  const isCsrfRequest = config.url?.endsWith('/api/v1/auth/csrf') ?? false;
  const hasExplicitCsrf = Boolean(config.headers.get('X-XSRF-TOKEN'));
  if (isUnsafe && !isCsrfRequest && !hasExplicitCsrf) {
    config.headers.set('X-XSRF-TOKEN', await ensureCsrfToken());
  }
  if ((config as RetriableRequestConfig)._sessionEpoch !== getSessionEpoch()) throw new axios.CanceledError('Session changed');
  return config;
});

httpClient.interceptors.response.use(
  (response: AxiosResponse) => {
    if ((response.config as RetriableRequestConfig)._sessionEpoch !== getSessionEpoch()) throw new axios.CanceledError('Session changed');
    recordRequest(response.config, response.status);
    if (response.config.url?.endsWith('/api/v1/auth/csrf')) {
      const payload = response.data as CsrfResponse;
      csrfToken = payload.data?.token || csrfToken;
    }
    if ((response.config as RetriableRequestConfig)._sessionEpoch === getSessionEpoch() && ['post', 'put', 'patch', 'delete'].includes((response.config.method || 'get').toLowerCase())) {
      void invalidateDomains(domainsForRequest(response.config.url || ''));
    }
    return response;
  },
  (error: unknown) => {
    if (axios.isCancel(error)) throw error;
    if (axios.isAxiosError<ApiErrorResponse>(error)) {
      const response = error.response;
      const requestConfig = (response?.config || error.config) as RetriableRequestConfig | undefined;
      const isLoginRequest = response?.config.url?.endsWith('/api/v1/auth/login') ?? false;
      const method = (requestConfig?.method || 'get').toLowerCase();
      const isUnsafe = ['post', 'put', 'patch', 'delete'].includes(method);
      const isCsrfFailure = response?.data?.code === 'CSRF_TOKEN_INVALID';
      if (requestConfig && requestConfig._sessionEpoch !== getSessionEpoch()) throw new axios.CanceledError('Session changed');
      if (requestConfig) recordRequest(requestConfig, response?.status, response?.data?.traceId);
      if (isUnsafe && (!response || response.status >= 500 || response.status === 409)) {
        // A timeout/5xx may follow a committed write; 409 means our revision may
        // be stale. Reconcile READ caches, never resend a potentially committed write.
        if (response?.data?.code !== 'SERVER_BUSY') void invalidateDomains(domainsForRequest(requestConfig?.url || ''));
      }
      if (response?.status === 403 && !isCsrfFailure && isUnsafe) {
        const denied = new HttpError('暂无权限访问该资源', 'PERMISSION_DENIED', 403);
        const epoch = getSessionEpoch();
        // Reads clear their own query resource in QueryCache.onError. A write
        // clears only its primary domain, never unrelated dashboard modules.
        setTimeout(() => {
          const domain = primaryDomainForRequest(requestConfig?.url || '');
          if (epoch === getSessionEpoch() && domain) clearDomain(domain, denied);
        }, 0);
      }
      if (response?.status === 403 && isCsrfFailure && isUnsafe && requestConfig && !requestConfig._csrfRetried) {
        requestConfig._csrfRetried = true;
        // Another failed request may already have refreshed the same stale token.
        const failedToken = requestConfig.headers.get('X-XSRF-TOKEN');
        const token = csrfToken && csrfToken !== failedToken
          ? Promise.resolve(csrfToken)
          : refreshCsrfToken();
        return token.then((token) => {
          requestConfig.headers.set('X-XSRF-TOKEN', token);
          return httpClient.request(requestConfig);
        });
      }
      if (response?.status === 401 && !isLoginRequest) notifyAuthRequired();
      throw new HttpError(
        response?.data?.message || error.message || '请求失败',
        response?.data?.code || 'HTTP_REQUEST_FAILED',
        response?.status,
        response?.data?.traceId,
        retryAfterMillis(response?.headers['retry-after']),
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
  return ensureCsrfToken();
}
