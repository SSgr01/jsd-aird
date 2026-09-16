import { describe, expect, it } from 'vitest';
import { canViewPath, canViewPathWithMenus, filterMenuRoute, hasMenu, hasPermission, requiredMenuForPath, requiredPermissionForPath } from './route-permissions';
describe('research test route permissions', () => {
  it('maps report and standard routes independently', () => {
    expect(requiredPermissionForPath('/research-test/upload')).toBe('research-test.report.create');
    expect(requiredPermissionForPath('/research-test/standard-upload')).toBe(
      'research-test.standard.create',
    );
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
  it('uses business-role menus independently from atomic operations', () => {
    expect(requiredMenuForPath('/research-test/reports')).toBe('research-test.report.view');
    expect(hasMenu('research-test.report.view', ['research-test'])).toBe(false);
    expect(canViewPathWithMenus('/research-test/reports', [], ['research-test'])).toBe(false);
    expect(canViewPathWithMenus('/research-test/reports', ['research-test.report.view'], ['research-test.report.view'])).toBe(true);
    expect(canViewPathWithMenus('/research-test/reports', [], ['quality'])).toBe(false);
  });

  it('does not let a stale parent module key reveal unselected project menus', () => {
    expect(requiredMenuForPath('/projects/list')).toBe('project.list');
    expect(requiredMenuForPath('/projects/phases')).toBe('project.stage');
    expect(requiredMenuForPath('/projects/tasks')).toBe('project.task');
    expect(canViewPathWithMenus('/projects/list', [], ['project'])).toBe(false);
    expect(canViewPathWithMenus('/projects/phases', [], ['project.list'])).toBe(false);
  });

  it('keeps a menu group when only one exact child menu is selected', () => {
    const projectGroup = { path: '/projects', routes: [
      { path: '/projects/list' },
      { path: '/projects/phases' },
      { path: '/projects/tasks' },
    ] };
    expect(filterMenuRoute(projectGroup, [], ['project'])).toBeNull();
    const filtered = filterMenuRoute(projectGroup, [], ['project.stage']);
    expect(filtered?.routes?.map((item) => item.path)).toEqual(['/projects/phases']);
  });

  it('shows each experiment submenu from its business-role selection', () => {
    expect(requiredMenuForPath('/experiments/new')).toBe('eln.create');
    expect(requiredMenuForPath('/experiments/upload')).toBe('eln.upload');
    expect(requiredMenuForPath('/experiments/list')).toBe('eln.view');
    expect(canViewPathWithMenus('/experiments/new', [], ['eln.create'])).toBe(true);
    expect(canViewPathWithMenus('/experiments/upload', [], ['eln.upload'])).toBe(true);
    expect(canViewPathWithMenus('/experiments/list', [], ['eln.view'])).toBe(true);
    expect(canViewPathWithMenus('/experiments/new', [], ['eln.upload'])).toBe(false);
    expect(canViewPathWithMenus('/experiments/upload', [], ['eln.view'])).toBe(false);
  });
});
