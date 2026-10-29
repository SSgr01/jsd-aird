import type { AuthUser } from '@/services/auth/auth-api';

export const testUser: AuthUser = {
  userId: 'user-1', organizationId: 'org-1', organizationName: '测试组织', username: 'test',
  displayName: '测试用户', status: 'ACTIVE', authVersion: 1,
  permissions: ['project.view', 'experiment.view', 'data.view', 'knowledge.view'],
};
