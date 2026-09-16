import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import { App, Input, Modal, Select, Spin, Tag, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';

import { HttpError } from '@/services/http/errors';
import { iamApi, type IamRole, type IamUser, type MenuOverride, type PermissionBinding, type PermissionDefinition, type PermissionLevelBinding, type PermissionMenu, type PermissionModel, type UserPermissionModel } from '@/services/iam/iam-api';
import { riskDisplayLabel } from '@/routes/route-permissions';
import { PermissionTargetPicker } from './PermissionTargetPicker';
import { levelBindingKey, updateLevelOperationBinding, updateLevelRowScopeBindings, updateUserRowScopeOverrides } from './permission-level-binding-utils';
import './iam.css';

type PermissionMode = 'user' | 'role' | 'level';

interface PermissionManagementPageProps {
  initialMode?: PermissionMode;
}

interface PermissionMatrixRow {
  menu: PermissionMenu;
  children: PermissionMenu[];
  operations: PermissionDefinition[];
}

const scopeOptions = [
  { value: 'ORG', label: '本组织' },
  { value: 'CREATOR', label: '本人创建/上传' },
  { value: 'OWNER', label: '本人负责' },
  { value: 'MANAGER', label: '我管理' },
  { value: 'PARTICIPANT', label: '我参与/项目组' },
  { value: 'PROJECT_GROUP', label: '项目组' },
  { value: 'ALL', label: '全部数据' },
  { value: 'SELECTED', label: '指定对象' },
];

const normalizedScope = (value: string) => value === 'ALL' ? 'ORG' : value === 'SELF' ? 'CREATOR' : value === 'ASSIGNED' ? 'OWNER' : value === 'PROJECT' ? 'PROJECT_GROUP' : value;
const scopeOptionValuesByModule: Record<string, string[]> = {
  customer: ['ORG', 'OWNER', 'PARTICIPANT', 'SELECTED'],
  project: ['ORG', 'OWNER', 'PARTICIPANT', 'SELECTED'],
  experiment: ['ORG', 'PARTICIPANT', 'OWNER', 'CREATOR', 'SELECTED'],
  'research-test': ['ORG', 'PARTICIPANT', 'OWNER', 'SELECTED'],
  template: ['ORG', 'CREATOR', 'SELECTED'],
  knowledge: ['ORG', 'PROJECT_GROUP', 'CREATOR', 'SELECTED'],
  data: ['ORG', 'CREATOR', 'SELECTED'],
  inventory: ['ORG', 'PROJECT_GROUP', 'SELECTED'],
  production: ['ORG', 'SELECTED'],
  quality: ['ORG', 'OWNER', 'SELECTED'],
  spectrum: ['ORG', 'PARTICIPANT', 'CREATOR', 'SELECTED'],
  system: ['ORG', 'SELECTED'],
};
const scopeLabelsByModule: Record<string, Record<string, string>> = {
  customer: { ORG: '全部客户', OWNER: '我负责', PARTICIPANT: '我参与', SELECTED: '指定客户' },
  project: { ORG: '全部项目', OWNER: '我管理', PARTICIPANT: '我参与', SELECTED: '指定项目' },
  template: { ORG: '全部模板', CREATOR: '本人上传', SELECTED: '指定分类' },
  experiment: { ORG: '全部实验', PARTICIPANT: '参与项目实验', OWNER: '本人负责', CREATOR: '本人创建', SELECTED: '指定实验' },
};
const scopeOptionValuesByMenuKey: Record<string, string[]> = {
  'customer.list': ['ORG', 'OWNER', 'PARTICIPANT', 'SELECTED'],
  'template.upload': ['ORG', 'CREATOR'],
  'template.view': ['ORG', 'CREATOR', 'SELECTED'],
  'project.list': ['ORG', 'MANAGER', 'PARTICIPANT', 'SELECTED'],
  'project.stage': ['ORG', 'MANAGER', 'PARTICIPANT', 'SELECTED'],
  'project.task': ['ORG', 'OWNER', 'SELECTED'],
};
const scopeLabelsByMenuKey: Record<string, Record<string, string>> = {
  'template.view': { ORG: '全部模板', CREATOR: '本人创建', SELECTED: '指定分类' },
};
const prototypeOperationsByMenuKey: Record<string, Array<readonly [string, string]>> = {
  'customer.list': [['customer.create', '新增'], ['customer.view', '查看'], ['customer.copy', '复制'], ['customer.delete', '删除'], ['customer.export', '导出']],
  'customer.overview': [['customer.update', '编辑']],
  'customer.needs': [['customer.create', '新增'], ['customer.update', '编辑'], ['customer.delete', '删除'], ['customer.link', '关联'], ['customer.unlink', '解除关联']],
  'customer.followup': [['customer.create', '新增'], ['customer.update', '编辑'], ['customer.delete', '删除']],
  'template.upload': [['template.upload', '上传'], ['template.delete', '删除'], ['template.retry', '重试']],
  'template.view': [['template.view', '查看'], ['category.create', '新增分类'], ['category.update', '编辑分类'], ['category.delete', '删除分类'], ['template.update', '编辑'], ['template.copy', '复制'], ['template.recognition', '识别复核'], ['template.review', '提交审核'], ['template.approve', '审核'], ['template.publish', '发布'], ['template.rollback', '回退版本'], ['template.export', '导出'], ['template.disable', '停用'], ['template.delete', '删除']],
  'template.published': [['template.view', '选择'], ['template.create', '引用']],
  'project.list': [['project.create', '新增'], ['project.view', '查看'], ['project.copy', '复制'], ['project.delete', '删除'], ['project.export', '导出']],
  'project.info': [['project.update', '编辑'], ['project.assign', '保存']],
  'project.stage': [['project.create', '新增'], ['project.update', '编辑'], ['project.delete', '删除']],
  'project.task': [['project.create', '新增'], ['project.update', '编辑'], ['project.delete', '删除'], ['project.assign', '分配负责人']],
  'project.document': [['project.create', '新增'], ['project.update', '编辑'], ['project.copy', '复制'], ['project.delete', '删除'], ['project.view', '导入'], ['project.export', '导出'], ['project.assign', '引用模板']],
  'project.customer-needs': [['project.assign', '关联'], ['project.update', '解除关联']],
  'project.reference': [['project.assign', '关联'], ['project.update', '解除关联'], ['project.view', '查看原文']],
  'project.meeting': [['project.create', '新增'], ['project.update', '编辑'], ['project.delete', '删除'], ['project.assign', '归档知识库']],
  'project.log': [['project.view', '查看']],
  'project.materials': [['ops.file.download', '下载']],
  'eln.create': [['experiment.create', '新增']],
  'eln.upload': [['ops.file.upload', '上传'], ['experiment.delete', '删除'], ['experiment.view', '查看'], ['ops.file.download', '下载'], ['experiment.update', '重试']],
  'eln.view': [['experiment.view', '查看'], ['experiment.update', '编辑'], ['experiment.submit', '提交审核'], ['experiment.approve', '审核'], ['experiment.review', '退回/作废'], ['ops.file.download', '下载'], ['experiment.delete', '删除']],
  'research-test.report.upload': [['ops.file.upload', '上传'], ['research-test.report.delete', '删除'], ['research-test.report.view', '查看'], ['ops.file.download', '下载'], ['research-test.report.update', '重试']],
  'research-test.report.view': [['research-test.report.view', '查看'], ['research-test.report.create', '新增'], ['research-test.report.update', '编辑'], ['research-test.report.submit', '提交审核'], ['research-test.report.approve', '审核'], ['research-test.report.publish', '发布'], ['research-test.report.export', '导出'], ['research-test.report.delete', '删除']],
  'research-test.standard.upload': [],
  'research-test.standard': [['research-test.standard.view', '查看'], ['research-test.standard.create', '新增'], ['research-test.standard.update', '编辑'], ['research-test.standard.submit', '提交审核'], ['research-test.standard.approve', '审核'], ['research-test.standard.publish', '发布'], ['research-test.standard.export', '导出'], ['research-test.standard.delete', '删除']],
  'quality.upload': [['quality.upload', '上传'], ['quality.create', '导入'], ['quality.delete', '删除']],
  'quality.view': [['quality.view', '查看'], ['quality.create', '新增'], ['quality.update', '编辑'], ['quality.publish', '发布'], ['quality.export', '导出'], ['quality.delete', '删除']],
  'spectrum.upload': [['ops.file.upload', '上传'], ['spectrum.delete', '删除'], ['spectrum.update', '分类'], ['spectrum.create', '关联实验'], ['spectrum.view', '查看'], ['spectrum.download', '下载'], ['ai.use', 'AI对话']],
  'spectrum.view': [['category.create', '新增分类'], ['category.update', '编辑分类'], ['category.delete', '删除分类'], ['spectrum.update', '编辑'], ['spectrum.delete', '删除'], ['spectrum.export', '导出'], ['spectrum.create', '关联']],
  'spectrum.ai': [['ai.use', '发起分析'], ['knowledge.create', '保存结果'], ['project.assign', '引用结果']],
  'production.upload': [['ops.file.upload', '上传'], ['project.assign', '关联项目'], ['production.delete', '删除'], ['production.update', '保存分类'], ['production.view', '查看'], ['ops.file.download', '下载'], ['production.submit', '重试']],
  'production.view': [['production.view', '查看'], ['production.create', '新增'], ['production.update', '编辑'], ['production.submit', '提交'], ['production.cancel', '取消'], ['production.export', '导出'], ['production.delete', '删除']],
  'ai.search': [['ai.use', '检索'], ['ops.file.download', '下载']],
  'ai.qa': [['ai.use', '提问'], ['knowledge.create', '保存回答'], ['project.assign', '引用回答']],
  'ai.formula': [['ai.use', '预测'], ['data.create', '保存'], ['project.assign', '引用结果']],
  'ai.analysis': [['ai.use', '分析'], ['knowledge.create', '保存'], ['project.assign', '加入项目资料']],
  'knowledge.review': [],
  'knowledge.upload': [['knowledge.upload', '上传'], ['knowledge.create', '新建'], ['knowledge.update', '分类'], ['project.assign', '关联项目'], ['ai.external', 'AI授权'], ['knowledge.delete', '删除']],
  'knowledge.view': [['knowledge.view', '查看'], ['category.create', '新增分类'], ['category.update', '编辑分类'], ['knowledge.update', '编辑/移动'], ['project.assign', '关联'], ['knowledge.submit', '提交审核'], ['knowledge.review', '审核/驳回'], ['knowledge.approve', '通过审核'], ['knowledge.publish', '发布'], ['knowledge.download', '下载'], ['knowledge.export', '导出'], ['knowledge.delete', '删除']],
  'data.upload': [['ops.file.view', '查看文件'], ['ops.file.upload', '上传'], ['data.create', '导入模板'], ['data.update', '分类'], ['project.assign', '关联项目/阶段/任务'], ['data.view', '预览'], ['data.download', '下载'], ['data.delete', '删除']],
  'data.view': [['data.view', '查看'], ['category.create', '新增分类'], ['category.update', '编辑分类'], ['category.delete', '删除分类'], ['data.update', '编辑'], ['data.submit', '提交'], ['data.approve', '审核'], ['data.download', '下载'], ['data.export', '导出'], ['data.delete', '删除']],
  'inventory.query': [['inventory.view', '查询'], ['data.export', '导出']],
  'inventory.rnd': [['inventory.create', '新增'], ['inventory.update', '编辑'], ['data.create', '入库'], ['data.submit', '出库'], ['inventory.reverse', '调整'], ['ops.file.upload', '导入'], ['data.export', '导出']],
  'inventory.production': [['inventory.create', '新增'], ['inventory.update', '编辑'], ['data.create', '入库'], ['data.submit', '出库'], ['inventory.reverse', '调整'], ['ops.file.upload', '导入'], ['data.export', '导出']],
  'inventory.sample': [['inventory.create', '新增'], ['inventory.update', '编辑'], ['data.delete', '删除'], ['data.export', '导出']],
  'inventory.shipment': [['inventory.create', '新增'], ['inventory.update', '编辑'], ['data.delete', '删除'], ['data.export', '导出']],
  'system.users': [['system.user.manage', '新增用户'], ['system.user.view', '编辑用户'], ['system.role.manage', '启停账号'], ['system.permission.manage', '重置密码']],
  'system.permissions': [['system.role.view', '查看角色'], ['system.role.create', '新增角色'], ['system.role.manage', '配置角色'], ['system.permission.manage', '配置个人权限']],
  'system.dictionary': [['category.create', '新增'], ['category.update', '编辑'], ['category.delete', '删除'], ['system.dictionary.manage', '启用'], ['system.parameter.manage', '停用']],
  'system.audit': [['system.audit.view', '查询'], ['data.export', '导出']],
  'system.parameters': [['system.parameter.manage', '编辑'], ['system.permission.manage', '保存']],
};
const prototypeOperationCodesByMenuKey = Object.fromEntries(Object.entries(prototypeOperationsByMenuKey).map(([menuKey, operations]) => [menuKey, operations.map(([code]) => code)]));
const prototypeOperationLabelsByMenuKey = Object.fromEntries(Object.entries(prototypeOperationsByMenuKey).map(([menuKey, operations]) => [menuKey, Object.fromEntries(operations)]));
const operationDisplayOrder = ['查看', '查询', '检索', '选择', '新增', '新建', '上传', '导入', '编辑', '分类', '移动', '重命名', '复制', '识别复核', '关联', '解除关联', '分配负责人', '保存', '提交', '提交审核', '审核', '通过审核', '退回/作废', '发布', '回退版本', '下载', '导出', '调整', '取消', '停用', '删除', '重试'];
const operationDisplayLabel = (menuKey: string, definition: PermissionDefinition) => prototypeOperationLabelsByMenuKey[menuKey]?.[definition.code] || definition.name;
const uniqueBindings = (bindings: PermissionBinding[]) => [...new Map(bindings.map((binding) => [levelBindingKey(binding.menuKey || '', binding.permissionCode), binding])).values()];
const operationOrder = (menuKey: string, definition: PermissionDefinition) => {
  const label = operationDisplayLabel(menuKey, definition);
  const exact = operationDisplayOrder.indexOf(label);
  if (exact >= 0) return exact;
  const partial = operationDisplayOrder.findIndex((item) => label.includes(item));
  return partial >= 0 ? partial : operationDisplayOrder.length;
};
const menuDescription = (menu: PermissionMenu) => menu.parent
  ? '可单独开放；一级模块仅呈现其二级功能的汇总状态'
  : '一级模块；是否可用由已选择的二级功能入口决定';
const scopeOptionsFor = (definition: PermissionDefinition, menuKeyOverride?: string) => {
  const menuKey = menuKeyOverride || definition.code.replace(/\.op\d+$/, '');
  const values = scopeOptionValuesByMenuKey[menuKey] || scopeOptionValuesByModule[definition.module] || ['ORG', 'CREATOR', 'SELECTED'];
  const labels = { ...(scopeLabelsByModule[definition.module] || {}), ...(scopeLabelsByMenuKey[menuKey] || {}) };
  return scopeOptions
    .filter((option) => values.includes(option.value) || option.value === normalizedScope(definition.defaultScope))
    .map((option) => ({ ...option, label: labels[option.value] || option.label }));
};

const normalizeRoleMenuKeys = (menuKeys: string[], menus: PermissionMenu[]) => {
  const next = new Set(menuKeys);
  menus.filter((menu) => !menu.parent).forEach((parent) => {
    const children = menus.filter((menu) => menu.parent === parent.key);
    if (!children.length) return;
    if (children.some((child) => next.has(child.key))) next.add(parent.key);
    else next.delete(parent.key);
  });
  return [...next];
};

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

function PermissionButton({
  children,
  className = '',
  danger = false,
  primary = false,
  disabled = false,
  loading = false,
  onClick,
}: {
  children: React.ReactNode;
  className?: string;
  danger?: boolean;
  primary?: boolean;
  disabled?: boolean;
  loading?: boolean;
  onClick?: () => void;
}) {
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
  const [levelVersion, setLevelVersion] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const activeUser = users.find((user) => user.id === activeUserId);
  const activeRole = roles.find((role) => role.id === activeRoleId);
  const activeLevel = model?.levels.find((level) => level.id === activeLevelId);

  const childrenByParent = useMemo(() => (model?.menus ?? []).reduce<Record<string, PermissionMenu[]>>((result, menu) => {
    if (menu.parent) (result[menu.parent] ||= []).push(menu);
    return result;
  }, {}), [model]);

  const matrixRows = useMemo(() => {
    const menus = model?.menus ?? [];
    const operations = model?.operations ?? [];
    const operationsByCode = new Map(operations.map((definition) => [definition.code, definition]));
    const rows: PermissionMatrixRow[] = [];
    menus.forEach((menu) => {
      const children = childrenByParent[menu.key] || [];
      if (!menu.parent) {
        rows.push({ menu, children, operations: [] });
        return;
      }
      const prototypeCodes = prototypeOperationCodesByMenuKey[menu.key];
      const menuModule = menu.parent === 'eln' ? 'experiment' : menu.parent || menu.key.split('.')[0] || menu.key;
      const direct = (prototypeCodes
        ? prototypeCodes.map((code) => operationsByCode.get(code) || ({ code, module: menu.key.split('.')[0] || menu.key, name: prototypeOperationLabelsByMenuKey[menu.key]?.[code] || code, risk: 'MEDIUM', defaultScope: 'ORG' } satisfies PermissionDefinition))
        : operations.filter((definition) => definition.module === menuModule))
        .sort((left, right) => operationOrder(menu.key, left) - operationOrder(menu.key, right));
      if (direct.length) {
        rows.push({ menu, children, operations: direct });
        return;
      }
      const siblings = menus.filter((item) => item.parent === menu.parent);
      const owner = siblings.find((item) => item.key.endsWith('.view') || item.key.endsWith('.list')) || siblings[0];
      rows.push({
        menu,
        children,
        operations: owner?.key === menu.key ? operations.filter((definition) => definition.module === menu.parent || definition.module === menu.parent?.split('.')[0]) : [],
      });
    });
    return rows;
  }, [childrenByParent, model]);

  const load = async () => {
    setLoading(true);
    try {
      const [userPage, roleItems, permissionModel] = await Promise.all([
        iamApi.users({ size: 100 }),
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
    setScopeOverrides(Object.fromEntries(data.scopeOverrides.flatMap((item) => item.menuKey
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
        operations: uniqueBindings(Object.values(operationOverrides)),
        scopes: uniqueBindings(Object.values(scopeOverrides)),
      });
      await loadUserModel(false);
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
      const normalizedMenus = normalizeRoleMenuKeys(roleMenus, model?.menus ?? []);
      const result = await iamApi.saveRoleMenus(activeRoleId, roleVersion, normalizedMenus);
      setRoleMenus(normalizedMenus);
      setRoleVersion(result.version);
      setRoleMenuBaselines((current) => ({ ...current, [activeRoleId]: normalizedMenus }));
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
      const result = await iamApi.savePermissionLevelPermissions(activeLevelId, levelVersion, Object.values(levelBindings));
      setLevelVersion(result.version);
      await loadLevelBindings();
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
    Modal.confirm({ title: '重命名业务角色', content: <Input defaultValue={name} onChange={(event) => { name = event.target.value; }} />, onOk: async () => { await iamApi.renameRole(activeRole.id, name.trim()); await load(); message.success('角色名称已更新'); } });
  };

  const deleteRole = () => {
    if (!activeRole || activeRole.builtin) return;
    Modal.confirm({ title: '删除业务角色', content: '删除后会从已绑定用户中移除该角色，用户的权限等级和个人覆盖保留。', okType: 'danger', onOk: async () => { await iamApi.deleteRole(activeRole.id); await load(); message.success('业务角色已删除'); } });
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

  const baseMenuValue = (menu: PermissionMenu) => {
    const children = childrenByParent[menu.key] || [];
    return children.length ? children.every((child) => userRoleMenuSet.has(child.key)) : userRoleMenuSet.has(menu.key);
  };
  const menuValue = (menu: PermissionMenu) => {
    const override = menuOverrides[menu.key];
    return override ? override.effect === 'ALLOW' : baseMenuValue(menu);
  };
  const roleMenuValue = (menu: PermissionMenu) => {
    const children = childrenByParent[menu.key] || [];
    return children.length ? children.every((child) => roleMenuSet.has(child.key)) : roleMenuSet.has(menu.key);
  };
  const menuIndeterminate = (menu: PermissionMenu) => {
    const children = childrenByParent[menu.key] || [];
    if (!children.length) return false;
    const count = children.filter((child) => menuValue(child)).length;
    return count > 0 && count < children.length;
  };
  const roleMenuIndeterminate = (menu: PermissionMenu) => {
    const children = childrenByParent[menu.key] || [];
    const count = children.filter((child) => roleMenuSet.has(child.key)).length;
    return count > 0 && count < children.length;
  };
  const updateMenuOverride = (key: string, checked: boolean) => {
    const base = userRoleMenuSet.has(key);
    setMenuOverrides((current) => {
      const next = { ...current };
      if (checked === base) delete next[key];
      else next[key] = { menuKey: key, effect: checked ? 'ALLOW' : 'DENY' };
      return next;
    });
  };
  const togglePersonalMenu = (menu: PermissionMenu, checked: boolean) => {
    const keys = [menu.key, ...(childrenByParent[menu.key] || []).map((child) => child.key)];
    keys.forEach((key) => updateMenuOverride(key, checked));
  };
  const toggleRoleMenu = (menu: PermissionMenu, checked: boolean) => {
    const keys = [menu.key, ...(childrenByParent[menu.key] || []).map((child) => child.key)];
    setRoleMenus((current) => {
      const next = new Set(current);
      keys.forEach((key) => checked ? next.add(key) : next.delete(key));
      return normalizeRoleMenuKeys([...next], model?.menus ?? []);
    });
  };

  const userOperationValue = (menuKey: string, definition: PermissionDefinition) => {
    const key = levelBindingKey(menuKey, definition.code);
    const override = operationOverrides[key];
    return override ? override.effect === 'ALLOW' : userLevelBindings[key]?.effect === 'ALLOW';
  };
  const setOperationOverride = (menuKey: string, definition: PermissionDefinition, checked: boolean) => {
    const key = levelBindingKey(menuKey, definition.code);
    const baseline = userLevelBindings[key]?.effect === 'ALLOW';
    setOperationOverrides((current) => {
      const next = { ...current };
      if (checked === baseline) delete next[key];
      else next[key] = { menuKey, permissionCode: definition.code, effect: checked ? 'ALLOW' : 'DENY', scopeType: normalizedScope(userLevelBindings[key]?.scopeType || definition.defaultScope), targetIds: [] };
      return next;
    });
  };
  const renderSource = (overridden: boolean, inherited: boolean) => <span className={`permission-v4-source ${overridden ? 'manual' : 'inherit'}`}>{overridden ? '个人覆盖' : inherited ? '业务角色继承' : '手工配置'}</span>;
  const renderMenuCell = (row: PermissionMatrixRow, personal: boolean) => {
    const checked = personal ? menuValue(row.menu) : roleMenuValue(row.menu);
    const overridden = personal && Boolean(menuOverrides[row.menu.key] || row.children.some((child) => menuOverrides[child.key]));
    const inherited = personal && roleIds.length > 0;
    return <div className={`permission-v4-function ${row.menu.parent ? 'child' : 'parent'}${overridden ? ' overridden' : ''}`}>
      <input type="checkbox" checked={checked} ref={(element) => { if (element) element.indeterminate = personal ? menuIndeterminate(row.menu) : roleMenuIndeterminate(row.menu); }} disabled={!personal && activeRole?.code === 'SYSTEM_ADMIN'} onChange={(event) => personal ? togglePersonalMenu(row.menu, event.target.checked) : toggleRoleMenu(row.menu, event.target.checked)} />
      <span className="permission-v4-tree">{row.menu.parent ? '└─' : ''}</span>
      <span className="permission-v4-function-label">{row.menu.label}</span>
      {personal && renderSource(overridden, inherited)}
    </div>;
  };

  const renderPersonalScopeCell = (row: PermissionMatrixRow) => {
    if (!row.operations.length) return <span className="permission-v4-muted">—</span>;
    const enabled = row.operations.filter((item) => userOperationValue(row.menu.key, item));
    const definition = enabled[0] || row.operations[0]!;
    const key = levelBindingKey(row.menu.key, definition.code);
    const override = scopeOverrides[key];
    const inherited = userLevelBindings[key];
    const value = normalizedScope(override?.scopeType || inherited?.scopeType || definition.defaultScope);
    const targetIds = override?.targetIds || inherited?.targetIds || [];
    const enabledCodes = new Set(enabled.map((item) => item.code));
    const updateRowScope = (scopeType: string, ids: string[] = []) => setScopeOverrides((current) =>
      updateUserRowScopeOverrides(current, userLevelBindings, row.menu.key, row.operations, enabledCodes, scopeType, ids));
    return <div className="permission-v4-scope-list"><div className="permission-v4-scope-line">
      <PermissionScopeChoices value={value} options={scopeOptionsFor(definition, row.menu.key)} disabled={!enabled.length} onChange={updateRowScope} />
      {value === 'SELECTED' && enabled.length > 0 && <PermissionTargetPicker permissionCode={definition.code} scopeType="SELECTED" value={targetIds} onChange={(ids) => updateRowScope('SELECTED', ids)} />}
    </div></div>;
  };

  const renderPersonalMatrix = () => <div className="permission-v4-table-wrap"><table className="permission-v4-table"><thead><tr><th>功能权限</th><th>操作权限</th><th>数据范围权限</th></tr></thead><tbody>{matrixRows.map((row) => <tr className={row.menu.parent ? 'child-row' : 'parent-row'} key={row.menu.key}>
    <td>{renderMenuCell(row, true)}</td>
    <td>{row.operations.length ? <div className="permission-v4-checks">{row.operations.map((definition) => { const key = levelBindingKey(row.menu.key, definition.code); const overridden = Boolean(operationOverrides[key]); return <label className={`permission-v4-check${overridden ? ' manual' : ''}`} key={definition.code} title={overridden ? '个人覆盖' : '继承权限等级'}><input type="checkbox" checked={userOperationValue(row.menu.key, definition)} onChange={(event) => setOperationOverride(row.menu.key, definition, event.target.checked)} /><span>{operationDisplayLabel(row.menu.key, definition)}</span></label>; })}</div> : <span className="permission-v4-muted">—</span>}</td>
    <td>{renderPersonalScopeCell(row)}</td>
  </tr>)}</tbody></table></div>;

  const renderPersonal = () => {
    const levelDescription = model?.levels.find((level) => level.id === levelId)?.description || (levelId ? '继承当前权限等级的操作与数据范围。' : '未选择等级：操作权限和数据范围均可直接在个人权限中配置。');
    return <div className="permission-v4-panel permission-v4-personal-panel">
      <div className="permission-v4-user-config">
        <div className="permission-v4-user-toolbar">
          <label className="permission-v4-config-field"><span>搜索用户</span><Select showSearch value={activeUserId} onChange={setActiveUserId} optionFilterProp="label" options={users.map((user) => ({ value: user.id, label: `${user.displayName} · ${user.username}`, email: user.email }))} placeholder="输入姓名、编号或邮箱搜索" /></label>
          <label className="permission-v4-config-field"><span>业务角色 <small>可多选；不选择时菜单权限由个人手工配置</small></span><Select mode="multiple" value={roleIds} onChange={setRoleIds} options={roles.map((role) => ({ value: role.id, label: `${role.name}${role.builtin ? '（内置）' : ''}` }))} placeholder="不选择业务角色" maxTagCount="responsive" /></label>
          <label className="permission-v4-config-field"><span>权限等级</span><Select allowClear value={levelId} onChange={setLevelId} options={(model?.levels ?? []).map((level) => ({ value: level.id, label: `${level.name}（${level.code}）` }))} placeholder="不选择权限等级（手工配置）" /><small className="permission-v4-level-description">{levelDescription}</small></label>
        </div>
        {activeUser && <div className="permission-v4-user-meta"><span>{activeUser.displayName} · {activeUser.username}</span><span className={activeUser.status === 'ACTIVE' ? 'ok' : 'off'}>{activeUser.status === 'ACTIVE' ? '启用' : '停用'}</span><span>{activeUser.departmentName || '未设置部门'}</span><span>{roleIds.length ? `${roleIds.length} 个业务角色` : '未设置业务角色'}</span><span className={levelId ? 'inherit' : 'manual'}>{levelId ? '继承权限等级' : '个人手工配置'}</span></div>}
      </div>
      {renderPersonalMatrix()}
      <div className="permission-v4-savebar"><span><i className="permission-v4-info-dot">i</i> 菜单继承业务角色；操作与数据范围继承权限等级。黄色标记为个人覆盖，个人覆盖优先。</span><div><PermissionButton onClick={resetUserOverrides} disabled={!activeUserId || saving}><ReloadOutlined />恢复角色/等级默认</PermissionButton><PermissionButton onClick={() => void cancelUserChanges()} disabled={!activeUserId || saving}>取消变更</PermissionButton><PermissionButton primary onClick={() => void saveUser()} disabled={!activeUserId || !userModel} loading={saving}><SaveOutlined />保存个人权限</PermissionButton></div></div>
    </div>;
  };

  const renderRole = () => {
    const locked = activeRole?.code === 'SYSTEM_ADMIN';
    const isBuiltin = Boolean(activeRole?.builtin);
    const resetRole = () => {
      setRoleMenus(locked ? (model?.menus ?? []).map((menu) => menu.key) : []);
      message.info(locked ? '系统管理员固定开放全部功能入口' : isBuiltin ? '已清空当前内置角色的菜单变更，请保存后生效' : '已清空当前业务角色菜单，请保存后生效');
    };
    return <div className="permission-v4-workarea permission-v4-role-workarea">
      <div className="permission-v4-tabbar"><div className="permission-v4-template-tabs">{roles.map((role) => <button type="button" className={`permission-v4-tab${role.id === activeRoleId ? ' active' : ''}`} key={role.id} onClick={() => setActiveRoleId(role.id)}>{role.name}</button>)}</div><div className="permission-v4-actions"><PermissionButton onClick={createRole}><PlusOutlined />新增角色</PermissionButton>{activeRole && !activeRole.builtin && <PermissionButton onClick={renameRole}><EditOutlined />重命名</PermissionButton>}<PermissionButton danger onClick={deleteRole} disabled={!activeRole || activeRole.builtin}><DeleteOutlined />删除角色</PermissionButton></div></div>
      <div className="permission-v4-template-note"><strong>{activeRole?.name || '未选择角色'}</strong><span>业务角色仅定义一级模块和二级功能/区域的默认入口权限，不参与操作权限和数据范围计算。</span></div>
      <div className="permission-v4-panel permission-v4-matrix-panel"><div className="permission-v4-table-wrap"><table className="permission-v4-table permission-v4-role-table"><thead><tr><th>功能权限</th><th>类型</th><th>说明</th></tr></thead><tbody>{matrixRows.map((row) => <tr className={row.menu.parent ? 'child-row' : 'parent-row'} key={row.menu.key}><td>{renderMenuCell(row, false)}</td><td><Tag color={row.menu.parent ? 'blue' : 'green'}>{row.menu.parent ? '二级功能/区域' : '一级模块'}</Tag></td><td className="permission-v4-muted">{menuDescription(row.menu)}</td></tr>)}</tbody></table></div><div className="permission-v4-savebar"><span><i className="permission-v4-info-dot">i</i> {locked ? '系统管理员为内置角色，固定开放全部功能入口。' : isBuiltin ? '普通用户为内置基础角色，可调整菜单但不能删除。' : '业务角色可作为多人复用的菜单权限模板。'}</span><div><PermissionButton onClick={resetRole} disabled={locked}><ReloadOutlined />{isBuiltin ? '恢复内置默认' : '清空菜单'}</PermissionButton><PermissionButton primary onClick={() => void saveRole()} disabled={!activeRoleId || locked} loading={saving}><SaveOutlined />保存业务角色</PermissionButton></div></div></div>
    </div>;
  };

  const renderLevel = () => {
    const isBuiltin = Boolean(activeLevel?.builtin);
    const resetLevel = () => {
      setLevelBindings({});
      message.info(isBuiltin ? '已清空当前内置等级的编辑结果，请保存后生效' : '已清空当前权限等级，请保存后生效');
    };
    const renderLevelScope = (row: PermissionMatrixRow) => {
      if (!row.operations.length) return <span className="permission-v4-muted">—</span>;
      const selectedDefinition = row.operations.find((definition) => Boolean(levelBindings[levelBindingKey(row.menu.key, definition.code)]));
      const scopeDefinition = selectedDefinition || row.operations[0]!;
      const binding = selectedDefinition ? levelBindings[levelBindingKey(row.menu.key, selectedDefinition.code)] : undefined;
      const value = normalizedScope(binding?.scopeType || scopeDefinition.defaultScope);
      return <div className="permission-v4-scope-list"><div className="permission-v4-scope-line">
        <PermissionScopeChoices value={value} options={scopeOptionsFor(scopeDefinition, row.menu.key)} disabled={!binding} onChange={(scopeType) => setLevelBindings((current) => updateLevelRowScopeBindings(current, row.menu.key, row.operations, scopeType))} />
        {value === 'SELECTED' && binding && <PermissionTargetPicker permissionCode={scopeDefinition.code} scopeType="SELECTED" value={binding.targetIds} onChange={(targetIds) => setLevelBindings((current) => updateLevelRowScopeBindings(current, row.menu.key, row.operations, 'SELECTED', targetIds))} />}
      </div></div>;
    };
    return <div className="permission-v4-workarea permission-v4-level-workarea">
      <div className="permission-v4-tabbar">
        <div className="permission-v4-template-tabs">
          {(model?.levels ?? []).map((level) => <button type="button" className={`permission-v4-tab${level.id === activeLevelId ? ' active' : ''}`} key={level.id} onClick={() => setActiveLevelId(level.id)}>{level.name}</button>)}
        </div>
        <div className="permission-v4-actions">
          <PermissionButton onClick={createLevel}><PlusOutlined />新增等级</PermissionButton>
          {activeLevel && !activeLevel.builtin && <><PermissionButton primary onClick={() => void saveLevel()} disabled={!activeLevelId} loading={saving}><SaveOutlined />保存</PermissionButton><PermissionButton onClick={renameLevel}><EditOutlined />重命名</PermissionButton></>}
          <PermissionButton danger onClick={deleteLevel} disabled={!activeLevel || activeLevel.builtin}><DeleteOutlined />删除等级</PermissionButton>
        </div>
      </div>
      <div className="permission-v4-level-summary"><div><strong>{activeLevel?.name || '未选择等级'}</strong></div><p>{activeLevel?.description || '自定义权限等级：请在下方配置默认操作权限和数据范围。'}</p></div>
      <div className="permission-v4-panel permission-v4-matrix-panel">
        <div className="permission-v4-table-wrap">
          <table className="permission-v4-table permission-v4-level-table">
            <thead><tr><th>功能/区域</th><th>操作权限</th><th>数据范围权限</th></tr></thead>
            <tbody>
              {matrixRows.map((row) => <tr className={row.menu.parent ? 'child-row' : 'parent-row'} key={row.menu.key}>
                <td><div className={`permission-v4-function ${row.menu.parent ? 'child' : 'parent'}`}><span className="permission-v4-tree">{row.menu.parent ? '└─' : ''}</span><strong>{row.menu.label}</strong></div></td>
                <td>{row.operations.length ? <div className="permission-v4-checks">{row.operations.map((definition) => {
                  const bindingKey = levelBindingKey(row.menu.key, definition.code);
                  return <label className="permission-v4-check" key={definition.code} title={riskDisplayLabel(definition.risk)}><input type="checkbox" checked={Boolean(levelBindings[bindingKey])} onChange={(event) => setLevelBindings((current) => {
                    const rowBinding = row.operations.map((item) => current[levelBindingKey(row.menu.key, item.code)]).find(Boolean);
                    return updateLevelOperationBinding(current, row.menu.key, definition, event.target.checked, rowBinding?.scopeType);
                  })} /><span>{operationDisplayLabel(row.menu.key, definition)}</span></label>;
                })}</div> : <span className="permission-v4-muted">—</span>}</td>
                <td>{renderLevelScope(row)}</td>
              </tr>)}
            </tbody>
          </table>
        </div>
        <div className="permission-v4-savebar"><span><i className="permission-v4-info-dot">i</i> 权限等级不开放任何菜单，只定义进入已授权功能后默认可以执行的操作和可访问的数据范围。</span><div><PermissionButton onClick={resetLevel}><ReloadOutlined />{isBuiltin ? '恢复内置默认' : '清空权限'}</PermissionButton><PermissionButton primary onClick={() => void saveLevel()} disabled={!activeLevelId || activeLevel?.code === 'L3_ADMIN'} loading={saving}><SaveOutlined />保存权限等级</PermissionButton></div></div>
      </div>
    </div>;
  };

  if (loading) return <div className="iam-loading"><Spin /><span>加载权限模型…</span></div>;
  return <div className="iam-page permission-v4-page">
    <div className="page-heading"><div><Typography.Title level={2}>用户权限配置</Typography.Title><Typography.Text type="secondary">业务角色决定一级/二级功能入口；权限等级决定操作权限和数据范围；个人权限可继承、覆盖或完全独立配置。</Typography.Text></div></div>
    <div className="permission-v4-mode-tabs"><button type="button" className={mode === 'user' ? 'active' : ''} onClick={() => setMode('user')}>个人权限</button><button type="button" className={mode === 'role' ? 'active' : ''} onClick={() => setMode('role')}>业务角色</button><button type="button" className={mode === 'level' ? 'active' : ''} onClick={() => setMode('level')}>权限等级</button></div>
    {mode === 'user' ? renderPersonal() : mode === 'role' ? renderRole() : renderLevel()}
  </div>;
}
