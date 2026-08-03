import axios from 'axios';

import { appEnv } from '@/app/config/env';
import { HttpError } from '@/services/http/errors';
import type { ApiErrorResponse } from '@/services/http/types';

export const httpClient = axios.create({
  baseURL: appEnv.apiBaseUrl,
  timeout: 15_000,
  headers: {
    Accept: 'application/json',
  },
});

httpClient.interceptors.request.use((config) => {
  config.headers.set('X-Request-Id', crypto.randomUUID());
  config.headers.set('X-Organization-Id', '00000000-0000-0000-0000-000000000001');
  config.headers.set('X-User-Id', '00000000-0000-0000-0000-000000000002');
  config.headers.set('X-Username', 'developer');
  return config;
});

httpClient.interceptors.response.use(
  (response) => response,
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
