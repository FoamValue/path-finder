# PathFinder 文件管理系统

> 寓意「寻找系统路径」——为个人或小型企业提供统一、安全、可审计的内容文件管理。

PathFinder 是一个**单组织私有部署**的文件管理系统，解决「文件放哪、谁能看、如何找」三个核心问题。支持大文件分片上传、断点续传、Range 断点下载、组织架构数据权限、操作审计。

## 功能特性

- 🔐 **安全登录**：图片验证码 + 前端 RSA 加密传输密码 + BCrypt 存储；连续失败 5 次锁定 10 分钟；多登录踢出（单会话）；会话超时自动登出；首次登录强制改密
- 📂 **大文件传输**：基于 `cn.chenxinjie:upload-file:1.0.0-rc.8` 分片上传 / 断点续传 / 秒传 / 分片 MD5 校验 / 异步合并 / Range 断点下载
- 🗂 **数据权限**：个人空间 / 部门空间 / 公共空间三级归属，部门树可见性继承，服务端强制过滤
- 🔍 **高效检索**：后端真分页（数据库层 `LIMIT/OFFSET`），文件名模糊搜索
- ♻️ **软删除**：回收站保留 30 天，支持恢复与物理清除
- 📝 **操作审计**：登录/上传/下载/删除/归属变更/改密全量留痕，12 个月归档
- 💾 **磁盘持久化**：UUID + 日期分目录落盘，使用率 85% 告警，启动自动初始化目录
- 🔄 **目录同步扫描**：定时扫描导入目录自动入库（默认管理员 + 公共空间），磁盘文件缺失/被更新自动标记并下载提示
- 🐳 **容器化部署**：nginx:alpine（TLS）+ server + redis:9 + mysql:8，存储目录宿主机挂载 + 证书卷持久化

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | React 18 · TypeScript · Ant Design 5 / ProComponents · Vite |
| 后端 | JDK 26 · Spring Boot 4.1.1 · Spring Security · Spring Data JPA · Jackson 3 |
| 缓存 | Redis 9（会话 / 验证码 / 锁定 / 元数据缓存，TTL 固定基础值 + 随机抖动防雪崩） |
| 数据库 | MySQL 8 |
| 大文件组件 | `cn.chenxinjie:upload-file:1.0.0-rc.8`（rc.6 迁移到官方 jakarta HTTP 层：`upload-file-spring-boot-starter-jakarta` 自动装配 + 组件 `UploadServlet` 承载 `/upload`；confirm 经受信读 `TrustedUploadService.getTask().finalPath` 定位合并产物、入库后 `cancelUpload` 显式回收、任务级归属 `AccessControl.decide()`（越权 403）、`AccessControlListener` 越权审计（rc.8 带 method/URI/IP/UA 审计上下文）、`StorageCleanupService` 定时回收、全局配额 `quota.max-bytes` 可选；`/download` 默认关闭，业务下载走 `/api/file/download/{token}`。rc.7 修复 Redis 索引泄漏/N+1、starter 消费 `UploadErrorRenderer`、multipart 安全默认；rc.8 新增 BOM 统一版本、`quota.store=redis` 启动自动对账 + 清理回收配额、分布式锁续租、`RedisTaskStore` 索引迁移原子化/`list()` 分批 MGET、`AccessContext` 审计上下文、`observability.access-log-scope`，并自动装配 `TrustedUploadService`。见 [ADR-001](docs/design/ADR-001-upload-file-starter-jakarta.md) / [UPGRADE](docs/design/UPGRADE-upload-file-starter-jakarta.md)） |
| 部署 | Docker Compose · nginx:alpine · TLS |

## 目录结构

```
path-finder/
├── server/                    # 后端（Spring Boot Maven 单模块，包 cn.chenxinjie.pathfinder）
│   └── src/main/java/cn/chenxinjie/pathfinder/
│       ├── config/            # 安全/Redis/异常/调度器/Seed（上传组件由 starter 自动装配）
│       ├── controller/        # auth/user/dept/file/recycle/log/storage
│       ├── service/           # 业务服务与数据权限判定
│       ├── repository/        # JPA Repository（真分页）
│       ├── entity/            # JPA 实体（含 ts 字段只读映射）
│       ├── security/          # Token 认证过滤器 / 当前用户上下文 / 上传归属授权与审计
│       └── util/              # RSA / 验证码 / Redis TTL 策略 / 路径工具
├── frontend/                  # 前端（Vite + React + AntD）
│   └── src/
│       ├── pages/             # login/changePassword/fileList/recycle/user/dept/log/storage
│       ├── components/        # MainLayout / UploadModal（分片上传）
│       ├── api/               # 请求封装与类型
│       └── utils/             # RSA 加密 / 分片 MD5 / 容量格式化
├── docker/                    # docker-compose + nginx.conf + Dockerfile
├── scripts/backup.sh          # 备份脚本（存储+MySQL+Redis）
└── docs/                      # PRD / TSDD / PLAN / TESTCASES / REVIEW
```

## 快速开始

### 前置条件

- JDK 26、Maven 3.9+
- Node.js 20+
- Redis（本地 `docker run -d -p 6379:6379 redis:7` 即可开发调试）

### 1. 启动后端

```bash
cd server
mvn spring-boot:run
# 服务地址 http://localhost:8080
```

> 数据库为 MySQL 8（`pathfinder` 库，需先就绪）；连接参数见下方环境变量。

### 2. 启动前端

```bash
cd frontend
npm install
npm run dev
# 浏览器访问 http://localhost:8000（/api、/upload、/captcha 等已代理到 8080）
```

### 3. 初始账号

| 账号 | 密码 | 说明 |
|---|---|---|
| `admin` | `Init@123` | 系统管理员，首次登录强制改密 |

## 配置说明

核心环境变量（覆盖 `server/src/main/resources/application.yml` 默认值）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DB` / `MYSQL_USER` / `MYSQL_PASSWORD` | localhost / 3306 / pathfinder / pathfinder / pathfinder123 | MySQL 连接（默认主配置，无需 profile） |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | localhost / 6379 / 空 | Redis 连接 |
| `STORAGE_ROOT` | `./data/storage` | 文件存储根目录（files/upload/del/tmp/archive） |
| `RSA_PRIVATE_KEY_PATH` | 空 | RSA 私钥文件路径，生产挂载持久化，避免重启后密钥变更 |
| `CAPTCHA_ENABLED` | true | 登录验证码开关；仅自动化测试部署置 false（绕过验证码），生产必须保持 true |
| `ADMIN_BOOTSTRAP_PASSWORD` | 空 | 空库 Seed 时给首个 admin 的固定密码（配置后不强制改密），用于可重复的 E2E 种子账号 |
| `SYNC_ENABLED` | true | 目录同步扫描开关 |
| `SYNC_WATCH_DIR` | `./data/import`（Docker 部署为 `/data/storage/import`） | 外部导入目录，放置其中的文件会被定时扫描自动入库（默认管理员 + 公共空间） |
| `SYNC_INTERVAL` | `5m` | 同步扫描间隔（如 `5m` / `1h`） |
| `SYNC_SKIP_RECENT_SECONDS` | 30 | 跳过最近 N 秒内写入的文件（防半写） |
| `SYNC_DEDUP_BY_MD5` | true | 导入时按 MD5 去重 |
| `UPLOAD_QUOTA_MAX_BYTES` | 0（关闭） | upload-file 组件全局容量配额（字节），超限上传返回 507 |

### 存储根目录

`STORAGE_ROOT` 为文件存储根目录，启动时自动初始化以下子目录：

| 目录 | 用途 |
|---|---|
| `files/` | 正式文件（`files/{yyyy-MM-dd}/{uuid}.{ext}`，UUID + 日期分目录落盘） |
| `upload/` | 分片上传暂存（分片 `chunks/`、合并产物 `files/`） |
| `del/` | 回收站物理文件（软删除后移入，恢复时迁回） |
| `tmp/` | 批量下载 ZIP 等临时文件 |
| `archive/` | 审计日志归档 CSV |
| `import/` | 外部导入目录（Docker 部署下位于存储根目录内，见上 `SYNC_WATCH_DIR`） |

**目录同步扫描**：定时（默认 5 分钟，单线程）扫描 `import/`，将新文件按约定命名迁入 `files/` 并入库（归属默认管理员、公共空间）；同时校验所有已入库文件的磁盘状态——物理文件缺失标记为「目录文件已被删除」并拦截下载，内容被替换标记为「源文件已被更新」，下载新版后自动复位。扫描器只读校验，绝不修改或删除磁盘文件。

Redis TTL 策略：所有写入默认「固定基础值 + 随机抖动（±20%）」防缓存雪崩；业务精确语义（验证码/锁定/会话/下载令牌）显式覆盖并关闭抖动，详见 TSDD 第 7 章。

## Docker 部署

```bash
# 1. 构建后端与前端镜像（前端为多阶段构建，自动 npm build）
docker compose -f docker/docker-compose.yml build

# 2. 挂载 TLS 证书后启动
mkdir -p certs && cp fullchain.pem certs/ && cp privkey.pem certs/
docker compose -f docker/docker-compose.yml up -d
```

- 入口：`https://<host>/`（80 端口自动重定向 HTTPS）
- 文件存储：宿主机目录绑定挂载到容器 `/data/storage`，通过 `docker/.env` 中 `STORAGE_HOST_DIR` 指定（默认 `./data`，容器内即存储根目录 `STORAGE_ROOT=/data/storage`，导入目录 `SYNC_WATCH_DIR=/data/storage/import`）
- 数据卷：`mysql-data`、`redis-data`、`rsa-key`、`certs`

> 本地部署（Docker Hub 不可达，本机已有 redis:7 镜像）时叠加 local 覆盖文件：
> `docker compose -f docker/docker-compose.yml -f docker/docker-compose.local.yml up -d`

## 测试

```bash
cd server && mvn test          # 后端单元/集成测试（JUnit 5 + Mockito；集成用例需 MySQL pathfinder_test）
cd frontend && npm test        # 前端单元测试（Vitest）
```

后端集成测试运行前先就绪 MySQL/Redis 测试库（容器化）：

```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.local.yml -f docker/docker-compose.test.yml up -d mysql redis
# 然后 cd server && mvn test
```

E2E（Playwright + 本机 Chrome，独立 Docker E2E 栈，跑完自动恢复原部署栈）：

```bash
bash scripts/run-e2e-docker.sh
# 等价：down 现有栈 → 重置 pathfinder_test → 以验证码绕过 + 种子账号启动 E2E 栈
#       → npm run test:e2e（01/02/03 号用例）→ EXIT 时恢复原栈
```

E2E 覆盖：登录页冒烟、TC-E2E-001 全链路（上传/搜索/下载/归属/删除/回收站恢复/审计）、TC-E2E-003 越权拦截与强制改密。测试专用开关均默认关闭，生产不生效。

覆盖率门禁（见 PRD §6）：后端整体行覆盖 ≥80%、核心模块 ≥85%；前端核心交互 ≥70%。

## 文档

| 文档 | 说明 |
|---|---|
| [PRD](docs/PRD-PathFinder-v1.0.0.md) | 产品需求（用户故事 / 功能需求 / 数据权限模型） |
| [TSDD](docs/design/TSDD-PathFinder-v1.0.0.md) | 技术详细设计（架构 / 数据库 / 接口 / 安全 / 组件集成 / 缓存 / 部署） |
| [PLAN](docs/design/PLAN-PathFinder-v1.0.0.md) | 敏捷迭代计划（Sprint / 任务卡 / DoD） |
| [TESTCASES](docs/design/TESTCASES-PathFinder-v1.0.0.md) | 测试用例（110+，含数据权限矩阵） |
| [REVIEW](docs/design/REVIEW-PathFinder-v1.0.0.md) | 生产上线基准审查与修订记录 |
| [ADR-001](docs/design/ADR-001-upload-file-starter-jakarta.md) | 决策记录：组件 jakarta starter 迁移（rc.5 暂不迁移 → rc.6 撤销条件触发后**已迁移**） |
| [UPGRADE starter](docs/design/UPGRADE-upload-file-starter-jakarta.md) | rc.6 迁移执行清单：坐标替换 / 删改（C1~C8）/ 契约差异 / 审计迁移 / 验收与回滚（**已执行**） |
| [CHANGELOG](CHANGELOG.md) | 变更记录（含 `upload-file` 组件 rc.3 → rc.4 → rc.5 → rc.6 → rc.7 → rc.8 升级条目） |

## 已知说明

- 大文件组件 `cn.chenxinjie:upload-file` 早期 starter 依赖 `javax.servlet`，与 Spring Boot 4（jakarta）不兼容，rc.3~rc.5 采用 `upload-file-core` 手工装配（TSDD §11 风险预案）。rc.6 组件补齐商业化 HTTP 层可控接入（`endpoint`/`http`/`multipart` 开关、`AccessControl.decide()`、`AccessControlListener`、`UploadErrorRenderer`），本工程据此**迁移到 `upload-file-spring-boot-starter-jakarta`**：删除 `UploadFileConfig`/`UploadController`，`/upload` 由组件 `UploadServlet` 承载，`/download` 默认关闭（最小暴露），接口契约不变。rc.7 进一步收口存储正确性与扩展点一致性（Redis 索引泄漏/N+1、starter 消费 `UploadErrorRenderer`、multipart 安全默认、受信读 `TrustedUploadService`）；rc.8 收口 GA 前的最后一批能力（`upload-file-bom`、`quota.store=redis` 启动自动对账 + 清理回收配额、分布式 identifier 锁持有期续租、`RedisTaskStore` 索引迁移原子化与 `list()` 分批 MGET、`AccessContext`/`AccessContextHolder` 审计上下文与监听器 6 参重载、`observability.access-log-scope` 降噪、starter 自动装配 `TrustedUploadService`）。本工程已升级并适配（`server/pom.xml` 经 `upload-file-bom:1.0.0-rc.8` 统一版本；移除手写 `UploadTrustedConfig`；越权审计改用 `AccessContext` 记录 method/URI/IP/UA，详见 [ADR-001](docs/design/ADR-001-upload-file-starter-jakarta.md) / [UPGRADE](docs/design/UPGRADE-upload-file-starter-jakarta.md)）。
- 组件 `/upload` 失败响应体为组件端点模型（`http.error-body=legacy`），前端仅消费 HTTP 状态码与文本，兼容；越权统一返回 403。
- v1.0.0 为单机部署形态（PRD 明确非目标），多实例与在线预览/全文检索见版本规划。

## License

MIT（大文件传输组件 `cn.chenxinjie:upload-file` 为 MIT 协议）。
