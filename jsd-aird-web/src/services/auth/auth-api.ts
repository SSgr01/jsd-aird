import { httpClient } from '@/services/http/client';
import type { ApiResponse } from '@/types/api';

export interface AuthUser {
  userId: string;
  organizationId: string;
  organizationName: string;
  username: string;
  displayName: string;
  email?: string;
  departmentName?: string;
  roleId?: string;
  roleCode?: string;
  roleName?: string;
  status: 'ACTIVE' | 'DISABLED';
  authVersion: number;
  permissions: string[];
  menuKeys?: string[];
}

export interface LoginRequest {
  username: string;
  password: string;
  rememberMe: boolean;
}

export const authApi = {
  sessionPolicy: async () => {
    const response = await httpClient.get<ApiResponse<{ idleTimeoutMillis: number }>>('/api/v1/auth/session-policy');
    return response.data.data;
  },
  activity: async () => {
    await httpClient.post('/api/v1/auth/activity');
  },
  csrf: async () => {
    const response = await httpClient.get<ApiResponse<{ token: string }>>('/api/v1/auth/csrf');
    return response.data.data;
  },
  login: async (request: LoginRequest) => {
    const csrf = await authApi.csrf();
    const response = await httpClient.post<ApiResponse<AuthUser & { firstLogin: boolean }>>('/api/v1/auth/login', request, {
      headers: { 'X-XSRF-TOKEN': csrf.token },
    });
    return response.data.data;
  },
  me: async () => {
    const response = await httpClient.get<ApiResponse<AuthUser>>('/api/v1/auth/me');
    return response.data.data;
  },
  logout: async () => {
    await httpClient.post('/api/v1/auth/logout');
  },
  changePassword: async (request: { currentPassword: string; newPassword: string }) => {
    await httpClient.post('/api/v1/auth/password/change', request);
  },
};
