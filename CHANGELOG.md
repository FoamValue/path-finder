# Changelog

本文件记录 PathFinder 的变更。版本遵循语义化版本，`[Unreleased]` 为尚未发布的变更；发布时把条目移入对应版本小节并打 tag。功能基线见 [README](README.md) 与 [PRD](docs/PRD-PathFinder-v1.0.0.md)。

## [Unreleased]

### Changed

- 大文件组件 `cn.chenxinjie:upload-file` 由 `1.0.0-rc.3` 升级至 **`1.0.0-rc.4`**（坐标 `upload-file-core` / `upload-file-store-redis`，core 手工装配，接口契约与组件一致；starter 仍因 `javax.servlet` 与 Spring Boot 4 不兼容未启用）：
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

- 测试：`UploadFileErrorMappingTest`（组件 rc.4 类型化异常 → HTTP 状态码映射回归，含 AccessDenied→403）；`FileUploadFlowTest` 增补 confirm/取消用例。
- 授权/回收组件接入类：`UploadOwnerAccessControl`（AccessControl SPI）、`UploadFileConfig` 内组件 `StorageCleanupService` 接线。
- 已知缺口收口（TESTCASES §17）：`GlobalExceptionAuditTest`（X1 403 审计）、`FileUploadFlowTest` 恢复负例（X3）、`AuthServiceTest` 停用口径用例（X2）。
- 文档：`docs/DEV-JOURNEY-REFLECTION.md`（共建反思）；README / TSDD / PRD / PLAN / TESTCASES / REVIEW 同步 rc.4 契约、组件复用清单与 X1~X3 收口，PLAN 追加 §12 升级补丁任务。

### Fixed

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

> 版本说明：本仓库维护自用，CHANGELOG 自该版本起补充；组件版本升级（rc.3 → rc.4）与「1.0.0 功能基线」间的关系详见 README「已知说明」与 PLAN §12。
