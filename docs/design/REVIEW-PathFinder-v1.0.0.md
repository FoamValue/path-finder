# PathFinder v1.0.0 — 文档审查报告（生产上线基准）

| 项目 | 内容 |
|---|---|
| 审查对象 | PRD / TSDD / PLAN / TESTCASES（v1.0.0） |
| 审查视角 | 资深产品经理，以**可生产环境上线**为基准 |
| 审查结论 | **未就绪**：存在 2 处前后矛盾 + 5 项 P0 生产阻断性遗漏，补齐后可达上线标准 |

---

## 一、前后矛盾

### C1. 下载端点体系描述不一致（P0，需统一）

| 位置 | 内容 |
|---|---|
| PRD F4（4.4） | 下载基于组件 `GET /download?identifier=xxx`（Range 断点） |
| TSDD 6.4 | 已入库文件由 PathFinder 本地流式下载（`/api/file/download/{token}`），组件 `/download` 仅"未入库临时文件/联调兜底" |
| TSDD 2.1 架构图 | nginx 将 `/upload /download` 反代后端"鉴权后透传" |
| TSDD 5.1 认证链 | `UploadAuthFilter` 作用于 `/upload /download`（含下载） |

**问题**：TSDD 6.4 已推翻"下载走组件 /download"的设计，但 2.1/5.1/4.7 仍按"组件 /download 承载下载"描述，内部角色（透传 vs 兜底）不一致；且与 PRD F4 的字面描述存在差异。

**建议**：冻结设计决定——下载统一走 `/api/file/download/{token}`（本地流式 Range）；`/download` 组件端点仅保留上传侧联调兜底并标注"v1.0 实际不承载业务下载"。同步修改 2.1/5.1/4.7 与 PRD F4 表述，保持三文档一致。

### C2. Sprint 范围重叠（P1）

| 位置 | 内容 |
|---|---|
| PLAN S2 | 范围 `U14~U22, U29~U31`（含 U30 存储监控、U31 目录配置） |
| PLAN S4 | 范围 `U30~U32`（含 U30 存储监控） |

**问题**：U30（磁盘监控）、U31（存储目录配置）在 S2 与 S4 重复出现；S4 同时出现 U30（PF-402 监控页）与 S2 PF-209（存储用量统计）。

**建议**：将磁盘监控/U30 与目录配置/U31 收敛到 S2（随存储落地），S4 仅保留 U32 备份 + 部署 + E2E；修订两处范围列。

---

## 二、生产上线遗漏（Gap）

### P0（阻断上线，必须补齐）

| # | 遗漏 | 位置 | 说明与建议 |
|---|---|---|---|
| G1 | **初始数据方案缺失** | TSDD 3/PLAN S0 | `sys_role` 四个角色（ADMIN/DEPT_ADMIN/USER/VIEWER）与**首个系统管理员账号**无初始化方案。F1 前置"账号由管理员创建"存在鸡生蛋问题。建议：启动初始化 Seed（Flyway 迁移插入角色 + 首个 ADMIN 账号，初始密码强制首登改密），并在 PLAN PF-003 补充该任务 |
| G2 | **UPLOADING 孤儿记录清理缺失** | TSDD 8.2 | 用户上传中断后放弃：组件 `cleanup.task-ttl=24h` 只清理组件分片/元数据，`file_info` 的 UPLOADING 记录会永久残留。建议：`StorageCleanupScheduler` 增加对 `status=UPLOADING` 且超过 24h 记录的清理（联动组件孤儿清理），并在 TESTCASES 增补用例 |
| G3 | **confirm 阶段合并产物定位方式未定义** | TSDD 6.3/9.2 | `mergeStatus=SUCCEEDED` 后，后端如何拿到组件合并产物的物理路径未写明（组件 `UploadResult` 含路径，需通过 TaskStore/存储 SPI 查询或由组件回调）。建议：设计层明确——confirm 时通过 `ResumableUploadService`/`TaskStore` 获取产物路径，或前端回传，二选一冻结 |
| G4 | **HTTPS/TLS 缺失** | TSDD 10 | 架构图为 HTTPS，部署设计无 TLS 证书、nginx 443 监听、证书挂载方案。生产必须。建议：nginx:alpine 挂载证书 + 443/80 重定向，写入部署设计 |
| G5 | **`/logout`、`/changePassword` 鉴权归属未定义** | TSDD 5.1 | 认证链中 `JwtSessionFilter` 仅覆盖 `/api/**`，但 `POST /logout`、`POST /changePassword` 不在其下（且 `changePassword` 涉及首次强制改密权限判断），无鉴权与权限定义。建议：明确这两个端点纳入认证链（或调整路径统一为 `/api/**`），并定义"仅允许改密"态的接口白名单 |

### P1（上线前应补齐）

| # | 遗漏 | 位置 | 说明与建议 |
|---|---|---|---|
| G6 | 滑动续期未同步刷新会话映射 | TSDD 5.2/7.2 | 活跃用户 `auth:session` 滑动续期，但 `auth:user:session:{userId}`（踢出用）未同步续期，超 30min 过期后映射丢失。建议：续期时一并刷新 |
| G7 | 回收站恢复路径未定义 | TSDD 8.2 | 软删除移入 `del/`，恢复需 `del/ → files/{date}/{uuid}` 迁回，设计未写明（TESTCASES TC-FILE-010 有预期但无实现依据）。建议：补充恢复规则，且恢复需校验原部门/存储路径仍有效 |
| G8 | 中间件访问认证缺失 | TSDD 10 | 部署未定义 Redis/MySQL 密码认证、最小账号权限。生产必须。建议：compose 配置密码 + 独立账号 |
| G9 | 磁盘告警无通知渠道 | TSDD 8.2 | 告警仅"输出日志"。生产建议：日志外接邮件/Webhook（v1.1 可排期，v1.0 至少说明告警日志落点与人工巡检） |
| G10 | 操作日志表无归档/清理策略 | TSDD 3.2 | `operation_log` 持续膨胀无上限。建议：定义保留周期（如 1 年）与归档任务，写入 F7 或运维设计 |
| G11 | RSA 密钥生命周期边界 | TSDD 5.2 | 密钥对启动时生成，重启后旧公钥密文无法解密；需强制前端每次登录页加载拉取新公钥（当前仅"登录失败可刷新"）。建议：登录页每次渲染拉取公钥 |
| G12 | 数据权限变更波及未定义 | TSDD 5.3/7.3 | 用户调部门/停用后，其个人空间文件（PERSONAL）与部门归属的可见性联动未说明（如离职用户文件处置）。建议：明确"用户停用/删除时个人文件归属处置规则"（归部门/移交/冻结） |

### P2（可延后）

| # | 遗漏 | 说明 |
|---|---|---|
| G13 | 前端配额/容量提示 | 上传前展示剩余配额（依赖 quota），v1.1 排期 |
| G14 | 组件错误码前端映射表 | TSDD 9.3 已提，建议产出显式映射清单（400/401/404/416/507 ↔ 文案） |
| G15 | 上传并发/带宽控制 | 50 并发上传的性能目标已定义，但无限流/队列设计，v1.0 单机可接受，建议观察 |

---

## 三、资源与进度评估

- PLAN 任务预估合计 ≈ **54.5 人日**（前端独立线 + 后端并行线），5+1 Sprint（9 周）与"2026-09 启动、10 月底发布"基本匹配，但**未含 G1~G5 新增工作量与缓冲**；建议将 G1~G5 纳入 S0/S1，并预留 1 周发布缓冲。

---

## 四、建议行动清单

| 优先级 | 动作 | 负责 |
|---|---|---|
| 立即 | 冻结 C1 下载设计，修订 PRD F4 / TSDD 2.1、4.7、5.1、6.4 表述一致 | 架构 |
| 立即 | 补齐 G1 初始数据（角色 + 首登 ADMIN）、G5 端点鉴权归属 | 架构/后端 |
| S0 前 | G3 confirm 产物定位冻结、G4 TLS 方案、G8 中间件认证 | 架构/运维 |
| S2 内 | G2 UPLOADING 孤儿清理、G6 会话映射续期、G7 恢复路径、G11 公钥策略 | 后端 |
| S3 内 | G9 告警落点、G10 日志归档策略、G12 离职文件处置规则 | 产品/后端 |
| 长期 | G13~G15 排入 v1.1 | 产品 |

---

## 五、修订进展（2026-08-30）

以下问题已在文档中修复：

| 编号 | 状态 | 修订位置 |
|---|---|---|
| C1 | ✅ 已修复 | PRD F4 / PRD §5 大文件传输；TSDD 2.1、4.7、5.1、6.4 |
| C2 | ✅ 已修复 | PLAN §3 迭代总览（S2 后端统计、S4 前端看板分工） |
| G1 | ✅ 已修复 | TSDD 3.4 初始数据（Seed）；PLAN PF-003；TESTCASES TC-ORG-014 |
| G2 | ✅ 已修复 | TSDD 8.2 UPLOADING 孤儿清理；PLAN PF-208；TESTCASES TC-FILE-014 |
| G3 | ✅ 已修复 | TSDD 6.3 confirm 产物定位（注入 `ResumableUploadService`）；PLAN PF-204；TESTCASES TC-UP-018 |
| G4 | ✅ 已修复 | TSDD 10（nginx TLS 443/80、证书卷）；PLAN PF-403；TESTCASES 14 TC-DEP-001/004 |
| G5 | ✅ 已修复 | TSDD 5.1 端点鉴权矩阵（`/api/**`、`/logout`、`/changePassword`）；PLAN PF-104；TESTCASES TC-LOGIN-023 |
| G6 | ✅ 已修复 | TSDD 5.2 会话行（续期同步 `auth:user:session`）；TESTCASES TC-LOGIN-024 |
| G7 | ✅ 已修复 | TSDD 8.2 恢复规则；PLAN PF-304；TESTCASES TC-FILE-015 |
| G8 | ✅ 已修复 | TSDD 10（Redis requirepass、MySQL 独立账号）；PLAN PF-403；TESTCASES TC-DEP-002 |
| G9 | ✅ 已修复 | TSDD 8.2（`STORAGE_ALERT` 落库 + 通知扩展点）；PLAN PF-209；TESTCASES TC-ST-007 |
| G10 | ✅ 已修复 | TSDD 9.4 日志归档（12 个月）；PLAN PF-307；TESTCASES TC-AUDIT-009 |
| G11 | ✅ 已修复 | TSDD 5.2（密钥文件持久化 + 每次渲染拉取公钥）；TESTCASES TC-LOGIN-025、TC-DEP-003 |
| G12 | ✅ 已修复 | TSDD 5.3 账号生命周期处置规则；PLAN PF-109；TESTCASES TC-ORG-015/016 |
| G13~G15 | ⏳ 延后 v1.1 | 保留为 P2 跟踪项 |
