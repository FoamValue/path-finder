# PathFinder 用户使用手册

- 版本：v1.0.0
- 适用版本：1.0.0
- 目标读者：系统管理员 / 部署运维 / 普通用户

PathFinder 是一款**单组织私有部署**的文件管理系统，解决「文件放哪、谁能看、如何找」三个核心问题。支持大文件分片上传、断点续传、Range 断点下载、组织架构数据权限、操作审计、目录同步扫描。

---

## 1. 系统概述

### 1.1 功能特性

| 能力 | 说明 |
|---|---|
| 安全登录 | 图片验证码 + 前端 RSA 加密传输密码 + BCrypt 存储；连续失败 5 次锁定 10 分钟；单会话互踢；30 分钟滑动超时；首次登录强制改密 |
| 大文件传输 | 分片上传 / 断点续传 / 秒传 / 分片 MD5 校验 / 异步合并 / Range 断点下载，单文件上限 500MB |
| 数据权限 | 个人空间 / 部门空间 / 公共空间三级归属，部门树可见性继承，服务端强制过滤 |
| 高效检索 | 后端真分页，文件名模糊搜索 |
| 软删除 | 回收站保留 30 天，支持恢复、物理清除、批量操作 |
| 目录同步扫描 | 定时扫描导入目录自动入库（默认管理员 + 公共空间），磁盘删除/更新自动标记并提示 |
| 操作审计 | 登录 / 上传 / 下载 / 删除 / 归属变更 / 改密全量留痕，12 个月自动归档 |
| 磁盘监控 | 存储使用率实时展示，达 85% 自动告警 |
| 持久化 | 文件按 UUID + 日期分目录落盘，可宿主机挂载；RSA 密钥持久化避免重启变更 |

### 1.2 角色与权限

| 角色 | 权限 |
|---|---|
| `ADMIN` 系统管理员 | 全部功能：文件、回收站（含物理清除）、用户管理、部门管理、审计日志、系统存储监控 |
| `DEPT_ADMIN` 部门管理员 | 本部门可见范围内的文件操作、本部门成员管理 |
| `USER` 普通员工 | 个人空间与可见范围内的文件管理 |
| `VIEWER` 访客 | 只读查看可见范围内的文件 |

### 1.3 技术架构

```
浏览器 (React + AntD)
   │ HTTPS (443) / HTTP(80 自动跳转)
nginx（静态前端 + /api、/upload 反向代理）
   │
   ├── server（Spring Boot，:8080）
   │     ├── MySQL 8（业务数据）
   │     ├── Redis（会话 / 验证码 / 锁定 / 上传元数据）
   │     └── 存储目录（文件 / 上传暂存 / 回收站 / 归档 / 导入）
```

---

## 2. 环境要求

| 项 | 要求 |
|---|---|
| Docker | 20.10+，Docker Compose V2 |
| 服务器 | 2C4G 起（单机部署），磁盘按文件量规划 |
| 端口 | 80 / 443（nginx），容器内部 8080 / 3306 / 6379 |
| 证书 | TLS 证书（fullchain.pem + privkey.pem），支持自签 |

---

## 3. Docker 安装与部署

### 3.1 获取代码与构建

```bash
git clone <仓库地址> path-finder
cd path-finder

# 构建后端与前端镜像（前端为多阶段构建，自动 npm build）
docker compose -f docker/docker-compose.yml build
```

> 首次构建需从 Docker Hub 拉取 `mysql:8.0`、`redis:9-alpine`、`nginx:alpine`、`eclipse-temurin` 等基础镜像。

### 3.2 服务编排说明（docker/docker-compose.yml）

| 服务 | 镜像 | 说明 |
|---|---|---|
| mysql | mysql:8.0 | 业务数据库（utf8mb4） |
| redis | redis:9-alpine | 会话 / 验证码 / 锁定 / 上传元数据 |
| server | 自建 | Spring Boot 后端，:8080（仅容器网络，不对外） |
| nginx | 自建 | 静态前端 + HTTPS + 反向代理，对外 80/443 |

容器网络与数据卷：

| 卷 / 挂载 | 用途 |
|---|---|
| `${STORAGE_HOST_DIR:-./data}` → `/data/storage` | 文件存储（宿主机绑定挂载，见 3.4） |
| `rsa-key` → `/data/keys` | RSA 私钥持久化 |
| `mysql-data` | MySQL 数据 |
| `redis-data` | Redis 数据（含 AOF） |
| `certs` → `/etc/nginx/certs` | TLS 证书 |

### 3.3 首次部署

```bash
# 1. 放置 TLS 证书
mkdir -p certs
cp fullchain.pem certs/
cp privkey.pem certs/

# 2. （可选）按需配置 docker/.env，见 3.4

# 3. 启动全部服务
docker compose -f docker/docker-compose.yml up -d

# 4. 验证
docker compose -f docker/docker-compose.yml ps   # 全部 Up (healthy)
curl -k https://<服务器地址>/                     # 返回登录页
```

浏览器访问 `https://<服务器地址>/`，初始账号：

| 账号 | 密码 | 说明 |
|---|---|---|
| `admin` | `Init@123` | 系统管理员，**首次登录强制修改密码** |

> HTTP 80 端口会自动 301 跳转到 HTTPS。

### 3.4 本地部署与宿主机存储

**Docker Hub 不可达时**（本机已有 redis:7 镜像），叠加 local 覆盖文件：

```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.local.yml up -d
```

**文件存储落宿主机**：通过 `docker/.env` 指定 `STORAGE_HOST_DIR`，容器内以 `/data/storage` 为存储根目录：

```bash
# docker/.env
STORAGE_HOST_DIR=/Users/chenxinjie/logs/path-finder
```

即宿主机 `/Users/chenxinjie/logs/path-finder` ↔ 容器 `/data/storage` 实时同步，文件直接落在宿主机目录，便于备份与排查。

### 3.5 升级 / 重新部署

```bash
# 1. 重新构建后端 jar 与镜像
mvn -q package -DskipTests -f server/pom.xml
docker compose -f docker/docker-compose.yml -f docker/docker-compose.local.yml build

# 2. 滚动重启（配置变更会自动重建容器）
docker compose -f docker/docker-compose.yml -f docker/docker-compose.local.yml up -d

# 3. 查看启动日志确认无异常
docker logs -f path-finder-server-1
```

### 3.6 常用运维命令

```bash
docker compose -f docker/docker-compose.yml ps          # 状态
docker compose -f docker/docker-compose.yml logs -f server   # 后端日志
docker compose -f docker/docker-compose.yml restart server   # 重启后端
docker compose -f docker/docker-compose.yml down            # 停止（保留数据卷）
```

---

## 4. 系统配置（持久化配置）

### 4.1 核心环境变量

后端配置通过环境变量覆盖 `server/src/main/resources/application.yml` 默认值（`docker-compose.yml` 中已注入容器）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DB` / `MYSQL_USER` / `MYSQL_PASSWORD` | localhost / 3306 / pathfinder / pathfinder / pathfinder123 | MySQL 连接 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | localhost / 6379 / 空 | Redis 连接 |
| `STORAGE_ROOT` | `./data/storage` | 文件存储根目录（容器内固定 `/data/storage`，宿主机路径由 `STORAGE_HOST_DIR` 决定） |
| `RSA_PRIVATE_KEY_PATH` | 空 | RSA 私钥文件路径；为空则启动时生成并写入存储根目录。**生产务必挂载持久化**（如容器内 `/data/keys/private.pem`），避免重启后密钥变更导致登录失败 |
| `SYNC_ENABLED` | true | 目录同步扫描开关 |
| `SYNC_WATCH_DIR` | `<STORAGE_ROOT>/import` | 外部导入目录 |
| `SYNC_INTERVAL` | `5m` | 同步扫描间隔 |
| `SYNC_SKIP_RECENT_SECONDS` | 30 | 跳过最近 N 秒内写入的文件（防半写） |
| `SYNC_DEDUP_BY_MD5` | true | 导入时按 MD5 去重 |

前端版权信息（页面底部）通过 Vite 环境变量配置，见 `frontend/.env.example`：

```bash
# frontend/.env
VITE_COPYRIGHT=chenxinjie
```

### 4.2 存储根目录结构

`STORAGE_ROOT`（宿主机 `STORAGE_HOST_DIR`）下子目录：

| 目录 | 用途 | 持久化要求 |
|---|---|---|
| `files/` | 正式文件（`files/{yyyy-MM-dd}/{uuid}.{ext}`） | 必须持久化 |
| `upload/` | 分片上传暂存（分片 `chunks/`、合并产物 `files/`） | 建议持久化（支持续传） |
| `del/` | 回收站物理文件 | 建议持久化 |
| `tmp/` | 批量下载 ZIP 等临时文件 | 可丢弃 |
| `archive/` | 审计日志归档 CSV | 必须持久化 |
| `import/` | 目录同步扫描导入目录 | 建议持久化 |

### 4.3 数据持久化清单

| 数据 | 位置 | 备份方式 |
|---|---|---|
| 文件 | 宿主机 `STORAGE_HOST_DIR`（绑定挂载） | 直接拷贝 / 压缩 |
| 数据库 | `mysql-data` 卷 | `scripts/backup.sh` |
| Redis | `redis-data` 卷（AOF） | `scripts/backup.sh` |
| RSA 私钥 | `rsa-key` 卷 | 首次生成后备份一份即可 |
| TLS 证书 | `certs` 卷 / 宿主机 `certs/` | 自行保管 |

### 4.4 备份与恢复

项目提供 `scripts/backup.sh`：

```bash
cd path-finder
./scripts/backup.sh                 # 默认输出到 ./backups/<时间戳>/
BACKUP_ROOT=/data/backups ./scripts/backup.sh
```

产物：

```
backups/<时间戳>/
├── storage.tar.gz   # 存储目录 + archive 归档
└── mysql.sql        # MySQL 全量逻辑备份
```

恢复：`storage.tar.gz` 解压回存储根目录；`mysql.sql` 通过 `mysql -u<user> -p <pathfinder> < mysql.sql` 导入（需先清空原库）。

### 4.5 目录同步扫描（外挂导入）

往 `SYNC_WATCH_DIR`（容器内 `/data/storage/import`，即宿主机 `STORAGE_HOST_DIR/import`）直接拷贝文件，扫描器会：

- **新增**：按约定命名迁入 `files/` 并自动入库，归属默认**管理员 + 公共空间**；
- **删除**：检测到磁盘文件缺失，列表标记「目录文件已被删除」，下载被拦截；
- **更新**：检测到内容被替换，列表标记「源文件已被更新」，下载新版后自动复位。

扫描器只读校验，绝不修改或删除磁盘文件；单线程运行，间隔默认 5 分钟（`SYNC_INTERVAL` 可调）。

---

## 5. 系统功能使用

### 5.1 登录与账号安全

1. 访问 `https://<服务器地址>/`，输入用户名、密码与图片验证码。
2. 密码经前端 RSA 加密传输，后端私钥解密后 BCrypt 校验。
3. `admin` 首次登录会强制要求修改初始密码（新密码 ≥ 8 位）。
4. 安全策略：单会话互踢（新登录顶掉旧会话）、30 分钟无操作自动登出、连续输错 5 次锁定 10 分钟。

### 5.2 文件管理

**上传**
- 点击「上传文件」，支持选择文件、按空间归属（个人/部门/公共）上传。
- 大文件自动分片（5MB/片）、断点续传、MD5 校验、异步合并；断网后重新选择同一文件可续传。
- 单文件上限 500MB。

**列表与检索**
- 顶部工具栏：文件名模糊搜索、空间类型筛选（个人/部门/公共）、部门筛选。
- 后端真分页，支持切换每页条数。

**单文件操作**（行内按钮）
- 下载：支持 Range 断点续传；文件处于异常状态时按提示处理（见 5.5）。
- 重命名：修改文件名（同步更新类型）。
- 归属：变更文件所在空间 / 目标部门 / 移交归属人（管理员与部门管理员可用）。
- 删除：软删除，文件进入回收站。

**批量操作**（勾选多行后）
- 批量下载：打包 ZIP（单次最多 100 个文件）。
- 批量归属：弹窗统一设置目标空间/部门/归属人。
- 批量删除：二次确认后批量软删除进回收站。

### 5.3 回收站

- 软删除的文件保留 30 天，到期自动物理清除。
- 单条/批量**恢复**：文件从 `del/` 迁回 `files/`，需具备操作权限（归属人 / 部门管理员 / 管理员）。
- 单条/批量**物理清除**：仅系统管理员可操作，不可恢复。
- 列表按数据权限过滤：仅展示当前用户可见范围内的记录。

### 5.4 用户与部门管理（管理员）

- **部门管理**：维护组织树，部门空间文件的可见性随部门树继承。
- **用户管理**：创建用户、分配角色（ADMIN/DEPT_ADMIN/USER/VIEWER）、归入部门、启停用、重置密码。
- 部门管理员仅能管理本部门成员。

### 5.5 目录同步与文件异常状态

导入目录扫描产生的文件在列表中会带状态角标：

| 状态 | 角标 | 行为 |
|---|---|---|
| 正常 | 无 | 正常下载 |
| `MISSING` | 目录文件已被删除 | 下载被拦截，提示"目录文件已经被删除" |
| `UPDATED` | 源文件已被更新 | 下载放行新版，成功后自动复位为正常 |

### 5.6 存储监控（管理员）

- 「系统存储」菜单（管理员可见，置顶展示）：实时展示总容量 / 已用 / 可用与使用率。
- 使用率达 85% 时顶部出现告警提示，请及时扩容或清理回收站。

### 5.7 审计日志（管理员）

- 全量记录登录、上传、下载、删除、归属变更、物理清除、改密、目录导入/状态同步等操作。
- 支持按操作人 / 类型 / 成功与否 / 时间范围检索；12 个月前的日志自动归档为 CSV。

### 5.8 修改密码

右上角头像菜单 →「修改密码」，需验证原密码，新密码 ≥ 8 位。

---

## 6. 常见问题（FAQ）

**Q1：登录报"密码解密失败，请刷新页面重新登录"？**
A：多为 RSA 私钥与公钥不匹配（私钥文件缺失/变更，或重启后未持久化）。确保 `RSA_PRIVATE_KEY_PATH` 指向已持久化的私钥（容器内 `/data/keys/private.pem`），刷新页面重试；若仍失败请检查 `rsa-key` 卷是否被重建。

**Q2：上传报"合并失败/分片校验失败"？**
A：多为网络抖动或分片损坏，前端会自动重传该分片；可刷新后重新选择同一文件续传。确认 nginx `client_max_body_size 600m` 与后端 `upload-file.max-file-size` 配置。

**Q3：下载提示"目录文件已经被删除"？**
A：文件物理文件已从磁盘丢失（非系统删除）。将原文件放回存储根目录对应相对路径，扫描器会自动复位为正常；或由管理员在回收站清理该记录。

**Q4：如何修改页面底部版权信息？**
A：在 `frontend/.env` 设置 `VITE_COPYRIGHT=xxx` 后重新构建前端镜像并部署。

**Q5：数据如何备份？**
A：运行 `scripts/backup.sh`（存储 + MySQL + Redis AOF）；文件目录建议同时做文件级异地备份。

---

## 7. 附：目录结构

```
path-finder/
├── server/                 # 后端（Spring Boot）
├── frontend/               # 前端（Vite + React + AntD）
├── docker/                 # docker-compose + nginx + Dockerfile + .env
├── scripts/backup.sh       # 备份脚本
├── certs/                  # TLS 证书（fullchain.pem / privkey.pem）
└── docs/                   # PRD / TSDD / 设计文档 / 本手册
```
