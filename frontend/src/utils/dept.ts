import type { DeptNode } from '../api/types';

export interface DeptOption {
  value: number;
  label: string;
}

/** 将部门树扁平化为 Select 选项，按层级缩进显示（支持子级部门） */
export function flatDepts(nodes: DeptNode[], depth = 0): DeptOption[] {
  const out: DeptOption[] = [];
  for (const n of nodes) {
    out.push({ value: n.id, label: `${'　'.repeat(depth)}${n.name}` });
    out.push(...flatDepts(n.children || [], depth + 1));
  }
  return out;
}
