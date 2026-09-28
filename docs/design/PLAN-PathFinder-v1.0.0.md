# PathFinder v1.0.0 — 代码编写计划（敏捷迭代）

| 项目 | 内容 |
|---|---|
| 关联文档 | PRD v1.0.0 / TSDD v1.0.0 |
| 团队规模 | 前端 1 人 + 后端 2 人 + 测试 1 人（共 4 人） |
| 迭代节奏 | 2 周 / Sprint，共 5 个 Sprint（含预备），单周 Sprint 评审 + 回顾 |
| 计划基准 | 2026-09 启动，2026-10 底发布 v1.0.0 |

---

## 1. 团队与协作约定

| 角色 | 职责 | 产出 |
|---|---|---|
| 后端 ×2 | 服务端实现、数据库、组件集成 | server/ 代码、DDL |
| 前端 ×1 | 页面、组件、协议对接 | frontend/ 代码 |
| 测试 ×1 | 用例执行、自动化回归 | 测试报告、E2E 脚本 |

协作约定：
- 每日 15 分钟站会；Sprint 末评审 + 回顾；产品（PRD 作者）随时答疑。
- 分支策略：`main`（受保护，仅 PR 合并）→ 功能分支 `feat/<编号>`。
- 提交遵循 Conventional Commits（见 PRD 5.2），PR 必须过静态检查 + 测试 + 覆盖率门禁。

## 2. 完成定义（DoD）

一个用户故事完成后必须满足：
1. 功能实现符合 PRD 验收标准，接口契约与 TSDD 一致。
2. 后端 JUnit 用例通过，核心模块行覆盖率 ≥ 85%（整体 ≥ 80%）；前端核心交互覆盖率 ≥ 70%。
3. Checkstyle / SpotBugs / ESLint / Prettier 零违规。
4. 关键路径输出审计日志（F7）。
5. 提交符合 Conventional Commits，PR 经 Review 合并。
6. 不残留 `FIXME`；`TODO` 需在评审说明理由。

## 3. 迭代规划总览

| Sprint | 主题 | 范围 | 里程碑验收 |
|---|---|---|---|
| S0（预备） | 脚手架 + 组件 POC | 工程初始化、CI、`upload-file` 集成验证 | 可运行骨架；组件 POC 结论 |
| S1 | 登录鉴权 + 组织管理 | U1~U13 / F1 / F2 | 安全登录闭环，管理员可管用户/部门 |
| S2 | 上传下载 + 磁盘持久化 | U14~U22, U29~U31 / F3 / F4 / F6 / F8（U29 落盘、U30 后端用量统计、U31 目录配置） | 分片上传/断点续传/下载端到端 |
| S3 | 文件管理 + 归属 + 审计 | U23~U28, U33~U35 / F5 / F7 / F9 | 真分页列表、搜索、软删除、归属变更、审计 |
| S4 | 回收站增强 + 存储看板 + 部署 | U32 备份 / U30 前端存储监控看板 / 部署 / E2E | 回收站清理、磁盘告警、Docker 上线、验收通过 |

## 4. Sprint 0：脚手架与组件验证（1 周）

> 目标：消除最大技术风险（第三方组件与 Spring Boot 4.1.1 / Redis 9 / JDK 26 兼容性），建立工程基线与 CI。

| 任务 | 内容 | 预估 | 验收标准 |
|---|---|---|---|
| PF-001 | 后端 Maven 骨架（Spring Boot 4.1.1，JDK 26），集成 Security/JPA/Redis；`BaseEntity`、全局异常、统一响应 | 1d | `mvn spring-boot:run` 启动；健康检查通过 |
| PF-002 | 前端脚手架（Ant Design Pro / UmiJS + TS strict），路由 + `access.ts` 骨架，登录页占位 | 1d | `npm run dev` 可访问 |
| PF-003 | 数据库初始化：PRD/TSDD 全部 DDL + Flyway（含 `V2__seed.sql`：四角色、根部门、首个 `admin` 账号，初始密码首登强制改密，见 TSDD 3.4） | 0.5d | 建表脚本在 MySQL 8 可执行；Seed 后 `admin` 可登录且强制改密 |
| PF-004 | **组件 POC**：引入 `cn.chenxinjie:upload-file:1.0.0-rc.5`（starter + store-redis），验证 `/upload /download` 与 Spring Boot 4.1.1 / Redis 9 / JDK 26 兼容 | 2d | 分片上传/断点续传/Range 下载 POC 通过；产出集成结论；失败则给出降级方案（TSDD 11） |
| PF-005 | CI 流水线：`main` 分支 PR 触发 build + test + 覆盖率门禁（JaCoCo/Jest）+ Checkstyle/ESLint | 1d | PR 自动运行且门禁生效 |

**Sprint 0 评审点**：组件兼容性结论（Go/No-Go 或降级方案）；骨架可运行。

## 5. Sprint 1：登录鉴权与组织管理（2 周）

| 任务 | 内容 | 关联 | 预估 | 验收标准 |
|---|---|---|---|---|
| PF-101 | 用户/角色/部门实体与 Repository | F2 | 1d | 数据访问层完成 |
| PF-102 | 图片验证码生成与校验（`CaptchaUtil` + Redis） | U3/F1 | 1d | 无/错验证码登录被拒；一次性失效 |
| PF-103 | RSA 密钥对 + 密码加解密 + BCrypt 存储 | U2/U35 | 1d | 抓包无明文；改密加密存储 |
| PF-104 | 登录/登出、会话 Redis 化、30min 滑动续期（**同步续期 `auth:user:session`**）；鉴权矩阵落地：`/api/**`、`/logout`、`/changePassword` 纳入会话校验，强制改密态白名单生效 | U1/U6/U8/U9 | 1d | 会话过期自动登出；/logout /changePassword 无会话被拒 |
| PF-105 | 失败锁定（5 次/10 分钟） | U4 | 0.5d | 计数正确、锁定期拒绝、到期解锁 |
| PF-106 | 多登录踢出（单会话覆盖） | U5 | 0.5d | 新登录后旧会话 401 |
| PF-107 | 首次强制改密 + `/changePassword` | U7 | 0.5d | 未改密仅能改密 |
| PF-108 | 部门管理（树 CRUD、删除约束） | U10 | 1.5d | 有子部门/部门内有文件禁止删除 |
| PF-109 | 用户管理（分页、增删改、停用、重置密码、指定角色）+ 账号生命周期文件处置校验（停用冻结个人文件、删除前强制移交） | U11/U12/U13/G12 | 2d | ADMIN 全量、DEPT_ADMIN 只读本部门；有个人文件的用户禁止直接删除 |
| PF-110 | 前端：登录页（验证码/RSA 加密/锁定提示）、改密页、用户/部门管理页 | F1/F2 | 3d | 端到端可用 |
| PF-111 | 后端安全测试（登录/锁定/踢出/权限）+ 前端登录交互测试 | F1/F2 | 1.5d | 覆盖率达标 |

**Sprint 1 评审点**：完整安全登录闭环；管理员可管理组织架构。

## 6. Sprint 2：上传下载与磁盘持久化（2 周）

| 任务 | 内容 | 关联 | 预估 | 验收标准 |
|---|---|---|---|---|
| PF-201 | `uploadTicket` + `file_info` 预注册（status=UPLOADING） | U14/U15 | 1d | 元数据先落库，返回 identifier |
| PF-202 | 前端 `useUploadTask`：对接组件分片协议（file 字段 + 7 参数）、进度展示、暂停/续传 | U14/U17/F3 | 2d | 分片上传协议与组件一致 |
| PF-203 | 断点续传/秒传：progress 查询跳过已传分片 | U16/U17 | 1d | 中断后续传内容完整 |
| PF-204 | mergeAsync 提交 + mergeStatus 轮询 + `/confirm`（注入 `ResumableUploadService` 按 identifier 解析合并产物路径 → 移动至统一存储，回填 MD5，同事务，见 TSDD 6.3 G3） | F3/F8 | 2d | 合并后入库；MD5 一致；失败自动重传分片；元数据不可达时返回明确错误 |
| PF-205 | 上传限制：`max-file-size`/`max-chunk-size`/同名后缀 | U18 | 0.5d | 超限拦截 400；同名 `xxx(1)` |
| PF-206 | 单文件下载（本地流式 + Range 206/416）+ 保留原始文件名 | U19/U21/F4 | 1d | 断点续传完整；文件名正确 |
| PF-207 | 批量 ZIP 打包下载（≤100，一次性 token） | U20/F4 | 1.5d | ZIP 可解压；超限拦截 |
| PF-208 | 磁盘存储：目录初始化（files/upload/del/tmp）、UUID+日期落盘、**UPLOADING 孤儿清理（24h，G2）** | U29/U31/F6 | 1d | 重启后文件可访问；目录自动创建；孤儿记录被清理并留痕 |
| PF-209 | 存储用量统计 + 85% 告警（结构化日志 + `STORAGE_ALERT` 落 `operation_log`） | U30 | 1d | 监控数据正确、告警日志输出 |
| PF-210 | 前端：文件上传弹窗（空间选择/进度/续传）+ 列表页下载 | F3/F4 | 2d | 端到端体验完成 |
| PF-211 | 上传/下载/断点续传测试（单元+集成+E2E 骨架） | F3/F4 | 2d | 大文件场景覆盖；覆盖率达标 |

**Sprint 2 评审点**：500MB 大文件从上传→断点续传→合并→下载全链路可用。

## 7. Sprint 3：文件管理、归属变更与审计（2 周）

| 任务 | 内容 | 关联 | 预估 | 验收标准 |
|---|---|---|---|---|
| PF-301 | 文件列表真分页（Specification + 数据权限过滤） | U23/F5 | 2d | 翻页只查当前页；total 准确；万级响应正常 |
| PF-302 | 文件名模糊搜索 | U24 | 0.5d | 命中准确 |
| PF-303 | 文件重命名（权限校验） | U25 | 0.5d | 非所有者/管理员被拒 |
| PF-304 | 软删除 + 回收站（记录、列表、恢复含 `del/ → files/` 迁回校验、期满物理清除） | U26 | 1.5d | 软删后列表不可见、可恢复（路径/部门校验）；30 天清除 |
| PF-305 | 文件元信息展示 | U27 | 0.5d | 大小/类型/上传人/时间正确 |
| PF-306 | 归属变更（空间/部门/归属人移交 + 越权拦截 + 即时生效） | U28/F9 | 2d | 变更后可见范围即时变化；越权 403 并留痕 |
| PF-307 | 操作审计：`AuditAspect` + 日志查询分页/导出 + **12 个月归档策略（G10）** | U33/U34/F7 | 2d | 关键操作全留痕；可按人/时间/类型筛选；超期归档清理正确 |
| PF-308 | 前端：文件列表页（筛选/搜索/分页/操作）、回收站页、归属变更弹窗、日志页 | F5/F7/F9 | 3d | 端到端可用 |
| PF-309 | 数据权限矩阵测试 + 归属变更/审计测试 | F5/F9/F7 | 1.5d | 越权全部拦截 |

**Sprint 3 评审点**：文件管理全功能 + 数据权限矩阵验证通过。

## 8. Sprint 4：回收站增强、部署与发布（2 周）

| 任务 | 内容 | 关联 | 预估 | 验收标准 |
|---|---|---|---|---|
| PF-401 | 回收站立即清除 + 归档目录清理调度完善 | U26 | 1d | 物理文件删除正确 |
| PF-402 | 存储监控页（管理员看板） | U30 | 1d | 展示与告警一致 |
| PF-403 | Docker 编排：nginx:alpine（**TLS 443 + 80 重定向、证书卷挂载**）+ server（RSA 密钥卷）+ redis:9（**requirepass**）+ mysql:8（**独立应用账号最小权限**），卷持久化，健康检查 | — | 1.5d | compose 一键启动；HTTPS 生效；重启不丢文件 |
| PF-404 | 备份脚本（存储目录 + archive + MySQL + Redis AOF/RDB） | U32 | 1d | 备份/恢复演练通过 |
| PF-405 | 前端 E2E 全流程（Playwright）：登录→上传→搜索→下载→归属→删除→审计 | F1~F9 | 2d | 关键路径自动回归 |
| PF-406 | 性能验证：真分页万级数据、并发上传 50 文件、缓存命中率 | 非功能 | 1.5d | 达标（PRD 6） |
| PF-407 | 全量回归 + 覆盖率复核 + 发布清单 + v1.0.0 打标签 | — | 1.5d | 全部 DoD 满足 |

**Sprint 4 评审点**：生产可发布，验收测试通过。

## 9. 测试策略

| 层次 | 工具 | 范围 | 门禁 |
|---|---|---|---|
| 单元测试 | JUnit 5 / Mockito | Service 逻辑、数据权限判定、锁定/踢出、归属变更 | 核心模块行覆盖 ≥85% |
| 集成测试 | Spring Boot Test + MySQL 8（测试库） | Controller 契约、真分页、审计落库 | 整体 ≥80% |
| 组件联调 | 集成测试直连组件 | 分片/断点续传/mergeAsync/Range | 端到端通过 |
| 前端单测 | Jest + RTL | 登录交互、上传流程、权限渲染、列表检索 | ≥70% |
| E2E | Playwright | S4 全流程 | 关键路径通过 |
| 性能 | 脚本压测 | 分页/并发上传/搜索 | 达标 PRD 6 |

## 10. 风险与依赖

| 风险 | 影响 | 应对 |
|---|---|---|
| 组件与 Spring Boot 4.1.1 不兼容 | 阻塞 S2 | S0 先行 POC（PF-004），留 1 周缓冲，降级方案就绪 |
| 前端协议对接偏差（multipart 字段/参数名） | 上传失败 | 以组件 README 契约为准；S2 第一周出前端联调冒烟 |
| 数据权限边界理解偏差 | 越权/误伤 | S1 评审时冻结权限矩阵（PRD 2.2 + 7）；S3 用矩阵用例回归 |
| 大文件 confirm 一致性 | 脏文件 | 同事务 + 残留清理兜底（TSDD 9.2） |

## 11. 依赖清单（供 Sprint 排期）

| 依赖 | 提供方 | 就绪点 |
|---|---|---|
| `cn.chenxinjie:upload-file:1.0.0-rc.5` 本地 mvn 仓库（`upload-file-core` + `upload-file-store-redis`，随组件仓库同版本推进） | 组件仓库 | S0 前（已就绪）；rc.4 升级补丁见 §12、rc.5 升级补丁见 §13 |
| 组件集成结论 | PF-004 | S0 末 |
| 权限矩阵冻结 | 评审 | S1 末 |
| 数据 DDL | PF-003 | S0 末 |
| 测试环境（MySQL/Redis/Docker） | 运维 | S1 前 |

---

## 12. 追加：组件 rc.4 升级补丁（v1.0.0 基线之上，2026-09）

> 背景：组件仓库已推进至 `1.0.0-rc.4`（新增 `getTask` 稳定读、`cancelUpload` 显式取消、`UploadErrorCode` 稳定错误语义，相对 rc.3 **只增不删**），而业务工程长期锁在 `1.0.0-rc.3`、从未试点（见 `docs/DEV-JOURNEY-REFLECTION.md` 盲点 1）。本补丁把该「自我升级滞后」闭环：依赖升级 → confirm 改用 rc.4 契约 → 显式回收 → 稳定错误码 → 回归。对应提交随本计划文档同期合入。

| 任务 | 内容 | 关联 | 状态 |
|---|---|---|---|
| PF-501 | 依赖升级：`server/pom.xml` 中 `upload-file-core` / `upload-file-store-redis` `1.0.0-rc.3 → 1.0.0-rc.4`（JDK 23+ javac 需显式 `-proc:full` 保证 Lombok 生效） | TSDD §6.1 / README | 已完成 |
| PF-502 | confirm 产物定位改用 rc.4 `getTask(identifier)` 的 `finalPath`（`FileService.resolveMergedProduct`），兼容旧版目录约定回退 | TSDD §6.3（G3） | 已完成 |
| PF-503 | confirm 入库成功后调用 `cancelUpload(identifier)` 显式回收任务/残留（`FileService.cleanupUploadTask`；异步合并 PENDING/RUNNING 期抛 409，等结束后重试） | TSDD §6.3 / §8.2 | 已完成 |
| PF-504 | `UploadController` 新增 `POST /upload?action=cancel`（返回 `{canceled}`）；缺失分片/非法 action 改由 `BizException.badRequest` 明确返回 400 | PRD F8 / TSDD §4.7 | 已完成 |
| PF-505 | `GlobalExceptionHandler` 按 rc.4 `UploadErrorCode` 注册稳定映射：400（参数/校验）/ 404（任务不存在）/ 409（合并中）/ 507（配额），不透传内部细节 | TSDD §9.3 | 已完成 |
| PF-506 | 回归：`FileUploadFlowTest` 增补 confirm/取消用例；新增 `UploadFileErrorMappingTest`（类型化异常 → HTTP 状态码）；E2E 全链路复跑 | TESTCASES / §9 测试策略 | 已完成 |

**验收标准**：`mvn test` 全绿；上传 → 合并 → confirm → 下载主链路无回归；中断/取消后任务可复用同 identifier 重传；全仓库文档无 `1.0.0-rc.3` 残留引用。

---

## 13. 追加：组件 rc.5 升级补丁与 jakarta starter 决策（v1.0.0 基线之上，2026-09）

> 背景：组件仓库推进至 `1.0.0-rc.5`（相对 rc.4 的 core/store 逻辑零改动，实质新增 `upload-file-servlet-jakarta` / `upload-file-spring-boot-starter-jakarta` 与 Boot 4 demo），而业务工程仍锁在 `1.0.0-rc.4`——盲点 1「自我升级滞后」再次出现。rc.4 收口已验证该流程（见 §12），本次按同一流程闭环，并把「组件新增 jakarta starter 后是否迁移」这一新决策点用 ADR 成文（`docs/design/ADR-001-upload-file-starter-jakarta.md`），避免「已知说明式」的债无人评估。

| 任务 | 内容 | 关联 | 状态 |
|---|---|---|---|
| PF-601 | 依赖升级：`server/pom.xml` 中 `upload-file-core` / `upload-file-store-redis` `1.0.0-rc.4 → 1.0.0-rc.5`（core/store 逻辑不变，升级仅版本对齐） | TSDD §6.1 / README | 已完成 |
| PF-602 | 分析 `upload-file-spring-boot-starter-jakarta` 直接可用性：`/upload` 路由冲突、Bean 半冲突（`@ConditionalOnMissingBean` 回退 + 无条件 Servlet 注册）、错误体契约、下载安全面等，结论为**不迁移**（继续 core 手工装配），输出 ADR-001（含撤销/迁移触发条件） | TSDD §6.1/§11 / ADR-001 | 已完成 |
| PF-603 | 回归验证：`mvn compile` + `UploadFileErrorMappingTest`（rc.4/rc.5 类型化异常映射）全绿；确认 rc.5 本地仓库可解析 | TESTCASES | 已完成 |
| PF-604 | 文档同步 rc.5：README / TSDD / PRD / PLAN / CHANGELOG 当前坐标与「未启用 starter」表述更新，登记 ADR-001；`docs/DEV-JOURNEY-REFLECTION.md` 补 rc.5 收口 | 全仓库 | 已完成 |

**验收标准**：`mvn test` 全绿；全仓库当前坐标引用为 `1.0.0-rc.5`（`rc.4` 仅作为「引入某契约的版本」历史标注保留）；「为何不用 jakarta starter」有 ADR-001 成文并在 README/TSDD/PRD 中被引用；rc.5 尚未发布至 Maven Central 一事已在 CHANGELOG 明示（发布前新环境需 `mvn install` 或等待中央仓库）。

---

## 14. 追加：组件 rc.6 升级与迁移到官方 starter-jakarta（v1.0.0 基线之上，2026-09）

> 背景：组件 `1.0.0-rc.6` 是「商业化 HTTP 层可接入」版本，专门回应本工程 [ADR-001](ADR-001-upload-file-starter-jakarta.md)/[UPGRADE](UPGRADE-upload-file-starter-jakarta.md) 评估——`/download` 默认关闭、`AccessControl.decide()` 返回 403、`AccessControlListener` 审计、`UploadErrorRenderer`/`endpoint`/`http`/`multipart` 可控。ADR-001 §6 撤销条件 #2/#3 满足，故执行迁移预案（C1~C8）。

| 任务 | 内容 | 关联 | 状态 |
|---|---|---|---|
| PF-701 | 依赖迁移：`server/pom.xml` `upload-file-core` → `upload-file-spring-boot-starter-jakarta:1.0.0-rc.6`，保留 `upload-file-store-redis:1.0.0-rc.6` | TSDD §6.1 / ADR-001 | 已完成 |
| PF-702 | 删除手工装配与自研端点：`UploadFileConfig`、`UploadController`；`/upload` 由组件 `UploadServlet` 承载 | UPGRADE C2/C3 | 已完成 |
| PF-703 | 配置对齐 rc.6：`endpoint.upload-enabled=true` / `download-enabled=false`、`http.error-body=legacy`、`multipart.strategy=component`、`cleanup.use-redis-lock=true`、`observability.access-log=false`；测试 profile 关闭 cleanup/redis-lock | UPGRADE C5 | 已完成 |
| PF-704 | AccessControl/审计迁移：`UploadOwnerAccessControl` 覆写 `decide()` → 403；新增 `UploadAccessAuditListener`（`AccessControlListener`）在决策点写 `FORBIDDEN success=0`；`GlobalExceptionHandler` 组件异常映射降级为防御性兜底 | UPGRADE C6 / TSDD §9.3 | 已完成 |
| PF-705 | 测试：新增 `UploadOwnerAccessControlTest` / `UploadAccessAuditListenerTest` / `UploadEndpointWiringTest`；`FileUploadFlowTest` 等集成链路复跑；E2E 全链路 | TESTCASES | 已完成 |
| PF-706 | 文档同步 rc.6：ADR-001 改「已接受 - 已迁移」、UPGRADE 改「已执行」并补 §14、README / TSDD / PRD / PLAN / CHANGELOG / DEV-JOURNEY 同步 | 全仓库 | 已完成 |

**验收标准**：`mvn test` 全绿；`/upload` 由组件 Servlet 服务且成功体与前端假设一致（前端零改动）；越权在 Servlet 路径返回 403 且落 `FORBIDDEN` 审计（X1 不回归）；`/download` 默认不注册；全仓库当前坐标引用为 `1.0.0-rc.6`，无 `UploadFileConfig`/`UploadController` 残留。

---

## 15. 追加：组件 rc.7 升级（存储正确性与扩展点一致性收口，2026-09）

> 背景：rc.6 迁移完成后，本工程向组件提交「rc.6 迁移评审反馈」（组件仓库 `doc/user-feedback/upload-file-rc6-migration-feedback.md`），列出 P0（`RedisTaskStore` 索引泄漏/N+1、starter 未消费宿主 `UploadErrorRenderer`、`multipart.strategy=component` 默认无上限）与 P1（进程内 `IdentifierLock`、非原子配额、受信读 API 命名、`AccessControl.check()` 默认实现）。组件 rc.7 逐条收口并发布。

| 任务 | 内容 | 关联 | 状态 |
|---|---|---|---|
| PF-801 | 依赖升级：`upload-file-spring-boot-starter-jakarta` / `upload-file-store-redis` `1.0.0-rc.6 → 1.0.0-rc.7` | TSDD §6.1 | 已完成 |
| PF-802 | 代码适配：`ResumableUploadService.getTask(String)` rc.7 起 `@Deprecated`；新增 `UploadTrustedConfig` 声明 `TrustedUploadService` Bean，`FileService.resolveMergedProduct` 改用受信读；`FileUploadFlowTest` 改用 `getTaskTrusted` | rc.7 受信读 API | 已完成 |
| PF-803 | 配置核对：`multipart.strategy=component` 默认推导变化对本工程无影响（已显式设 `max-request-size`）；`lock.*`/`quota.store` 保持默认（单实例），在 yml 注释登记可选值 | TSDD §6.2 | 已完成 |
| PF-804 | 回归验证：`mvn test` 全绿（161 例 / 0 失败），含 `FileUploadFlowTest` confirm 链路与 `UploadEndpointWiringTest` 装配 | TESTCASES | 已完成 |
| PF-805 | 文档同步 rc.7：README / TSDD / PRD / PLAN / CHANGELOG / ADR-001 / UPGRADE 当前坐标与 rc.7 能力更新 | 全仓库 | 已完成 |

**验收标准**：`mvn test` 全绿；全仓库当前坐标引用为 `1.0.0-rc.7`；`/upload` 契约与成功体不变、前端零改动；无编译期弃用告警（`getTask` 已迁 `TrustedUploadService`）。

---

## 16. 追加：组件 rc.8 升级（GA 前最后一批能力，2026-09）

> 背景：rc.8 是 `1.0.0` GA 前最后一个 rc，合并后 API 与 `upload-file.*` 属性面冻结。本工程在 rc.7 基础上升级，收口版本管理（BOM）与新增能力（配额自动对账、分布式锁续租、审计上下文、starter 自动装配受信读门面），为 PF-901「组件 GA 化」铺路。

| 任务 | 内容 | 关联 | 状态 |
|---|---|---|---|
| PF-1001 | 依赖升级：`server/pom.xml` 引入 `upload-file-bom:1.0.0-rc.8`（`dependencyManagement`/`import`），starter-jakarta 与 store-redis 去显式 version；`upload-file.version` 单点锁 | TSDD §6.1 / ADR-001 | 已完成 |
| PF-1002 | 代码适配：删除 `UploadTrustedConfig`（rc.8 starter 自动装配 `TrustedUploadService`）；`UploadAccessAuditListener` 覆写 6 参 `onDecision(AccessContext, ...)` 写入 method/URI/IP/UA；`LogService` 新增带 IP/UA 的 `record` 重载 | rc.8 审计上下文 / 受信读自动装配 | 已完成 |
| PF-1003 | 配置核对：显式 `trusted-upload-service.enabled=true`、`observability.access-log-scope=task`；补 `lock.renew-interval` / `quota.store=redis` 自动对账注释；单实例默认 `local` 锁 + `task-store` 配额不变 | TSDD §6.2 | 已完成 |
| PF-1004 | 回归验证：`mvn test` 全绿（163 例 / 0 失败），`UploadEndpointWiringTest` 增补 `TrustedUploadService` 装配断言，`UploadAccessAuditListenerTest` 增补上下文用例 | TESTCASES | 已完成 |
| PF-1005 | 文档同步 rc.8：README / TSDD / PRD / PLAN / CHANGELOG / ADR-001 / UPGRADE / DEV-JOURNEY 当前坐标与 rc.8 能力更新 | 全仓库 | 已完成 |

**验收标准**：`mvn test` 全绿；依赖树仅 `1.0.0-rc.8`；`/upload` 契约与成功体不变、前端零改动；越权审计含请求上下文（method/URI/IP/UA）；无 `UploadTrustedConfig` 残留。

---

## 17. 追加：v1.0.0 发布治理（GA Gate，2026-09）

> 背景：功能与文档闭环（G1~G12、X1~X3）已具备，但「发布治理」尚未成型——正式版仍依赖 RC 组件、CHANGELOG 未收口、500 响应泄露内部信息、无 CI/质量门禁、生产默认凭据偏弱。本节把评审结论转成带验收的任务卡：**P0 为发布阻断项，P1 为发版前应完成项**；全部完成后 v1.0.0 方可对外打 tag。

| 任务 | 优先级 | 内容 | 关联 | 验收标准 |
|---|---|---|---|---|
| PF-901 | P0 | **组件 GA 化与依赖对齐**：`upload-file` 由 `1.0.0-rc.7` 发布为 GA（`1.0.0`）并推送 Maven Central/私服；`server/pom.xml` 仅引用 GA 坐标；新增 `maven-enforcer-plugin`（release profile 禁 `SNAPSHOT`/`-rc.`、校验 JDK 26 与 Maven 3.9+）与 `dependencyManagement` 单点锁组件版本 | PRD F8 / TSDD §11 / CHANGELOG | 干净环境（清空本地 `.m2` 组件缓存）可 `mvn -Prelease verify` 成功；`help:effective-pom` 无 RC/SNAPSHOT 依赖 |
| PF-902 | P0 | **CHANGELOG 版本收口**：把 `[Unreleased]` 全部变更归并至 `[1.0.0]` 并冻结发布范围；产出发布清单（tag、镜像、组件版本、回滚点） | CHANGELOG / PLAN §15 | `[Unreleased]` 为空；`git tag v1.0.0` 内容与 CHANGELOG 一致 |
| PF-903 | P0 | **500 错误脱敏**：`GlobalExceptionHandler.handleOther` 改为固定文案（`系统内部错误，请稍后重试`），细节仅入服务端日志；新增回归断言「响应体不含绝对路径/异常类名」 | DEV-JOURNEY 盲点3 / `GlobalExceptionHandler.java:128` | 新增 `GlobalExceptionHandlerTest` 通过；构造异常时响应体无内部细节 |
| PF-904 | P0 | **CI 与质量门禁**：新增 `.github/workflows/ci.yml`（PR 触发 build + `mvn test` + JaCoCo 整体 ≥80%/核心 ≥85% + Checkstyle/SpotBugs + 前端 Vitest ≥70% + ESLint/Prettier），失败阻塞合并，覆盖率报告回注 PR | PRD §6 / PLAN PF-005 / TESTCASES X6 | PR 上 CI 绿且门禁生效；故意降覆盖率或违规可阻断合并 |
| PF-905 | P0 | **生产默认凭据收紧**：启动期弱口令校验（MySQL/Redis/admin）或强制首登改密；确认 `CAPTCHA_ENABLED=false`、`ADMIN_BOOTSTRAP_PASSWORD` 仅测试 profile 可达，生产禁用 | PRD F1 / `application.yml:12,40-42` | 弱默认口令启动被拒或告警；生产 profile 下测试开关不生效 |
| PF-906 | P1 | **性能基线（补 TC-PERF）**：造数脚本（5000 文件、50 并发上传、Range 下载、千级搜索）跑一次并记录基线；未达标项在 PRD §6 标注「未验证/待优化」 | PLAN PF-406 / TESTCASES TC-PERF / PRD §6 | 产出性能报告；PRD NFR 状态与报告一致 |
| PF-907 | P1 | **可复现构建**：后端测试引入 Testcontainers（MySQL/Redis），E2E 收敛为 `make test` 一键；扫描并清除仓库内绝对路径（如 `/Users/...`） | TESTCASES X7 / DEV-JOURNEY 盲点5 | 新机器无需手动起栈即可 `make test`；仓库无绝对路径 |
| PF-908 | P1 | **安全流水线**：CI 增加依赖漏洞扫描（OWASP dependency-check/OSV）、密钥扫描（gitleaks）、`npm audit`，高危即阻断 | DEV-JOURNEY 建议5 / TESTCASES X6 | 扫描入 CI；高危依赖或泄露密钥可阻断合并 |
| PF-909 | P1 | **运维就绪**：交付《发布/运维手册》（升级、回滚、备份恢复演练、健康检查、磁盘告警通知渠道），`backup.sh` 升级为可演练 | REVIEW G9 / PLAN PF-404 / `scripts/backup.sh` | 备份→恢复演练通过；告警有明确通知渠道；手册评审通过 |

**发布门禁**：PF-901 ~ PF-905 全绿 + PF-902 发布清单签署后，方可执行 PF-407「v1.0.0 打标签」；PF-906 ~ PF-909 若未完成，须在发布说明的「已知限制」中逐条列明并给出 v1.1 排期，不得对外宣称对应 NFR 已达标。

**验收标准**：干净环境可复现构建；`mvn -Prelease verify` 通过且无 RC/SNAPSHOT 依赖；CI 门禁生效；500 响应无内部信息；生产默认凭据安全；发布清单与 tag 一致。
