# Changelog

本文件记录 PathFinder 的变更。版本遵循语义化版本，`[Unreleased]` 为尚未发布的变更；发布时把条目移入对应版本小节并打 tag。功能基线见 [README](README.md) 与 [PRD](docs/PRD-PathFinder-v1.0.0.md)。

## [Unreleased]

### Changed

- rc.8 集成后的项目侧优化（性能 / 正确性 / 安全），`mvn test` 164 例全绿：
  - **A1 分片授权缓存**：组件对每个分片请求都调用 `AccessControl.decide()`，原 `UploadOwnerAccessControl` 每次都查库（500MB/5MB 上传 100+ 次查询）。新增 `util/TtlCache`（LRU 有界 + TTL），缓存 identifier→creatorId（60s / 上限 1 万条），同任务分片/进度请求只查库一次；缓存的是不可变 creatorId，软删除在 60s 窗口内失效可接受；
  - **B1 回收站分页**：`FileService.recyclePage` 原「全表 `findAll` + 每条两次 `findById`」改为数据库层分页 JOIN（`FileRecycleBinRepository.pageWithFile` / `pageVisibleTo`，数据权限谓词下推）+ 按页批量取文件信息，消除 N+1 与无界内存占用；
  - **B2 列表用户名批量加载**：`FileService.toVo` 原逐行查归属人/创建人（每页最多 200 次查询）改为一次 `findAllById` 批量取，列表页查询数由 O(2N) 降为 O(1)。
- 大文件组件 `cn.chenxinjie:upload-file` 由 `1.0.0-rc.7` 升级至 **`1.0.0-rc.8`**（GA 前最后一个 rc，合并后 API 与 `upload-file.*` 属性面冻结）：
  - **依赖对齐**：`server/pom.xml` 引入 **`cn.chenxinjie:upload-file-bom:1.0.0-rc.8`**（`dependencyManagement`/`import`），starter-jakarta 与 store-redis 不再各自写 version，单点锁组件版本（`upload-file.version`）；
  - **移除手写装配**：rc.8 starter 自动装配 `TrustedUploadService`（`@ConditionalOnMissingBean`），删除 `UploadTrustedConfig`；`FileService` 注入源不变（仍为受信读门面），可信服务端读语义不变；
  - **审计上下文（AccessContext）**：`UploadAccessAuditListener` 改覆写 6 参 `onDecision(AccessContext, ...)`，把组件携带的 method/URI/客户端 IP/User-Agent 写入 `FORBIDDEN success=0` 审计行；`LogService` 新增带 IP/UA 的 `record(...)` 重载（并对 IP/UA 做长度截断），5 参重载以 `AccessContext.EMPTY` 桥接，MVC 越权审计语义不变；
  - **配置**：`application.yml` 显式 `trusted-upload-service.enabled=true` 与 `observability.access-log-scope=task`（access-log 关闭时无效果），并补 `lock.renew-interval`/`quota.store=redis` 自动对账说明；单实例部署仍沿用 `lock.identifier-lock=local` + `quota.store=task-store` 默认，行为不变；
  - **组件侧修正（无需配置，结果等价/更稳）**：`quota.store=redis` 启动期 `QuotaStore.reconcile` 自动对账 + 清理回收「已合并未确认」任务配额（修永久泄漏）；分布式 identifier 锁持有期按 `ttl/3` 续租；`RedisTaskStore` 索引迁移原子化、`list()` 分批 `MGET`。
  - 测试：`UploadEndpointWiringTest` 增补 `TrustedUploadService` 自动装配断言；`UploadAccessAuditListenerTest` 增补带上下文（method/URI/IP/UA）用例与 9 参 `record` 校验。回归：`mvn test` 全绿（**163 例 / 0 失败**）。
- 大文件组件 `cn.chenxinjie:upload-file` 由 `1.0.0-rc.6` 升级至 **`1.0.0-rc.7`**（坐标 `upload-file-spring-boot-starter-jakarta` / `upload-file-store-redis`），rc.7 直接回应本工程「rc.6 迁移评审反馈」（组件仓库 `doc/user-feedback/upload-file-rc6-migration-feedback.md`）的 P0/P1：
  - **存储正确性**：`RedisTaskStore` 索引由 `SET` 改 `ZSET` 并按 TTL 惰性修剪（修复索引无限泄漏），`list()` 改单次 `MGET`（修复 N+1）；
  - **扩展点一致性**：starter 现消费宿主 `UploadErrorRenderer` Bean（与文档一致）；`multipart.strategy=component` 在未显式设置时按 `max-chunk-size`/`max-file-size` 推导有界上限（本项目已显式设 `max-request-size`，行为不变）；
  - **新增可选能力**：分布式 `IdentifierLockProvider`（`upload-file.lock.identifier-lock=redis`）、原子 `QuotaStore`（`upload-file.quota.store=redis`）、受信读门面 `TrustedUploadService`、`AbstractAccessControl` 基座、starter 安全默认告警、`ResumableDownloadService` `@Lazy`；
  - **代码适配**：`ResumableUploadService.getTask(String)` rc.7 起 `@Deprecated`；新增 `UploadTrustedConfig` 声明 `TrustedUploadService` Bean，`FileService.resolveMergedProduct` 改用受信读（显式无访问门控的可信服务端读）；`FileUploadFlowTest` 同步改用 `getTaskTrusted`。
  - 回归：`mvn test` 全绿（**161 例 / 0 失败**）。
- 大文件组件 `cn.chenxinjie:upload-file` 由 `1.0.0-rc.5` 升级至 **`1.0.0-rc.6`**，并按 [ADR-001](docs/design/ADR-001-upload-file-starter-jakarta.md) 撤销条件**迁移到官方 HTTP 层**（[UPGRADE 预案](docs/design/UPGRADE-upload-file-starter-jakarta.md) 已执行）：
  - 依赖坐标：`upload-file-core` → **`upload-file-spring-boot-starter-jakarta`**（自动装配 core 服务并注册 `UploadServlet`），保留 `upload-file-store-redis`（starter 声明为 optional）；
  - 删除手工装配 `UploadFileConfig` 与自研 MVC 端点 `UploadController`；`/upload` 由组件 `UploadServlet` 承载（URL/action 集与协议不变，前端零改动）；
  - rc.6 端点开关：`endpoint.upload-enabled=true`、`endpoint.download-enabled=false`（最小暴露——业务下载仍走 `/api/file/download/{token}`，不暴露「已合并未确认」临时产物）；
  - rc.6 失败响应体 `http.error-body=legacy`（组件端点模型；前端仅消费状态码/文本，兼容），multipart 策略 `multipart.strategy=component`；
  - **AccessControl 迁移**：`UploadOwnerAccessControl` 改覆写 rc.6 `decide()`，已认证但无归属返回 **403**（`AccessDecision.deny(403, ...)`，区分未认证的 401）；
  - **X1 越权审计迁移**：新增 `UploadAccessAuditListener`（`AccessControlListener` SPI），在组件决策点写 `FORBIDDEN success=0` 审计；Servlet 路径不再经 `@ControllerAdvice`，`GlobalExceptionHandler` 组件异常映射保留为防御性兜底且不再重复审计。
  - 测试：新增 `UploadOwnerAccessControlTest`、`UploadAccessAuditListenerTest`、`UploadEndpointWiringTest`（starter 装配 / `/upload` 注册 / `/download` 默认关闭）。
- 大文件组件 `cn.chenxinjie:upload-file` 由 `1.0.0-rc.4` 升级至 **`1.0.0-rc.5`**（坐标 `upload-file-core` / `upload-file-store-redis`，core 手工装配不变）。rc.5 相对 rc.4 的 core/store 逻辑零改动（仅补齐测试），实质新增 `upload-file-servlet-jakarta` / `upload-file-spring-boot-starter-jakarta`（jakarta 适配）与 Boot 4 demo：
  - 评估是否迁移 `upload-file-spring-boot-starter-jakarta` 后**决定不迁移**：starter 自动注册 `/upload` `/download` 原始 Servlet，与本工程自研 `UploadController`/`UploadFileConfig` 路径与职责冲突，且 core 手工装配已承载全部所需能力——理由与撤销条件见 [ADR-001](docs/design/ADR-001-upload-file-starter-jakarta.md)；
  - 依赖版本号核对点：rc.5 尚未发布至 Maven Central（本机经 `mvn install` 组件仓库本地就绪），CI/新环境需先完成组件 rc.5 发布。
- 大文件组件 `cn.chenxinjie:upload-file` 由 `1.0.0-rc.3` 升级至 **`1.0.0-rc.4`**（坐标 `upload-file-core` / `upload-file-store-redis`，core 手工装配，接口契约与组件一致；starter 曾因 `javax.servlet` 与 Spring Boot 4 不兼容未启用）：
  - confirm 改用 rc.4 稳定读接口 `getTask(identifier).finalPath` 定位合并产物（`FileService.resolveMergedProduct`），并兼容旧版目录约定回退；
  - confirm 入库成功后调用 `cancelUpload(identifier)` 显式回收任务/分片/残留（异步合并期抛 409，等待结束后重试）；
  - `UploadController` 新增 `POST /upload?action=cancel`（返回 `{canceled}`），缺失分片/非法 action 由 `BizException.badRequest` 明确返回 400；
  - `GlobalExceptionHandler` 按 rc.4 `UploadErrorCode` 注册稳定错误映射（400 参数/校验、404 任务不存在或过期、409 上传/合并中、507 配额不足），不透传内部细节。
- 进一步复用组件能力（core 手工装配下接线组件级服务，替代自研/默认实现）：
  - **归属授权**：注册组件 `AccessControl` SPI（`UploadOwnerAccessControl`），`/upload` 各 action 绑定 `file_info.upload_identifier` 归属人（creator）或 ADMIN，替代 `PermitAllAccessControl`（越权 → `AccessDeniedException` → 403）；
  - **物理回收**：接线组件 `StorageCleanupService`（`upload-file.cleanup.*` 生效：启动跑一轮 + 每 1h 定时，`task-ttl=24h` + 孤儿分片/合并目录回收），与上传共用 `IdentifierLock`；补充此前仅 Redis TTL 过期、物理分片无人回收的缺口；
  - **全局配额**：`upload-file.quota.max-bytes`（`UPLOAD_QUOTA_MAX_BYTES`，默认 0 关闭）→ `ResumableUploadService.setMaxTotalBytes`，超限返回 507。
- JDK 23+ javac 默认关闭隐式注解处理导致 Lombok 失效：`pom.xml` 显式开启编译参数 `-proc:full`。

### Added

- 决策记录：`docs/design/ADR-001-upload-file-starter-jakarta.md`（rc.5 提供 jakarta starter 后是否迁移：不迁移，理由/约束/撤销条件成文，README/TSDD/PRD 同步引用）。
- 迁移预案：`docs/design/UPGRADE-upload-file-starter-jakarta.md`（若 ADR-001 撤销条件触发，迁到 `upload-file-spring-boot-starter-jakarta` 的变更清单 C1~C8 / 契约差异表 / X1 审计迁移 / 开放问题 / 验收与回滚，ADR-001 §6 已挂接）。
- 测试：`UploadFileErrorMappingTest`（组件 rc.4 类型化异常 → HTTP 状态码映射回归，含 AccessDenied→403）；`FileUploadFlowTest` 增补 confirm/取消用例。
- 授权/回收组件接入类：`UploadOwnerAccessControl`（AccessControl SPI）、`UploadFileConfig` 内组件 `StorageCleanupService` 接线。
- 已知缺口收口（TESTCASES §17）：`GlobalExceptionAuditTest`（X1 403 审计）、`FileUploadFlowTest` 恢复负例（X3）、`AuthServiceTest` 停用口径用例（X2）。
- 文档：`docs/DEV-JOURNEY-REFLECTION.md`（共建反思）；README / TSDD / PRD / PLAN / TESTCASES / REVIEW 同步 rc.4 契约、组件复用清单与 X1~X3 收口，PLAN 追加 §12 升级补丁任务。

### Fixed

- A2 确认链路事务与 I/O 分离：`FileService.confirm` 取消方法级 `@Transactional`，文件迁移与整文件 MD5 在事务外执行（避免长事务占用 DB 连接），DB 更新由单条 `save` 提交成功后才调用 `cancelUpload` 回收组件任务，杜绝「任务已删、DB 回滚」的产物与记录不一致。
- B3 500 错误脱敏（PLAN PF-903）：`GlobalExceptionHandler.handleOther` 对外改为固定文案「系统内部错误，请稍后重试」，异常细节仅记服务端日志，不再回传 `e.getMessage()`（可能含路径/类名）。
- X2 停用账号登录口径：`AuthService.login` 对 `status=0` 单列分支（403「账号已停用，请联系管理员」+ `recordLogin(false)`），不再并入「用户名或密码错误」且不再累加失败计数/触发锁定。
- X3 回收站恢复校验：`FileService.restore` 前置校验原归属部门有效与 `del/` 物理文件存在，缺失时给出明确提示（不再静默置 READY）；`DeptService.get` 忽略软删除部门（目标部门视为不存在）。
- X1 越权审计留痕：`GlobalExceptionHandler` 对 403（Biz / Spring Security / upload-file 组件）统一写 `FORBIDDEN success=0` 审计，审计失败不掩盖原响应。
- 测试环境：`application-test.yml` 补齐 Redis 默认口令 `pathfinder123`（与 compose `requirepass` 对齐），`DeptService.get` 语义修正相关集成用例可稳定运行。

## [1.0.0] - 2026-09

首个发布版本（单组织私有部署）。核心能力：

- 安全登录：验证码 + RSA 传输加密 + BCrypt + 失败锁定 + 多登录踢出 + 强制改密。
- 大文件传输：分片上传 / 断点续传 / 秒传 / MD5 校验 / 异步合并 / Range 断点下载（组件 `upload-file` 集成，rc.3 基线）。
- 数据权限：个人 / 部门 / 公共空间三级归属 + 服务端过滤；文件管理（真分页、搜索、软删除回收站、归属变更）。
- 操作审计与目录同步扫描、存储用量监控、Docker Compose 部署（nginx TLS + redis:9 + mysql:8）。

> 版本说明：本仓库维护自用，CHANGELOG 自该版本起补充；组件版本升级（rc.3 → rc.4 → rc.5）与「1.0.0 功能基线」间的关系详见 README「已知说明」、PLAN §12/§13 与 ADR-001。
