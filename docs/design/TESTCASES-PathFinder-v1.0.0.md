# PathFinder v1.0.0 — 测试用例（TC）

| 项目 | 内容 |
|---|---|
| 关联文档 | PRD v1.0.0 / TSDD v1.0.0 / PLAN v1.0.0 / REVIEW v1.0.0 / SYNC 设计稿 |
| 测试层次 | 单元 / 集成 / 组件联调 / 前端 / E2E |
| 优先级 | P0（阻塞发布）/ P1（必须）/ P2（可选） |
| 迭代登记 | 2026-09-02：登记自动化回归映射（§15）、批量操作与目录同步（§15）、安全矩阵（§16）、已知缺口（§17） |

---

## 1. 测试范围与策略

| 类别 | 工具 | 覆盖模块 |
|---|---|---|
| 后端单元/集成 | JUnit 5 + Mockito + MySQL 8（测试库） | 鉴权、锁定、踢出、数据权限、归属变更、审计、调度器、Seed、存储监控 |
| 安全层 | Spring Security（@WebMvcTest slice 或过滤器直测，Redis/Repo mock） | 端点鉴权矩阵、TokenAuthFilter 白名单/账号状态、会话失效 |
| 组件联调 | 集成测试直连 `upload-file` | 分片、断点续传、秒传、mergeAsync、Range |
| 前端单测 | **Vitest** + React Testing Library + jsdom（实际采用 Vitest；非 Jest） | 登录、上传交互、权限渲染、改密、请求封装 |
| E2E | Playwright（骨架见 `frontend/tests/e2e`，待浏览器/测试库就绪） | 关键全流程 |
| 性能 | 压测脚本（待建） | 真分页、并发上传、搜索 |

环境：MySQL 8（`pathfinder_test`，集成/矩阵用例依赖）+ Redis 9（生产必需；自动化矩阵已避免依赖 Redis 实例）；大文件测试数据：1MB、5MB+、200MB、500MB。

> 技术选型注：实际前端工程为 Vite + React + AntD（非 UmiJS），单测框架为 Vitest；Boot 4 下 `@WebMvcTest` 位于独立产物 `spring-boot-starter-webmvc-test`。文档其余处按现状理解。


---

## 2. 登录与鉴权（F1 / U1~U9）

### 2.1 验证码

| ID | 优先级 | 用例 | 步骤 | 预期 |
|---|---|---|---|---|
| TC-LOGIN-001 | P0 | 验证码正常获取 | 打开登录页 | 返回 `uuid` + base64 图片，可正常渲染 |
| TC-LOGIN-002 | P0 | 无验证码登录被拒 | 不填验证码直接登录 | 拒绝，提示"验证码错误或已过期" |
| TC-LOGIN-003 | P0 | 错误验证码被拒 | 输入错误验证码 + 正确密码 | 拒绝，并刷新验证码 |
| TC-LOGIN-004 | P0 | 验证码一次性 | 同一验证码连续登录两次 | 第二次提示验证码过期，需重新获取 |
| TC-LOGIN-005 | P1 | 验证码过期 | 获取验证码后等待 5 分钟再登录 | 提示"验证码已过期"并刷新 |

### 2.2 密码加密传输

| ID | 优先级 | 用例 | 步骤 | 预期 |
|---|---|---|---|---|
| TC-LOGIN-006 | P0 | 传输无明文密码 | 登录抓包（DevTools/代理） | 请求中无明文密码（RSA 密文） |
| TC-LOGIN-007 | P0 | 正确密码登录成功 | 正确用户名/密码/验证码 | 返回 token，进入系统 |
| TC-LOGIN-008 | P0 | 错误密码提示 | 错误密码登录 | 提示"用户名或密码错误"，不泄露用户名是否存在 |
| TC-LOGIN-009 | P1 | 密码 BCrypt 存储 | 查看数据库 `sys_user.password` | 全部为 BCrypt 哈希，无明文 |

### 2.3 失败锁定

| ID | 优先级 | 用例 | 步骤 | 预期 |
|---|---|---|---|---|
| TC-LOGIN-010 | P0 | 连续 5 次失败锁定 | 同一用户名连续输错 5 次密码 | 第 5 次后提示"账号已锁定，请 10 分钟后再试" |
| TC-LOGIN-011 | P0 | 锁定期内正确密码也被拒 | 锁定期间用正确密码登录 | 仍拒绝，提示锁定 |
| TC-LOGIN-012 | P1 | 到期自动解锁 | 等待 10 分钟后用正确密码登录 | 登录成功 |
| TC-LOGIN-013 | P1 | 成功登录清零计数 | 失败 4 次后第 5 次成功，再失败 4 次 | 未触发锁定（计数已清零） |
| TC-LOGIN-014 | P1 | 不同用户名独立计数 | 用户 A 连续失败 5 次，用户 B 登录 | B 不受影响，可正常登录 |

### 2.4 多登录踢出与会话

| ID | 优先级 | 用例 | 步骤 | 预期 |
|---|---|---|---|---|
| TC-LOGIN-015 | P0 | 新登录踢出旧会话 | 同一账号 A 设备登录后，B 设备再登录，A 设备调用接口 | A 设备返回 401；B 设备正常 |
| TC-LOGIN-016 | P0 | 会话超时 | 登录后闲置超过 30 分钟调用接口 | 401，跳转登录页 |
| TC-LOGIN-017 | P1 | 滑动续期 | 会话内频繁操作接近 30 分钟 | 会话不中断 |
| TC-LOGIN-018 | P1 | 主动登出 | 登录后点"退出登录" | 会话失效，再访问需重新登录 |

### 2.5 强制改密与账号状态

| ID | 优先级 | 用例 | 步骤 | 预期 |
|---|---|---|---|---|
| TC-LOGIN-019 | P0 | 首次登录强制改密 | 用初始密码登录 | 仅允许访问改密页，其余接口 403 |
| TC-LOGIN-020 | P0 | 改密后放开访问 | 完成改密 | 可正常访问全部权限接口 |
| TC-LOGIN-021 | P0 | 停用账号登录被拒 | 管理员停用某用户后该用户登录 | 提示"账号已停用，请联系管理员" |
| TC-LOGIN-022 | P1 | 停用账号现有会话失效 | 用户在线时被停用后调用接口 | 401 立即失效 |
| TC-LOGIN-023 | P0 | 无会话访问改密/登出被拒（G5） | 未登录直接调用 `/changePassword`、`/logout` | 401 |
| TC-LOGIN-024 | P1 | 滑动续期同步会话映射（G6） | 会话活跃超过 30min（映射不失效）后再次登录 | 未因映射过期产生异常，踢出行为正确 |
| TC-LOGIN-025 | P1 | 登录页每次渲染拉取公钥（G11） | 刷新登录页观察网络请求 | 每次渲染均请求 `/publicKey`；后端重启后前端仍可正常加密登录 |

---

## 3. 用户与部门管理（F2 / U10~U13）

| ID | 优先级 | 用例 | 步骤 | 预期 |
|---|---|---|---|---|
| TC-ORG-001 | P0 | 部门树展示 | ADMIN 访问部门管理 | 按父子关系正确渲染树 |
| TC-ORG-002 | P0 | 新增部门 | 新增"研发部"（根下） | 保存成功，树刷新 |
| TC-ORG-003 | P0 | 编辑部门 | 修改部门名称 | 更新生效 |
| TC-ORG-004 | P1 | 删除空部门 | 删除无子部门/无文件部门 | 删除成功 |
| TC-ORG-005 | P1 | 删除有子部门被拒 | 删除含子部门的部门 | 拒绝并提示先删除子部门 |
| TC-ORG-006 | P1 | 删除含文件部门被拒 | 删除部门空间下有文件的部门 | 拒绝并提示先转移文件 |
| TC-ORG-007 | P0 | 新增用户 | ADMIN 新增用户并分配部门/角色 | 用户可用初始密码登录 |
| TC-ORG-008 | P0 | 编辑用户 | 修改部门/角色/姓名 | 保存生效 |
| TC-ORG-009 | P0 | 停用用户 | 停用某用户 | 立即无法登录（见 TC-LOGIN-021/022） |
| TC-ORG-010 | P1 | 重置密码 | 重置用户密码 | 该用户需重新强制改密 |
| TC-ORG-011 | P0 | 用户真分页 | 100+ 用户分页查询 | 每页按 pageSize 返回，total 准确 |
| TC-ORG-012 | P1 | 权限约束 | DEPT_ADMIN 访问用户管理 | 仅见本部门成员，无新增/编辑按钮 |
| TC-ORG-013 | P1 | 非管理员访问被拒 | USER 角色调用用户/部门写接口 | 403 |
| TC-ORG-014 | P0 | 初始 admin 可登录（G1） | 首次启动后用 Seed 的 admin/Init@123 登录 | 登录成功但被强制改密，完成后可管理 |
| TC-ORG-015 | P1 | 停用用户个人文件冻结（G12） | 停用某用户后以其身份访问个人空间 | 个人文件不可见；ADMIN/部门管理员可查看并移交 |
| TC-ORG-016 | P1 | 删除用户强制移交（G12） | 删除仍持有个人文件的用户 | 系统阻止并提示先移交；移交后可删除 |

---

## 4. 文件上传（F3 / F8 / U14~U18）

### 4.1 基础上传

| ID | 优先级 | 用例 | 步骤 | 预期 |
|---|---|---|---|---|
| TC-UP-001 | P0 | 小文件单传 | 上传 1MB 文件到个人空间 | 上传→合并→入库成功，列表可见 |
| TC-UP-002 | P0 | 批量上传 | 一次选择 10 个文件 | 全部入库成功 |
| TC-UP-003 | P0 | 指定空间 | 分别上传到个人/部门/公共空间 | 各空间列表正确显示 |
| TC-UP-004 | P1 | 部门空间必选部门 | 空间=部门但不选部门上传 | 拦截并提示 |
| TC-UP-005 | P1 | 同名不覆盖 | 上传同名文件两次 | 第二次命名为 `xxx(1).pdf` |

### 4.2 分片 / 断点续传 / 秒传

| ID | 优先级 | 用例 | 步骤 | 预期 |
|---|---|---|---|---|
| TC-UP-006 | P0 | 大文件分片上传 | 上传 200MB 文件 | 自动分片（5MB）上传，merge 后内容完整（MD5 校验一致） |
| TC-UP-007 | P0 | 断点续传 | 上传中断（断网/强杀），重新选择同一文件 | 从断点继续，仅传缺失分片，最终完整 |
| TC-UP-008 | P1 | 秒传 | 已上传完的文件再次上传 | 查询 progress 后跳过全部已传分片，秒级完成 |
| TC-UP-009 | P0 | 分片 MD5 校验 | 篡改某个分片后上传 | 该分片被拒并重传，最终文件 MD5 正确 |
| TC-UP-010 | P1 | mergeAsync 状态轮询 | 提交异步合并 | 状态 NONE→RUNNING→SUCCEEDED，结果文件可下载 |
| TC-UP-011 | P1 | 合并失败重试 | 构造分片缺失场景 merge | 明确失败信息，补传后可恢复 |
| TC-UP-012 | P1 | 上传进度展示 | 上传大文件观察 UI | 进度百分比实时更新，可暂停/恢复 |

### 4.3 限制与异常

| ID | 优先级 | 用例 | 步骤 | 预期 |
|---|---|---|---|---|
| TC-UP-013 | P0 | 超单文件上限 | 上传 >500MB 文件 | 落盘前拒绝，提示超限（400） |
| TC-UP-014 | P1 | 超单片大小 | 单分片 >5MB 直接提交 | 拒绝 |
| TC-UP-015 | P1 | 配额超限 | 全局配额开启后上传至超限 | 返回 507 并提示存储不足 |
| TC-UP-016 | P1 | 磁盘满处理 | 模拟磁盘满上传 | 明确错误提示，不产生脏文件 |
| TC-UP-017 | P2 | 上传中文件归属变更 | 文件 UPLOADING 时改归属 | 提示"上传/合并中，请稍后再试" |
| TC-UP-018 | P1 | confirm 元数据不可达重试（G3） | 构造合并任务元数据丢失后调用 confirm | 返回明确错误；重新 mergeAsync 并轮询 SUCCEEDED 后 confirm 成功 |

---

## 5. 文件下载（F4 / U19~U22）

| ID | 优先级 | 用例 | 步骤 | 预期 |
|---|---|---|---|---|
| TC-DL-001 | P0 | 单文件下载 | 下载有权限文件 | 文件名保留原始名，内容 MD5 一致 |
| TC-DL-002 | P0 | 无权限下载被拒 | 访问他人个人空间文件下载 | 403 |
| TC-DL-003 | P0 | Range 断点下载 | 携带 `Range: bytes=100-199` | 返回 206，内容为对应区间 |
| TC-DL-004 | P1 | 无效 Range | 携带超出文件大小的 Range | 返回 416 |
| TC-DL-005 | P1 | 下载中断续传 | 中断后断点续传 | 最终文件 MD5 完整一致 |
| TC-DL-006 | P0 | 批量 ZIP | 勾选 5 个文件批量下载 | 返回 ZIP 且解压后文件完整 |
| TC-DL-007 | P1 | ZIP 数量上限 | 选择 101 个文件批量下载 | 拒绝并提示上限 100 |
| TC-DL-008 | P1 | ZIP 内文件名正确 | 批量下载含同名不同文件 | ZIP 内保留原始文件名，无覆盖 |
| TC-DL-009 | P1 | 下载令牌一次性 | 使用后的下载 token 再访问 | 失效（401/404） |
| TC-DL-010 | P2 | VIEWER 权限 | VIEWER 下载被授权文件 | 可下载；尝试上传则无入口/403 |

---

## 6. 文件管理与检索（F5 / U23~U27）

| ID | 优先级 | 用例 | 步骤 | 预期 |
|---|---|---|---|---|
| TC-FILE-001 | P0 | 列表真分页 | 构建 5000 条文件数据，翻页浏览 | 每页仅请求当前页（接口无全量返回），total 准确 |
| TC-FILE-002 | P0 | pageSize 上限 | 请求 pageSize=1000 | 被钳制为 100 |
| TC-FILE-003 | P0 | 空间筛选 | 按个人/部门/公共筛选 | 仅返回当前空间可见数据 |
| TC-FILE-004 | P0 | 部门筛选 | DEPT_ADMIN 查看部门空间 | 返回本部门及全部下级部门文件 |
| TC-FILE-005 | P0 | 关键字搜索 | 搜索"合同" | 文件名包含"合同"的文件命中，分页正确 |
| TC-FILE-006 | P1 | 重命名 | 所有者重命名文件 | 生效，原名不覆盖 |
| TC-FILE-007 | P1 | 非所有者重命名被拒 | 他人对文件重命名 | 403 |
| TC-FILE-008 | P1 | 文件元信息 | 查看文件详情 | 大小/类型/上传人/上传时间/归属正确 |
| TC-FILE-009 | P0 | 软删除 | 删除文件 | 列表不再显示，回收站出现记录 |
| TC-FILE-010 | P0 | 回收站恢复 | 恢复刚删除文件 | 文件回到原空间列表，内容完整可下载 |
| TC-FILE-011 | P0 | 回收站到期清除 | 模拟删除 30 天后 | 回收站记录删除，物理文件清除（`del/` 清理） |
| TC-FILE-012 | P1 | 立即物理清除 | 回收站中手动清除 | 物理文件删除，记录删除 |
| TC-FILE-013 | P1 | 缓存一致性 | 修改部门/用户后立即查询 | 部门树/用户信息为最新（缓存失效） |
| TC-FILE-014 | P1 | UPLOADING 孤儿清理（G2） | 构造中断上传（24h 前创建、未完成） | 调度器清理该 file_info 记录并留痕；分片由组件清理 |
| TC-FILE-015 | P1 | 恢复后物理路径迁回（G7） | 删除后从回收站恢复 | 文件迁回 `files/{原日期}/{uuid}`，可正常下载，原路径/部门校验通过 |

---

## 7. 归属变更（F9 / U28）

| ID | 优先级 | 用例 | 步骤 | 预期 |
|---|---|---|---|---|
| TC-OWNER-001 | P0 | 所有者变更空间 | 个人空间文件改为部门空间（选研发部） | 变更成功，研发部成员可见，个人空间不再显示 |
| TC-OWNER-002 | P0 | 部门间移交 | 研发部空间文件移交至财务部 | 研发部不可见，财务部可见，`dept_id` 更新 |
| TC-OWNER-003 | P1 | 移交归属人 | 文件移交归属人为同事 B | 列表归属人显示 B，B 获得操作权 |
| TC-OWNER-004 | P0 | 越权变更被拒 | 普通用户修改他人文件归属 | 403 且记录审计 |
| TC-OWNER-005 | P1 | DEPT_ADMIN 变更本部门 | 部门管理员调整本部门文件归属 | 成功 |
| TC-OWNER-006 | P1 | DEPT_ADMIN 越部门被拒 | 部门管理员变更其他部门文件 | 403 |
| TC-OWNER-007 | P1 | 目标部门不存在 | 归属到已删除部门 | 拒绝并提示 |
| TC-OWNER-008 | P1 | 可见性即时生效 | 变更后立即以目标部门身份查询 | 立即可见（无需刷新缓存） |
| TC-OWNER-009 | P1 | 物理文件不移动 | 变更前后对比存储路径 | `storage_path` 不变 |
| TC-OWNER-010 | P0 | 审计留痕 | 任意归属变更后查审计 | 记录操作人/原归属/新归属/时间 |

---

## 8. 磁盘存储与监控（F6 / U29~U32）

| ID | 优先级 | 用例 | 步骤 | 预期 |
|---|---|---|---|---|
| TC-ST-001 | P0 | 重启文件不丢 | 上传文件后重启服务 | 文件仍可访问 |
| TC-ST-002 | P0 | 目录自动创建 | 删除存储目录后启动 | 启动时自动重建 files/upload/del/tmp |
| TC-ST-003 | P0 | 存储用量统计 | 查询存储监控 | 总容量/已用/剩余/使用率数值正确 |
| TC-ST-004 | P1 | 85% 告警 | 模拟使用率超 85% | 输出告警日志 |
| TC-ST-005 | P1 | 按日期分目录 | 上传文件检查物理路径 | 落盘于 `files/{YYYY-MM-DD}/{uuid}.{ext}` |
| TC-ST-006 | P2 | 备份脚本 | 执行备份并模拟恢复 | 备份产物齐全，恢复后文件与库一致 |
| TC-ST-007 | P1 | 告警落库可查（G9） | 模拟使用率超 85% | 输出结构化告警日志且 `operation_log` 出现 `STORAGE_ALERT` 记录 |

---

## 9. 审计日志（F7 / U33~U35）

| ID | 优先级 | 用例 | 步骤 | 预期 |
|---|---|---|---|---|
| TC-AUDIT-001 | P0 | 登录留痕 | 登录成功/失败 | 记录操作人/IP/UA/类型/结果/时间 |
| TC-AUDIT-002 | P0 | 上传留痕 | 上传合并完成 | 记录 UPLOAD 与目标文件 |
| TC-AUDIT-003 | P0 | 下载留痕 | 下载文件 | 记录 DOWNLOAD 与目标文件 |
| TC-AUDIT-004 | P0 | 删除/恢复留痕 | 软删除与恢复 | 分别记录 DELETE / RESTORE |
| TC-AUDIT-005 | P1 | 归属变更留痕 | 执行归属变更 | 记录原/新归属（JSON detail） |
| TC-AUDIT-006 | P0 | 日志分页筛选 | 按用户/时间/类型筛选 | 结果准确，真分页 |
| TC-AUDIT-007 | P1 | 越权操作留痕 | 触发一次越权访问 | 记录失败事件（success=0） |
| TC-AUDIT-008 | P1 | 权限控制 | USER 调用日志接口 | 403 |
| TC-AUDIT-009 | P1 | 日志归档清理（G10） | 构造超 12 个月的操作记录并触发归档调度 | 超期记录导出至 archive CSV 并从库中删除 |

---

## 10. 数据权限矩阵（PRD 2.2 / 7）

> 数据集：员工 A（研发部）、员工 B（财务部）、部门管理员 D1（研发部）、系统管理员 AD。

| ID | 优先级 | 场景 | A(USER) | B(USER) | D1(DEPT_ADMIN) | AD(ADMIN) |
|---|---|---|---|---|---|---|
| TC-PERM-001 | P0 | A 的个人空间文件 | ✔ | ✘(403) | ✘ | ✔ |
| TC-PERM-002 | P0 | 研发部空间文件 | ✔ | ✘(403) | ✔ | ✔ |
| TC-PERM-003 | P0 | 研发部下设子部门空间文件 | ✘(403) | ✘ | ✔ | ✔ |
| TC-PERM-004 | P0 | 财务部空间文件 | ✘(403) | ✔ | ✘ | ✔ |
| TC-PERM-005 | P0 | 公共空间文件 | ✔ | ✔ | ✔ | ✔ |
| TC-PERM-006 | P0 | A 对他人个人空间文件重命名/删除 | ✘(403) | ✘ | ✘（非本部门文件） | ✔ |
| TC-PERM-007 | P1 | 研发部文件归属变更（文件非 A 所有） | ✘(403) | ✘ | ✔ | ✔ |
| TC-PERM-008 | P1 | 财务部空间文件，A 越权下载 | ✘(403) | ✔ | ✘ | ✔ |

> 说明：TC-PERM-003 中文件归属"研发部下设子部门"，子部门空间仅子部门成员（及其下级）可见，故研发部成员 A 不可见；D1 管辖研发部及全部下级，故可见。

**执行方式**：以上矩阵以"同一请求由不同角色用户发起"断言返回码与列表内容，必须全绿方可在 Sprint 3 关闭 F5/F9。

---

## 11. 前端交互测试（Jest + RTL）

| ID | 优先级 | 用例 | 预期 |
|---|---|---|---|
| TC-UI-001 | P0 | 登录页渲染验证码并校验 | 验证码图片加载、错误提示出现 |
| TC-UI-002 | P0 | 登录成功跳转 / 失败提示 | 跳转文件列表 / 错误提示正确 |
| TC-UI-003 | P1 | 按角色渲染菜单 | USER 无"用户管理"菜单，ADMIN 有 |
| TC-UI-004 | P0 | 上传弹窗空间选择与进度 | 选择空间必填、进度展示、成功后刷新列表 |
| TC-UI-005 | P1 | 断点续传交互 | 暂停/恢复按钮状态正确 |
| TC-UI-006 | P0 | 列表分页与筛选 | 翻页触发接口、筛选参数正确 |
| TC-UI-007 | P1 | 归属变更弹窗 | 部门必选校验、确认后列表刷新 |
| TC-UI-008 | P1 | 错误码友好提示 | 组件 507/416 等错误显示中文提示 |

## 12. E2E 主流程（Playwright）

| ID | 用例 | 路径 |
|---|---|---|
| TC-E2E-001 | 全链路：登录→上传→搜索→下载→归属→删除→回收站恢复→审计 | P0 |
| TC-E2E-002 | 大文件断点续传 | P0 |
| TC-E2E-003 | 越权访问拦截（列表/下载/归属） | P0 |

---

## 13. 性能测试（PRD 第 6 章）

| ID | 指标 | 目标 | 方法 |
|---|---|---|---|
| TC-PERF-001 | 列表接口响应 | <500ms | 万级数据压测 |
| TC-PERF-002 | 真分页翻页 | 万级数据响应正常 | 多页循环 |
| TC-PERF-003 | 并发上传 | 50 文件并发无阻塞 | 并发脚本 |
| TC-PERF-004 | 文件搜索 | 千级 <3s | 关键字压测 |
| TC-PERF-005 | 缓存命中率 | ≥85% | 监控统计 |

---

## 14. 部署与运维（G4 / G8 / G11 部署侧）

| ID | 优先级 | 用例 | 步骤 | 预期 |
|---|---|---|---|---|
| TC-DEP-001 | P0 | HTTPS 生效（G4） | 浏览器访问 `http://host` | 302 重定向至 `https://host`；443 证书有效无告警 |
| TC-DEP-002 | P1 | 中间件认证（G8） | 无密码直连 Redis/MySQL 端口 | 认证失败被拒；应用正常启动且使用加密连接 |
| TC-DEP-003 | P1 | RSA 密钥持久化（G11） | 重启后端容器后再次登录 | 登录正常（密钥卷沿用）；数据库密码仍可解密验证 |
| TC-DEP-004 | P1 | 证书/密钥卷缺失 fail-fast | 删除证书或 RSA 密钥卷后启动 | nginx 或后端启动失败并给出明确错误，不静默降级 |

---

## 15. 功能迭代补登记（原文档缺失 → 已补齐自动化）

> 以下新代码/新功能此前未登记 TC，现已补齐代码级用例，登记于此保持文档与代码一致。

### 15.1 批量文件操作（commit 5f2d942 批量归属与批量删除）

| ID | 优先级 | 用例 | 预期 | 自动化 |
|---|---|---|---|---|
| TC-BATCH-001 | P1 | 批量删除 | 全部进入回收站，列表移除 | `FileUploadFlowTest.batchDelete_movesToRecycle` |
| TC-BATCH-002 | P1 | 批量恢复 | 全部回到原空间列表 | `FileUploadFlowTest.batchRestore_restoresAllSelected` |
| TC-BATCH-003 | P1 | 批量物理清除 | 仅 ADMIN 可执行；非管理员 403 | `FileUploadFlowTest.batchPurge_requiresAdmin` |
| TC-BATCH-004 | P1 | 批量归属变更 | 全部按目标空间/部门生效 | `FileUploadFlowTest.batchOwnerChange_appliesToAll` |
| TC-BATCH-005 | P1 | 批量中逐条鉴权 | 无权限项跳过并计数失败 | `FileService.batchXxx`（逐条 try/ignore + success/fail 计数） |
| TC-BATCH-006 | P2 | 恢复权限 | 非归属人/非管理员/非部门管理员恢复部门空间文件被拒 | `FileUploadFlowTest.restore_requiresOperatePermission` |

### 15.2 目录同步扫描（SYNC 设计稿，评审通过）

| ID | 优先级 | 用例 | 预期 | 自动化 |
|---|---|---|---|---|
| TC-SYNC-001 | P0 | 导入目录新文件入库 | 迁入 files/，归属 admin + PUBLIC + READY | `SyncScannerServiceTest.importNewFile_createsRecord_adminPublicReady` |
| TC-SYNC-002 | P0 | MD5 去重 | 重复内容跳过，源文件保留 | `import_skipsDuplicateByMd5` |
| TC-SYNC-003 | P0 | 磁盘文件缺失 | 标记 MISSING，不删库记录 | `missingFile_marksMissing` |
| TC-SYNC-004 | P0 | touch 不误报 | 仅 mtime 变化保持 READY 并刷基线 | `touchOnly_keepsReady` |
| TC-SYNC-005 | P0 | 内容替换 | 标记 UPDATED | `contentReplaced_marksUpdated` |
| TC-SYNC-006 | P0 | MISSING 复活（同内容） | 复位 READY | `missingFileReappears_restoresReady` |
| TC-SYNC-007 | P0 | 下载拦截 | MISSING 阻断下载；UPDATED 放行并刷新复位 | `download_missing_blocked` / `download_updated_refreshesToReady` |
| TC-SYNC-008 | P0 | 防重入 | 扫描进行中本次触发跳过 | `scan_skipsWhenAlreadyRunning` |
| TC-SYNC-009 | P1 | 历史数据无基线 | 仅建立基线不误标 | `legacyRecord_withoutDiskBaseline_establishesBaselineWithoutFlag` |
| TC-SYNC-010 | P1 | MISSING 复活（内容不同） | 标记 UPDATED 而非复位 | `missingFileReappears_withDifferentContent_marksUpdated` |
| TC-SYNC-011 | P1 | UPDATED 还原为原内容 | 复位 READY | `updatedFile_restoredToOriginalContent_resetsReady` |
| TC-SYNC-012 | P1 | 内容持续变更 | 保持 UPDATED | `contentReplacedAgain_keepsUpdatedUntilRestored` |
| TC-SYNC-013 | P1 | skip-recent 半写防护 | 窗口内文件不导入 | `import_skipsFileWithinSkipRecentWindow` |
| TC-SYNC-014 | P1 | 嵌套子目录导入 | 递归扫描并入库 | `import_recursiveNestedDirectories` |
| TC-SYNC-015 | P1 | sync.enabled=false | 定时任务跳过 | `scheduledScan_whenDisabled_skips` |

> 运行依赖：MySQL（@SpringBootTest，profile `test`）。

---

## 16. 自动化回归覆盖映射（截至 2026-09-02）

> 目的：让每条重要 TC 都能反查到落地代码与运行条件；纯单元/安全层无需 DB，集成/矩阵需 MySQL。

| 自动化测试类 | 覆盖 TC / 行为 | 运行条件 |
|---|---|---|
| `AuthServiceTest` | LOGIN-002/003/004(部分)/006/007/008/010/013/015、G6 会话映射续期、改密/登出 | 纯单元 |
| `util/CaptchaUtilTest` | LOGIN-001/003（验证码生成） | 纯单元 |
| `util/PathUtilTest`、`RsaKeyHolderTest`、`RedisTtlPolicyTest` | 路径/扩展名、DEP-003、TTL 抖动 | 纯单元 |
| `security/TokenAuthFilterTest` | LOGIN-016/019/020/021/022/023（过滤器语义） | 纯单元 |
| `security/EndpointSecurityMatrixTest` | TSDD 5.1 端点矩阵、LOGIN-019/020/022/023（HTTP 状态） | 纯单元（@WebMvcTest） |
| `service/DeptServiceTest` | ORG-004/005/006（删除约束）、数据权限集合 V、部门树、缓存失效 | 纯单元 |
| `service/UserServiceTest` | ORG-007~016（含 G12 删除前移交）、分页范围 | 纯单元 |
| `service/DataPermissionMatrixTest` | PERM-001~008 全矩阵、OWNER-001~009(越权部分)、上传部门必选、归属审计 | MySQL |
| `controller/FileControllerDownloadTest` | DL-003/004/005、Range 200/206/416、ZIP 头 | 纯单元 |
| `config/StorageCleanupSchedulerTest` | FILE-011/014（到期清理 / UPLOADING 孤儿，G2） | 纯单元 |
| `config/DataInitializerTest` | ORG-014、G1 Seed | 纯单元 |
| `service/StorageServiceTest` | ST-002/003/007 降级路径、目录初始化 | 纯单元 |
| `service/LogServiceTest` | AUDIT-001/002/003/009（record/recordLogin/归档 CSV） | 纯单元 |
| `service/SyncScannerServiceTest` | SYNC-001~015、下载拦截与刷新 | MySQL |
| `service/FileUploadFlowTest` | UP-001/003(部分)/006/010/011、FILE-009~012(部分)、BATCH-001~006 | MySQL |
| 前端 `pages/Login.test.tsx`、`api/client.test.ts` | UI-001/002、401/403/组件错误码映射、会话失效跳转 | Vitest |
| 前端 `pages/ChangePassword.test.tsx` | 改密页必填/一致性/最小长度/成功回登录/失败提示 | Vitest |
| 前端 `components/MainLayout.test.tsx` | UI-003 角色菜单（ADMIN/USER/DEPT_ADMIN） | Vitest |
| 前端 `components/UploadModal.test.tsx` | UI-004、UP-004 部门空间必选拦截 | Vitest |
| 前端 `pages/FileList.test.tsx` | UI-006/007：列表/状态角标/搜索参数/重命名/归属弹窗 | Vitest |
| 前端 `pages/Recycle.test.tsx` | FILE-009~012：恢复/物理清除(角色)/批量恢复 | Vitest |
| 前端 `pages/UserPage.test.tsx` | ORG-007~013：行渲染/启停用/重置密码/删除确认/弹窗 | Vitest |
| 前端 `pages/DeptPage.test.tsx` | ORG-001~005：树展开/编辑/新增根与子部门 | Vitest |
| 前端 `pages/StoragePage.test.tsx` | ST-003/004：统计卡片与 85% 告警 | Vitest |
| 前端 `pages/LogPage.test.tsx` | AUDIT-006：日志行渲染与操作人筛选参数 | Vitest |
| 前端 `utils/*`（file/dept/uploadTask） | 容量格式、部门扁平化、分片/断点/合并/超时 | Vitest |

---

## 17. 已知实现缺口与待办（影响"全绿"的验收项）

| # | 项 | 现状 | 建议 |
|---|---|---|---|
| X1 | 越权操作审计留痕 | 403 直接抛 `BizException.forbidden`，无 `success=0` 审计写入（TSDD 9.4 `AuditAspect` 未实现） | 补实现后启用 TC-AUDIT-007 / OWNER-004 审计断言 |
| X2 | 停用账号登录口径 | `AuthService` 将 status=0 并入"用户名或密码错误"并累加失败计数，与 PRD F1「账号已停用，请联系管理员」不符 | 产品定口径后固化测试 |
| X3 | 回收站恢复校验 | `restore` 未校验原部门/存储路径有效性（TSDD 8.2/G7 要求） | 补实现 + TC-FILE-015 物理路径断言 |
| X4 | 页面组件覆盖 | FileList/Recycle/User/Dept/Log/Storage/ChangePassword/MainLayout/UploadModal/Login 已覆盖；批量归属提交、回收站/用户批量勾选等深层弹窗交互待补 | 追加覆盖（优先级低于 X1~X3） |
| X5 | E2E（Playwright） | 骨架已建（`frontend/tests/e2e`），验证码阻断全自动登录 | 测试环境提供验证码绕过/种子账号后跑 TC-E2E-001~003 |
| X6 | 性能与 CI | 无压测脚本；无 `.github` CI 与 JaCoCo/Jest coverage 门禁 | 建 CI（对应 PLAN PF-005）+ 性能脚本后启用 TC-PERF |
| X7 | 集成测试运行条件 | `@SpringBootTest` 需 MySQL `pathfinder_test` 实例 | 建议 Testcontainers / CI 服务化，本地 `mvn test` 前先就绪 MySQL |
