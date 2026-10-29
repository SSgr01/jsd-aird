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
export interface PermissionBinding { menuKey?: string; permissionCode: string; effect: 'ALLOW' | 'DENY'; scopeType: string; targetIds: string[]; }
export interface PermissionLevelBinding { menuKey?: string | null; permissionCode: string; effect: 'ALLOW'; scopeType: string; targetIds: string[]; }
export interface PermissionMenu { key: string; label: string; parent?: string; route?: string; }
export interface PermissionLevel { id: string; code: string; name: string; description?: string; builtin: boolean; enabled: boolean; tier: number; policyVersion: number; }
export interface PermissionModel { menus: PermissionMenu[]; levels: PermissionLevel[]; operations: PermissionDefinition[]; }
export interface MenuOverride { menuKey: string; effect: 'ALLOW' | 'DENY'; }
export interface UserPermissionModel {
  userId: string;
  roles: IamRole[];
  level?: PermissionLevel;
  menuOverrides: MenuOverride[];
  operationOverrides: PermissionBinding[];
  scopeOverrides: PermissionBinding[];
  authVersion: number;
}

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

export interface AuditLogQuery {
  keyword?: string;
  module?: string;
  operation?: string;
  operator?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

const responseData = async <T>(request: Promise<{ data: ApiResponse<T> }>) => (await request).data.data;

export const iamApi = {
  users: (params?: { keyword?: string; page?: number; size?: number }, signal?: AbortSignal) => responseData<PageResponse<IamUser>>(httpClient.get('/api/v1/iam/users', { params, signal })),
  createUser: (payload: { username: string; displayName: string; email?: string; phone?: string; departmentName?: string; roleId: string; password: string }) => responseData<{ user: IamUser }>(httpClient.post('/api/v1/iam/users', payload)),
  updateUser: (id: string, payload: { expectedVersion: number; displayName: string; email?: string; phone?: string; departmentName?: string; roleId: string }) => responseData<IamUser>(httpClient.patch(`/api/v1/iam/users/${id}`, payload)),
  enableUser: (id: string, expectedVersion: number) => responseData<void>(httpClient.post(`/api/v1/iam/users/${id}/enable`, { expectedVersion })),
  disableUser: (id: string, expectedVersion: number) => responseData<void>(httpClient.post(`/api/v1/iam/users/${id}/disable`, { expectedVersion })),
  resetPassword: (id: string, password: string, expectedVersion: number) => responseData<void>(httpClient.post(`/api/v1/iam/users/${id}/reset-password`, { password, expectedVersion })),
  forceLogout: (id: string, expectedVersion: number) => responseData<void>(httpClient.post(`/api/v1/iam/users/${id}/force-logout`, { expectedVersion })),
  roles: () => responseData<IamRole[]>(httpClient.get('/api/v1/iam/roles')),
  createRole: (payload: { code: string; name: string }) => responseData<IamRole>(httpClient.post('/api/v1/iam/roles', payload)),
  renameRole: (id: string, name: string, expectedVersion: number) => responseData<void>(httpClient.patch(`/api/v1/iam/roles/${id}`, { name, expectedVersion })),
  deleteRole: (id: string, expectedVersion: number) => responseData<void>(httpClient.delete(`/api/v1/iam/roles/${id}`, { params: { expectedVersion } })),
  definitions: () => responseData<PermissionDefinition[]>(httpClient.get('/api/v1/iam/permission-definitions')),
  permissionModel: () => responseData<PermissionModel>(httpClient.get('/api/v1/iam/permission-model')),
  roleMenus: (id: string) => responseData<{ version: number; menuKeys: string[] }>(httpClient.get(`/api/v1/iam/roles/${id}/menus`)),
  saveRoleMenus: (id: string, expectedVersion: number, menuKeys: string[]) => responseData<{ version: number }>(httpClient.put(`/api/v1/iam/roles/${id}/menus`, { expectedVersion, menuKeys })),
  permissionLevels: () => responseData<PermissionLevel[]>(httpClient.get('/api/v1/iam/permission-levels')),
  createPermissionLevel: (payload: { code: string; name: string; description?: string; tier: number }) => responseData<PermissionLevel>(httpClient.post('/api/v1/iam/permission-levels', payload)),
  renamePermissionLevel: (id: string, payload: { name: string; description?: string }) => responseData<void>(httpClient.patch(`/api/v1/iam/permission-levels/${id}`, payload)),
  deletePermissionLevel: (id: string) => responseData<void>(httpClient.delete(`/api/v1/iam/permission-levels/${id}`)),
  permissionLevelPermissions: (id: string) => responseData<{ version: number; bindings: PermissionLevelBinding[] }>(httpClient.get(`/api/v1/iam/permission-levels/${id}/permissions`)),
  savePermissionLevelPermissions: (id: string, expectedVersion: number, bindings: PermissionLevelBinding[]) => responseData<{ version: number }>(httpClient.put(`/api/v1/iam/permission-levels/${id}/permissions`, { expectedVersion, bindings })),
  userModel: (id: string) => responseData<UserPermissionModel>(httpClient.get(`/api/v1/iam/users/${id}/permission-model`)),
  saveUserModel: (id: string, payload: { expectedVersion: number; roleIds: string[]; primaryRoleId?: string; permissionLevelId?: string; menus: MenuOverride[]; operations: PermissionBinding[]; scopes: PermissionBinding[] }) => responseData<{ version: number }>(httpClient.put(`/api/v1/iam/users/${id}/permission-model`, payload)),
  rolePermissions: (id: string, signal?: AbortSignal) => responseData<{ version: number; bindings: PermissionBinding[] }>(httpClient.get(`/api/v1/iam/roles/${id}/permissions`, { signal })),
  saveRolePermissions: (id: string, expectedVersion: number, bindings: PermissionBinding[]) => responseData<{ version: number }>(httpClient.put(`/api/v1/iam/roles/${id}/permissions`, { expectedVersion, bindings })),
  userPermissions: (id: string, signal?: AbortSignal) => responseData<{ version: number; bindings: PermissionBinding[] }>(httpClient.get(`/api/v1/iam/users/${id}/permissions`, { signal })),
  saveUserPermissions: (id: string, expectedVersion: number, bindings: PermissionBinding[]) => responseData<{ version: number }>(httpClient.put(`/api/v1/iam/users/${id}/permissions`, { expectedVersion, bindings })),
  restoreUserPermission: (id: string, code: string, expectedVersion: number) => responseData<void>(httpClient.delete(`/api/v1/iam/users/${id}/permissions/${encodeURIComponent(code)}`, { params: { expectedVersion } })),
  auditLogs: (params?: AuditLogQuery) => responseData<PageResponse<AuditLogView>>(httpClient.get('/api/v1/iam/audit-logs', { params })),
};
