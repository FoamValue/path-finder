# PathFinder 文件管理系统

> 寓意「寻找系统路径」——为个人或小型企业提供统一、安全、可审计的内容文件管理。

PathFinder 是一个**单组织私有部署**的文件管理系统，解决「文件放哪、谁能看、如何找」三个核心问题。支持大文件分片上传、断点续传、Range 断点下载、组织架构数据权限、操作审计。

## 功能特性

- 🔐 **安全登录**：图片验证码 + 前端 RSA 加密传输密码 + BCrypt 存储；连续失败 5 次锁定 10 分钟；多登录踢出（单会话）；会话超时自动登出；首次登录强制改密
- 📂 **大文件传输**：基于 `cn.chenxinjie:upload-file:1.0.0-rc.3` 分片上传 / 断点续传 / 秒传 / 分片 MD5 校验 / 异步合并 / Range 断点下载
- 🗂 **数据权限**：个人空间 / 部门空间 / 公共空间三级归属，部门树可见性继承，服务端强制过滤
- 🔍 **高效检索**：后端真分页（数据库层 `LIMIT/OFFSET`），文件名模糊搜索
- ♻️ **软删除**：回收站保留 30 天，支持恢复与物理清除
- 📝 **操作审计**：登录/上传/下载/删除/归属变更/改密全量留痕，12 个月归档
- 💾 **磁盘持久化**：UUID + 日期分目录落盘，使用率 85% 告警，启动自动初始化目录
- 🐳 **容器化部署**：nginx:alpine（TLS）+ server + redis:9 + mysql:8，存储/证书卷持久化

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | React 18 · TypeScript · Ant Design 5 / ProComponents · Vite |
| 后端 | JDK 26 · Spring Boot 4.1.1 · Spring Security · Spring Data JPA · Jackson 3 |
| 缓存 | Redis 9（会话 / 验证码 / 锁定 / 元数据缓存，TTL 固定基础值 + 随机抖动防雪崩） |
| 数据库 | MySQL 8（生产）/ H2（开发） |
| 大文件组件 | `cn.chenxinjie:upload-file:1.0.0-rc.3`（core 手动装配，接口契约与组件一致） |
| 部署 | Docker Compose · nginx:alpine · TLS |

## 目录结构

```
path-finder/
├── server/                    # 后端（Spring Boot Maven 单模块，包 com.pathfinder）
│   └── src/main/java/com/pathfinder/
│       ├── config/            # 安全/Redis/上传组件/异常/调度器/Seed
│       ├── controller/        # auth/user/dept/file/recycle/log/storage/upload
│       ├── service/           # 业务服务与数据权限判定
│       ├── repository/        # JPA Repository（真分页）
│       ├── entity/            # JPA 实体（含 ts 字段只读映射）
│       ├── security/          # Token 认证过滤器 / 当前用户上下文
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
mvn spring-boot:run -Dspring-boot.run.profiles=h2
# 服务地址 http://localhost:8080
```

> 开发默认 H2 内存库（重启重置数据）；生产使用 MySQL，见 `application-mysql.yml`。

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
| `SPRING_PROFILES_ACTIVE` | `h2` | `h2` 开发 / `mysql` 生产 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | localhost / 6379 / 空 | Redis 连接 |
| `MYSQL_HOST` / `MYSQL_USER` / `MYSQL_PASSWORD` / `MYSQL_DB` | — | MySQL 连接（mysql profile） |
| `STORAGE_ROOT` | `./data/storage` | 文件存储根目录（files/upload/del/tmp/archive） |
| `RSA_PRIVATE_KEY_PATH` | 空 | RSA 私钥文件路径，生产挂载持久化，避免重启后密钥变更 |

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
- 数据卷：`storage-data`（文件）、`mysql-data`、`redis-data`、`rsa-key`、`certs`

## 测试

```bash
cd server && mvn test          # 后端单元测试（JUnit 5 + Mockito）
cd frontend && npm test        # 前端单元测试（Vitest）
```

覆盖率门禁（见 PRD §6）：后端整体行覆盖 ≥80%、核心模块 ≥85%；前端核心交互 ≥70%。

## 文档

| 文档 | 说明 |
|---|---|
| [PRD](docs/PRD-PathFinder-v1.0.0.md) | 产品需求（用户故事 / 功能需求 / 数据权限模型） |
| [TSDD](docs/design/TSDD-PathFinder-v1.0.0.md) | 技术详细设计（架构 / 数据库 / 接口 / 安全 / 组件集成 / 缓存 / 部署） |
| [PLAN](docs/design/PLAN-PathFinder-v1.0.0.md) | 敏捷迭代计划（Sprint / 任务卡 / DoD） |
| [TESTCASES](docs/design/TESTCASES-PathFinder-v1.0.0.md) | 测试用例（110+，含数据权限矩阵） |
| [REVIEW](docs/design/REVIEW-PathFinder-v1.0.0.md) | 生产上线基准审查与修订记录 |

## 已知说明

- 大文件组件 `upload-file-spring-boot-starter` 依赖 `javax.servlet`，与 Spring Boot 4（jakarta）不兼容，故采用组件 `upload-file-core` 手动装配（TSDD §11 风险预案），对外接口契约不变。
- v1.0.0 为单机部署形态（PRD 明确非目标），多实例与在线预览/全文检索见版本规划。

## License

MIT（大文件传输组件 `cn.chenxinjie:upload-file` 为 MIT 协议）。
