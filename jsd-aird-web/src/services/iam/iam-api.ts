import { httpClient } from '@/services/http/client';
import type { ApiResponse, PageResponse } from '@/types/api';

export interface IamUser {
  id: string;
  username: string;
  displayName: string;
  email?: string;
  phone?: string;
  departmentName?: string;
  roleId?: string;
  roleCode?: string;
  roleName?: string;
  status: 'ACTIVE' | 'DISABLED';
  lastLoginAt?: string;
  authVersion: number;
}

export interface IamRole { id: string; code: string; name: string; builtin: boolean; enabled: boolean; policyVersion: number; }
export interface PermissionDefinition { code: string; module: string; name: string; risk: string; defaultScope: string; }
export interface PermissionBinding { permissionCode: string; effect: 'ALLOW' | 'DENY'; scopeType: string; targetIds: string[]; }
export interface AuditLogTechnicalDetail {
  auditId: string;
  actionCode: string;
  aggregateType: string;
  aggregateId: string;
  fields: Record<string, string>;
}
export interface AuditLogView {
  id: string;
  createdAt: string;
  module: string;
  operation: string;
  operator: string;
  objectType: string;
  objectName: string;
  summary: string;
  technical: AuditLogTechnicalDetail;
}

const responseData = async <T>(request: Promise<{ data: ApiResponse<T> }>) => (await request).data.data;

export const iamApi = {
  users: (params?: { keyword?: string; page?: number; size?: number }) => responseData<PageResponse<IamUser>>(httpClient.get('/api/v1/iam/users', { params })),
  createUser: (payload: { username: string; displayName: string; email?: string; phone?: string; departmentName?: string; roleId: string; password: string }) => responseData<{ user: IamUser }>(httpClient.post('/api/v1/iam/users', payload)),
  updateUser: (id: string, payload: { displayName: string; email?: string; phone?: string; departmentName?: string; roleId: string }) => responseData<IamUser>(httpClient.patch(`/api/v1/iam/users/${id}`, payload)),
  enableUser: (id: string) => responseData<void>(httpClient.post(`/api/v1/iam/users/${id}/enable`)),
  disableUser: (id: string) => responseData<void>(httpClient.post(`/api/v1/iam/users/${id}/disable`)),
  resetPassword: (id: string, password: string) => responseData<void>(httpClient.post(`/api/v1/iam/users/${id}/reset-password`, { password })),
  forceLogout: (id: string) => responseData<void>(httpClient.post(`/api/v1/iam/users/${id}/force-logout`)),
  roles: () => responseData<IamRole[]>(httpClient.get('/api/v1/iam/roles')),
  createRole: (payload: { code: string; name: string }) => responseData<IamRole>(httpClient.post('/api/v1/iam/roles', payload)),
  renameRole: (id: string, name: string) => responseData<void>(httpClient.patch(`/api/v1/iam/roles/${id}`, { name })),
  deleteRole: (id: string) => responseData<void>(httpClient.delete(`/api/v1/iam/roles/${id}`)),
  definitions: () => responseData<PermissionDefinition[]>(httpClient.get('/api/v1/iam/permission-definitions')),
  rolePermissions: (id: string) => responseData<{ version: number; bindings: PermissionBinding[] }>(httpClient.get(`/api/v1/iam/roles/${id}/permissions`)),
  saveRolePermissions: (id: string, expectedVersion: number, bindings: PermissionBinding[]) => responseData<{ version: number }>(httpClient.put(`/api/v1/iam/roles/${id}/permissions`, { expectedVersion, bindings })),
  userPermissions: (id: string) => responseData<{ version: number; bindings: PermissionBinding[] }>(httpClient.get(`/api/v1/iam/users/${id}/permissions`)),
  saveUserPermissions: (id: string, expectedVersion: number, bindings: PermissionBinding[]) => responseData<{ version: number }>(httpClient.put(`/api/v1/iam/users/${id}/permissions`, { expectedVersion, bindings })),
  restoreUserPermission: (id: string, code: string) => responseData<void>(httpClient.delete(`/api/v1/iam/users/${id}/permissions/${encodeURIComponent(code)}`)),
  auditLogs: (params?: { keyword?: string; module?: string; operation?: string; operator?: string; from?: string; to?: string; page?: number; size?: number }) => responseData<PageResponse<AuditLogView>>(httpClient.get('/api/v1/iam/audit-logs', { params })),
};
