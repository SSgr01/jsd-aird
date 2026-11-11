import { describe, expect, it } from 'vitest';

import { canViewPathWithMenus, filterMenuRoute, firstAccessiblePath, firstAccessibleSystemPath } from './route-permissions';

describe('knowledge workspace access', () => {
  it('allows the catalog workspace for viewers and reviewers', () => {
    expect(canViewPathWithMenus('/knowledge/view', ['knowledge.view'])).toBe(true);
    expect(canViewPathWithMenus('/knowledge/view', ['knowledge.review'])).toBe(true);
    expect(canViewPathWithMenus('/knowledge/view', [], [])).toBe(false);
  });

  it('does not fall back to legacy permissions when the menu list is explicitly empty', () => {
    const customer = { path: '/partners', name: '客户管理' };
    expect(canViewPathWithMenus('/partners', ['customer.view'], [])).toBe(false);
    expect(filterMenuRoute(customer, ['customer.view'], [])).toBeNull();
    expect(firstAccessiblePath(['customer.view'], [])).toBeNull();
    expect(canViewPathWithMenus('/partners', ['customer.view'])).toBe(true);
  });

  it('shows the knowledge view menu to review-only roles', () => {
    const menu = filterMenuRoute({
      path: '/knowledge',
      name: '研发知识库',
      routes: [
        { path: '/knowledge/library', name: '资料上传' },
        { path: '/knowledge/view', name: '知识库查看' },
      ],
    }, ['knowledge.review'], ['knowledge.review']);

    expect(menu?.routes).toEqual([{ path: '/knowledge/view', name: '知识库查看' }]);
  });

  it('routes review-only users to the unified workspace', () => {
    expect(firstAccessiblePath(['knowledge.review'], ['knowledge.review'])).toBe('/knowledge/view');
  });
});

describe('system settings view access', () => {
  it.each([
    ['/system/user-permissions', 'system.permissions.view'],
    ['/system/dictionary', 'system.dictionary.view'],
    ['/system/parameters', 'system.parameters.view'],
  ])('opens %s with its view permission', (path, permission) => {
    expect(canViewPathWithMenus(path, [permission])).toBe(true);
    expect(canViewPathWithMenus(path, [])).toBe(false);
  });
});

describe('system settings navigation', () => {
  const systemRoute = {
    path: '/system',
    name: '系统设置',
    routes: [
      { path: '/system/users', name: '用户管理' },
      { path: '/system/user-permissions', name: '用户权限配置' },
      { path: '/system/dictionary', name: '基础字典' },
      { path: '/system/audit-logs', name: '操作日志' },
      { path: '/system/parameters', name: '系统参数' },
    ],
  };

  it('hides the menu without a system grant', () => {
    expect(firstAccessibleSystemPath([], [])).toBeUndefined();
    expect(filterMenuRoute(systemRoute, [], [])).toBeNull();
  });

  it('respects menu grants and permission grants', () => {
    expect(firstAccessibleSystemPath([], ['system.permissions'])).toBe('/system/user-permissions');
    expect(firstAccessibleSystemPath(['system.user.view'], ['system.users'])).toBe('/system/users');
    expect(firstAccessibleSystemPath(['system.audit.view'], ['system.audit'])).toBe('/system/audit-logs');
    expect(firstAccessibleSystemPath(['system.user.view'], ['system.audit'])).toBe('/system/audit-logs');
  });
});

describe('page operation access', () => {
  it.each([
    ['/templates/library', 'template.view.create'],
    ['/experiments/new', 'eln.view.create'],
    ['/experiments/list', 'eln.view.copy'],
    ['/data/upload', 'data.view.create'],
    ['/research-test/reports', 'research-test.report.view.export'],
    ['/production-orders/list', 'production.view.download'],
    ['/quality/view', 'quality.view.update'],
    ['/spectrum/view', 'spectrum.view.delete'],
  ])('opens %s for a page operation grant', (path, permission) => {
    expect(canViewPathWithMenus(path, [permission])).toBe(true);
    expect(canViewPathWithMenus(path, [permission], [])).toBe(true);
    expect(canViewPathWithMenus(path, [], [])).toBe(false);
  });

  it.each([
    ['/templates/upload', 'template.upload.create'],
    ['/experiments/new', 'eln.create.create'],
    ['/experiments/upload', 'eln.upload.create'],
    ['/research-test/upload', 'research-test.report.upload.create'],
    ['/research-test/standard-upload', 'research-test.standard.upload.create'],
    ['/production-orders/upload', 'production.upload.create'],
    ['/data/upload', 'data.upload.create'],
    ['/quality/upload', 'quality.upload.create'],
    ['/spectrum/upload', 'spectrum.upload.create'],
    ['/knowledge/library', 'knowledge.upload.create'],
  ])('opens %s for its upload grant', (path, permission) => {
    expect(canViewPathWithMenus(path, [permission])).toBe(true);
    expect(canViewPathWithMenus(path, [permission], [])).toBe(true);
  });

  it.each([
    ['/templates/upload', 'template.view'],
    ['/experiments/new', 'experiment.view'],
    ['/experiments/upload', 'experiment.view'],
    ['/research-test/upload', 'research-test.report.view'],
    ['/research-test/standard-upload', 'research-test.standard.view'],
    ['/production-orders/upload', 'production.view'],
    ['/data/upload', 'data.view'],
    ['/quality/upload', 'quality.view'],
    ['/spectrum/upload', 'spectrum.view'],
    ['/knowledge/library', 'knowledge.view'],
    ['/knowledge/upload-tasks/task-1', 'knowledge.view'],
  ])('opens the read-only %s workspace with its module view grant', (path, viewPermission) => {
    expect(canViewPathWithMenus(path, [viewPermission])).toBe(true);
    expect(canViewPathWithMenus(path, [viewPermission], [])).toBe(true);
    expect(canViewPathWithMenus(path, [], [])).toBe(false);
  });

  it.each([
    ['/templates/upload', 'template.upload.download'],
    ['/experiments/upload', 'eln.upload.retry'],
    ['/research-test/upload', 'research-test.report.upload.delete'],
    ['/production-orders/upload', 'production.upload.download'],
    ['/knowledge/library', 'knowledge.upload.download'],
    ['/data/upload', 'data.upload.retry'],
    ['/quality/upload', 'quality.upload.download'],
  ])('opens %s for a record-only operation', (path, permission) => {
    expect(canViewPathWithMenus(path, [permission], [])).toBe(true);
    expect(canViewPathWithMenus(path, [], [])).toBe(false);
  });
});
