import { describe, expect, it } from 'vitest';

import { departmentTreeOptions } from './department-tree-options';

describe('departmentTreeOptions', () => {
  it('keeps department hierarchy while using names as form values', () => {
    const options = departmentTreeOptions([
      { id: 'child', name: '技术部', parentId: 'root', sortOrder: 10, userCount: 1 },
      { id: 'root', name: 'AI研发平台', parentId: null, sortOrder: 10, userCount: 3 },
      { id: 'leaf', name: '主管', parentId: 'child', sortOrder: 10, userCount: 0 },
    ]);

    expect(options).toEqual([{
      key: 'root', value: 'AI研发平台', title: 'AI研发平台', children: [{
        key: 'child', value: '技术部', title: '技术部', children: [{
          key: 'leaf', value: '主管', title: '主管', children: [],
        }],
      }],
    }]);
    expect(departmentTreeOptions([
      { id: 'root', name: 'AI研发平台', parentId: null, sortOrder: 10, userCount: 3 },
    ], 'id')[0]?.value).toBe('root');
  });

  it('orders siblings by sort number', () => {
    const options = departmentTreeOptions([
      { id: 'second', name: '二科', parentId: 'root', sortOrder: 30, userCount: 0 },
      { id: 'root', name: '技术部', parentId: null, sortOrder: 10, userCount: 0 },
      { id: 'first', name: '一科', parentId: 'root', sortOrder: 20, userCount: 0 },
    ]);
    expect(options[0]?.children.map((node) => node.title)).toEqual(['一科', '二科']);
  });
});
