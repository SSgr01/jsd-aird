import { describe, expect, it } from 'vitest';

import { canViewPathWithMenus, filterMenuRoute, firstAccessiblePath } from './route-permissions';

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
