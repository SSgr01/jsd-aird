import { beforeEach, describe, expect, it, vi } from 'vitest';

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  patch: vi.fn(),
  delete: vi.fn(),
}));

vi.mock('@/services/http/client', () => ({ httpClient: httpMock }));

import { authApi } from '@/services/auth/auth-api';
import { iamApi } from '@/services/iam/iam-api';

const response = <T,>(data: T) => Promise.resolve({ data: { data } });

describe('IAM and session API contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    httpMock.get.mockResolvedValue(response({ token: 'csrf-token' }));
    httpMock.post.mockResolvedValue(response(undefined));
    httpMock.put.mockResolvedValue(response({ version: 2 }));
    httpMock.delete.mockResolvedValue(response(undefined));
  });

  it('logs in without exposing organization code in the request contract', async () => {
    await authApi.login({ username: 'admin', password: 'a'.repeat(12), rememberMe: false });

    expect(httpMock.get).toHaveBeenCalledWith('/api/v1/auth/csrf');
    expect(httpMock.post).toHaveBeenCalledWith(
      '/api/v1/auth/login',
      {
        username: 'admin',
        password: 'a'.repeat(12),
        rememberMe: false,
      },
      { headers: { 'X-XSRF-TOKEN': 'csrf-token' } },
    );
  });

  it('sends optimistic-lock versions for role and user permission saves', async () => {
    const bindings = [{ permissionCode: 'knowledge.view', effect: 'ALLOW' as const, scopeType: 'CATEGORY', targetIds: ['category-1'] }];

    await iamApi.saveRolePermissions('role-1', 12, bindings);
    await iamApi.saveUserPermissions('user-1', 7, bindings);

    expect(httpMock.put).toHaveBeenNthCalledWith(1, '/api/v1/iam/roles/role-1/permissions', {
      expectedVersion: 12,
      bindings,
    });
    expect(httpMock.put).toHaveBeenNthCalledWith(2, '/api/v1/iam/users/user-1/permissions', {
      expectedVersion: 7,
      bindings,
    });
  });

  it('encodes permission codes when restoring a user override', async () => {
    await iamApi.restoreUserPermission('user-1', 'knowledge.document.view');

    expect(httpMock.delete).toHaveBeenCalledWith('/api/v1/iam/users/user-1/permissions/knowledge.document.view');
  });
});
