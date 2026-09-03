import { test, expect } from '@playwright/test';
import {
  ADMIN,
  login,
  apiJson,
  downloadFile,
  rowOf,
  charSpaced,
  visibleDialog,
  okInDialog,
} from './helpers';

/**
 * TC-E2E-001 P0 全链路（TESTCASES §12）：
 * 登录 → 上传 → 搜索 → 下载(内容校验) → 归属变更(个人→公共) → 删除 → 回收站恢复 → 审计留痕。
 * 运行环境：Docker E2E 栈（验证码绕过 + bootstrap 种子账号），见 scripts/run-e2e-docker.sh。
 */
test('全流程：登录→上传→搜索→下载→归属→删除→回收站恢复→审计', async ({ page }) => {
  test.setTimeout(180_000);

  const stem = `e2e-flow-${Date.now()}`;
  const fileName = `${stem}.txt`;
  const content = `PathFinder E2E 全流程验证内容 ${Date.now()}`;

  // 1) 登录（bootstrap admin，跳过强制改密）
  await login(page, ADMIN.username, ADMIN.password);
  await page.waitForURL((u) => u.pathname === '/');
  await expect(page.getByText('系统管理员（ADMIN）')).toBeVisible();

  // 2) 上传单个文本文件到个人空间
  await page.getByRole('button', { name: '上传文件' }).click();
  const uploadDialog = page.locator('.ant-modal-content:visible').filter({ hasText: '上传文件' });
  await expect(uploadDialog).toBeVisible();
  await uploadDialog
    .locator('input[type=file]')
    .setInputFiles({ name: fileName, mimeType: 'text/plain', buffer: Buffer.from(content, 'utf-8') });
  await expect(page.getByText('上传完成')).toBeVisible({ timeout: 90_000 });
  // AntD 文案可能含空格（“关 闭”），用归一化后的文本匹配
  await page
    .locator('.ant-modal-content:visible')
    .getByRole('button', { name: /关\s*闭/ })
    .click();

  // 3) 列表出现该文件（默认个人空间）
  const row = rowOf(page, fileName);
  await expect(row).toBeVisible({ timeout: 20_000 });

  // 4) 关键字搜索命中且结果唯一
  await page.getByPlaceholder('按文件名搜索').fill(stem);
  await page.keyboard.press('Enter');
  await expect(page.getByText(/共 1 个文件/)).toBeVisible({ timeout: 15_000 });

  // 5) 下载并校验内容完整（TC-DL-001）
  const pageRes = await apiJson(page, 'GET', `/api/file/page?pageSize=50&keyword=${encodeURIComponent(fileName)}`);
  expect(pageRes.status).toBe(200);
  const file = pageRes.body.data.list.find((f: { originalName: string }) => f.originalName === fileName);
  expect(file, '搜索接口应返回已上传文件').toBeTruthy();
  const dl = await downloadFile(page, file.id);
  expect(dl.status).toBe(200);
  expect(dl.text).toContain('PathFinder E2E');

  // 6) 归属变更 个人空间 → 公共空间（TC-OWNER-001 变体）
  await rowOf(page, fileName).getByRole('button', { name: '归属' }).click();
  const ownerDialog = visibleDialog(page).filter({ hasText: '修改归属' });
  await ownerDialog.locator('.ant-select').first().click();
  await page.locator('.ant-select-dropdown:visible').getByText('公共空间').click();
  await okInDialog(page, ownerDialog);
  await expect(page.getByText('归属变更成功')).toBeVisible();
  await expect(rowOf(page, fileName)).toContainText('公共');

  // 7) 软删除（TC-FILE-009）
  await rowOf(page, fileName).locator('button.ant-btn-dangerous').click();
  const popover = page.locator('.ant-popover:visible');
  await popover.getByRole('button', { name: /^(确\s*定|OK)$/ }).click();
  await expect(page.getByText('已删除')).toBeVisible();

  // 8) 回收站出现并可恢复（TC-FILE-010）
  await page.locator('.ant-menu').getByText('回收站').click();
  await page.waitForURL((u) => u.pathname === '/recycle');
  await expect(rowOf(page, fileName)).toBeVisible({ timeout: 15_000 });
  await rowOf(page, fileName).getByRole('button', { name: charSpaced('恢复') }).click();
  await expect(page.getByText('已恢复')).toBeVisible();
  await expect(rowOf(page, fileName)).toBeHidden({ timeout: 15_000 });

  // 回到文件管理，恢复后重新可见
  await page.locator('.ant-menu').getByText('文件管理').click();
  await page.waitForURL((u) => u.pathname === '/');
  await expect(rowOf(page, fileName)).toBeVisible({ timeout: 15_000 });

  // 9) 审计留痕覆盖登录/上传/下载/归属/删除/恢复（TC-AUDIT-001~005）
  await page.locator('.ant-menu').getByText('审计日志').click();
  await page.waitForURL((u) => u.pathname === '/log');
  for (const type of ['LOGIN', 'UPLOAD', 'OWNER_CHANGE', 'DOWNLOAD', 'DELETE', 'RESTORE']) {
    await expect(page.getByText(type, { exact: true }).first()).toBeVisible({ timeout: 15_000 });
  }
});
