import { test, expect } from '@playwright/test';
import { ADMIN, INIT_PASSWORD, login, apiJson } from './helpers';

/**
 * TC-E2E-003 P0 越权访问拦截（TESTCASES §12）+ TC-LOGIN-019/020 强制改密 E2E 侧：
 *   ADMIN 新建 USER → 该用户首登强制改密 → 改密后：
 *   1) 菜单仅见 文件管理/回收站，无 用户管理/部门管理/审计日志/系统存储；
 *   2) 直接调用 ADMIN 接口（新增用户/审计日志/存储监控）返回 403；
 *   3) 自己的可见用户列表正常（数据权限：仅本部门成员）。
 */
test('USER 越权访问拦截与菜单收敛', async ({ browser }) => {
  test.setTimeout(180_000);

  const username = `e2e.user.${Date.now()}`;
  const password = 'E2e@12345';

  // --- ADMIN 上下文：新建 USER（归属根部门 组织）---
  const adminCtx = await browser.newContext();
  const adminPage = await adminCtx.newPage();
  await login(adminPage, ADMIN.username, ADMIN.password);
  await adminPage.waitForURL((u) => u.pathname === '/');

  const treeRes = await apiJson(adminPage, 'GET', '/api/dept/tree');
  expect(treeRes.status).toBe(200);
  const rootDeptId = treeRes.body.data[0].id;
  const created = await apiJson(adminPage, 'POST', '/api/user', {
    username,
    realName: 'E2E测试用户',
    deptId: rootDeptId,
    roleCode: 'USER',
    status: 1,
  });
  expect(created.status, `新增用户应成功：${JSON.stringify(created.body)}`).toBe(200);
  await adminCtx.close();

  // --- USER 上下文：首登强制改密（TC-LOGIN-019/020）---
  const userCtx = await browser.newContext();
  const page = await userCtx.newPage();

  await login(page, username, INIT_PASSWORD);
  await page.waitForURL((u) => u.pathname === '/changePassword');
  await expect(page.getByText('首次登录或密码被重置后，需先修改初始密码')).toBeVisible();

  const pwdInputs = page.locator('input[type=password]');
  await pwdInputs.nth(0).fill(INIT_PASSWORD);
  await pwdInputs.nth(1).fill(password);
  await pwdInputs.nth(2).fill(password);
  await page.getByRole('button', { name: '确认修改' }).click();
  await expect(page.getByText('密码修改成功，请重新登录')).toBeVisible();
  await page.waitForURL((u) => u.pathname === '/login');

  // 重新登录：改密后直达文件管理
  await login(page, username, password);
  await page.waitForURL((u) => u.pathname === '/');
  await expect(page.getByText(new RegExp(`^E2E测试用户（USER）$`))).toBeVisible();

  // 菜单收敛（TC-UI-003 / TC-ORG-012 前端）
  const menu = page.locator('.ant-menu');
  await expect(menu.getByText('文件管理')).toBeVisible();
  await expect(menu.getByText('回收站')).toBeVisible();
  for (const forbidden of ['用户管理', '部门管理', '审计日志', '系统存储']) {
    await expect(menu.getByText(forbidden)).toHaveCount(0);
  }

  // ADMIN 专属写接口 → 403
  const postUser = await apiJson(page, 'POST', '/api/user', {
    username: `x.${Date.now()}`,
    realName: 'x',
    deptId: rootDeptId,
    roleCode: 'USER',
  });
  expect(postUser.status).toBe(403);

  // ADMIN 专属读接口 → 403
  const logPage = await apiJson(page, 'GET', '/api/log/page?pageNum=1&pageSize=20');
  expect(logPage.status).toBe(403);
  const storageInfo = await apiJson(page, 'GET', '/api/storage/info');
  expect(storageInfo.status).toBe(403);

  // 数据权限：USER 仅能列出本部门成员，且可见自己（TC-PERM 数据可见侧）
  const userPageRes = await apiJson(page, 'GET', '/api/user/page?pageNum=1&pageSize=100');
  expect(userPageRes.status).toBe(200);
  expect(userPageRes.body.data.list.some((u: { username: string }) => u.username === username)).toBe(true);

  await userCtx.close();
});
