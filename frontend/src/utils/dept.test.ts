import { describe, expect, it } from 'vitest';
import { flatDepts, type DeptOption } from './dept';
import type { DeptNode } from '../api/types';

describe('flatDepts', () => {
  it('扁平化多层部门树并保留父子顺序', () => {
    const node = (id: number, parentId: number, name: string, children: DeptNode[] = []): DeptNode => ({
      id,
      parentId,
      name,
      sortOrder: id,
      status: 1,
      children,
    });

    const tree: DeptNode[] = [
      node(1, 0, '研发部', [
        node(2, 1, '前端组'),
        node(3, 1, '测试组'),
      ]),
      node(4, 0, '财务部'),
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
