import type { AuthUser } from '@/services/auth/auth-api';

export function operationTestUser(
  permissions = ['experiment.view', 'experiment.create'],
): AuthUser {
  return {
    userId: 'customer-1',
    organizationId: 'organization-1',
    organizationName: '测试组织',
    username: 'customer',
    displayName: '测试用户',
    roleCode: 'USER',
    status: 'ACTIVE',
    authVersion: 1,
    permissions,
  };
}
