import { test } from '@playwright/test';

/**
 * 关键全流程（TESTCASES TC-E2E-001~003），默认 SKIP。
 *
 * 登录含图片验证码，无法在脚本中自动通过；请在测试环境提供验证码绕过
 * （例如测试 profile 关闭 CaptchaFilter / 固定验证码）后，
 * 将下方 test.skip 移除并补全断言，即可作为发布回归门禁：
 *   TC-E2E-001 登录→上传→搜索→下载→归属→删除→回收站恢复→审计
 *   TC-E2E-002 大文件断点续传
 *   TC-E2E-003 越权访问拦截（列表/下载/归属）
 */
test.skip('全流程：登录→上传→搜索→下载→归属→删除→回收站恢复→审计', async ({ page }) => {
  await page.goto('/login');
  // TODO(测试环境就绪后)：验证码绕过/种子账号登录
  // await page.getByPlaceholder('用户名').fill('admin');
  // await page.getByPlaceholder('密码').fill('Init@123');
  // await page.getByPlaceholder('验证码').fill('xxxx');
  // 随后补充上传/搜索/下载/归属/删除/回收站恢复/审计断言
});
