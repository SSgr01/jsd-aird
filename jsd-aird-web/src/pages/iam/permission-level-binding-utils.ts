import type { PermissionBinding, PermissionDefinition, PermissionLevelBinding } from '@/services/iam/iam-api';

// Legacy stored scopes are folded onto the four standard data scopes: "我负责/我管理/我参与"
// collapse into 我创建, and the object based scopes (PROJECT/CATEGORY) into 指定.
const normalizedScope = (value: string) => {
  if (['SELF', 'CREATOR', 'ASSIGNED', 'OWNER', 'MANAGER', 'PARTICIPANT', 'PROJECT_GROUP'].includes(value)) return 'CREATOR';
  if (['PROJECT', 'CATEGORY', 'SELECTED'].includes(value)) return 'SELECTED';
  return value || 'ORG';
};

export const levelBindingKey = (menuKey: string, permissionCode: string) => `${menuKey}::${permissionCode}`;

export const menuGrantsView = (menuKey: string, permissionCode: string, menuSelected: boolean) =>
  menuSelected && permissionCode === `${menuKey}.view`;

export const updateLevelOperationBinding = (
  current: Record<string, PermissionLevelBinding>,
  menuKey: string,
  definition: PermissionDefinition,
  checked: boolean,
  rowScopeType?: string,
  rowTargetIds: string[] = [],
) => {
  const next = { ...current };
  const key = levelBindingKey(menuKey, definition.code);
  if (checked) {
    const previous = current[key];
    const scopeType = normalizedScope(previous?.scopeType || rowScopeType || definition.defaultScope);
    const targetIds = previous?.targetIds?.length ? previous.targetIds : scopeType === 'SELECTED' ? rowTargetIds : [];
    next[key] = { menuKey, permissionCode: definition.code, effect: 'ALLOW', scopeType, targetIds };
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
  const nextScope = normalizedScope(scopeType);
  definitions.forEach((definition) => {
    const key = levelBindingKey(menuKey, definition.code);
    const binding = current[key];
    if (!binding) return;
    next[key] = { ...binding, scopeType: nextScope, ...(targetIds ? { targetIds } : {}) };
  });
  return next;
};

/**
 * Applies a row data scope to every operation of the row, independently from the
 * operations the user currently holds: a scope override alone grants nothing, it
 * only narrows the data range once the matching operation is allowed.
 */
export const updateUserRowScopeOverrides = (
  current: Record<string, PermissionBinding>,
  baselines: Record<string, PermissionBinding>,
  menuKey: string,
  definitions: PermissionDefinition[],
  scopeType: string,
  targetIds: string[] = [],
) => {
  const next = { ...current };
  const nextScope = normalizedScope(scopeType);
  definitions.forEach((definition) => {
    const key = levelBindingKey(menuKey, definition.code);
    const baselineScope = normalizedScope(baselines[key]?.scopeType || definition.defaultScope);
    if (nextScope === baselineScope && targetIds.length === 0) {
      delete next[key];
      return;
    }
    next[key] = { menuKey, permissionCode: definition.code, effect: 'ALLOW', scopeType: nextScope, targetIds };
  });
  return next;
};
