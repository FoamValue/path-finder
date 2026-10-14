# PathFinder 文档索引

本目录集中维护 PathFinder 项目的全部规格与技术文档。文档采用统一编号：`Dnn-<类型>-<主题>-<版本>.md`，编号与主题稳定，重命名/重排章节不允许改动编号。

## 文档一览

| 编号 | 文档 | 类型 | 定位 | 状态 |
|---|---|---|---|---|
| D01 | [PRD-PathFinder-v1.0.0](D01-PRD-PathFinder-v1.0.0.md) | 产品需求 | 产品定位、目标形态与 F1~F9 功能需求 | 基线 v1.0.0 |
| D02 | [TSDD-PathFinder-v1.0.0](D02-TSDD-PathFinder-v1.0.0.md) | 技术设计 | 技术架构、组件设计与接口规范 | 基线 v1.0.0 |
| D03 | [PLAN-PathFinder-v1.0.0](D03-PLAN-PathFinder-v1.0.0.md) | 迭代计划 | 敏捷迭代规划、任务卡与验收标准 | 基线 v1.0.0 |
| D04 | [TESTCASES-PathFinder-v1.0.0](D04-TESTCASES-PathFinder-v1.0.0.md) | 测试用例 | 单元/集成/组件联调/前端/E2E 用例 | 基线 v1.0.0 |
| D05 | [REVIEW-PathFinder-v1.0.0](D05-REVIEW-PathFinder-v1.0.0.md) | 文档审查 | 规格链生产上线就绪度审查 | 未就绪（2 矛盾 + 5 P0） |
| D06 | [USER-MANUAL-PathFinder-v1.0.0](D06-USER-MANUAL-PathFinder-v1.0.0.md) | 使用手册 | 面向管理员/运维/普通用户的操作指南 | 适用 v1.0.0 |
| D07 | [SYNC-directory-scan-design-v1.0.0](D07-SYNC-directory-scan-design-v1.0.0.md) | 专项设计 | 目录定时扫描与磁盘/数据库一致性 | 已评审通过 |
| D08 | [ADR-001-upload-file-starter-jakarta](D08-ADR-001-upload-file-starter-jakarta.md) | 架构决策 | 是否迁移 `upload-file` jakarta starter | 已接受 - 已迁移（rc.8） |
| D09 | [UPGRADE-upload-file-starter-jakarta](D09-UPGRADE-upload-file-starter-jakarta.md) | 迁移方案 | 组件迁移执行清单与回滚预案 | 已执行 |

> 独立文档（不带编号，非规格链）：[DEV-JOURNEY-REFLECTION](DEV-JOURNEY-REFLECTION.md) — 开发历程共建反思。

## 阅读顺序（推荐）

1. 了解产品做什么 → **[D01 PRD](D01-PRD-PathFinder-v1.0.0.md)**
2. 了解怎么实现 → **[D02 TSDD](D02-TSDD-PathFinder-v1.0.0.md)**（需组件迁移背景时先读 D08/D09）
3. 了解怎么排期交付 → **[D03 PLAN](D03-PLAN-PathFinder-v1.0.0.md)**
4. 了解怎么验证 → **[D04 TESTCASES](D04-TESTCASES-PathFinder-v1.0.0.md)**
5. 上线前就绪度评估 → **[D05 REVIEW](D05-REVIEW-PathFinder-v1.0.0.md)**
6. 日常使用 → **[D06 USER-MANUAL](D06-USER-MANUAL-PathFinder-v1.0.0.md)**
7. 专项背景 → **[D07 目录同步](D07-SYNC-directory-scan-design-v1.0.0.md)、[D08 ADR-001](D08-ADR-001-upload-file-starter-jakarta.md)、[D09 UPGRADE](D09-UPGRADE-upload-file-starter-jakarta.md)**

## 编号约定

- `D01`~`D05`：规格链主干（需求→设计→计划→测试→审查），按生命周期顺序编号。
- `D06`：面向交付对象的文档（使用手册）。
- `D07`~`D09`：专项设计与决策（SYNC / ADR / UPGRADE）。
- 后续新增文档按既有编号顺序追加（D10 起）；已发布的文档编号不再复用。
- `DEV-JOURNEY-REFLECTION` 等非规格类笔记不占编号。