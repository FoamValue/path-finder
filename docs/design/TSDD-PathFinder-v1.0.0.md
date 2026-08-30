# PathFinder 文件管理系统 — 技术详细设计文档（TSDD）

| 项目 | 内容 |
|---|---|
| 关联文档 | 《PathFinder PRD v1.0.0》 |
| 文档版本 | v1.0.0 |
| 技术基线 | JDK 26 / Spring Boot 4.1.1 / React 18 + Ant Design Pro / Redis 9 / MySQL 8 / nginx:alpine |
| 阅读对象 | 前后端开发、测试、运维 |

---

## 1. 概述

### 1.1 目的与范围

本文档基于 PRD v1.0.0，将需求转化为可实施的技术方案，覆盖：总体架构、模块划分、数据库设计、接口契约、安全设计、大文件传输组件集成、缓存与文件存储设计、部署方案。是代码编写计划（PLAN）与测试用例（TC）的依据。

### 1.2 设计原则

1. **分层清晰**：Controller → Service → Repository 严格分层，禁止跨层调用。
2. **贴合组件**：大文件分片上传/断点续传/Range 下载一律复用 `cn.chenxinjie:upload-file:1.0.0-rc.3`，不重复造轮子。
3. **数据权限前置**：所有文件接口统一走"可见性过滤"，服务端强制校验，前端仅做展示层。
4. **安全纵深**：验证码 → 传输加密 → 凭证校验 → 失败锁定 → 单会话踢出 → 越权拦截。
5. **可观测性**：关键操作全量审计落库。

---

## 2. 总体架构

### 2.1 架构视图

```
┌──────────────────────────────────────────────────────────────┐
│                        浏览器（Chrome/Edge/Firefox）            │
│         React 18 + Ant Design Pro（UmiJS）+ TypeScript          │
└───────────────┬───────────────────────────┬────────────────────┘
                │ HTTPS / REST              │ multipart + Range
┌───────────────▼───────────────────────────▼────────────────────┐
│                      nginx:alpine（静态资源 + 反向代理）          │
│      /api/* → 后端   /upload → 后端（鉴权后透传）                 │
└───────────────┬───────────────────────────┬────────────────────┘
                │                            │
┌───────────────▼───────────────────────────▼────────────────────┐
│                   PathFinder Server（JDK 26 + Spring Boot）     │
│  ┌────────────┐ ┌──────────────┐ ┌───────────────────────────┐ │
│  │ Security   │ │ 业务服务层    │ │ upload-file 组件           │ │
│  │ 认证/权限   │ │ file/dept/   │ │ (starter: /upload /download)││
│  │ 验证码/锁定 │ │ user/log     │ │ store-redis: 任务元数据     │ │
│  └─────┬──────┘ └──────┬───────┘ └──────────┬────────────────┘ │
└────────┼───────────────┼────────────────────┼──────────────────┘
         ▼               ▼                    ▼
   ┌──────────┐   ┌────────────┐   ┌──────────────────┐
   │ Redis 9  │   │  MySQL 8   │   │ 本地磁盘存储       │
   │ 会话/缓存 │   │ 业务数据    │   │ storage.root       │
   │ 验证码/锁 │   │ 文件元数据  │   │ 分片/合并/归档     │
   └──────────┘   └────────────┘   └──────────────────┘
```

### 2.2 后端分层（`server/`，包结构 `com.pathfinder`）

| 层 | 职责 | 关键类 |
|---|---|---|
| `config` | 配置类：Security、Redis、Web、上传组件、全局异常、调度任务 | `SecurityConfig`、`RedisConfig`、`GlobalExceptionHandler`、`StorageCleanupScheduler`、`LogArchiveScheduler` |
| `controller` | HTTP 入参校验与路由 | `AuthController`、`UserController`、`DeptController`、`FileController`、`LogController`、`StorageController` |
| `service` | 业务逻辑、事务边界、数据权限判定 | `AuthService`、`UserService`、`DeptService`、`FileService`、`LogService` |
| `repository` | JPA 数据访问、真分页查询 | `UserRepository`、`DeptRepository`、`FileRepository`、`OperationLogRepository` |
| `entity` | JPA 实体（含公共基类） | `User`、`Dept`、`Role`、`FileInfo`、`OperationLog`、`FileRecycleBin` |
| `security` | 认证过滤器、处理器、UserDetailsService、权限判定 | `JwtSessionFilter`、`LoginFailureHandler`、`SecurityUserDetailsService` |
| `util` | 工具：RSA、UUID、MD5、目录工具 | `RsaKeyHolder`、`CaptchaUtil`、`PathUtil` |

### 2.3 前端分层（`frontend/`）

| 模块 | 职责 |
|---|---|
| `src/pages` | 页面：`login`、`fileList`、`recycle`、`user`、`dept`、`log`、`changePassword` |
| `src/components` | 通用组件：`FileUploadModal`、`FileTable`、`OwnerTransferModal`、`SearchBar` |
| `src/services` | API 封装：`auth`、`user`、`dept`、`file`、`log`、`storage` |
| `src/access.ts` | 角色访问控制（`ADMIN`/`DEPT_ADMIN`/`USER`/`VIEWER`） |
| `src/hooks` | `usePagination`、`useUploadTask`（对接组件分片协议） |

---

## 3. 数据库设计

统一约定：表名/字段名 `snake_case`；所有业务表含公共审计字段；删除使用软删除 `del_flag`。

### 3.1 表清单

| 表 | 说明 |
|---|---|
| `sys_dept` | 部门树 |
| `sys_role` | 角色（ADMIN / DEPT_ADMIN / USER / VIEWER） |
| `sys_user` | 用户 |
| `sys_user_role` | 用户-角色关联（v1.0 单角色，保留关联以扩展） |
| `file_info` | 文件元数据（核心表，含权限归属字段） |
| `operation_log` | 操作审计日志 |
| `file_recycle_bin` | 回收站（软删除记录，保留 30 天） |

### 3.2 表结构

**sys_dept**
```sql
CREATE TABLE sys_dept (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  parent_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '上级部门，0=根',
  name        VARCHAR(64)  NOT NULL COMMENT '部门名称',
  sort_order  INT          NOT NULL DEFAULT 0,
  status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  del_flag    TINYINT      NOT NULL DEFAULT 0,
  creator     VARCHAR(32), updater VARCHAR(32),
  created_at  DATETIME     NOT NULL,
  updated_at  DATETIME     NOT NULL,
  ts          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '版本时间戳（MySQL 自动维护）'
) COMMENT '部门';
```

**sys_role**
```sql
CREATE TABLE sys_role (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  role_code    VARCHAR(32)  NOT NULL UNIQUE COMMENT 'ADMIN/DEPT_ADMIN/USER/VIEWER',
  role_name    VARCHAR(64)  NOT NULL,
  description  VARCHAR(255),
  ts           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '版本时间戳（MySQL 自动维护）'
) COMMENT '角色';
```

**sys_user**
```sql
CREATE TABLE sys_user (
  id                   BIGINT PRIMARY KEY AUTO_INCREMENT,
  username             VARCHAR(64)  NOT NULL UNIQUE,
  password             VARCHAR(128) NOT NULL COMMENT 'BCrypt',
  real_name            VARCHAR(64)  NOT NULL,
  dept_id              BIGINT       NOT NULL COMMENT '所属部门',
  status               TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  must_change_password TINYINT      NOT NULL DEFAULT 1 COMMENT '首次登录强制改密',
  last_login_at        DATETIME,
  del_flag             TINYINT      NOT NULL DEFAULT 0,
  creator, updater     VARCHAR(32),
  created_at, updated_at DATETIME NOT NULL,
  ts                   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '版本时间戳（MySQL 自动维护）',
  KEY idx_dept (dept_id)
) COMMENT '用户';
```

**sys_user_role**
```sql
CREATE TABLE sys_user_role (
  id      BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  ts      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '版本时间戳（MySQL 自动维护）',
  UNIQUE KEY uk_user_role (user_id, role_id)
) COMMENT '用户角色';
```

**file_info**（核心，体现数据权限模型）
```sql
CREATE TABLE file_info (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  original_name   VARCHAR(255) NOT NULL COMMENT '原始文件名（下载用）',
  file_name       VARCHAR(255) NOT NULL COMMENT '物理文件名（UUID）',
  file_size       BIGINT       NOT NULL,
  file_md5        VARCHAR(64)  COMMENT '合并后整体 MD5',
  file_type       VARCHAR(32)  COMMENT '扩展名（小写，不含点）',
  storage_path    VARCHAR(512) NOT NULL COMMENT '相对 storage.root 的物理路径',
  space_type      VARCHAR(16)  NOT NULL COMMENT 'PERSONAL/DEPT/PUBLIC',
  dept_id         BIGINT       COMMENT 'space_type=DEPT 时必填',
  owner_id        BIGINT       NOT NULL COMMENT '归属人（归属变更可移交）',
  creator_id      BIGINT       NOT NULL COMMENT '上传人',
  status          VARCHAR(16)  NOT NULL DEFAULT 'UPLOADING' COMMENT 'UPLOADING/READY',
  upload_identifier VARCHAR(64) COMMENT '组件分片任务 identifier',
  del_flag        TINYINT      NOT NULL DEFAULT 0,
  del_at          DATETIME,
  created_at, updated_at DATETIME NOT NULL,
  ts              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '版本时间戳（MySQL 自动维护）',
  KEY idx_owner (owner_id), KEY idx_dept (dept_id), KEY idx_space (space_type),
  KEY idx_creator (creator_id), KEY idx_status (status)
) COMMENT '文件元数据';
```

**operation_log**
```sql
CREATE TABLE operation_log (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  operator_id    BIGINT,
  operator_name  VARCHAR(64),
  ip             VARCHAR(64),
  user_agent     VARCHAR(255),
  operation_type VARCHAR(32) COMMENT 'LOGIN/LOGOUT/UPLOAD/DOWNLOAD/DELETE/RESTORE/RENAME/OWNER_CHANGE/PASSWORD',
  target_type    VARCHAR(32), target_id VARCHAR(64), target_name VARCHAR(255),
  detail         VARCHAR(1024) COMMENT '原值/新值等结构化内容',
  success        TINYINT NOT NULL DEFAULT 1,
  created_at     DATETIME NOT NULL,
  ts             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '版本时间戳（MySQL 自动维护）',
  KEY idx_type (operation_type), KEY idx_operator (operator_id), KEY idx_time (created_at)
) COMMENT '操作审计';
```

**file_recycle_bin**
```sql
CREATE TABLE file_recycle_bin (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  file_id    BIGINT NOT NULL,
  deleted_by BIGINT NOT NULL,
  deleted_at DATETIME NOT NULL,
  expire_at  DATETIME NOT NULL COMMENT 'deleted_at + 30 天，届时物理清除',
  ts         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '版本时间戳（MySQL 自动维护）',
  UNIQUE KEY uk_file (file_id)
) COMMENT '回收站';
```

### 3.3 实体基类（`BaseEntity`）

`@MappedSuperclass`：`id`（`IDENTITY`）、`creator`、`updater`、`createdAt`、`updatedAt`；`@PrePersist`/`@PreUpdate` 自动填充。`sys_dept`、`sys_user`、`file_info` 额外含 `delFlag`（软删除）。

**`ts` 字段约定（所有表强制）**：每张表必须包含 `ts TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP`，由 **MySQL 自动维护**——插入时默认当前时间，行被更新时自动刷新。应用层以只读方式映射（`@Column(name = "ts", insertable = false, updatable = false)`），不参与任何写入/回填，保证其值只由数据库控制。

### 3.4 初始数据（Seed，对应 G1）

随 Flyway 迁移初始化（`V2__seed.sql`，随 PLAN PF-003 落地）：

1. **角色**：`sys_role` 写入四角色——`ADMIN` / `DEPT_ADMIN` / `USER` / `VIEWER`（`role_code` 唯一）。
2. **根部门**：初始化根部门"组织"（`sys_dept` 根节点，`parent_id=0`）。
3. **首个系统管理员**：`username=admin`，归属根部门，角色 `ADMIN`，初始密码 `Init@123`（BCrypt 预生成哈希），`must_change_password=1`——首次登录强制改密后方可使用，解决"首个管理员由谁创建"的引导问题。

---

## 4. 接口设计

> 统一响应：`{ code, message, data }`；`code=0` 成功。分页 data：`{ list, total, pageNum, pageSize }`。

### 4.1 认证（`AuthController`）

| 方法 | 路径 | 说明 | 入参 | 返回 |
|---|---|---|---|---|
| GET | `/captcha` | 生成图片验证码 | — | `{ uuid, image(base64) }`；Redis `auth:captcha:{uuid}` TTL 5min |
| GET | `/publicKey` | 下发 RSA 公钥 | — | `{ publicKey }` |
| POST | `/login` | 登录 | `{ username, encryptedPassword, captchaUuid, captchaCode }` | `{ token }`；写入 Redis 会话 |
| POST | `/logout` | 登出 | Header: Token | — |
| POST | `/changePassword` | 修改密码（含首次强制改密） | `{ oldPassword, newPassword }` | — |
| GET | `/api/auth/me` | 当前用户信息 + 角色 | — | `{ user, role }` |

**登录时序**（对应 PRD F1）：
1. 前端 `GET /captcha` 获取验证码（uuid + base64 图）。
2. 前端 `GET /publicKey` 获取公钥，RSA 加密密码。
3. `POST /login`：后端先校验 `auth:lock:{username}`（锁定则拒绝）→ 校验验证码（一次性，比对后删除）→ RSA 私钥解密密码 → 查用户 + BCrypt 比对 → 成功：`auth:user:session:{userId}=token` 覆盖旧值（多登录踢出）→ `auth:session:{token}=userId` TTL 30min；失败：`auth:fail:{username}` 计数 +1，达到 5 次写入 `auth:lock:{username}` TTL 10min 并清零计数。

### 4.2 用户管理（`UserController`，仅 ADMIN）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/user/page` | 用户分页列表（真分页，可按部门/关键字过滤） |
| POST | `/api/user` | 新增用户（初始密码默认 `Init@123`，`mustChangePassword=1`） |
| PUT | `/api/user/{id}` | 编辑（部门、姓名、角色） |
| PUT | `/api/user/{id}/status` | 启用/停用 |
| PUT | `/api/user/{id}/resetPassword` | 重置密码 |

### 4.3 部门管理（`DeptController`，仅 ADMIN；DEPT_ADMIN 只读本部门）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/dept/tree` | 部门树（Redis 缓存 `cache:dept:tree`） |
| POST | `/api/dept` | 新增部门 |
| PUT | `/api/dept/{id}` | 编辑部门 |
| DELETE | `/api/dept/{id}` | 删除（有子部门或部门内有文件时禁止） |

### 4.4 文件（`FileController`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/file/page` | 列表（`spaceType`、`deptId`、`keyword`、`pageNum/pageSize`），真分页 + 数据权限过滤 |
| GET | `/api/file/{id}` | 文件元信息 |
| POST | `/api/file/uploadTicket` | 预注册上传任务：`{ fileName, fileSize, spaceType, deptId }` → 落 `file_info`（status=UPLOADING）并返回 `{ identifier, fileId }` |
| POST | `/api/file/{id}/confirm` | 合并确认：组件 `mergeStatus=SUCCEEDED` 后调用，将合并产物迁移至统一存储目录、回填 MD5/路径、status=READY |
| PUT | `/api/file/{id}/rename` | 重命名（校验所有者/管理员） |
| PUT | `/api/file/{id}/owner` | 归属变更（F9）：`{ spaceType, deptId?, ownerId? }` |
| DELETE | `/api/file/{id}` | 软删除 → 写回收站 |
| POST | `/api/file/batchDownload` | 批量下载：`{ ids }` → 权限校验 → ZIP 打包（≤100 个）→ 返回临时下载 token |
| GET | `/api/file/download/{token}` | 携带一次性 token 跳转/代理下载 |

**回收站（`RecycleController`）**
| GET | `/api/recycle/page` | 回收站分页 |
| POST | `/api/recycle/{id}/restore` | 恢复 |
| DELETE | `/api/recycle/{id}/purge` | 立即物理清除 |

### 4.5 审计日志（`LogController`，仅 ADMIN）

| GET | `/api/log/page` | 按操作人/时间/操作类型筛选，真分页 |
| GET | `/api/log/export` | 导出（CSV，P2） |

### 4.6 存储监控（`StorageController`，仅 ADMIN）

| GET | `/api/storage/info` | 总容量/已用/剩余/使用率 |

### 4.7 大文件传输组件端点（由 `upload-file` 提供，PathFinder 会话鉴权前置）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/upload` | 上传分片（见 PRD F8 契约，业务上传唯一入口） |
| GET | `/upload?action=progress` | 查询进度 |
| POST | `/upload?action=merge` | 合并 |
| POST | `/upload?action=mergeAsync` | 异步合并（202） |
| GET | `/upload?action=mergeStatus` | 异步合并状态 |
| GET | `/download?identifier=xxx` | 组件下载端点：**仅用于未入库临时文件/联调兜底，v1.0 不承载业务下载**（业务下载见 4.4 `/api/file/download/{token}`） |

> 鉴权策略：Spring Security 放行登录相关端点，`/upload` 必须携带 PathFinder 会话 Token，由自定义 `UploadAuthFilter` 校验后放行至组件 Servlet；`identifier` 由 `/api/file/uploadTicket` 签发，不对外暴露真实路径。

---

## 5. 安全设计

### 5.1 认证链与端点鉴权矩阵

```
Request → Spring Security FilterChain
  ├─ CaptchaFilter       （/login 前置，校验 auth:captcha 一次性）
  ├─ UploadAuthFilter    （/upload：校验会话 Token + identifier 归属）
  ├─ JwtSessionFilter    （会话校验，覆盖 /api/**、/logout、/changePassword）
  └─ 授权判定            （@PreAuthorize 角色 + FileService 数据权限）
```

**端点鉴权矩阵（SecurityConfig 生效）**：

| 端点 | 鉴权 |
|---|---|
| `/captcha`、`/publicKey`、`/login`、`/error`、静态资源 | 放行（匿名） |
| `/upload`（组件） | 需会话 + identifier 归属校验（UploadAuthFilter） |
| `/api/**`、`/logout`、`/changePassword` | 需会话（JwtSessionFilter） |
| `mustChangePassword=1` 的用户 | 仅放行 `/changePassword`、`/logout`、`/api/auth/me`，其余接口 403 |

> 说明：`/logout`、`/changePassword` 不在 `/api/**` 前缀下，鉴权矩阵显式纳入会话校验，避免认证空档；强制改密态的白名单保证首次登录只能改密。

### 5.2 关键安全点（对应 PRD F1）

| 安全项 | 实现 |
|---|---|
| 图片验证码 | `CaptchaUtil` 生成（4 位干扰线 PNG），Redis key `auth:captcha:{uuid}`，TTL 5min，校验后 DEL（一次性） |
| 密码传输加密 | 启动时 `RsaKeyHolder` 生成 2048 位密钥对（**优先从密钥文件加载，`security.rsa.private-key-path` 可配置，便于生产挂载持久化**）；公钥经 `/publicKey` 下发；**前端登录页每次渲染强制拉取最新公钥**（密钥对可能因重启更换），登录失败后再刷新；私钥解密登录密码 |
| 密码存储 | BCrypt（cost=12） |
| 失败锁定 | Redis `auth:fail:{username}` 计数，5 次触发 `auth:lock:{username}` TTL 10min，锁定期间拒绝登录 |
| 多登录踢出 | 登录成功写入 `auth:user:session:{userId}=newToken`（覆盖），旧 token 的 `auth:session:{oldToken}` 删除，旧会话下次请求 401 |
| 会话 | `auth:session:{token}` 存 userId，TTL 30min，滑动续期；**续期时同步刷新 `auth:user:session:{userId}` 的 TTL**，保持踢出映射一致；超时 401 自动登出 |
| 首次强制改密 | `mustChangePassword=1` 时登录后仅允许 `/changePassword`、`/logout`、`/api/auth/me`，其余接口 403 |

### 5.3 数据权限过滤（核心，对应 PRD 2.2）

`FileService` 组装可见性条件（Specification）：
```
可见文件 = (space_type = 'PUBLIC')
        ∪ (space_type = 'DEPT' AND dept_id ∈ 当前用户可见部门集合 V)
        ∪ (space_type = 'PERSONAL' AND owner_id = 当前用户)
        ∪ (角色 ADMIN：全部文件)
```
「可见部门集合 V」按用户视角推导（文件归属于部门 X 时，X 及 X 的全部下级部门成员可见）：
- USER / VIEWER：`V = {用户所属部门} ∪ {其全部上级部门}`
- DEPT_ADMIN：`V = {所辖部门子树（本部门及全部下级）} ∪ {该子树的上级链}`
- ADMIN：`V = 全部部门`

> 例：文件在"研发部"空间 → 研发部及其子部门成员可见。前端组成员（子部门）查询时 `V={前端组, 研发部}` 命中。
操作级权限：
- 重命名/软删除：仅 `owner_id=当前用户` 或 `ADMIN` 或 `DEPT_ADMIN`（文件属本部门管辖）。
- 归属变更：所有者 / 本部门及下级部门 DEPT_ADMIN / ADMIN。

**账号生命周期与文件处置规则（G12）**：
- 停用用户：个人空间文件冻结（原 owner 不可见），ADMIN / 所属部门 DEPT_ADMIN 可查看，可通过归属变更移交；账号重新启用后恢复可见。
- 删除用户：系统强制校验其个人空间文件已移交完毕（或由 ADMIN 一键移交至所属部门空间，`owner_id` 置为所属部门 DEPT_ADMIN），否则禁止删除。此规则在 `UserService.delete` 内强制实现并记录审计。

---

## 6. 大文件传输组件集成设计

### 6.1 依赖

```xml
<dependency>
  <groupId>cn.chenxinjie</groupId>
  <artifactId>upload-file-spring-boot-starter</artifactId>
  <version>1.0.0-rc.3</version>
</dependency>
<dependency>
  <groupId>cn.chenxinjie</groupId>
  <artifactId>upload-file-store-redis</artifactId>
  <version>1.0.0-rc.3</version>
</dependency>
```

### 6.2 配置（`application.yml`）

```yaml
upload-file:
  storage-dir: ${storage.root}/upload          # 分片与合并根目录
  metadata-store: redis
  redis:
    host: ${redis.host}
    port: ${redis.port}
    key-prefix: upload:task:
    ttl-seconds: 86400
  verify-checksum: true
  max-chunk-size: 5242880                       # 5MB
  max-file-size: 524288000                      # 500MB
  max-request-size: 10485760
  async-merge:
    enabled: true
    thread-pool-size: 2
  cleanup:
    enabled: true
    run-on-startup: true
    interval: 1h
    task-ttl: 24h
    orphan-enabled: true
    use-redis-lock: true
  security:
    enabled: false                              # 由 PathFinder 会话鉴权替代共享令牌
```

### 6.3 上传流程时序（对应 F3/F8）

```
前端                       PathFinder Server                 upload-file 组件
  │  POST /api/file/uploadTicket {fileName,fileSize,spaceType,deptId}
  │ ────────────────────────▶ 生成 identifier=uuid，落 file_info(UPLOADING)
  │ ◀───────────────────────  {identifier, fileId}
  │  POST /upload (chunk, identifier,...)  ──▶ 分片落盘 / Redis 记录已传分片
  │ ◀─────────────────────────────────────  进度 JSON
  │  GET /upload?action=progress (断点续传时跳过已传分片)
  │  POST /upload?action=mergeAsync ──────▶ 202，后台合并（临时文件+原子改名+fsync）
  │  GET /upload?action=mergeStatus (轮询至 SUCCEEDED)
  │  POST /api/file/{id}/confirm
  │ ────────────────────────▶ 移动合并产物→统一存储；回填 MD5/路径；status=READY；记录审计
```

**confirm 阶段合并产物定位（G3，冻结方案）**：`/confirm` 在后端注入组件核心服务 `ResumableUploadService`，按 `file_info.upload_identifier` 调用 `getProgress(identifier)` 获取任务元数据，从中解析合并产物绝对路径（组件 `UploadResult`/任务元数据含最终文件路径）；产物位于 `upload-file.storage-dir` 任务目录下，与 PathFinder 统一存储同盘，故 `Files.move` 为同盘原子移动。若元数据不可达（如 Redis 清理），返回明确错误并要求客户端重新触发 `mergeAsync` 后重试 confirm。

### 6.4 下载流程（对应 F4）

```
前端 GET /api/file/download/{token}（单文件或 ZIP 包）
  → 校验 token 与数据权限
  → PathFinder 本地流式返回（支持 Range 206/416 断点续传）
  → 组件端点 GET /download?identifier=xxx 保留用于未入库临时文件/联调兜底
```

- 单文件：PathFinder 下载端点以本地流式输出，实现与组件一致的 Range 断点续传语义（`200` 完整 / `206` 区间 / `416` 不可满足），原始文件名保留（`Content-Disposition`）。
- 批量：`FileService` 用 `ZipOutputStream` 打包 ≤100 个文件为临时 ZIP（`storage.root/tmp/`），下发一次性 token 下载，完成/过期后清理。

> 设计决定：PRD F4 的 Range 断点下载能力由 PathFinder 下载端点实现（本地流式，语义与组件 `GET /download` 一致）；组件 `/download` 端点保留用于 upload 目录内未入库文件与联调兜底。理由：`confirm` 后文件已按 PathFinder 统一存储管理（`files/{date}/{uuid}`），路径与 identifier 由本系统掌控，避免向组件暴露内部路径。该决定不改变对外能力契约（Range 断点下载、原始文件名、批量 ZIP）。

---

## 7. 缓存与 Redis 设计

### 7.1 Redis 超时（TTL）策略约定

**默认策略（强制）**：所有 Redis 写入必须带超时时间，且**默认 TTL = 固定基础值 + 随机数**（抖动），防止大量 Key 在同一时刻过期引发缓存雪崩：
- 默认基础值：`10 分钟`；随机抖动：`±20%`（实际 TTL ∈ [8min, 12min]，按秒取整）。
- 所有写入统一经 `RedisTtlPolicy` 封装：`setWithDefaultTtl(key, value)`（默认策略）或 `setWithExplicitTtl(key, value, baseTtl, jitter)`（显式基础值，`jitter=true` 叠加随机 / `jitter=false` 精确值）。
- 抖动公式：`ttl = baseTtl + random(-baseTtl * 0.2, +baseTtl * 0.2)`。

**覆盖规则**：功能已有明确超时语义的 Key 可覆盖默认 TTL：
- 业务语义必须精确（如验证码一次性 5min、失败计数 10min、锁定 10min、会话 30min、下载令牌 10min、组件任务 24h）→ 显式覆盖且 `jitter=false`。
- 纯缓存类（部门树、用户信息、文件元信息）→ 可覆盖基础值，但**必须 `jitter=true`** 叠加随机抖动，错开过期时刻。

### 7.2 Key 规划

| Key | 类型 | TTL | 用途 | 策略 |
|---|---|---|---|---|
| `auth:session:{token}` | string(userId) | 30min | 会话（滑动续期） | 覆盖（精确） |
| `auth:user:session:{userId}` | string(token) | 30min | 单会话映射（多登录踢出） | 覆盖（精确） |
| `auth:captcha:{uuid}` | string(code) | 5min | 验证码（一次性） | 覆盖（精确） |
| `auth:fail:{username}` | string(count) | 10min | 失败计数 | 覆盖（精确） |
| `auth:lock:{username}` | string(1) | 10min | 锁定标记 | 覆盖（精确） |
| `cache:dept:tree` | string(json) | 1h ±20% | 部门树缓存（变更时 DEL） | 覆盖 + 抖动 |
| `cache:user:{id}` | string(json) | 30min ±20% | 用户信息缓存（变更时 DEL） | 覆盖 + 抖动 |
| `cache:file:meta:{id}` | string(json) | 10min ±20% | 文件元信息缓存（变更时 DEL） | 覆盖 + 抖动 |
| `upload:task:*` | hash | 24h | 组件分片任务元数据（由组件管理） | 组件固定 |
| `download:token:{token}` | string(fileIds) | 10min | 一次性下载令牌 | 覆盖（精确） |

> 未在表中列出的其他 Redis 写入（临时标记、限流等）一律使用默认策略：固定基础 10min + 随机 ±20%。

### 7.3 缓存一致性

写操作（用户/部门/文件变更、删除、归属变更）后主动 `DEL` 对应缓存 key；列表查询不走缓存，仅热元数据（部门树/用户/文件元信息）缓存，保证真分页计数准确。

---

## 8. 文件存储设计

### 8.1 目录结构

```
{storage.root}/
├── files/                     # 已入库业务文件
│   └── {YYYY-MM-DD}/{uuid}.{ext}
├── upload/                    # 组件分片/合并目录（= upload-file.storage-dir）
├── del/                       # 软删除归档（回收站期满后物理清除）
└── tmp/                       # ZIP 打包临时目录
```

### 8.2 存储规则（对应 F6）

- 入库：`confirm` 时由 `PathUtil` 生成 `files/{YYYY-MM-DD}/{uuid}.{ext}`，`Files.move`（同盘原子移动）。
- 软删除：文件记录 `del_flag=1`、`del_at`，物理文件 `move` 到 `del/`，回收站记录 `expire_at=+30d`。
- 恢复（G7）：`restore` 时校验原 `storage_path` 目录仍存在、且文件归属的部门/空间仍有效，将物理文件从 `del/` 迁回 `files/{原日期目录}/{原 uuid}.{ext}`，清除 `del_flag`/`del_at`，删除回收站记录。
- 到期清理：`StorageCleanupScheduler`（每日 2:00）扫描回收站过期记录 → 删除物理文件 + 清库。
- **UPLOADING 孤儿清理（G2）**：同调度器扫描 `status=UPLOADING` 且 `created_at` 超过 24h 的 `file_info`，直接删除记录并记录审计；物理分片由组件 `cleanup.task-ttl=24h` 兜底清理。
- 启动初始化：`ApplicationReadyEvent` 校验/创建 `files|upload|del|tmp` 目录。
- 用量监控与告警（G9）：`StorageController` 用 `Files.getFileStore` 统计；使用率 ≥85% 时输出结构化告警日志，并以 `STORAGE_ALERT` 类型写入 `operation_log`（人工可查），预留通知扩展点（v1.1 接 Webhook/邮件）。

---

## 9. 关键技术实现要点

### 9.1 真分页

`FileRepository extends JpaRepository<FileInfo, Long>, JpaSpecificationExecutor<FileInfo>`；`FileService.page(...)` 用 `Specification` 拼装可见性 + 过滤条件，`Pageable.of(pageNum-1, min(pageSize,100))` 查询，返回 `Page<FileInfo>` → DTO `{list,total}`。

### 9.2 事务边界

写操作（用户/部门/文件/归属/回收站）在 Service 方法标注 `@Transactional`；上传 `confirm` 内部"移动文件 + 更新元数据 + 审计"必须同事务（物理移动失败则事务回滚并清理残留）。

### 9.3 异常与错误码

`GlobalExceptionHandler` 统一处理：`BusinessException`（400/403/404）、`AccessDeniedException`（403）、组件 `ChecksumMismatchException`（重传分片）、`QuotaExceededException`（507）。前端依据 `code` 映射组件错误码（400/401/404/416/507）与业务码。

### 9.4 审计

`AuditAspect`（`@Aspect`）拦截关键方法或 Service 显式调用 `LogService.record(...)`：记录操作人/IP/UA/类型/目标/结果；登录与登出在认证成功/失败处理器内记录。结构化 `detail` 采用 JSON 保存变更前后值（归属变更的旧/新归属）。

**日志归档策略（G10）**：`operation_log` 保留周期 12 个月；`LogArchiveScheduler`（每日 3:00）将超期记录导出归档 CSV 至 `storage.root/archive/` 后批量删除，归档产物纳入备份范围（见 10 部署设计）。

---

## 10. 部署设计

| 组件 | 镜像 | 说明 |
|---|---|---|
| 前端 | `nginx:alpine` | 托管构建产物 + `/api` `/upload` 反向代理；**TLS 终结：监听 443（挂载证书卷）+ 80 强制重定向 HTTPS** |
| 后端 | 自建 `eclipse-temurin:26-jre-alpine` | Spring Boot fat-jar；挂载 RSA 密钥文件卷（`security.rsa.private-key-path`） |
| Redis | `redis:9-alpine` | 开启 AOF 持久化；**配置 `requirepass`（仅容器网络内可达）** |
| MySQL | `mysql:8` | 数据卷持久化；**禁用 root 远程登录，为应用创建独立账号并仅授予业务库最小权限** |

`docker-compose.yml` 服务：`server`（挂载 `./data/storage:/data/storage` 存储卷 + RSA 密钥卷，`depends_on` redis/mysql）、`redis`、`mysql`、`nginx`（挂载证书卷 + 静态产物卷）。证书通过 volume 挂载（`certs:/etc/nginx/certs`，含 `fullchain.pem`/`privkey.pem`）。健康检查：nginx 与后端分别轮询 `/api/auth/me`（401=服务存活判定）。RSA 密钥对文件由首次启动生成并持久化至密钥卷，重启沿用，保证旧公钥密文可解密。

---

## 11. 关键风险与依赖

| 项 | 说明 | 对策 |
|---|---|---|
| 组件与 Spring Boot 4.1.1 兼容 | 官方适配 Spring Boot 2.x | Sprint 1 先行 POC；失败降级 `upload-file-servlet`/`upload-file-core` 手动装配（接口契约不变） |
| 组件产物 JDK8 字节码 | 与 JDK 26 运行兼容 | POC 阶段验证 |
| Redis 9 + Jedis 兼容 | 组件 store-redis 基于 Jedis | POC 阶段验证，失败切 `metadata-store=file` |
| 大文件 confirm 原子性 | 移动+元数据必须一致 | 同事务 + 残留清理兜底 |
