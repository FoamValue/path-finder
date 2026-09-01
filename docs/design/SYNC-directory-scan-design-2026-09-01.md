# 目录同步扫描 设计文档

- 版本：v1.0.0
- 日期：2026-09-01
- 状态：已评审通过
- 关联需求：定时扫描文件目录更新数据库中文件记录

## 1. 背景与目标

系统支持外部文件通过"独立导入目录"落地，并定时同步磁盘与数据库记录的一致性。

- 新增文件：扫描导入目录，自动入库，归属默认管理员 + 公共空间。
- 删除文件：检测到磁盘文件缺失，更新记录状态，下载时提示"目录文件已经被删除"。
- 文件被更新：检测到源文件内容变更，更新记录状态，下载时提示"源文件已经被更新"。

### 约束

- 单线程处理，严格控制系统资源占用，同一时刻仅允许一个扫描任务执行。
- 扫描器只允许移动（move）文件用于导入；校验阶段只读，绝不修改或删除磁盘文件。

## 2. 方案选型

采用 **方案 A：独立同步扫描器**。

- 新增 `SyncScannerService` + 定时任务，独立于现有上传/回收站链路。
- 用独立字段 `disk_status` 表达磁盘同步状态，不影响现有 `status`（UPLOADING/READY）的列表过滤语义。
- 下载拦截：删除态阻断，更新态放行新版并在成功后刷新记录。
- 已否决方案 B（仅告警不自动改状态，自动化弱）与方案 C（复用回收站状态机，复杂度高收益低）。

## 3. 配置

```yaml
pathfinder:
  sync:
    enabled: true
    watch-dir: ${SYNC_WATCH_DIR:./data/import}   # 外部导入目录
    interval: 5m                                  # 扫描间隔（可配置）
    skip-recent-seconds: 30                       # 跳过最近修改的文件（防半写）
    dedup-by-md5: true                            # 导入按 MD5 去重
```

## 4. 数据模型扩展（FileInfo）

| 列 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `disk_status` | varchar(16) | `READY` | `READY` / `MISSING`（目录文件已被删除）/ `UPDATED`（源文件已被更新） |
| `disk_modified_at` | datetime | null | 磁盘文件最后修改时间基线 |

- 现有 `status`（UPLOADING/READY）与 `delFlag` 语义完全不变。
- 列表页过滤逻辑不动，异常文件通过 `disk_status` 在列表带角标展示。
- 历史数据（功能上线前已入库的 READY 文件）`disk_modified_at` 为 null，首次扫描时以当前磁盘 md5 + mtime 建立基线，仅刷新不标记，避免批量误报 `UPDATED`。

## 5. 定时任务 SyncScannerService

### 5.1 单线程与防重入

- `AtomicBoolean running` 标记：扫描进行中直接跳过本次触发，保证任意时刻仅一个扫描在运行。
- 扫描内部不开启并行流/线程池。

### 5.2 阶段一 · 导入（可移动文件）

1. 递归遍历 `watch-dir` 下的文件。
2. 跳过最近 `skip-recent-seconds` 秒内写入的文件（防半写）。
3. 计算文件 MD5；若命中已有 READY 记录且 `dedup-by-md5=true`，跳过。
4. `Files.move`（原子移动）迁入 `files/{yyyy-MM-dd}/{uuid}.{ext}`，沿用 `PathUtil.relativeStorePath` 命名约定。
5. 插入 `file_info`：`originalName`=原文件名、`fileName`=uuid.ext、`fileSize`、`fileMd5`、`fileType`、`storagePath`=相对路径、`spaceType=PUBLIC`、`deptId=null`、`ownerId/creatorId`=admin 用户、`status=READY`、`diskStatus=READY`、`diskModifiedAt`=源文件 mtime。
6. 写操作日志（IMPORT）。
7. admin 用户通过用户名 `admin` 查询（DataInitializer 种子），启动时解析并缓存。

### 5.3 阶段二 · 校验（只读）

分页遍历所有 `delFlag=0 AND status=READY` 记录：

1. 物理文件（`{root}/{storagePath}`）缺失 → `diskStatus=MISSING`（仅更新 DB，不删除文件）。
2. 物理文件存在：
   - 先比对 `fileSize` + `disk_modified_at`，均一致 → 保持 READY。
   - 不一致 → 重新计算 MD5：
     - MD5 相同（仅 touch/属性变化）→ 刷新 `disk_modified_at` 基线，保持 READY。
     - MD5 不同（内容被替换）→ `diskStatus=UPDATED`，**不刷新基线**，状态持续保留，直到下载复位或文件还原。
   - `disk_modified_at` 为 null（历史数据）→ 计算 md5 + 记录 mtime 基线，保持 READY，不标记。
   - 说明：UPDATED 文件每次扫描基线不一致会重算 MD5（用于识别"还原为原内容"→ 复位 READY）。UPDATED 为小集合，开销可接受。
3. 原 `MISSING` 文件重新出现 → 重算 MD5：与记录一致 → 复位 READY 并刷新基线；不一致 → 置 `UPDATED`（不刷新基线）。

## 6. 下载拦截（FileController.download / FileService）

- `diskStatus=MISSING`：抛出 `BizException`，提示"目录文件已经被删除"，阻断下载。
- `diskStatus=UPDATED`：列表页角标提示"源文件已经被更新"；下载时放行新版，成功后刷新 md5/size/mtime，`diskStatus` 复位 READY。
- 校验发生在下载令牌解析后的实际取文件阶段（`resolveDownloadPath` 处），保证时序一致。
- 批量下载（ZIP）已天然跳过缺失文件（`FileService.batchDownload` 检查 `Files.exists`），不受影响。

## 7. 资源与安全约束

- 单线程调度 + 防重入。
- watch-dir 与 files/ 均限定在配置根目录内，复用 `PathUtil.resolve` 防路径穿越。
- 扫描器仅允许 move 导入；校验阶段绝不删除/覆盖磁盘文件。
- MD5 全量重算仅在 size/mtime 变化时触发，控制 IO 开销。

## 8. 测试

沿用项目 `@TempDir` 单测模式，覆盖：

1. 导入目录新文件 → 迁入 files/ 并入库（admin + PUBLIC + READY）。
2. MD5 去重：重复文件跳过。
3. 磁盘文件缺失 → `MISSING`。
4. touch 不误报：仅 mtime 变化、内容不变 → 保持 READY 并刷新基线。
5. 内容替换 → `UPDATED`。
6. `MISSING` 文件复活 → 复位 READY。
7. 下载拦截：MISSING 阻断；UPDATED 放行并刷新复位 READY。
8. 防重入：扫描未结束时再次触发被跳过。
