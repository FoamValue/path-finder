import { describe, expect, it } from 'vitest';
import { flatDepts, type DeptOption } from './dept';
import type { DeptNode } from '../api/types';

describe('flatDepts', () => {
  it('扁平化多层部门树并保留父子顺序', () => {
    const tree: DeptNode[] = [
      {
        id: 1,
        parentId: 0,
        name: '研发部',
        children: [
          { id: 2, parentId: 1, name: '前端组', children: [] },
          { id: 3, parentId: 1, name: '测试组', children: [] },
        ],
      },
      { id: 4, parentId: 0, name: '财务部', children: [] },
    ];

    const options = flatDepts(tree);

    expect(options.map((o) => o.value)).toEqual([1, 2, 3, 4]);
    expect(options[0].label).toBe('研发部');
    expect(options[1].label).toContain('前端组');
    expect(options[1].label.startsWith('　')).toBe(true);
  });

  it('空树返回空数组', () => {
    expect(flatDepts([])).toEqual([] as DeptOption[]);
  });

  it('无 children 字段的节点不报错', () => {
    const options = flatDepts([{ id: 9, parentId: 0, name: '组织' } as DeptNode]);
    expect(options).toEqual([{ value: 9, label: '组织' }]);
  });
});
