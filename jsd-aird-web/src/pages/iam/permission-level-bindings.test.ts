import { describe, expect, it } from 'vitest';

import type { PermissionBinding, PermissionDefinition } from '@/services/iam/iam-api';
import { menuGrantsView, levelBindingKey, updateLevelOperationBinding, updateLevelRowScopeBindings, updateUserRowScopeOverrides } from './permission-level-binding-utils';

describe('customer menu view inheritance', () => {
  it('checks only the matching view operation while the customer menu is selected', () => {
    expect(menuGrantsView('customer.list', 'customer.list.view', true)).toBe(true);
    expect(menuGrantsView('customer.list', 'customer.list.create', true)).toBe(false);
    expect(menuGrantsView('customer.list', 'customer.list.view', false)).toBe(false);
    expect(menuGrantsView('project.list', 'project.list.view', true)).toBe(true);
    expect(menuGrantsView('knowledge.review', 'knowledge.review.view', true)).toBe(true);
  });
});

const createDefinition = (code: string, defaultScope = 'SELF'): PermissionDefinition => ({
  code,
  module: 'project',
  name: code,
  risk: 'LOW',
  defaultScope,
});

describe('personal permission row scopes', () => {
  const create = createDefinition('project.create');
  const view = createDefinition('project.view', 'ALL');
  const definitions = [create, view];

  it('stores the row scope against every operation of the row, regardless of what is selected', () => {
    const result = updateUserRowScopeOverrides({}, {}, 'project.list', definitions, 'ORG');

    expect(result[levelBindingKey('project.list', create.code)]?.scopeType).toBe('ORG');
    expect(result[levelBindingKey('project.list', view.code)]?.scopeType).toBe('ORG');
  });

  it('folds legacy scopes and object scopes onto the four standard options', () => {
    const legacy = updateUserRowScopeOverrides({}, {}, 'project.list', definitions, 'OWNER');
    expect(legacy[levelBindingKey('project.list', view.code)]?.scopeType).toBe('CREATOR');

    const selected = updateUserRowScopeOverrides({}, {}, 'project.list', definitions, 'CATEGORY', ['c1']);
    expect(selected[levelBindingKey('project.list', view.code)]?.scopeType).toBe('SELECTED');
    expect(selected[levelBindingKey('project.list', create.code)]?.targetIds).toEqual(['c1']);
  });

  it('removes a redundant override when the selected scope equals the inherited scope', () => {
    const key = levelBindingKey('project.list', view.code);
    const current: Record<string, PermissionBinding> = {};
    const baselines: Record<string, PermissionBinding> = { [key]: { permissionCode: view.code, effect: 'ALLOW', scopeType: 'ALL', targetIds: [] } };
    const result = updateUserRowScopeOverrides(current, baselines, 'project.list', [view], 'ALL');

    expect(result[key]).toBeUndefined();
  });
});

describe('permission level row scopes', () => {
  const create = createDefinition('project.create');
  const view = createDefinition('project.view', 'ALL');
  const definitions = [create, view];

  it('enables and updates the row scope when a non-first operation is selected', () => {
    const selected = updateLevelOperationBinding({}, 'project.list', view, true);
    const viewKey = levelBindingKey('project.list', view.code);

    expect(selected[viewKey]?.scopeType).toBe('ALL');
    expect(updateLevelRowScopeBindings(selected, 'project.list', definitions, 'CREATOR')[viewKey]?.scopeType).toBe('CREATOR');
  });

  it('uses the existing row scope when another operation is selected', () => {
    const viewSelected = updateLevelOperationBinding({}, 'project.list', view, true);
    const scoped = updateLevelRowScopeBindings(viewSelected, 'project.list', definitions, 'CREATOR');
    const withCreate = updateLevelOperationBinding(scoped, 'project.list', create, true, 'CREATOR');

    expect(withCreate[levelBindingKey('project.list', create.code)]?.scopeType).toBe('CREATOR');
    expect(withCreate[levelBindingKey('project.list', view.code)]?.scopeType).toBe('CREATOR');
  });

  it('carries the row 指定 targets into newly selected operations', () => {
    const withCreate = updateLevelOperationBinding({}, 'project.list', create, true, 'SELECTED', ['p1']);

    expect(withCreate[levelBindingKey('project.list', create.code)]?.scopeType).toBe('SELECTED');
    expect(withCreate[levelBindingKey('project.list', create.code)]?.targetIds).toEqual(['p1']);
  });
});
