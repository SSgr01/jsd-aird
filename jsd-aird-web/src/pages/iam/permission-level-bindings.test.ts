import { describe, expect, it } from 'vitest';

import type { PermissionBinding, PermissionDefinition } from '@/services/iam/iam-api';
import { levelBindingKey, updateLevelOperationBinding, updateLevelRowScopeBindings, updateUserRowScopeOverrides } from './permission-level-binding-utils';

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

  it('stores the row scope against enabled operations instead of the first operation', () => {
    const result = updateUserRowScopeOverrides({}, {}, 'project.list', definitions, new Set([view.code]), 'OWNER');

    expect(result[levelBindingKey('project.list', create.code)]).toBeUndefined();
    expect(result[levelBindingKey('project.list', view.code)]?.scopeType).toBe('OWNER');
  });

  it('removes a redundant override when the selected scope equals the inherited scope', () => {
    const key = levelBindingKey('project.list', view.code);
    const current: Record<string, PermissionBinding> = { [key]: { menuKey: 'project.list', permissionCode: view.code, effect: 'ALLOW', scopeType: 'OWNER', targetIds: [] } };
    const baselines: Record<string, PermissionBinding> = { [key]: { permissionCode: view.code, effect: 'ALLOW', scopeType: 'ALL', targetIds: [] } };
    const result = updateUserRowScopeOverrides(current, baselines, 'project.list', definitions, new Set([view.code]), 'ORG');

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

    expect(selected[viewKey]?.scopeType).toBe('ORG');
    expect(updateLevelRowScopeBindings(selected, 'project.list', definitions, 'OWNER')[viewKey]?.scopeType).toBe('OWNER');
  });

  it('uses the existing row scope when another operation is selected', () => {
    const viewSelected = updateLevelOperationBinding({}, 'project.list', view, true);
    const scoped = updateLevelRowScopeBindings(viewSelected, 'project.list', definitions, 'PARTICIPANT');
    const withCreate = updateLevelOperationBinding(scoped, 'project.list', create, true, 'PARTICIPANT');

    expect(withCreate[levelBindingKey('project.list', create.code)]?.scopeType).toBe('PARTICIPANT');
    expect(withCreate[levelBindingKey('project.list', view.code)]?.scopeType).toBe('PARTICIPANT');
  });
});
