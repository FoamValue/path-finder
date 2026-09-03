import { expect, type Page } from '@playwright/test';

/**
 * E2E 公共辅助：确定性种子账号登录、带 token 的 API 调用、AntD 弹窗交互。
 * 依赖 Docker E2E 栈：验证码绕过 + bootstrap 账号（见 docker-compose.e2e.yml）。
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

/** 当前页 localStorage 中的会话 token。 */
export function tokenOf(page: Page): Promise<string | null> {
  return page.evaluate(() => localStorage.getItem('pf_token'));
}

/** 以浏览器上下文直接调用后端 JSON API（附带当前 token），返回 {status, body}。 */
export async function apiJson(
  page: Page,
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  url: string,
  body?: unknown,
): Promise<{ status: number; body: any }> {
  const token = await tokenOf(page);
  return page.evaluate(
    async ({ method, url, body, token }) => {
      const resp = await fetch(url, {
        method,
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      let parsed: any = null;
      try {
        parsed = await resp.json();
      } catch {
        /* 非 JSON */
      }
      return { status: resp.status, body: parsed };
    },
    { method, url, body, token },
  );
}

/** 获取文件下载内容（走 downloadToken + /api/file/download 两段式）。 */
export async function downloadFile(page: Page, fileId: number): Promise<{ status: number; text: string }> {
  const token = await tokenOf(page);
  if (!token) throw new Error('downloadFile 需要已登录的会话 token');
  return page.evaluate(
    async ({ fileId, token }) => {
      const h = { Authorization: `Bearer ${token}` };
      const r1 = await fetch(`/api/file/${fileId}/downloadToken`, { headers: h });
      const j1 = await r1.json();
      const r2 = await fetch(`/api/file/download/${j1.data.token}`, { headers: h });
      const buf = await r2.arrayBuffer();
      return { status: r2.status, text: new TextDecoder().decode(buf) };
    },
    { fileId, token },
  );
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
