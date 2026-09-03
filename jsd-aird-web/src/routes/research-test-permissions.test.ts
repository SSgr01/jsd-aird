import { describe, expect, it } from 'vitest';
import { canViewPath, hasPermission, requiredPermissionForPath } from './route-permissions';
describe('research test route permissions', () => {
  it('maps report and standard routes independently', () => {
    expect(requiredPermissionForPath('/research-test/upload')).toBe('research-test.report.create');
    expect(requiredPermissionForPath('/research-test/reports')).toBe('research-test.report.view');
    expect(requiredPermissionForPath('/research-test/standards')).toBe(
      'research-test.standard.view',
    );
  });
  it('allows an action permission to reveal its module', () => {
    expect(hasPermission('research-test.report.view', ['research-test.report.update'])).toBe(true);
    expect(canViewPath('/research-test/standards', ['research-test.standard.create'])).toBe(true);
    expect(canViewPath('/research-test/reports', ['research-test.standard.view'])).toBe(false);
  });
});
