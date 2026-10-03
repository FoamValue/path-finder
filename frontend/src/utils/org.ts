import type { OrgNode } from '../api/types';

export interface OrgOption {
  value: number;
  label: string;
}

/** 将组织树扁平化为 Select 选项，按层级缩进显示（支持子级组织） */
export function flatOrgs(nodes: OrgNode[], orgh = 0): OrgOption[] {
  const out: OrgOption[] = [];
  for (const n of nodes) {
    out.push({ value: n.id, label: `${'　'.repeat(orgh)}${n.name}` });
    out.push(...flatOrgs(n.children || [], orgh + 1));
  }
  return out;
}
