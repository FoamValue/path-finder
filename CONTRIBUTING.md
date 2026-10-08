# Contributing / 贡献指南

欢迎贡献！PathFinder 是一个自维护的个人开源项目，但任何 Bug 修复、文档改进、测试补充都受欢迎。参与前请阅读本指南。

## 行为准则 / Code of Conduct

请保持友善、专业，尊重不同意见。拒绝人身攻击、骚扰与不相关话题。

## 起步 / Getting Started

```bash
# 克隆
git clone https://github.com/FoamValue/path-finder.git
cd path-finder

# 后端（需 JDK 26 + Maven）
cd server && mvn test

# 前端（需 Node 22+）
cd ../frontend && npm ci && npm run build
```

后端测试依赖 MySQL 8 与 Redis（参数可经 `MYSQL_HOST` / `REDIS_HOST` 等环境变量注入，见 `application-test.yml` default 值）。

## 如何贡献 / How to Contribute

### 1. 提 Issue
- 先搜索是否已存在相关 Issue。
- Bug 请附复现步骤、期望/实际行为、环境（OS / JDK / 版本）。
- 功能建议请说明使用场景与预期收益。

### 2. 提交 Pull Request
- 先派生（fork）仓库并新建功能分支：`feat/xxx` 或 `fix/xxx`。
- **一个 PR 只解决一个问题/特性**，保持改动最小化。
- 遵循现有代码风格（Java / React / 命名规范见 `docs/` 与代码注释）。
- 为新增逻辑补充**单元测试**；改动行为需通过既有测试，避免破坏现有功能。
- 本地自测：后端 `mvn test`、前端 `npm test && npm run build` 全绿。
- 提交信息使用中文 `type(scope): 描述` 约定（如 `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `build:`, `ci:`）。
- PR 标题简短（< 70 字），正文说明动机与改动要点。

### 3. 分支与发布 / Branching & Releases
- 默认分支为 `main`，`main` 始终可用。
- 语义化版本（SemVer）；`v1.x.y` tag 表示发布点。
- `CHANGELOG.md` 记录变更；进入 `[Unreleased]` 或随发布的版本小节。

## 质量要求 / Quality Bar
- 后端：`mvn test` 全绿（含授权/审计/上传流等）。
- 前端：`npm test`（Vitest）全绿；`npm run build`（tsc + vite）通过。
- 行为变更需在 `CHANGELOG.md` 登记。

感谢你的贡献！