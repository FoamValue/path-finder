import { test, expect } from '@playwright/test';

/**
 * 冒烟：登录页就绪（验证码渲染 / 输入元素 / 可提交）。
 * 不依赖登录验证码通过，可在任意正常部署上运行（TC-LOGIN-001 前端侧）。
 */
test.describe('登录页冒烟', () => {
  test('渲染验证码图片与表单并可发起登录', async ({ page }) => {
    await page.goto('/login');

    const username = page.getByPlaceholder('用户名');
    const password = page.getByPlaceholder('密码');
    const captcha = page.getByPlaceholder('验证码');
    await expect(username).toBeVisible();
    await expect(password).toBeVisible();
    await expect(captcha).toBeVisible();

    // 验证码图片由 /api/captcha 异步加载，点击可刷新（TC-LOGIN-001）
    const img = page.locator('img[alt="验证码"]');
    await expect(img).toBeVisible({ timeout: 10_000 });
    const src = await img.getAttribute('src');
    expect(src).toContain('base64');

    // 表单可提交（登 录 按钮存在且可用）
    const submit = page.locator('button[type=submit]');
    await expect(submit).toBeEnabled();
    await expect(page.getByText('文件管理系统 · 寻找系统路径')).toBeVisible();
  });
});
