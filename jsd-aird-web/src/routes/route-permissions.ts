export const permissionLabels: Record<string, string> = {
  'system.user.view': '查看用户管理',
  'system.role.view': '查看角色权限',
  'system.permission.manage': '配置权限',
  'system.audit.view': '查看操作日志',
  'customer.view': '查看客户管理',
  'customer.create': '新建客户（customer.create）',
  'customer.update': '编辑客户（customer.update）',
  'customer.delete': '删除客户资料（customer.delete）',
  'project.view': '查看项目管理',
  'project.create': '新建项目（project.create）',
  'project.update': '编辑项目（project.update）',
  'project.copy': '复制项目（project.copy）',
  'project.delete': '删除项目（project.delete）',
  'project.assign': '关联项目资料/分配项目成员（project.assign）',
  'experiment.view': '查看实验记录本',
  'experiment.create': '新建实验记录（experiment.create）',
  'experiment.update': '编辑实验记录（experiment.update）',
  'experiment.delete': '删除实验记录（experiment.delete）',
  'experiment.submit': '提交实验审核（experiment.submit）',
  'experiment.approve': '通过实验审核（experiment.approve）',
  'experiment.review': '退回/作废实验记录（experiment.review）',
  'knowledge.view': '查看研发知识库',
  'knowledge.upload': '上传知识文档（knowledge.upload）',
  'knowledge.create': '新建知识文档（knowledge.create）',
  'knowledge.update': '编辑/移动知识文档（knowledge.update）',
  'knowledge.delete': '删除知识文档（knowledge.delete）',
  'knowledge.submit': '提交知识文档审核（knowledge.submit）',
  'knowledge.review': '查看知识审核',
  'knowledge.approve': '通过知识文档审核（knowledge.approve）',
  'knowledge.publish': '发布知识文档（knowledge.publish）',
  'knowledge.export': '导出知识文档（knowledge.export）',
  'knowledge.download': '下载知识文档（knowledge.download）',
  'template.view': '查看模板中心（template.view）',
  'template.create': '新建模板（template.create）',
  'template.update': '编辑模板/移动分类/新建修订（template.update）',
  'template.upload': '上传模板文件（template.upload）',
  'template.copy': '复制模板（template.copy）',
  'template.recognition': '识别与复核模板字段（template.recognition）',
  'template.review': '提交/通过/驳回模板审核（template.review）',
  'template.publish': '发布模板（template.publish）',
  'template.rollback': '回退历史模板版本（template.rollback）',
  'template.delete': '删除/停用模板（template.delete）',
  'template.export': '导出模板（template.export）',
  'category.create': '新建模板分类（category.create）',
  'category.update': '编辑模板分类（category.update）',
  'category.delete': '删除模板分类（category.delete）',
  'production.view': '查看生产单',
  'production.create': '新建生产单（production.create）',
  'production.update': '编辑生产单草稿（production.update）',
  'production.delete': '删除生产单（production.delete）',
  'production.submit': '提交生产单（production.submit）',
  'production.cancel': '取消生产单（production.cancel）',
  'production.export': '导出生产单（production.export）',
  'data.view': '查看数据中心',
  'data.create': '新建/导入数据任务（data.create）',
  'data.update': '编辑数据任务（data.update）',
  'data.delete': '删除数据（data.delete）',
  'data.submit': '提交数据导入（data.submit）',
  'data.approve': '审核训练数据（data.approve）',
  'data.export': '导出数据（data.export）',
  'data.download': '下载数据（data.download）',
  'spectrum.view': '查看图谱中心',
  'spectrum.create': '新建图谱（spectrum.create）',
  'spectrum.update': '编辑图谱（spectrum.update）',
  'spectrum.delete': '删除图谱（spectrum.delete）',
  'spectrum.export': '导出图谱（spectrum.export）',
  'spectrum.download': '下载谱图数据（spectrum.download）',
  'ai.use': '使用 AI 研发助手',
  'ops.file.upload': '上传文件（ops.file.upload）',
  'ops.file.download': '下载文件（ops.file.download）',
};

const moduleLabels: Record<string, string> = {
  system: '系统设置',
  customer: '客户管理',
  project: '项目管理',
  template: '模板中心',
  experiment: '实验记录本',
  production: '生产管理',
  knowledge: '研发知识库',
  data: '数据中心',
  spectrum: 'AI图谱中心',
  ai: 'AI研发助手',
  ops: '文件管理',
};

export function moduleDisplayLabel(module: string): string {
  return moduleLabels[module.trim().toLowerCase()] || module;
}

export function riskDisplayLabel(risk: string): string {
  return { LOW: '低风险', MEDIUM: '中风险', HIGH: '高风险', CRITICAL: '极高风险' }[risk] || risk;
}

export function requiredPermissionForPath(pathname: string): string | undefined {
  const path = pathname.replace(/\/$/, '') || '/';
  if (path === '/assistant' || path === '/knowledge/search') return 'ai.use';
  if (path.startsWith('/system/users')) return 'system.user.view';
  if (path.startsWith('/system/roles') || path.startsWith('/system/user-permissions')) return 'system.permission.manage';
  if (path.startsWith('/system/audit-logs')) return 'system.audit.view';
  if (path.startsWith('/partners')) return 'customer.view';
  if (path.startsWith('/projects')) return 'project.view';
  if (path.startsWith('/experiments')) return 'experiment.view';
  if (path.startsWith('/knowledge/review')) return 'knowledge.review';
  if (path.startsWith('/knowledge')) return 'knowledge.view';
  if (path === '/templates/upload') return 'template.upload';
  if (path.startsWith('/templates') || path.startsWith('/render/import')) return 'template.view';
  if (path.startsWith('/production-orders')) return 'production.view';
  if (path.startsWith('/data')) return 'data.view';
  if (path.startsWith('/spectrum')) return 'spectrum.view';
  return undefined;
}

export function canViewPath(pathname: string, permissions: string[]): boolean {
  const required = requiredPermissionForPath(pathname);
  return !required || permissions.includes(required);
}

export function firstAccessiblePath(permissions: string[]): string | null {
  const candidates = [
    ['/assistant', 'ai.use'],
    ['/projects/list', 'project.view'],
    ['/templates/library', 'template.view'],
    ['/knowledge/view', 'knowledge.view'],
    ['/experiments/list', 'experiment.view'],
    ['/production-orders/list', 'production.view'],
    ['/data/view', 'data.view'],
    ['/spectrum/view', 'spectrum.view'],
    ['/partners', 'customer.view'],
    ['/system/users', 'system.user.view'],
  ] as const;
  return candidates.find(([, permission]) => permissions.includes(permission))?.[0] ?? null;
}

type MenuRoute = {
  path?: string;
  name?: string;
  icon?: unknown;
  routes?: MenuRoute[];
  [key: string]: unknown;
};

export function filterMenuRoute(route: MenuRoute, permissions: string[]): MenuRoute | null {
  const children = route.routes?.map((child) => filterMenuRoute(child, permissions)).filter((child): child is MenuRoute => child !== null);
  const permission = route.path ? requiredPermissionForPath(route.path) : undefined;
  if (permission && !permissions.includes(permission)) return null;
  if (route.routes && (!children || children.length === 0)) return null;
  return { ...route, ...(route.routes ? { routes: children } : {}) };
}
