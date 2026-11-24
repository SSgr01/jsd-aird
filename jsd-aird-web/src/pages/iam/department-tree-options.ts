import type { DepartmentNode } from '@/services/iam/iam-api';

export interface DepartmentTreeOption {
  key: string;
  value: string;
  title: string;
  children: DepartmentTreeOption[];
}

export function departmentTreeOptions(nodes: DepartmentNode[], valueMode: 'name' | 'id' = 'name'): DepartmentTreeOption[] {
  const ids = new Set(nodes.map((node) => node.id));
  const sorted = [...nodes].sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name, 'zh-CN'));
  const build = (parentId: string | null): DepartmentTreeOption[] =>
    sorted.filter((node) => parentId === null
      ? !node.parentId || !ids.has(node.parentId)
      : node.parentId === parentId).map((node) => ({
      key: node.id,
      value: valueMode === 'id' ? node.id : node.name,
      title: node.name,
      children: build(node.id),
    }));
  return build(null);
}
