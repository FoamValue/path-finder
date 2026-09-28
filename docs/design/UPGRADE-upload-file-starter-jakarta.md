# UPGRADE：迁移到 `cn.chenxinjie:upload-file-spring-boot-starter-jakarta`（升级内容清单）

| 项 | 值 |
|---|---|
| 编号 | UPGRADE-upload-file-starter-jakarta |
| 日期 | 2026-09（2026-09 执行：rc.6 迁移完成，rc.7/rc.8 升级收口） |
| 状态 | 已执行（Executed）——rc.6 满足 ADR-001 撤销条件后按本文实施，结果见 §14；rc.8 升级见 §15 |
| 目标组件 | `cn.chenxinjie:upload-file-spring-boot-starter-jakarta`（Spring Boot 4.0.0+ / jakarta，版本由 `upload-file-bom:1.0.0-rc.8` 统一管理；rc.6 完成迁移，rc.7/rc.8 升级收口） |
| 关联 | [ADR-001](ADR-001-upload-file-starter-jakarta.md)（决策）、[TSDD §6](TSDD-PathFinder-v1.0.0.md)（集成设计）、[PRD F8](../PRD-PathFinder-v1.0.0.md)、[PLAN §13/§14/§15/§16](PLAN-PathFinder-v1.0.0.md)、TESTCASES |
| 当前基线 | 已迁移并升级：`upload-file-spring-boot-starter-jakarta` + `upload-file-store-redis`（`upload-file-bom:1.0.0-rc.8`；原基线 core 手工装配 `UploadFileConfig` + `UploadController` 已删除） |

> 阅读前提：先读 [ADR-001](ADR-001-upload-file-starter-jakarta.md)。本文档不重述“为何不迁”，只回答“**如果要迁，改什么、怎么验收、怎么回滚**”。

---

## 1. 何时执行本预案

ADR-001 §6 的撤销/迁移触发条件（出现其一）：
1. 自研 `UploadController` 需持续镜像组件协议的新增行为（协议漂移成本 > 迁移成本）；
2. 需求方要求对外暴露组件原生 `/upload`、`/download` 端点（如第三方直传）；
3. 组件 HTTP 层错误体/审计扩展与本工程 `ApiResponse` 约定合并；
4. 多实例化需要 starter 级调度/注册能力。

执行前硬前置：**`1.0.0-rc.5` 已发布至 Maven Central**（当前仅本机 `~/.m2` 存在，见 CHANGELOG 提示），CI/新环境可解析。

---

## 2. 目标形态（迁移后）

```
UploadServlet(/upload) ──► ResumableUploadService (starter 自动装配)
DownloadServlet(/download) ► ResumableDownloadService(starter 自动装配)
                                │
                                ├── TaskStore          = RedisTaskStore（metadata-store=redis）
                                ├── ChunkStorage       = LocalFileChunkStorage
                                ├── AccessControl      = UploadOwnerAccessControl（本项目 Bean 覆盖）
                                ├── IdentifierLock / ExecutorService / StorageCleanupService
```

HTTP 上传层从「自研 `UploadController` 镜像契约」改为「组件 `UploadServlet` 原生实现」；业务确认/下载层保持不变。

---

## 3. 变更总览

| # | 动作 | 对象 |
|---|---|---|
| C1 | **依赖坐标替换** | `server/pom.xml`：`upload-file-core` → `upload-file-spring-boot-starter-jakarta`（保留 `upload-file-store-redis`） |
| C2 | **删除手工装配** | `UploadFileConfig.java`（TaskStore/ChunkStorage/IdentifierLock/ResumableUploadService/ExecutorService/StorageCleanupService 六个 Bean 全部交由 starter `@ConditionalOnMissingBean` 自动装配） |
| C3 | **删除自研上传端点** | `UploadController.java`（`/upload` 与组件 `UploadServlet` 同路径，避免双映射遮蔽） |
| C4 | **保留不动** | `UploadOwnerAccessControl`（覆盖 starter 默认 AccessControl）、`FileService` confirm（getTask→move→cancelUpload）、`FileController` `/api/file/download/{token}`、`GlobalExceptionHandler`、`SecurityConfig` |
| C5 | **配置核对** | `application.yml`：补充/修正迁移后“从被忽略变为生效”的键（见 §5） |
| C6 | **越权审计迁移** | X1 审计从 `GlobalExceptionHandler`（Controller 路径）迁到 `UploadOwnerAccessControl` 拒绝点（Servlet 路径绕过 @ControllerAdvice） |
| C7 | **测试改造** | `UploadFileErrorMappingTest` 语义拆分；`UploadController` 相关单测删除/改指 Servlet；E2E 补 `action=cancel 404/401` 断言 |
| C8 | **文档翻转** | ADR-001 状态改「已接受-已迁移」，README/TSDD/PRD/CHANGELOG/PLAN §14/DEV-JOURNEY 同步 |

---

## 4. 依赖（C1）

```xml
<!-- server/pom.xml：删除 upload-file-core 与注释，替换为 -->
<dependency>
    <groupId>cn.chenxinjie</groupId>
    <artifactId>upload-file-spring-boot-starter-jakarta</artifactId>
    <version>1.0.0-rc.6</version>
</dependency>
<!-- 保留：RedisTaskStore / RedisCleanupLock 类需在 classpath（starter 中为 optional，不会传递引入） -->
<dependency>
    <groupId>cn.chenxinjie</groupId>
    <artifactId>upload-file-store-redis</artifactId>
    <version>1.0.0-rc.6</version>
</dependency>
```

说明：
- `upload-file-core` 由 starter **编译期依赖传递**引入，`FileService` 中 `ResumableUploadService`/`UploadTask`/类型化异常 import 无需改动；
- `upload-file-store-redis` 必须**显式保留**：starter 声明其为 optional，不引入则 `metadata-store=redis` 失效、`cleanup.use-redis-lock` 时 `RedisCleanupLock` 缺席；
- 禁止与 javax 版 `upload-file-spring-boot-starter`/`upload-file-servlet` 同 classpath（FQCN 相同、javax 与 jakarta 互斥）。

---

## 5. 配置（C5）——「手工装配下不消费、迁移后生效」的键

TSDD §6.2 标注为 starter 专属的键，迁移后语义从「忽略」变为「生效」，需显式核对（多数 `application.yml` 已就位）：

| 键（当前值） | 迁移前 | 迁移后 | 核对点 |
|---|---|---|---|
| `async-merge.enabled=true` / `thread-pool-size=2` | 被忽略（`UploadFileConfig` 固定 2 线程） | starter 创建 `uploadFileAsyncMergeExecutor`（gated on 该属性 + `@ConditionalOnMissingBean(name)`） | 线程池大小开始由配置驱动；如需自控，保留同名 Bean 即可覆盖 |
| `max-request-size=10485760` | 被忽略（Spring 层无上限） | 作为 `MultipartConfigElement.maxRequestSize`（分片请求上限 10MB） | 5MB 分片 + 表单开销 <10MB，正常；改大需同步 |
| `security.enabled=false` | 被忽略 | 决定 starter `AccessControl` 是否回退为 PermitAll/Token | **依赖本项目 `UploadOwnerAccessControl` 覆盖**，务必保持 false + Bean 存在，否则端点裸奔 |
| `security.header-name`（默认 `X-Access-Token`） | 无意义 | Servlet 读取的令牌头 | 本项目不用共享令牌（token 由 AccessControl 忽略），无影响 |
| `cleanup.enabled/run-on-startup/interval/task-ttl/orphan-enabled` | 已消费（手工接线） | 由 starter `StorageCleanupService` 消费 | 语义同前；注意 starter 的 `run-on-startup` 默认 false，须保持 `true` |
| `cleanup.use-redis-lock=true` | 被忽略（单实例注释） | 生效：启动 `RedisCleanupLock`（`SET NX EX`，租约 60s） | 单实例无副作用；多实例时是期望行为 |
| `observability.log-stats`（默认 true） | 手工 listener 等价日志 | starter 写 `upload-file cleanup: run=...` 日志 | 默认已开启，无需改 |
| `quota.max-bytes` / `max-chunk-size` / `max-file-size` / `verify-checksum` / `merge.*` / `metadata-store=redis` + `redis.*` | 已消费 | 同名消费 | 不变，属性零迁移 |

无需删除 `upload-file.*` 配置——前缀与嵌套组（`merge/cleanup/async-merge/security/quota/observability/redis`）与手工装配完全一致。

---

## 6. 代码变更明细

### C2 删除 `UploadFileConfig.java`

starter `UploadFileAutoConfiguration` 同名类型 Bean 均带 `@ConditionalOnMissingBean`，删除后自动回退到自动装配，等价接线：
- `TaskStore`（`metadata-store=redis` + `redis.*`）→ `RedisTaskStore.create(host,port,password,keyPrefix,ttlSeconds)`
- `ResumableUploadService`（`max-chunk/max-file/quota`、verifyChecksum、merge.fsync/atomic 均注入）
- `StorageCleanupService(destroyMethod="stop")`、`ExecutorService(destroyMethod="shutdownNow")`、`IdentifierLock`

> 手工装配特有的**观测增强会丢失**：当前自定义的 `setErrorListener`/`setStatsListener` 日志（`cleanedTasks/cleanedOrphans/elapsedMs`）会被 starter 默认 `CLEANUP_STATS_LOG` 替代（等价但格式不同）；若需完全一致，保留一个自定义 `StorageCleanupService` Bean 覆盖（`@ConditionalOnMissingBean` 会让 starter 让位）。

### C3 删除 `UploadController.java`

`/upload` 完全由 `UploadServlet` 接管（URL/action 集一致：chunk / progress / merge / mergeAsync / mergeStatus / cancel）。删除后无需新写 Controller。

### C4 保留项（不动但需复核注入源）

- `FileService.confirm` / `resolveMergedProduct` / `cleanupUploadTask`：继续注入 `ResumableUploadService`（现在来自 starter）——**代码零改动**；
- `UploadOwnerAccessControl`：仍是 `AccessControl` 类型 Bean，starter 的 `uploadFileAccessControl` 因 `@ConditionalOnMissingBean` 让位；
- `GlobalExceptionHandler`：保留（confirm 等 MVC 路径抛出的组件类型化异常仍走它）。

---

## 7. 行为契约差异（迁移必知，前端/后端各一条红线）

`UploadServlet`（`upload-file-servlet-jakarta`，rc.5 源码实证）与本工程现状的差异：

| 场景 | 现状（自研 `UploadController` + `GlobalExceptionHandler`） | 迁移后（组件 `UploadServlet`） |
|---|---|---|
| chunk 缺 `file` | 抛 `BizException` → `ApiResponse` **400** | **400** + `UploadProgress.empty` JSON（Gson 序列化） |
| chunk 上传成功 | `UploadProgress` JSON | `UploadProgress` JSON（同模型，成功路径字段一致） |
| `action=cancel` 命中 | 200 `{canceled:true}` | 200 `UploadResult{success:true,message:"Upload task cancelled"}` |
| `action=cancel` 无任务 | 200 `{canceled:false}` | **404** `UploadResult{success:false,...}` |
| 归属越权 `AccessDeniedException` | **403** + 写 FORBIDDEN 审计（X1） | **401**（Servlet 捕获，绕过 @ControllerAdvice → **不写审计**） |
| 配额 `QuotaExceededException` | 507 + `ApiResponse` | 507（部分端点 catch 单列，其余走 `statusOf`→507） |
| `merge` 失败 | 状态码映射 + `ApiResponse` | `UploadResult.error` + 由 `UploadErrorCode` 决定的码，**其余一律 400**（不返回 500） |
| `GET /upload` 未知 action | 400 `BizException` | **按 progress 处理**（`doGet` 只分 mergeStatus / 其余）
| `GET /download?identifier=` | 无此端点（业务下载走 `/api/file/download/{token}`） | **新暴露**：identifier 级下载，Range 200/206/416、文件缺失 404、越权 401 |
| 错误体 JSON | `ApiResponse{code,message,...}` | `UploadResult{success,message,identifier,finalPath,finalFileSize}` / `UploadProgress.empty` / `MergeStatus.none` |

前端影响评估（`frontend/src/utils/uploadTask.ts` 只消费成功体字段 + 状态码/文本）：
- 成功路径（progress.uploadedChunks / mergeStatus.state / mergeAsync 状态）字段兼容，**无需改前端**；
- 错误路径只读 `HTTP ${status}` + 文本前 200 字符，**兼容**；
- 唯一语义关注点：越权码 403→401（前端未按其做分支，无感知）。

后端红线（两条）：
1. **X1 越权审计回归**：Servlet 捕获 `AccessDeniedException` → 401，不经过 `GlobalExceptionHandler`，`FORBIDDEN success=0` 审计会丢。→ 见 §8 C6 处理；
2. **`/download` 新暴露面**：迁移后自动注册 `DownloadServlet`，任何人凭 `identifier`（+AccessControl）可下载「已合并未确认」的临时产物。需按业务口径决策 `download-url` 或放行策略（见 §8）。

---

## 8. 安全与审计处理（C6）

### 8.1 X1 审计迁移（必做）

把 FORBIDDEN 审计从「异常被 @ControllerAdvice 捕获后写入」改为「**拒绝点内写入**」——`UploadOwnerAccessControl.check` 判定越权并抛异常前，先注入 `LogService` 记录 `FORBIDDEN success=0`（记录动作含 `action/identifier/当前用户/URI`）。这样 Servlet 路径（401）与 MVC 路径（403）都能留痕，且无重复记录：

```java
// UploadOwnerAccessControl 伪代码（迁移版本）
@Override
public void check(String identifier, String action, String token) {
    AuthUser user = SecurityUtil.currentOrNull();
    if (user == null) { return; }                    // 内部调用/confirm 回收放行
    boolean allowed = ...归属判定...;
    if (!allowed) {
        logService.record(user, "FORBIDDEN", "UPLOAD", "POST", "/upload?action=" + action, "...", false);
        throw denied(action, identifier);
    }
}
```

同时把 `GlobalExceptionHandler` 中 `AccessDenied→403` 的映射**保留给 MVC/confirm 路径**（该路径仍返回 403），避免双端重复审计——若希望全链路统一为 401/403 需单独决策（见 §9 开放问题 O1）。

### 8.2 `/download` 新端点处置（三选一，**执行采用 ①**）

① **关掉（执行采用）**：rc.6 提供 `upload-file.endpoint.download-enabled`（默认 `false`），PathFinder 保持默认关闭——业务下载走 `/api/file/download/{token}`，不暴露「已合并未确认」临时产物的裸下载面，也无需 nginx 透传 `/download`；
② 保留并纳入鉴权：设 `download-enabled=true` + `UploadOwnerAccessControl` 承载（identifier 需存在且属当前用户）；
③ 保留但加强：在 ② 基础上把 AccessControl 委托升级为绑定 `file_info` 属主。

> nginx 反代：`docker/nginx.conf` 已把 `/api` 与 `/upload` 透传后端；迁移后若采用 `/download` 默认路径，需确认 nginx 对 `/download` 的透传存在（业务下载在 `/api/file/download/*`，与裸 `/download` 是两个前缀）。

---

## 9. 开放问题（迁移执行前需拍板）

| # | 问题 | 建议默认 |
|---|---|---|
| O1 | 越权状态码是否全局统一？Servlet=401、MVC confirm=403 并存 | 接受并存（契约表注明），不做全局改写 |
| O2 | 取消语义（无任务 404 vs `{canceled:false}`）是否影响内部调用 | 内部 confirm 走 service API 非 HTTP，无影响；仅外部/联调方需知 |
| O3 | `GET /upload` 未知 action 由 400 变 progress | 接受组件行为（前端从不发未知 action） |
| O4 | 是否补 `observability.log-stats` / 保留自定义 cleanup Bean 以维持观测格式 | 接受 starter 默认日志 |
| O5 | `/download` 处置（§8.2 ①/②/③） | **① 关闭**（rc.6 `endpoint.download-enabled=false` 默认，最小暴露；业务下载走 `/api/file/download/{token}`） |

---

## 10. 回归与验收（C7）

### 10.1 测试改造

- **删除**：`UploadController` 的 MVC 测试（若有直接对 Controller 的用例）；`UploadFileErrorMappingTest` 中纯 Controller 路径的 AccessDenied→403 断言需拆分——保留「confirm/MVC 路径 403」与「Servlet 路径 401」两套语义；
- **新增/指向**：
  - `UploadServlet` / `DownloadServlet` 的协议用例由组件侧保证（`upload-file-servlet-jakarta` 自带 `UploadServletTest` 等），本工程不重复；本工程新增**集成冒烟**：真实 Boot 上下文里 `/upload` 由 Servlet 服务（而非 404/Controller）——用 `MockMvc` 无法打 Servlet？改用 `@SpringBootTest(webEnvironment=RANDOM_PORT)` + HTTP 客户端断言；
  - cancel 无任务 → 404、越权 → 401 两条 E2E/集成断言；
  - `/download?identifier=` 未确认产物 200/206/416、已 confirm 后 404；
  - confirm 后 `cancelUpload` 幂等（服务层，不变）。

### 10.2 回归范围（全量）

- 后端 `mvn test`（含需 MySQL/Redis 的集成用例）；
- 前端 `npm test`（`uploadTask` mock 对 `/upload` 的 URL/裸对象假设不变则绿）；
- E2E `run-e2e-docker.sh`：上传→断点续传→mergeAsync→confirm→下载、越权、取消；
- 存储巡检：cleanup 日志、孤儿回收、配额 507。

### 10.3 验收标准（DoD）

1. `/upload` 路由由组件 Servlet 服务且成功路径对象与前一致；
2. 前端零改动跑通主链路（若被迫改前端，视为升级失败信号，需回到 ADR-001 §6 重评估）；
3. 越权请求在 Servlet 路径仍产生 `FORBIDDEN` 审计（X1 不回归）；
4. `/download` 口径按 §8.2 ② 落地并有用例；
5. 无 `UploadFileConfig`/`UploadController` 残留；仓库坐标引用更新为 starter-jakarta；
6. `mvn test` 全绿 + E2E 全链路通过。

---

## 11. 上线与回滚

上线顺序：
1. 组件 `1.0.0-rc.5` 发布 Central → 2. 依赖/代码变更合入 → 3. 全量回归（§10）→ 4. 灰度验证单台（上传/下载/审计日志）→ 5. 全量。

回滚（简单，因无数据结构/磁盘格式变化）：
- `git revert` 本迁移提交即回到 core 手工装配基线；任务元数据与磁盘布局在组件两端完全一致（同一 core 同一 `upload-file.*` 语义），**无需数据迁移**，仅重启。

---

## 12. 文档与登记（C8，迁移完成后）

- [ADR-001](ADR-001-upload-file-starter-jakarta.md)：状态改「已接受 - 已迁移」，§6 撤销条件改为回滚说明；
- `README` / `docs/design/TSDD §6.1/§11` / `docs/PRD F8`：坐标改 starter-jakarta，删除「未启用」表述，补 `/download` 处置与 401 语义；
- `CHANGELOG`：Unreleased 新增「core 手工装配 → starter-jakarta」升级条目；
- `PLAN`：追加 §14 任务卡（引用本文）；
- `docs/DEV-JOURNEY-REFLECTION.md`：盲点/ADR 收口补记；
- 组件侧 skill（`~/.claude/skills/upload-file-component`）：契约版本 rc.4 → rc.5 + jakarta 坐标。

---

## 13. 关联文档

- [ADR-001](ADR-001-upload-file-starter-jakarta.md)（决策与撤销条件）
- [TSDD §6](TSDD-PathFinder-v1.0.0.md) / [PRD F8](../PRD-PathFinder-v1.0.0.md)（集成设计）
- [PLAN §13/§14](PLAN-PathFinder-v1.0.0.md)（rc.5 升级补丁 / rc.6 迁移任务卡）
- 组件仓库：`upload-file-servlet-jakarta`（UploadServlet/DownloadServlet 契约）、`upload-file-spring-boot-starter-jakarta`（UploadFileAutoConfiguration/UploadFileProperties）、Boot 4 demo（`example/upload-file-boot4-demo`）

---

## 14. 执行结果（2026-09，rc.6）

按 C1~C8 落地，与预案的差异点（均为收紧/最小暴露）：

| # | 动作 | 结果 |
|---|---|---|
| C1 | 依赖坐标 | `upload-file-spring-boot-starter-jakarta:1.0.0-rc.6` + `upload-file-store-redis:1.0.0-rc.6`（显式保留，starter 中 optional） |
| C2 | 删除 `UploadFileConfig` | 已删除；TaskStore/ChunkStorage/IdentifierLock/ResumableUploadService/ExecutorService/StorageCleanupService 交由 starter 自动装配 |
| C3 | 删除 `UploadController` | 已删除；`/upload` 由组件 `UploadServlet` 承载（`endpoint.upload-enabled=true`） |
| C4 | 保留项 | `FileService`（`getTask`/`cancelUpload` 无 token 受信路径）、`FileController` 下载、`SecurityConfig` 不变；`UploadOwnerAccessControl` 改覆写 `decide()` |
| C5 | 配置核对 | `endpoint.download-enabled=false`、`http.error-body=legacy`、`multipart.strategy=component`、`cleanup.use-redis-lock=true`、`observability.access-log=false`、`security.enabled=false`；测试 profile 关闭 cleanup/redis-lock |
| C6 | 越权审计迁移 | 新增 `UploadAccessAuditListener`（`AccessControlListener`）在决策点写 `FORBIDDEN success=0`；`GlobalExceptionHandler` 组件异常映射降级为防御性兜底、不再重复审计 |
| C7 | 测试改造 | 新增 `UploadOwnerAccessControlTest`（decide 403）、`UploadAccessAuditListenerTest`、`UploadEndpointWiringTest`（装配/路由/默认关闭）；E2E 全链路复跑 |
| C8 | 文档翻转 | ADR-001 改「已接受 - 已迁移」并补 §7；本文状态改「已执行」；README/TSDD/PRD/PLAN/CHANGELOG/DEV-JOURNEY 同步 rc.6 坐标与语义 |

**契约差异落点**：`/upload` 成功体不变；失败体由组件 `legacy` 模型承载（前端仅消费状态码/文本，兼容）；越权 403（Servlet 与 MVC 一致）；`GET /upload` 缺/未知 action → 400（前端从不发未知 action）。**回滚**：`git revert` 迁移提交 + 重启，无需数据迁移（任务元数据/磁盘布局两端一致）。

---

## 15. 执行结果追加：rc.8 升级（2026-09，GA 前最后一批）

> rc.8 是 `1.0.0` GA 前最后一个 rc，合并后 API 与 `upload-file.*` 属性面**冻结**；本工程升级沿用本文既有迁移形态（starter-jakarta + `UploadServlet` + 业务下载端点），无新的迁移动作，仅收口版本与新增能力。

| # | 动作 | 结果 |
|---|---|---|
| R1 | 依赖版本 | `server/pom.xml` 新增 `upload-file.version=1.0.0-rc.8` 与 `dependencyManagement` 导入 **`upload-file-bom:1.0.0-rc.8`**；starter-jakarta / store-redis 去掉显式 version（BOM 单点管理） |
| R2 | 删除手写装配 | 删除 `UploadTrustedConfig`：rc.8 starter 自动装配 `TrustedUploadService`（`@ConditionalOnMissingBean`；`upload-file.trusted-upload-service.enabled=false` 可抑制）。`FileService` 注入与 confirm 受信读逻辑零改动 |
| R3 | 审计上下文 | `UploadAccessAuditListener` 改覆写 rc.8 6 参 `onDecision(AccessContext, ...)`，`FORBIDDEN success=0` 审计行补齐 `method/uri/remoteAddr/userAgent`（与 `GlobalExceptionHandler` MVC 路径口径一致：targetId=method、targetName=uri）；5 参重载以 `AccessContext.EMPTY` 桥接，既有 5 参语义不变 |
| R4 | 审计存储 | `LogService` 新增带 `ip/userAgent` 的 `record(...)` 重载（写入前按列长截断 64/255），原 7 参方法委托之，登录审计等调用不变 |
| R5 | 配置 | `application.yml` 显式 `trusted-upload-service.enabled=true`、`observability.access-log-scope=task`；补 `lock.renew-interval`、`quota.store=redis` 启动自动对账注释。单实例仍用 `lock.identifier-lock=local` + `quota.store=task-store` 默认，行为不变 |
| R6 | 测试 | `UploadEndpointWiringTest` 增补 `TrustedUploadService` 自动装配断言；`UploadAccessAuditListenerTest` 增补 AccessContext（method/URI/IP/UA）用例与 9 参 `record` 校验 |
| R7 | 文档同步 | ADR-001 补 §7 rc.8 说明；本文补 §15；README / TSDD / PRD / PLAN §16 / CHANGELOG / DEV-JOURNEY 同步 rc.8 坐标与能力 |

**rc.8 组件侧行为修正（无需配置，结果等价/更稳）**：`quota.store=redis` 启动期 `QuotaStore.reconcile(TaskStore)` 自动对账 + 清理回收「已合并未确认」任务配额（修永久泄漏）；分布式 identifier 锁持有期按 `lock.renew-interval`（默认 `ttl/3`）续租；`RedisTaskStore` 索引迁移原子化、`list()` 分批 `MGET`。本工程为单实例，默认 `local` 锁 + `task-store` 配额，不受影响。

**验收**：`mvn test` 163 例全绿（含真实 MySQL/Redis 的集成用例）；依赖树仅 `1.0.0-rc.8`；`/upload` 契约与成功体不变、前端零改动；越权审计含请求上下文。**回滚**：`git revert` 本次提交即回 rc.7，无数据结构/磁盘格式变化（rc.8 明确无磁盘布局与任务元数据格式变化、无 Redis 索引结构变化），仅重启。
