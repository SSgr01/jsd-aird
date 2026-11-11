import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import { App, Input, Modal, Select, Spin, Tag, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';

import { HttpError } from '@/services/http/errors';
import { iamApi, type IamRole, type IamUser, type MenuOverride, type PermissionBinding, type PermissionDefinition, type PermissionLevelBinding, type PermissionMenu, type PermissionModel, type UserPermissionModel } from '@/services/iam/iam-api';
import { useAuthStore } from '@/stores/auth-store';
import { riskDisplayLabel } from '@/routes/route-permissions';
import { PermissionTargetPicker } from './PermissionTargetPicker';
import { menuGrantsView, levelBindingKey, updateLevelOperationBinding, updateLevelRowScopeBindings, updateUserRowScopeOverrides } from './permission-level-binding-utils';
import './iam.css';

type PermissionMode = 'user' | 'role' | 'level';

interface PermissionManagementPageProps {
  initialMode?: PermissionMode;
}

interface PermissionMatrixRow {
  menu: PermissionMenu;
  operations: PermissionDefinition[];
}

// Row level data scope: the operation that carries the stored value plus that value.
interface RowScopeSetting {
  definition: PermissionDefinition;
  scopeType: string;
  targetIds: string[];
}

const scopeOptions = [
  { value: 'ALL', label: '全部' },
  { value: 'CREATOR', label: '我创建' },
  { value: 'DEPARTMENT', label: '本部门' },
  { value: 'SELECTED', label: '指定' },
];
const projectTargetMenus = new Set(['eln.view', 'research-test.report.view', 'quality.view', 'production.view']);
const selectedScopeLabels: Record<string, string> = {
  'project.list': '指定项目及我创建',
  'customer.list': '指定客户及我创建',
  'template.view': '指定模板及我创建',
  'template.upload': '指定文件及我创建',
  'eln.upload': '指定文件及我创建',
  'research-test.report.upload': '指定文件及我创建',
  'research-test.standard.upload': '指定文件及我创建',
  'quality.upload': '指定文件及我创建',
  'production.upload': '指定文件及我创建',
  'spectrum.view': '指定文件及我创建',
  'knowledge.view': '指定文件及我创建',
  'data.view': '指定文件及我创建',
  'research-test.standard': '指定文件及我创建',
};

// Modules / functional rows that do not offer the 指定 data scope.
const modulesWithoutSelectedScope = new Set(['system']);
const menusWithoutSelectedScope = new Set<string>();
// 用户管理 / 用户权限配置的数据对象是用户，支持本部门范围，但不提供「指定」。
const userScopeMenus = new Set(['system.users', 'system.permissions']);
const isUserScopeRow = (menuKey: string | null | undefined, permissionCode: string) =>
  (!!menuKey && userScopeMenus.has(menuKey))
  || [...userScopeMenus].some((key) => permissionCode.startsWith(`${key}.`));
// 历史遗留的「指定」在这两行归一为全部：界面不会出现无选中项，保存时也会清掉旧配置。
const rowScopeValue = (menuKey: string, value: string) =>
  userScopeMenus.has(menuKey) && normalizedScope(value) === 'SELECTED' ? 'ALL' : normalizedScope(value);
const withoutSelectedScope = <T extends PermissionBinding | PermissionLevelBinding>(binding: T): T =>
  isUserScopeRow(binding.menuKey, binding.permissionCode) && normalizedScope(binding.scopeType) === 'SELECTED'
    ? { ...binding, scopeType: 'ALL', targetIds: [] }
    : binding;
const dataScopeOptionsFor = (menuKey: string, definition: PermissionDefinition, grant?: { scopeType: string; targetIds: string[] }, unrestricted = true) => {
  const withoutSelected = modulesWithoutSelectedScope.has(definition.module) || menusWithoutSelectedScope.has(menuKey);
  return scopeOptions.filter((option) =>
    (option.value !== 'SELECTED' || !withoutSelected)
    && (unrestricted || grant?.scopeType === 'ALL' || grant?.scopeType === 'ORG' || normalizedScope(grant?.scopeType || '') === option.value)
    && (option.value !== 'DEPARTMENT' || userScopeMenus.has(menuKey)
      || ['quality.view', 'quality.upload', 'spectrum.view', 'production.view', 'production.upload', 'knowledge.view', 'data.view'].includes(menuKey)
      || ['customer', 'project', 'experiment', 'research-test', 'template', 'inventory'].includes(definition.module)))
    .map((option) => option.value === 'SELECTED'
      ? { ...option, label: selectedScopeLabels[menuKey]
          || (projectTargetMenus.has(menuKey) ? '指定项目及我创建' : menuKey.startsWith('inventory.') ? '指定产品及我创建' : '指定及我创建') }
      : option);
};

// Legacy stored scopes are folded onto the four standard options: "我负责/我管理/我参与"
// collapse into 我创建, and the object based scopes (PROJECT/CATEGORY) into 指定.
const normalizedScope = (value: string) => {
  if (['SELF', 'CREATOR', 'ASSIGNED', 'OWNER', 'MANAGER', 'PARTICIPANT', 'PROJECT_GROUP'].includes(value)) return 'CREATOR';
  if (['PROJECT', 'CATEGORY', 'SELECTED'].includes(value)) return 'SELECTED';
  if (value === 'ORG') return 'ALL';
  return value || 'ALL';
};
const standardOperationSpecs = [
  { suffix: 'view', label: '查看', risk: 'LOW', defaultScope: 'ALL' },
  { suffix: 'create', label: '新增', risk: 'MEDIUM', defaultScope: 'ALL' },
  { suffix: 'update', label: '编辑', risk: 'MEDIUM', defaultScope: 'ALL' },
  { suffix: 'delete', label: '删除', risk: 'HIGH', defaultScope: 'ALL' },
  { suffix: 'submit', label: '提交审核', risk: 'MEDIUM', defaultScope: 'ALL' },
  { suffix: 'approve', label: '审核', risk: 'HIGH', defaultScope: 'ALL' },
  { suffix: 'publish', label: '发布', risk: 'HIGH', defaultScope: 'ALL' },
] as const;
const menuModuleKey = (menu: PermissionMenu) => menu.parent === 'eln' ? 'experiment' : menu.parent || menu.key.split('.')[0] || menu.key;
// Modules / functional rows whose operation list has no approval or publishing step.
const modulesWithoutApproveAndPublish = new Set(['customer', 'ai', 'inventory', 'system']);
const menusWithoutApproveAndPublish = new Set([
  'template.upload',
  'eln.create',
  'eln.upload',
  'research-test.report.upload',
  'research-test.standard.upload',
  'quality.upload',
  'spectrum.upload',
  'spectrum.ai',
  'production.upload',
  'knowledge.upload',
  'data.upload',
  'project.list',
  'project.stage',
  'project.task',
  'project.customer-needs',
  'project.reference',
  'project.log',
  'project.materials',
]);
// 基础字典、操作日志、系统参数配置是组织级配置与审计能力，不存在可划分的数据对象，
// 因此不提供任何数据范围权限；历史配置在保存时一并清除。
const dataScopeFreeMenus = ['system.dictionary', 'system.audit', 'system.parameters'];
const isDataScopeFree = (menuKey: string | null | undefined, permissionCode: string) =>
  (!!menuKey && dataScopeFreeMenus.includes(menuKey))
  || dataScopeFreeMenus.some((key) => permissionCode.startsWith(`${key}.`));
const withoutDataScope = <T extends PermissionBinding | PermissionLevelBinding>(binding: T): T =>
  isDataScopeFree(binding.menuKey, binding.permissionCode)
    ? { ...binding, scopeType: 'ALL', targetIds: [] }
    : binding;
const withoutOwnProjectTargets = <T extends PermissionBinding | PermissionLevelBinding>(binding: T): T =>
  binding.menuKey && projectTargetMenus.has(binding.menuKey) && binding.scopeType === 'SELECTED'
    ? { ...binding, targetIds: [] } : binding;
// Functional rows that do not carry a data scope at all.
const menusWithoutDataScope = new Set([
  ...dataScopeFreeMenus,
  'customer.overview',
  'customer.needs',
  'customer.projects',
  'customer.followup',
  'project.stage',
  'project.task',
  'project.document',
  'project.customer-needs',
  'project.reference',
  'project.meeting',
  'project.log',
  'project.materials',
  'spectrum.ai',
  'ai.search',
  'ai.qa',
  'ai.formula',
  'ai.analysis',
  'ai.optimization',
  'ai.model',
  'knowledge.review',
  'knowledge.upload',
  'data.upload',
  'eln.create',
  'spectrum.upload',
  // Category maintenance configures organization level classification; it is
  // granted or revoked as a capability, so no data scope applies.
  'template.category',
  'eln.category',
  'quality.category',
  'spectrum.category',
  'knowledge.category',
  'data.category',
]);
// Category maintenance rows are operation-only: they never own a page entry, so
// they are configured per user/level but never appear in the business-role list.
const categoryMenus = new Set(['template.category', 'eln.category', 'quality.category', 'spectrum.category', 'knowledge.category', 'data.category']);
const isCategoryMenu = (menuKey: string) => categoryMenus.has(menuKey);
// Functional rows that only expose a single operation.
const viewOnlyMenus = new Set(['ai.search', 'ai.qa']);
const queryOnlyMenus = new Set(['customer.projects']);
const menusWithoutUpdate = new Set(['project.reference', 'eln.upload', 'knowledge.upload', 'data.upload']);
// 研发/生产库存台账只支持冲销，不提供删除动作，权限矩阵不再展示「删除」。
const menusWithoutDelete = new Set(['inventory.rnd', 'inventory.production']);
const submitReviewMenus = new Set([
  'project.document', 'template.view', 'eln.view', 'research-test.report.view',
  'research-test.standard', 'quality.view', 'production.view',
]);
const approveOnlyMenus = new Set(['knowledge.review']);
// Operation codes that were retired from a feature. Definitions are append-only in
// the database, so they are hidden here even when an existing environment still
// reports them.
const retiredOperationCodes = new Set(['spectrum.view.publish']);
const standardOperationsFor = (menu: PermissionMenu, availableCodes: Set<string>): PermissionDefinition[] => {
  const module = menuModuleKey(menu);
  const withoutApproveAndPublish = modulesWithoutApproveAndPublish.has(module) || menusWithoutApproveAndPublish.has(menu.key);
  const specs = viewOnlyMenus.has(menu.key) || queryOnlyMenus.has(menu.key)
    ? standardOperationSpecs.filter((spec) => spec.suffix === 'view')
    : approveOnlyMenus.has(menu.key)
      ? standardOperationSpecs.filter((spec) => spec.suffix === 'view' || spec.suffix === 'approve')
      : withoutApproveAndPublish
        ? standardOperationSpecs.filter((spec) => spec.suffix !== 'approve' && spec.suffix !== 'publish')
        : standardOperationSpecs;
  return specs.filter((spec) =>
    (spec.suffix !== 'submit' || submitReviewMenus.has(menu.key))
    && !(menusWithoutUpdate.has(menu.key) && spec.suffix === 'update')
    && !(menusWithoutDelete.has(menu.key) && spec.suffix === 'delete'),
  ).map((spec) => ({
    code: `${menu.key}.${spec.suffix}`,
    module,
    name: menu.key === 'project.meeting' && spec.suffix === 'publish' ? '归档' : spec.label,
    risk: spec.risk,
    defaultScope: spec.defaultScope,
  })).filter((definition) => availableCodes.has(definition.code) && !retiredOperationCodes.has(definition.code));
};
const uniqueBindings = (bindings: PermissionBinding[]) => [...new Map(bindings.map((binding) => [levelBindingKey(binding.menuKey || '', binding.permissionCode), binding])).values()];
const menuDescription = (menu: PermissionMenu) => menu.parent
  ? '二级功能/区域；入口可独立开放，不随一级模块联动'
  : '一级模块；入口可独立开放，不随二级功能联动';
function PermissionScopeChoices({
  value,
  options,
  disabled,
  onChange,
}: {
  value: string;
  options: typeof scopeOptions;
  disabled?: boolean;
  onChange: (value: string) => void;
}) {
  const selected = normalizedScope(value);
  return <div className="permission-v4-checks permission-v4-scope-choices" role="group" aria-label="数据范围权限">
    {options.map((option) => <label className="permission-v4-check" key={option.value}>
      <input type="checkbox" checked={selected === option.value} disabled={disabled} onChange={(event) => { if (event.target.checked) onChange(option.value); }} />
      <span>{option.label}</span>
    </label>)}
  </div>;
}

// 权限配置页早期只发放统一的 *.manage 编码，后端仍然接受；这里同步放行，
// 避免界面隐藏后端其实允许的操作。
const legacyPermissionCodes: Record<'create' | 'update' | 'delete', readonly string[]> = {
  create: ['system.role.create', 'system.permission.manage'],
  update: ['system.role.manage', 'system.permission.manage'],
  delete: ['system.role.manage', 'system.permission.manage'],
};

function PermissionButton({
  children,
  className = '',
  danger = false,
  primary = false,
  disabled = false,
  loading = false,
  onClick,
  permission,
}: {
  children: React.ReactNode;
  className?: string;
  danger?: boolean;
  primary?: boolean;
  disabled?: boolean;
  loading?: boolean;
  onClick?: () => void;
  permission?: 'create' | 'update' | 'delete';
}) {
  const permissions = useAuthStore((state) => state.user?.permissions);
  const allowed = !permission
    || (permissions?.includes(`system.permissions.${permission}`) ?? false)
    || legacyPermissionCodes[permission].some((code) => permissions?.includes(code) ?? false);
  if (!allowed) return null;
  return <button type="button" className={`permission-v4-btn${primary ? ' primary' : ''}${danger ? ' danger' : ''}${className ? ` ${className}` : ''}`} disabled={disabled || loading} onClick={onClick}>
    {loading ? '保存中…' : children}
  </button>;
}

export function PermissionManagementPage({ initialMode = 'user' }: PermissionManagementPageProps) {
  const { message } = App.useApp();
  const [mode, setMode] = useState<PermissionMode>(initialMode);
  const [users, setUsers] = useState<IamUser[]>([]);
  const [roles, setRoles] = useState<IamRole[]>([]);
  const [model, setModel] = useState<PermissionModel>();
  const [activeUserId, setActiveUserId] = useState<string>();
  const [userModel, setUserModel] = useState<UserPermissionModel>();
  const [roleIds, setRoleIds] = useState<string[]>([]);
  const [levelId, setLevelId] = useState<string>();
  const [menuOverrides, setMenuOverrides] = useState<Record<string, MenuOverride>>({});
  const [operationOverrides, setOperationOverrides] = useState<Record<string, PermissionBinding>>({});
  const [scopeOverrides, setScopeOverrides] = useState<Record<string, PermissionBinding>>({});
  const [userLevelBindings, setUserLevelBindings] = useState<Record<string, PermissionBinding>>({});
  const [roleMenuBaselines, setRoleMenuBaselines] = useState<Record<string, string[]>>({});
  const [activeRoleId, setActiveRoleId] = useState<string>();
  const [roleMenus, setRoleMenus] = useState<string[]>([]);
  const [roleVersion, setRoleVersion] = useState(0);
  const [activeLevelId, setActiveLevelId] = useState<string>();
  const [levelBindings, setLevelBindings] = useState<Record<string, PermissionLevelBinding>>({});
  // Data scopes of rows without any selected operation are kept locally so the
  // column stays configurable no matter which operations are granted.
  const [levelScopeDrafts, setLevelScopeDrafts] = useState<Record<string, { scopeType: string; targetIds: string[] }>>({});
  const [levelVersion, setLevelVersion] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const activeUser = users.find((user) => user.id === activeUserId);
  const activeRole = roles.find((role) => role.id === activeRoleId);
  const activeLevel = model?.levels.find((level) => level.id === activeLevelId);
  const currentUserId = useAuthStore((state) => state.user?.userId);
  const editingSelf = activeUserId === currentUserId;
  const editingOwnRole = !!activeRoleId && (model?.ownRoleIds ?? []).includes(activeRoleId);
  const editingOwnLevel = !!activeLevelId && model?.ownLevelId === activeLevelId;

  const matrixRows = useMemo(() => {
    const availableCodes = new Set((model?.operations ?? []).map((definition) => definition.code));
    return (model?.menus ?? []).map<PermissionMatrixRow>((menu) => ({
      menu,
      operations: menu.parent ? standardOperationsFor(menu, availableCodes) : [],
    }));
  }, [model]);

  const load = async () => {
    setLoading(true);
    try {
      const [userPage, roleItems, permissionModel] = await Promise.all([
        iamApi.users({ size: 100, permissionView: 'permissions' }),
        iamApi.roles(),
        iamApi.permissionModel(),
      ]);
      setUsers(userPage.items);
      setRoles(roleItems);
      setModel(permissionModel);
      setActiveUserId((current) => current && userPage.items.some((user) => user.id === current) ? current : userPage.items[0]?.id);
      setActiveRoleId((current) => current && roleItems.some((role) => role.id === current) ? current : roleItems[0]?.id);
      setActiveLevelId((current) => current && permissionModel.levels.some((level) => level.id === current) ? current : permissionModel.levels[0]?.id);
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '权限模型加载失败');
    } finally {
      setLoading(false);
    }
  };

  const applyUserModel = (data: UserPermissionModel) => {
    setUserModel(data);
    setRoleIds(data.roles.map((role) => role.id));
    setLevelId(data.level?.id);
    setMenuOverrides(Object.fromEntries(data.menuOverrides.map((item) => [item.menuKey, item])));
    setOperationOverrides(Object.fromEntries(data.operationOverrides.flatMap((item) => item.menuKey
      ? [[levelBindingKey(item.menuKey, item.permissionCode), item] as const]
      : matrixRows
      .filter((row) => row.operations.some((definition) => definition.code === item.permissionCode))
      .map((row) => [levelBindingKey(row.menu.key, item.permissionCode), { ...item, menuKey: row.menu.key }] as const))));
    setScopeOverrides(Object.fromEntries(data.scopeOverrides
      .filter((item) => !isDataScopeFree(item.menuKey, item.permissionCode))
      .flatMap((item) => item.menuKey
      ? [[levelBindingKey(item.menuKey, item.permissionCode), item] as const]
      : matrixRows
      .filter((row) => row.operations.some((definition) => definition.code === item.permissionCode))
      .map((row) => [levelBindingKey(row.menu.key, item.permissionCode), { ...item, menuKey: row.menu.key }] as const))));
  };

  const loadUserModel = async (reportError = true) => {
    if (!activeUserId) return;
    try {
      applyUserModel(await iamApi.userModel(activeUserId));
    } catch (error) {
      if (reportError) {
        message.error(error instanceof HttpError ? error.message : '个人权限模型加载失败');
        return;
      }
      throw error;
    }
  };

  const loadRoleMenuBaseline = async () => {
    if (!roleIds.length) {
      setRoleMenuBaselines({});
      return;
    }
    try {
      const entries = await Promise.all(roleIds.map(async (roleId) => [roleId, (await iamApi.roleMenus(roleId)).menuKeys] as const));
      setRoleMenuBaselines(Object.fromEntries(entries));
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '业务角色菜单加载失败');
    }
  };

  const loadUserLevelBaseline = async () => {
    if (!levelId) {
      setUserLevelBindings({});
      return;
    }
    try {
      const result = await iamApi.permissionLevelPermissions(levelId);
      setUserLevelBindings(Object.fromEntries(result.bindings.flatMap((item) => {
        const binding = { permissionCode: item.permissionCode, effect: item.effect, scopeType: item.scopeType, targetIds: item.targetIds } satisfies PermissionBinding;
        if (item.menuKey) return [[levelBindingKey(item.menuKey, item.permissionCode), binding] as const];
        return matrixRows.filter((row) => row.operations.some((definition) => definition.code === item.permissionCode))
          .map((row) => [levelBindingKey(row.menu.key, item.permissionCode), binding] as const);
      })));
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '权限等级基线加载失败');
    }
  };

  const loadRoleMenus = async () => {
    if (!activeRoleId) return;
    try {
      const result = await iamApi.roleMenus(activeRoleId);
      setRoleMenus(result.menuKeys);
      setRoleVersion(result.version);
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '业务角色菜单加载失败');
    }
  };

  const loadLevelBindings = async () => {
    if (!activeLevelId) return;
    try {
      const result = await iamApi.permissionLevelPermissions(activeLevelId);
      const entries = result.bindings.flatMap((item) => {
        if (item.menuKey) return [[levelBindingKey(item.menuKey, item.permissionCode), item] as const];
        const rows = matrixRows.filter((row) => row.operations.some((definition) => definition.code === item.permissionCode));
        return rows.map((row) => [levelBindingKey(row.menu.key, item.permissionCode), { ...item, menuKey: row.menu.key }] as const);
      });
      setLevelBindings(Object.fromEntries(entries));
      setLevelScopeDrafts({});
      setLevelVersion(result.version);
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '权限等级加载失败');
    }
  };

  useEffect(() => { void load(); }, []);
  useEffect(() => { void loadUserModel(); }, [activeUserId]);
  useEffect(() => { void loadRoleMenuBaseline(); }, [roleIds.join('|')]);
  useEffect(() => { void loadUserLevelBaseline(); }, [levelId, matrixRows]);
  useEffect(() => { void loadRoleMenus(); }, [activeRoleId]);
  useEffect(() => { void loadLevelBindings(); }, [activeLevelId, matrixRows]);

  const saveUser = async () => {
    if (!activeUserId || !userModel) return;
    setSaving(true);
    try {
      await iamApi.saveUserModel(activeUserId, {
        expectedVersion: userModel.authVersion,
        roleIds,
        primaryRoleId: roleIds[0],
        permissionLevelId: levelId,
        menus: Object.values(menuOverrides),
        operations: uniqueBindings(Object.values(operationOverrides))
          .map(withoutDataScope).map(withoutSelectedScope),
        scopes: uniqueBindings(Object.values(scopeOverrides))
          .filter((item) => !isDataScopeFree(item.menuKey, item.permissionCode))
          .map(withoutSelectedScope).map(withoutOwnProjectTargets),
      });
      await loadUserModel(false);
      await useAuthStore.getState().load();
      message.success(`${activeUser?.displayName || '用户'}的个人权限已保存`);
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '保存失败，可能已被其他管理员修改');
    } finally {
      setSaving(false);
    }
  };

  const saveRole = async () => {
    if (!activeRoleId) return;
    setSaving(true);
    try {
      const result = await iamApi.saveRoleMenus(activeRoleId, roleVersion, roleMenus);
      setRoleVersion(result.version);
      setRoleMenuBaselines((current) => ({ ...current, [activeRoleId]: roleMenus }));
      await useAuthStore.getState().load();
      message.success('业务角色菜单已保存');
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '保存失败，请刷新后重试');
    } finally {
      setSaving(false);
    }
  };

  const saveLevel = async () => {
    if (!activeLevelId) return;
    setSaving(true);
    try {
      const result = await iamApi.savePermissionLevelPermissions(activeLevelId, levelVersion,
        Object.values(levelBindings).map(withoutDataScope).map(withoutSelectedScope).map(withoutOwnProjectTargets));
      setLevelVersion(result.version);
      await loadLevelBindings();
      await useAuthStore.getState().load();
      message.success('权限等级已保存，已分配该等级的用户重新登录后生效');
    } catch (error) {
      message.error(error instanceof HttpError ? error.message : '保存失败，请刷新后重试');
    } finally {
      setSaving(false);
    }
  };

  const resetUserOverrides = () => {
    setMenuOverrides({});
    setOperationOverrides({});
    setScopeOverrides({});
    message.info('已清除个人覆盖，保存后恢复当前业务角色与权限等级的默认结果');
  };

  const cancelUserChanges = async () => {
    await loadUserModel();
    message.info('未保存的个人权限变更已取消');
  };

  const resetRole = () => {
    const locked = activeRole?.code === 'SYSTEM_ADMIN';
    setRoleMenus(locked ? (model?.menus ?? []).map((menu) => menu.key) : []);
    message.info(locked ? '系统管理员固定开放全部功能入口' : activeRole?.builtin ? '已清空当前内置角色的菜单变更，请保存后生效' : '已清空当前业务角色菜单，请保存后生效');
  };

  const resetLevel = () => {
    setLevelBindings({});
    setLevelScopeDrafts({});
    message.info(activeLevel?.builtin ? '已清空当前内置等级的编辑结果，请保存后生效' : '已清空当前权限等级，请保存后生效');
  };

  const createRole = () => {
    let code = '';
    let name = '';
    Modal.confirm({
      title: '新增业务角色',
      content: <div className="permission-v4-modal-fields"><label>角色编码<Input placeholder="例如 LAB_REVIEWER" onChange={(event) => { code = event.target.value; }} /></label><label>角色名称<Input placeholder="例如 技术负责人" onChange={(event) => { name = event.target.value; }} /></label><span>新增角色默认不开放任何菜单，请创建后在业务角色模式中配置。</span></div>,
      onOk: async () => {
        if (!code.trim() || !name.trim()) { message.error('角色编码和角色名称不能为空'); return Promise.reject(new Error('invalid')); }
        try {
          const role = await iamApi.createRole({ code: code.trim(), name: name.trim() });
          await load();
          setActiveRoleId(role.id);
          setMode('role');
          message.success('业务角色已创建');
        } catch (error) {
          message.error(error instanceof HttpError ? error.message : '创建失败');
          throw error;
        }
      },
    });
  };

  const renameRole = () => {
    if (!activeRole || activeRole.builtin) return;
    let name = activeRole.name;
    Modal.confirm({ title: '重命名业务角色', content: <Input defaultValue={name} onChange={(event) => { name = event.target.value; }} />, onOk: async () => { await iamApi.renameRole(activeRole.id, name.trim(), activeRole.policyVersion); await load(); message.success('角色名称已更新'); } });
  };

  const deleteRole = () => {
    if (!activeRole || activeRole.builtin) return;
    Modal.confirm({ title: '删除业务角色', content: '删除后会从已绑定用户中移除该角色，用户的权限等级和个人覆盖保留。', okType: 'danger', onOk: async () => { await iamApi.deleteRole(activeRole.id, activeRole.policyVersion); await load(); message.success('业务角色已删除'); } });
  };

  const createLevel = () => {
    let code = '';
    let name = '';
    let description = '';
    Modal.confirm({
      title: '新增权限等级',
      content: <div className="permission-v4-modal-fields"><label>等级编码<Input placeholder="例如 LAB_REVIEW" onChange={(event) => { code = event.target.value; }} /></label><label>等级名称<Input placeholder="例如 L4 专项管理级" onChange={(event) => { name = event.target.value; }} /></label><label>等级说明<Input placeholder="说明该等级适用人员及权限边界" onChange={(event) => { description = event.target.value; }} /></label><span>新增等级默认不开放操作权限，数据范围也为空。</span></div>,
      onOk: async () => {
        if (!code.trim() || !name.trim()) { message.error('等级编码和名称不能为空'); return Promise.reject(new Error('invalid')); }
        try {
          const level = await iamApi.createPermissionLevel({ code: code.trim(), name: name.trim(), description: description.trim(), tier: 1 });
          await load();
          setActiveLevelId(level.id);
          setMode('level');
          message.success('权限等级已创建');
        } catch (error) {
          message.error(error instanceof HttpError ? error.message : '创建失败');
          throw error;
        }
      },
    });
  };

  const renameLevel = () => {
    if (!activeLevel || activeLevel.builtin) return;
    let name = activeLevel.name;
    let description = activeLevel.description || '';
    Modal.confirm({ title: '重命名权限等级', content: <div className="permission-v4-modal-fields"><label>等级名称<Input defaultValue={name} onChange={(event) => { name = event.target.value; }} /></label><label>等级说明<Input defaultValue={description} onChange={(event) => { description = event.target.value; }} /></label></div>, onOk: async () => { await iamApi.renamePermissionLevel(activeLevel.id, { name: name.trim(), description: description.trim() }); await load(); message.success('权限等级已更新'); } });
  };

  const deleteLevel = () => {
    if (!activeLevel || activeLevel.builtin) return;
    Modal.confirm({ title: '删除权限等级', content: '删除后会清空用户对此等级的分配，但保留用户的业务角色和个人覆盖。', okType: 'danger', onOk: async () => { await iamApi.deletePermissionLevel(activeLevel.id); await load(); message.success('权限等级已删除'); } });
  };

  const userRoleMenuSet = useMemo(() => new Set(roleIds.flatMap((roleId) => roleMenuBaselines[roleId] || [])), [roleIds, roleMenuBaselines]);
  const roleMenuSet = useMemo(() => new Set(roleMenus), [roleMenus]);

  const menuValue = (menuKey: string) => {
    const override = menuOverrides[menuKey];
    return override ? override.effect === 'ALLOW' : userRoleMenuSet.has(menuKey);
  };
  const roleMenuValue = (menu: PermissionMenu) => roleMenuSet.has(menu.key);
  const updateMenuOverride = (key: string, checked: boolean) => {
    const base = userRoleMenuSet.has(key);
    setMenuOverrides((current) => {
      const next = { ...current };
      if (checked === base) delete next[key];
      else next[key] = { menuKey: key, effect: checked ? 'ALLOW' : 'DENY' };
      return next;
    });
  };
  const togglePersonalMenu = (menu: PermissionMenu, checked: boolean) => updateMenuOverride(menu.key, checked);
  const toggleRoleMenu = (menu: PermissionMenu, checked: boolean) => {
    setRoleMenus((current) => {
      const next = new Set(current);
      if (checked) next.add(menu.key);
      else next.delete(menu.key);
      return [...next];
    });
  };

  const userOperationValue = (menuKey: string, definition: PermissionDefinition) => {
    const key = levelBindingKey(menuKey, definition.code);
    const override = operationOverrides[key];
    const menuView = menuGrantsView(menuKey, definition.code, menuValue(menuKey));
    return override ? override.effect === 'ALLOW' : menuView || userLevelBindings[key]?.effect === 'ALLOW';
  };
  const setOperationOverride = (menuKey: string, definition: PermissionDefinition, checked: boolean, rowScope?: RowScopeSetting) => {
    const key = levelBindingKey(menuKey, definition.code);
    const baseline = menuGrantsView(menuKey, definition.code, menuValue(menuKey))
      || userLevelBindings[key]?.effect === 'ALLOW';
    setOperationOverrides((current) => {
      const next = { ...current };
      if (checked === baseline) delete next[key];
      else next[key] = {
        menuKey,
        permissionCode: definition.code,
        effect: checked ? 'ALLOW' : 'DENY',
        scopeType: normalizedScope(rowScope?.scopeType || current[key]?.scopeType || userLevelBindings[key]?.scopeType || definition.defaultScope),
        targetIds: checked && rowScope?.scopeType === 'SELECTED' ? rowScope.targetIds : [],
      };
      return next;
    });
  };
  const renderSource = (overridden: boolean, inherited: boolean) => <span className={`permission-v4-source ${overridden ? 'manual' : 'inherit'}`}>{overridden ? '个人覆盖' : inherited ? '业务角色继承' : '手工配置'}</span>;
  const renderMenuCell = (row: PermissionMatrixRow, personal: boolean) => {
    const checked = personal ? menuValue(row.menu.key) : roleMenuValue(row.menu);
    const overridden = personal && Boolean(menuOverrides[row.menu.key]);
    const inherited = personal && roleIds.length > 0;
    return <div className={`permission-v4-function ${row.menu.parent ? 'child' : 'parent'}${overridden ? ' overridden' : ''}`}>
      <input type="checkbox" checked={checked} disabled={personal ? editingSelf : activeRole?.code === 'SYSTEM_ADMIN' || editingOwnRole} onChange={(event) => personal ? togglePersonalMenu(row.menu, event.target.checked) : toggleRoleMenu(row.menu, event.target.checked)} />
      <span className="permission-v4-tree">{row.menu.parent ? '└─' : ''}</span>
      <span className="permission-v4-function-label">{row.menu.label}</span>
      {personal && renderSource(overridden, inherited)}
    </div>;
  };

  // The data scope is a row level setting: it is read from the row's own stored value and is
  // never re-derived from whichever operations happen to be granted.
  const personalRowScope = (menuKey: string): RowScopeSetting | undefined => {
    const row = matrixRows.find((item) => item.menu.key === menuKey);
    if (!row?.operations.length || menusWithoutDataScope.has(menuKey)) return undefined;
    const anchor = row.operations[0]!;
    const stored = row.operations
      .map((definition) => {
        const key = levelBindingKey(menuKey, definition.code);
        const override = scopeOverrides[key];
        const inherited = userLevelBindings[key];
        if (!override && !inherited) return undefined;
        return {
          definition,
          scopeType: rowScopeValue(menuKey, override?.scopeType || inherited!.scopeType),
          targetIds: override?.targetIds || inherited!.targetIds || [],
        } satisfies RowScopeSetting;
      })
      .find(Boolean);
    return stored || { definition: anchor, scopeType: rowScopeValue(menuKey, anchor.defaultScope), targetIds: [] };
  };
  const renderPersonalScopeCell = (row: PermissionMatrixRow) => {
    const setting = personalRowScope(row.menu.key);
    if (!setting) return <span className="permission-v4-muted">—</span>;
    const updateRowScope = (scopeType: string, ids: string[] = []) => setScopeOverrides((current) =>
      updateUserRowScopeOverrides(current, userLevelBindings, row.menu.key, row.operations, scopeType, ids));
    return <div className="permission-v4-scope-list"><div className="permission-v4-scope-line">
      <PermissionScopeChoices value={setting.scopeType} options={dataScopeOptionsFor(row.menu.key, setting.definition, model?.delegableScopes[setting.definition.code], model?.unrestricted)} disabled={editingSelf} onChange={updateRowScope} />
      {setting.scopeType === 'SELECTED' && menusWithoutSelectedScope.has(row.menu.key) && <Typography.Text type="warning">历史指定范围，请改选其他范围</Typography.Text>}
      {setting.scopeType === 'SELECTED' && !menusWithoutSelectedScope.has(row.menu.key) && !projectTargetMenus.has(row.menu.key) && !editingSelf && <PermissionTargetPicker key={setting.definition.code} permissionCode={setting.definition.code} module={setting.definition.module} scopeType="SELECTED" value={setting.targetIds} allowedTargetIds={model?.unrestricted || model?.delegableScopes[setting.definition.code]?.scopeType !== 'SELECTED' ? undefined : model.delegableScopes[setting.definition.code]?.targetIds} onChange={(ids) => updateRowScope('SELECTED', ids)} />}
    </div></div>;
  };

  const renderPersonalMatrix = () => <div className="permission-v4-table-wrap"><table className="permission-v4-table permission-v4-personal-table"><thead><tr><th>功能权限</th><th>操作权限</th><th>数据范围权限</th></tr></thead><tbody>{matrixRows.map((row) => <tr className={row.menu.parent ? 'child-row' : 'parent-row'} key={row.menu.key}>
    <td>{renderMenuCell(row, true)}</td>
    <td>{row.operations.length ? <div className="permission-v4-checks">{row.operations.map((definition) => { const key = levelBindingKey(row.menu.key, definition.code); const overridden = Boolean(operationOverrides[key]); return <label className={`permission-v4-check${overridden ? ' manual' : ''}`} key={definition.code} title={overridden ? '个人覆盖' : '继承权限等级'}><input type="checkbox" disabled={editingSelf} checked={userOperationValue(row.menu.key, definition)} onChange={(event) => setOperationOverride(row.menu.key, definition, event.target.checked, personalRowScope(row.menu.key))} /><span>{definition.name}</span></label>; })}</div> : <span className="permission-v4-muted">—</span>}</td>
    <td>{renderPersonalScopeCell(row)}</td>
  </tr>)}</tbody></table></div>;

  const renderPersonal = () => {
    return <div className="permission-v4-panel permission-v4-personal-panel">
      <div className="permission-v4-user-config">
        <div className="permission-v4-user-toolbar">
          <label className="permission-v4-config-field"><span>搜索用户</span><Select showSearch value={activeUserId} onChange={setActiveUserId} optionFilterProp="label" options={users.map((user) => ({ value: user.id, label: `${user.displayName} · ${user.username}`, email: user.email }))} placeholder="输入姓名、编号或邮箱搜索" /></label>
          <label className="permission-v4-config-field"><span>业务角色 <small>可多选；不选择时菜单权限由个人手工配置</small></span><Select mode="multiple" disabled={editingSelf} value={roleIds} onChange={setRoleIds} options={roles.map((role) => ({ value: role.id, label: `${role.name}${role.builtin ? '（内置）' : ''}` }))} placeholder="不选择业务角色" maxTagCount="responsive" /></label>
          <label className="permission-v4-config-field"><span>权限等级 </span><Select allowClear disabled={editingSelf} value={levelId} onChange={setLevelId} options={(model?.levels ?? []).map((level) => ({ value: level.id, label: `${level.name}（${level.code}）` }))} placeholder="不选择权限等级（手工配置）" /></label>
        </div>
      </div>
      {renderPersonalMatrix()}
    </div>;
  };

  const renderRole = () => {
    return <div className="permission-v4-workarea permission-v4-role-workarea">
      <div className="permission-v4-tabbar"><div className="permission-v4-template-tabs">{roles.map((role) => <button type="button" className={`permission-v4-tab${role.id === activeRoleId ? ' active' : ''}`} key={role.id} onClick={() => setActiveRoleId(role.id)}>{role.name}</button>)}</div></div>
      <div className="permission-v4-template-note"><strong>{activeRole?.name || '未选择角色'}</strong><span>业务角色仅定义一级模块和二级功能/区域的默认入口权限，不参与操作权限和数据范围计算。</span></div>
      <div className="permission-v4-panel permission-v4-matrix-panel"><div className="permission-v4-table-wrap"><table className="permission-v4-table permission-v4-role-table"><thead><tr><th>功能权限</th><th>类型</th><th>说明</th></tr></thead><tbody>{matrixRows.filter((row) => !isCategoryMenu(row.menu.key)).map((row) => <tr className={row.menu.parent ? 'child-row' : 'parent-row'} key={row.menu.key}><td>{renderMenuCell(row, false)}</td><td><Tag color={row.menu.parent ? 'blue' : 'green'}>{row.menu.parent ? '二级功能/区域' : '一级模块'}</Tag></td><td className="permission-v4-muted">{menuDescription(row.menu)}</td></tr>)}</tbody></table></div></div>
    </div>;
  };

  const renderLevel = () => {
    // Row level data scope of a permission level: an explicit choice wins, otherwise the value
    // already stored on the row is shown. It is never re-derived from the selected operations.
    const levelRowScope = (menuKey: string): RowScopeSetting | undefined => {
      const row = matrixRows.find((item) => item.menu.key === menuKey);
      if (!row?.operations.length || menusWithoutDataScope.has(menuKey)) return undefined;
      const anchor = row.operations[0]!;
      const draft = levelScopeDrafts[menuKey];
      if (draft) return { definition: anchor, scopeType: rowScopeValue(menuKey, draft.scopeType), targetIds: draft.targetIds };
      const stored = row.operations
        .map((definition) => {
          const binding = levelBindings[levelBindingKey(menuKey, definition.code)];
          if (!binding) return undefined;
          return { definition, scopeType: rowScopeValue(menuKey, binding.scopeType), targetIds: binding.targetIds } satisfies RowScopeSetting;
        })
        .find(Boolean);
      return stored || { definition: anchor, scopeType: rowScopeValue(menuKey, anchor.defaultScope), targetIds: [] };
    };
    const renderLevelScope = (row: PermissionMatrixRow) => {
      const setting = levelRowScope(row.menu.key);
      if (!setting) return <span className="permission-v4-muted">—</span>;
      const updateRowScope = (scopeType: string, ids: string[] = []) => {
        setLevelScopeDrafts((current) => ({ ...current, [row.menu.key]: { scopeType, targetIds: ids } }));
        setLevelBindings((current) => updateLevelRowScopeBindings(current, row.menu.key, row.operations, scopeType, ids));
      };
      return <div className="permission-v4-scope-list"><div className="permission-v4-scope-line">
        <PermissionScopeChoices value={setting.scopeType} options={dataScopeOptionsFor(row.menu.key, setting.definition, model?.delegableScopes[setting.definition.code], model?.unrestricted)} disabled={editingOwnLevel} onChange={updateRowScope} />
        {setting.scopeType === 'SELECTED' && menusWithoutSelectedScope.has(row.menu.key) && <Typography.Text type="warning">历史指定范围，请改选其他范围</Typography.Text>}
        {setting.scopeType === 'SELECTED' && !menusWithoutSelectedScope.has(row.menu.key) && !projectTargetMenus.has(row.menu.key) && !editingOwnLevel && <PermissionTargetPicker key={setting.definition.code} permissionCode={setting.definition.code} module={setting.definition.module} scopeType="SELECTED" value={setting.targetIds} allowedTargetIds={model?.unrestricted || model?.delegableScopes[setting.definition.code]?.scopeType !== 'SELECTED' ? undefined : model.delegableScopes[setting.definition.code]?.targetIds} onChange={(ids) => updateRowScope('SELECTED', ids)} />}
      </div></div>;
    };
    return <div className="permission-v4-workarea permission-v4-level-workarea">
      <div className="permission-v4-tabbar">
        <div className="permission-v4-template-tabs">
          {(model?.levels ?? []).map((level) => <button type="button" className={`permission-v4-tab${level.id === activeLevelId ? ' active' : ''}`} key={level.id} onClick={() => setActiveLevelId(level.id)}>{level.name}</button>)}
        </div>
      </div>
      <div className="permission-v4-level-summary"><strong>{activeLevel?.name || '未选择等级'}</strong><span>{activeLevel?.description || '自定义权限等级：请在下方配置默认操作权限和数据范围。'}</span></div>
      <div className="permission-v4-panel permission-v4-matrix-panel">
        <div className="permission-v4-table-wrap">
          <table className="permission-v4-table permission-v4-level-table">
            <thead><tr><th>功能/区域</th><th>操作权限</th><th>数据范围权限</th></tr></thead>
            <tbody>
              {matrixRows.map((row) => <tr className={row.menu.parent ? 'child-row' : 'parent-row'} key={row.menu.key}>
                <td><div className={`permission-v4-function ${row.menu.parent ? 'child' : 'parent'}`}><span className="permission-v4-tree">{row.menu.parent ? '└─' : ''}</span><strong>{row.menu.label}</strong></div></td>
                <td>{row.operations.length ? <div className="permission-v4-checks">{row.operations.map((definition) => {
                  const bindingKey = levelBindingKey(row.menu.key, definition.code);
                  return <label className="permission-v4-check" key={definition.code} title={riskDisplayLabel(definition.risk)}><input type="checkbox" disabled={editingOwnLevel} checked={Boolean(levelBindings[bindingKey])} onChange={(event) => setLevelBindings((current) => {
                    const rowScope = levelRowScope(row.menu.key);
                    return updateLevelOperationBinding(current, row.menu.key, definition, event.target.checked, rowScope?.scopeType, rowScope?.targetIds || []);
                  })} /><span>{definition.name}</span></label>;
                })}</div> : <span className="permission-v4-muted">—</span>}</td>
                <td>{renderLevelScope(row)}</td>
              </tr>)}
            </tbody>
          </table>
        </div>
      </div>
    </div>;
  };

  if (loading) return <div className="iam-loading"><Spin /><span>加载权限模型…</span></div>;
  return <div className="iam-page permission-v4-page">
    <div className="page-heading">
      <div><Typography.Title level={2}>用户权限配置</Typography.Title><Typography.Text type="secondary">业务角色决定一级/二级功能入口；权限等级决定操作权限和数据范围；个人权限可继承、覆盖或完全独立配置。</Typography.Text></div>
      {mode === 'user' ? <div className="permission-v4-heading-actions">
        <PermissionButton permission="update" onClick={resetUserOverrides} disabled={!activeUserId || editingSelf || saving}><ReloadOutlined />恢复默认</PermissionButton>
        <PermissionButton permission="update" onClick={() => void cancelUserChanges()} disabled={!activeUserId || saving}>取消变更</PermissionButton>
        <PermissionButton permission="update" primary onClick={() => void saveUser()} disabled={!activeUserId || !userModel || editingSelf} loading={saving}><SaveOutlined />保存</PermissionButton>
      </div> : mode === 'role' ? <div className="permission-v4-heading-actions">
        <PermissionButton permission="create" onClick={createRole}><PlusOutlined />新增角色</PermissionButton>
        {activeRole && !activeRole.builtin && <PermissionButton permission="update" onClick={renameRole} disabled={editingOwnRole}><EditOutlined />重命名</PermissionButton>}
        <PermissionButton permission="delete" danger onClick={deleteRole} disabled={!activeRole || activeRole.builtin || editingOwnRole}><DeleteOutlined />删除角色</PermissionButton>
        <PermissionButton permission="update" onClick={resetRole} disabled={activeRole?.code === 'SYSTEM_ADMIN' || editingOwnRole}><ReloadOutlined />{activeRole?.builtin ? '恢复默认' : '清空菜单'}</PermissionButton>
        <PermissionButton permission="update" primary onClick={() => void saveRole()} disabled={!activeRoleId || activeRole?.code === 'SYSTEM_ADMIN' || editingOwnRole} loading={saving}><SaveOutlined />保存</PermissionButton>
      </div> : <div className="permission-v4-heading-actions">
        <PermissionButton permission="create" onClick={createLevel}><PlusOutlined />新增等级</PermissionButton>
        {activeLevel && !activeLevel.builtin && <PermissionButton permission="update" onClick={renameLevel} disabled={editingOwnLevel}><EditOutlined />重命名</PermissionButton>}
        <PermissionButton permission="delete" danger onClick={deleteLevel} disabled={!activeLevel || activeLevel.builtin || editingOwnLevel}><DeleteOutlined />删除等级</PermissionButton>
        <PermissionButton permission="update" onClick={resetLevel} disabled={editingOwnLevel}><ReloadOutlined />{activeLevel?.builtin ? '恢复默认' : '清空权限'}</PermissionButton>
        <PermissionButton permission="update" primary onClick={() => void saveLevel()} disabled={!activeLevelId || activeLevel?.code === 'L3_ADMIN' || editingOwnLevel} loading={saving}><SaveOutlined />保存</PermissionButton>
      </div>}
    </div>
    <div className="permission-v4-mode-tabs"><button type="button" className={mode === 'user' ? 'active' : ''} onClick={() => setMode('user')}>个人权限</button><button type="button" className={mode === 'role' ? 'active' : ''} onClick={() => setMode('role')}>业务角色</button><button type="button" className={mode === 'level' ? 'active' : ''} onClick={() => setMode('level')}>权限等级</button></div>
    {mode === 'user' ? renderPersonal() : mode === 'role' ? renderRole() : renderLevel()}
  </div>;
}
