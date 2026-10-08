# 安全策略 / Security Policy

## 支持版本 / Supported Versions

| 版本 / Version | 支持状态 / Support      |
| -------------- | ----------------------- |
| 1.0.x          | ✅ 积极维护（Active）   |
| 0.x (rc)       | ❌ 不再维护（EOL）      |

## 报告漏洞 / Reporting a Vulnerability

请**不要**在公开 Issue / PR / 论坛中披露安全漏洞。

请将漏洞报告以**私密方式**发送到维护者邮箱。若发现严重高危漏洞，建议同时将弱点同步给依赖组件维护方。

报告时请尽量提供：

- 漏洞类型（如 XSS / SSRF / 越权 / 注入 / 会话劫持等）与触发场景
- 复现步骤或最小复现用例
- 影响范围与潜在后果
- （可选）建议的修复方案

我们承诺在收到报告后的 **48 小时**内回复确认，并在修复完成、发布补丁**前**对漏洞细节保密。修复版本发布后，将在 CHANGELOG 中登记致谢。

## 安全设计要点 / Security Highlights

- 登录会话采用 HttpOnly Cookie 管理，避免前端 JS 读取令牌。
- 上传/下载组件 `cn.chenxinjie:upload-file` 启用 `verify-checksum` 与 `require-checksum`（MD5 校验兜底）。
- 在线预览为**本地只读渲染**：MD/TXT→marked、PDF→pdf.js、DOCX→mammoth、XLSX→SheetJS，转出的 HTML 均经 DOMPurify 清洗防 XSS；仅预览不可修改。
- 越权访问统一返回并落地 `FORBIDDEN` 审计留痕；500 错误对外脱敏。
- 生产建议通过反向代理（nginx）启用 TLS；默认禁止弱密码、失败多次锁定账号。

## 依赖声明 / Dependency Notice

`server` 依赖 `cn.chenxinjie:upload-file-*` 组件（1.0.0），其自身安全修复请关注组件仓库的 Release 与安全公告。