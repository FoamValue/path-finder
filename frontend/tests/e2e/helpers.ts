import { expect, type Page } from '@playwright/test';

/**
 * E2E 公共辅助：确定性种子账号登录、带会话的 API 调用、AntD 弹窗交互。
 * 依赖 Docker E2E 栈：验证码绕过 + bootstrap 账号（见 docker-compose.e2e.yml）。
 *
 * M2 会话迁移至 HttpOnly Cookie 后，前端 JS 不再读取 token，
 * API 调用统一使用 Playwright 的 page.request（自动携带页面上下文 Cookie）。
 */

export const E2E_ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD || 'E2e@12345';
export const ADMIN = { username: 'admin', password: E2E_ADMIN_PASSWORD };
export const INIT_PASSWORD = 'Init@123';
export const CAPTCHA_TEXT = '0000';

export function uniqueFile(base: string, ext = 'txt'): string {
  return `${base}-${Date.now()}.${ext}`;
}

/** 以表单登录（验证码已绕过），等待离开 /login（成功进入 / 或 /changePassword）。 */
export async function login(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill(username);
  await page.getByPlaceholder('密码').fill(password);
  await page.getByPlaceholder('验证码').fill(CAPTCHA_TEXT);
  await page.locator('button[type=submit]').click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20_000 });
}

/** 把按钮中文文案转为忽略中间空格的正则（AntD 两个汉字按钮会自动插空格，如 “恢复”→“恢 复”）。 */
export function charSpaced(text: string): RegExp {
  const esc = text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(esc.split('').join('\\s*'));
}

/**
 * 判断当前页面是否已有登录会话（Cookie 模式下 JS 读不到 HttpOnly Cookie，
 * 改用 Playwright request 调 /api/auth/me 探测；返回 true 表示会话有效）。
 */
export async function hasSession(page: Page): Promise<boolean> {
  const resp = await page.request.get('/api/auth/me');
  return resp.status() === 200;
}

/**
 * 以页面会话调后端 JSON API（自动携带 Cookie，等同页面自身请求），返回 {status, body}。
 * 使用 Playwright 原生 request API，比 page.evaluate(fetch) 更稳定可靠。
 * 内置 15s 超时，避免证书/网络问题导致整测试挂起。
 */
export async function apiJson(
  page: Page,
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  url: string,
  body?: unknown,
): Promise<{ status: number; body: any }> {
  const options: Record<string, unknown> = { timeout: 15_000 };
  if (body !== undefined) {
    options.data = body;
  }
  const resp = await page.request[method.toLowerCase() as 'get' | 'post' | 'put' | 'delete'](url, options);
  let parsed: any = null;
  try {
    parsed = await resp.json();
  } catch {
    /* 非 JSON 响应（如 403 HTML 错误页等）*/
  }
  return { status: resp.status(), body: parsed };
}

/**
 * 获取文件下载内容（走 downloadToken + /api/file/download 两段式，凭 Cookie 会话）。
 */
export async function downloadFile(page: Page, fileId: number): Promise<{ status: number; text: string }> {
  const r1 = await page.request.get(`/api/file/${fileId}/downloadToken`);
  const j1 = await r1.json();
  const r2 = await page.request.get(`/api/file/download/${j1.data.token}`);
  const buf = await r2.body();
  return { status: r2.status(), text: buf.toString('utf-8') };
}

/** 按文件名在列表/回收站页定位表格行。 */
export function rowOf(page: Page, fileName: string) {
  return page.getByRole('row', { name: new RegExp(fileName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) });
}

/** 点击行内某按钮（AntD 行操作）。 */
export async function clickRowAction(page: Page, fileName: string, action: string | RegExp): Promise<void> {
  const row = rowOf(page, fileName);
  await expect(row).toBeVisible();
  const btn = typeof action === 'string' ? row.getByRole('button', { name: action }) : row.getByRole('button', { name: action });
  await expect(btn).toBeVisible();
  await btn.click();
}

/** 当前可见的 AntD 对话框（含显式标题文本的定位更稳，调用方可再叠加 filter）。 */
export function visibleDialog(page: Page) {
  return page.locator('.ant-modal-content:visible').last();
}

/** 在可见对话框中点击默认确认按钮（AntD 文案可能为 OK 或 确 定，兼容任意 locale/空格）。 */
export async function okInDialog(page: Page, dialog = visibleDialog(page)): Promise<void> {
  await dialog.getByRole('button', { name: /^(确\s*定|OK)$/ }).click();
}
