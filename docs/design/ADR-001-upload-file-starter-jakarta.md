# ADR-001：upload-file rc.5 提供 jakarta starter 后是否迁移

| 项 | 值 |
|---|---|
| 编号 | ADR-001 |
| 日期 | 2026-09（2026-09 rc.6 迁移；rc.7/rc.8 升级收口） |
| 状态 | 已接受 - 已迁移（Accepted - Migrated），现行组件版本 `1.0.0-rc.8` |
| 决策 | **rc.5 时暂不迁移**；**rc.6 起迁移**到 `upload-file-spring-boot-starter-jakarta`，删除手工装配 `UploadFileConfig` 与自研 `UploadController`，`/upload` 由组件 `UploadServlet` 承载 |
| 关联文档 | [TSDD §6.1/§11](TSDD-PathFinder-v1.0.0.md)、[PRD F8](PRD-PathFinder-v1.0.0.md)、[PLAN §13/§14/§15/§16](PLAN-PathFinder-v1.0.0.md)、[UPGRADE](UPGRADE-upload-file-starter-jakarta.md)、README、CHANGELOG |
| 触发事件 | 组件仓库发布 `1.0.0-rc.5`（新增 `upload-file-spring-boot-starter-jakarta`，消除 javax 阻塞）；随后 `1.0.0-rc.6` 为 path-finder 的 ADR/UPGRADE 评估专门补齐商业化 HTTP 层可控接入能力，**本 ADR §6 撤销条件被触发** |

## 1. 背景

大文件分片传输一直复用 `cn.chenxinjie:upload-file`。rc.3/rc.4 时代官方 starter 仅面向 Spring Boot 2（`javax.servlet`），与本工程 Spring Boot 4.1.1（jakarta）不兼容，故按 TSDD §11 风险预案降级为 `upload-file-core` 手工装配 + 自研 MVC 上传端点（`UploadController`），对外协议与组件契约一致。

rc.5 起组件提供 `-jakarta` 二件套（同 FQCN、同 `upload-file.*` 属性、`@AutoConfiguration` + `AutoConfiguration.imports` 注册）。因此需要一次**正式决策**：能否「直接」把依赖换成 `cn.chenxinjie:upload-file-spring-boot-starter-jakarta`，删除自研的 `UploadFileConfig` / `UploadController`，让组件接管 HTTP 层。

## 2. 目标与非目标

- 目标：在「接口契约不变、错误语义稳定、安全面不扩大、回归成本可控」前提下，判断迁移是否划算。
- 非目标：不在本决策内升级 Spring Boot / 迁移业务下载端点（`/api/file/download/{token}`）到组件 `/download`。

## 3. 评估：直接迁移（方案 B）的四类冲突

对 rc.5 `UploadFileAutoConfiguration`（jakarta 版）与本工程现状逐一核对：

### 3.1 `/upload` 路由冲突（决定性）

starter 的 `uploadFileServletRegistration` / `downloadFileServletRegistration` **无条件**注册 `UploadServlet`/`DownloadServlet` 于 `/upload`、`/download`（无 `@ConditionalOnMissingBean` 兜底）。本工程已有 `@RestController` 同时映射 `GET/POST /upload`（`UploadController`）。精确 Servlet 映射（`/upload`）优先于 DispatcherServlet（`/`）→ 现有 MVC 上传端点**静默失效**，由组件 Servlet 接管，前端行为随之切换。

### 3.2 Bean 半冲突

- 服务类 Bean（TaskStore / ChunkStorage / IdentifierLock / AccessControl / ResumableUploadService / ExecutorService / StorageCleanupService）：本工程 `UploadFileConfig` 已定义同类型 Bean，starter 侧 `@ConditionalOnMissingBean` 会正确回退，无重复 Bean 风险；
- 但 `ResumableDownloadService` 与两个 `ServletRegistrationBean` 无本地对应物，会被无条件创建 → 凭空新增一个**未经业务评估的 `GET /download?identifier=` 原始下载能力**（PathFinder 下载走业务票据 `/api/file/download/{token}`，组件端点仅“联调兜底”，见 PRD F4）。

### 3.3 HTTP 错误体 / 语义契约让渡

- 成功对象：组件 Servlet 与 `UploadController` 均直接回序列化 core 模型（`UploadProgress`/`MergeStatus`/`UploadResult`），成功路径字段对前端兼容；
- 错误路径：组件 Servlet 用自己的错误 JSON；本工程经 `GlobalExceptionHandler` 按 `ApiResponse.error(status, message)` 统一兜底并写 `FORBIDDEN` 失败审计（X1）。换 Servlet 后这些约定失效，前端虽只读 HTTP 状态 + 文本（`uploadTask.ts`），但等价于把已冻结契约交给组件实现；
- multipart 上限语义：starter 以 `upload-file.max-chunk-size` 作 Servlet `@MultipartConfig` 的 maxFileSize、`max-request-size` 作 maxRequestSize，与本工程 `spring.servlet.multipart=-1` 的无上限语义不同。

### 3.4 安全面与归属鉴权耦合

`UploadOwnerAccessControl`（组件 AccessControl SPI）依赖「请求先过 Spring Security 会话鉴权、再进组件」的串接；迁移后 `/upload` 裸 Servlet 虽仍在安全 filter 链内，但新增的 `/download` 会扩大对外可触达面（需重新走一遍越权评审）。

## 4. 方案对比

| 方案 | 说明 | 代价 / 收益 |
|---|---|---|
| **A：维持 core 手工装配（采纳）** | 依赖升级 rc.5（core/store 逻辑零变化），`UploadFileConfig` + `UploadController` 保留 | 收益：零回归风险；core 已覆盖所需全部能力（AccessControl / StorageCleanupService / 配额 / getTask / cancelUpload / UploadErrorCode）；错误体、审计、multipart 语义、下载安全面全部不变。代价：保留约 150 行装配代码与自研端点（已在组件契约框架内，属于受控镜像） |
| B：迁移 starter-jakarta | 换坐标，删 `UploadFileConfig`/`UploadController`，服务类 Bean 回退到自动装配，HTTP 层交给 Servlet | 收益：减少自研装配/端点代码，端点/错误码由组件单点维护。代价：需处理 3.1 路由冲突（删 Controller）、3.2 多出的 `/download`、3.3 错误体与审计契约变化、3.4 安全面再评审，且须全量回归（含 E2E 与前端错误提示）；RC 阶段收益不足以覆盖 |
| C：双轨共存 | 保留现有端点，`upload-file.upload-url/download-url` 改到未用路径挂载组件 Servlet | 无实际消费者，只增加冗余面与维护成本，无收益 |

## 5. 决策

**rc.5 阶段（历史）**：采纳 **方案 A**：依赖升至 `1.0.0-rc.5`（坐标 `upload-file-core` / `upload-file-store-redis`），**不引入** `upload-file-spring-boot-starter-jakarta`，HTTP 上传端点继续由 PathFinder 自研 `UploadController` 承载。理由：jakarta starter 是相对 **javax starter** 的 drop-in，而非相对本工程「core 手工装配 + 自研 MVC 端点」方案的 drop-in；§3 的四类冲突（路由 / Bean / 错误体审计契约 / 下载安全面）回归成本大于收益。

**rc.6 阶段（现行）**：组件 `1.0.0-rc.6` 是专为本 ADR/UPGRADE 评估发布的「商业化 HTTP 层可接入」版本，**§6 的撤销条件 #2、#3 已满足**：
- `/download` 默认不注册（`endpoint.download-enabled=false`），消除 §3.2 的凭空下载面；
- `AccessControl.decide()` 返回 `AccessDecision`（`deny(403)`），消除 §3.4 的 401/403 语义缺口；
- `AccessControlListener` + `UploadErrorRenderer` SPI，使审计/错误体可对接本工程约定（§3.3）；
- `endpoint.enabled` / `multipart.strategy` 提供端点与 multipart 上限的显式控制（§3.1/§3.3）。

故**改采方案 B**：迁移到 `upload-file-spring-boot-starter-jakarta:1.0.0-rc.6`，删除 `UploadFileConfig` / `UploadController`；`/upload` 由 `UploadServlet` 承载；`/download` 保持默认关闭（最小暴露，业务下载仍走 `/api/file/download/{token}`）。执行清单与验收见 [UPGRADE](UPGRADE-upload-file-starter-jakarta.md)。

## 6. 影响与撤销条件（rc.5 评估原文，保留备查）

- 影响：无运行行为变化；本次仅版本对齐 + 决策成文。rc.5 相对 rc.4 的 core/store 逻辑零改动，风险极低。
- 已知约束：`1.0.0-rc.5` 尚未发布至 Maven Central（本机经组件仓库 `mvn install` 就绪），CI/新环境需先完成组件 rc.5 发布。
- 撤销 / 触发迁移的信号（出现其一可重开本 ADR，改走方案 B）：
  1. 自研 `UploadController` 需要持续镜像组件协议的新增行为（协议漂移成本大于迁移成本）；
  2. 需求方要求对外暴露组件原生 `/upload`、`/download` 端点（如第三方直传）；
  3. 组件 HTTP 层错误体/审计扩展与本工程 `ApiResponse` 约定合并（上游支持自定义错误渲染）；
  4. 多实例化改造需要 starter 级调度/注册能力且 core 手工装配无法平价补齐。

  > 信号触发后按 [UPGRADE-upload-file-starter-jakarta.md](UPGRADE-upload-file-starter-jakarta.md) 执行：含删改清单（C1~C8）、契约差异表、X1 审计迁移、回归与回滚方案，迁移执行前先拍板其 §9 开放问题（O1~O5）。

## 7. rc.6 迁移执行结果（2026-09）

撤销条件 #2/#3 于 rc.6 满足，已按 UPGRADE 执行：

| 项 | 结果 |
|---|---|
| 依赖坐标 | `upload-file-spring-boot-starter-jakarta` + `upload-file-store-redis`（版本由 `upload-file-bom:1.0.0-rc.8` 统一管理；store-redis 在 starter 中为 optional 故显式声明；rc.6 完成迁移，rc.7/rc.8 升级收口） |
| 删除 | `UploadFileConfig`（手工装配 6 个 Bean）、`UploadController`（自研 MVC `/upload`） |
| `/upload` | 组件 `UploadServlet`（`endpoint.upload-enabled=true`），协议/成功体不变，前端零改动 |
| `/download` | **默认关闭**（`endpoint.download-enabled=false`）：O5 取「最小暴露」，不新开裸下载面，业务下载仍走 `/api/file/download/{token}` |
| 错误体 | `http.error-body=legacy`（组件端点模型；前端仅读状态码/文本，兼容）；`GlobalExceptionHandler` 组件异常映射降级为防御性兜底 |
| AccessControl | `UploadOwnerAccessControl` 覆写 `decide()` → 越权 `deny(403)` |
| 审计 | 新增 `UploadAccessAuditListener`（`AccessControlListener`），在决策点写 `FORBIDDEN success=0`；X1 不回归 |
| multipart | `multipart.strategy=component`（沿用 max-chunk/max-request，5MB 分片 + 表单 < 10MB） |
| 清理 | starter 装配 `StorageCleanupService`，`cleanup.use-redis-lock=true` 启用 `RedisCleanupLock` |
| 测试 | 新增 `UploadOwnerAccessControlTest` / `UploadAccessAuditListenerTest` / `UploadEndpointWiringTest`；E2E 全链路复跑 |
| 回滚 | `git revert` 迁移提交即回手工装配基线；任务元数据/磁盘布局两端一致，无需数据迁移，仅重启 |

> **rc.7 升级（2026-09）**：组件 rc.7 直接回应本工程「rc.6 迁移评审反馈」（组件仓库 `doc/user-feedback/upload-file-rc6-migration-feedback.md`）的 P0/P1（Redis 索引泄漏/N+1、starter 消费 `UploadErrorRenderer`、multipart 安全默认、分布式 `IdentifierLockProvider`、原子 `QuotaStore`、受信读 `TrustedUploadService`）。本工程升级至 rc.7 并适配：新增 `UploadTrustedConfig`，`FileService` 改用 `TrustedUploadService.getTask`（原 `ResumableUploadService.getTask(String)` 已 `@Deprecated`）。`mvn test` 161 例全绿。

> **rc.8 升级（2026-09，GA 前最后一批）**：rc.8 合并后 API 与 `upload-file.*` 属性面冻结。本工程：① `server/pom.xml` 改用 `upload-file-bom:1.0.0-rc.8` 统一版本；② 删除 `UploadTrustedConfig`（starter 自动装配 `TrustedUploadService`，`@ConditionalOnMissingBean` 可覆写/`trusted-upload-service.enabled=false` 可关闭）；③ `UploadAccessAuditListener` 覆写 6 参 `onDecision(AccessContext, ...)`，审计行补齐 method/URI/IP/UA（`LogService` 新增带 IP/UA 的 `record` 重载）；④ `application.yml` 补 `trusted-upload-service.enabled`、`observability.access-log-scope=task` 与 rc.8 可选能力注释。单实例部署仍用默认 `lock.identifier-lock=local` + `quota.store=task-store`，行为不变。`mvn test` 163 例全绿。
