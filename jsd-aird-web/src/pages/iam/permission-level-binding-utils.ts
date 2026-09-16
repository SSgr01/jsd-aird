import type { PermissionBinding, PermissionDefinition, PermissionLevelBinding } from '@/services/iam/iam-api';

const normalizedScope = (value: string) => value === 'ALL' ? 'ORG' : value === 'SELF' ? 'CREATOR' : value === 'ASSIGNED' ? 'OWNER' : value === 'PROJECT' ? 'PROJECT_GROUP' : value;

export const levelBindingKey = (menuKey: string, permissionCode: string) => `${menuKey}::${permissionCode}`;

export const updateLevelOperationBinding = (
  current: Record<string, PermissionLevelBinding>,
  menuKey: string,
  definition: PermissionDefinition,
  checked: boolean,
  rowScopeType?: string,
) => {
  const next = { ...current };
  const key = levelBindingKey(menuKey, definition.code);
  if (checked) {
    const previous = current[key];
    next[key] = {
      menuKey,
      permissionCode: definition.code,
      effect: 'ALLOW',
      scopeType: normalizedScope(previous?.scopeType || rowScopeType || definition.defaultScope),
      targetIds: previous?.targetIds || [],
    };
  } else {
    delete next[key];
  }
  return next;
};

export const updateLevelRowScopeBindings = (
  current: Record<string, PermissionLevelBinding>,
  menuKey: string,
  definitions: PermissionDefinition[],
  scopeType: string,
  targetIds?: string[],
) => {
  const next = { ...current };
  definitions.forEach((definition) => {
    const key = levelBindingKey(menuKey, definition.code);
    const binding = current[key];
    if (!binding) return;
    next[key] = { ...binding, scopeType, ...(targetIds ? { targetIds } : {}) };
  });
  return next;
};

export const updateUserRowScopeOverrides = (
  current: Record<string, PermissionBinding>,
  baselines: Record<string, PermissionBinding>,
  menuKey: string,
  definitions: PermissionDefinition[],
  enabledPermissionCodes: Set<string>,
  scopeType: string,
  targetIds: string[] = [],
) => {
  const next = { ...current };
  definitions.forEach((definition) => {
    if (!enabledPermissionCodes.has(definition.code)) return;
    const key = levelBindingKey(menuKey, definition.code);
    const baselineScope = normalizedScope(baselines[key]?.scopeType || definition.defaultScope);
    if (scopeType === baselineScope && targetIds.length === 0) {
      delete next[key];
      return;
    }
    next[key] = { menuKey, permissionCode: definition.code, effect: 'ALLOW', scopeType, targetIds };
  });
  return next;
};
