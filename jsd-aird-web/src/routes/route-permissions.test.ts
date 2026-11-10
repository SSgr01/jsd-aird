import { describe, expect, it } from 'vitest';

import { canViewPathWithMenus, filterMenuRoute, firstAccessiblePath, firstAccessibleSystemPath } from './route-permissions';

describe('knowledge workspace access', () => {
  it('allows the catalog workspace for viewers and reviewers', () => {
    expect(canViewPathWithMenus('/knowledge/view', ['knowledge.view'], [])).toBe(true);
    expect(canViewPathWithMenus('/knowledge/view', ['knowledge.review'], [])).toBe(true);
    expect(canViewPathWithMenus('/knowledge/view', [], [])).toBe(false);
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

describe('system settings visibility', () => {
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

  it('hides the entry and the menu group without system permissions', () => {
    expect(firstAccessibleSystemPath([], [])).toBeUndefined();
    expect(filterMenuRoute(systemRoute, [], [])).toBeNull();
  });

  it('shows the entry for a role with only one system menu grant', () => {
    expect(firstAccessibleSystemPath([], ['system.permissions'])).toBe('/system/user-permissions');
    expect(filterMenuRoute(systemRoute, [], ['system.permissions'])?.routes).toEqual([{ path: '/system/user-permissions', name: '用户权限配置' }]);
  });

  it('shows the entry for a role with only a permission grant', () => {
    expect(firstAccessibleSystemPath(['system.user.view'], [])).toBe('/system/users');
    expect(firstAccessibleSystemPath(['system.audit.view'], [])).toBe('/system/audit-logs');
    expect(firstAccessibleSystemPath(['system.dictionary.manage'], [])).toBe('/system/dictionary');
    expect(firstAccessibleSystemPath(['system.parameter.manage'], [])).toBe('/system/parameters');
  });

  it('uses menu grants for navigation visibility when they are present', () => {
    expect(firstAccessibleSystemPath(['system.user.view'], ['system.audit'])).toBe('/system/audit-logs');
  });
});
