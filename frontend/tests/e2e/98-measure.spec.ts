import { test, type Page } from '@playwright/test';

/**
 * 控件盒模型测量复核：确认 AntD Input 实际渲染高度与可疑的 22px 来源。
 * 运行方式：npx playwright test --config tests/e2e/playwright.config.ts 98-measure.spec.ts
 */
async function measure(page: Page, selector: string) {
  return page.evaluate((sel) => {
    const el = document.querySelector(sel);
    if (!el) return { missing: true, sel };
    const c = getComputedStyle(el);
    return {
      sel,
      tag: el.tagName,
      cls: el.className,
      offsetHeight: (el as HTMLElement).offsetHeight,
      clientHeight: (el as HTMLElement).clientHeight,
      styleH: c.height,
      lineH: c.lineHeight,
      padT: c.paddingTop,
      padB: c.paddingBottom,
      borderBottom: c.borderBottomWidth,
      boxSizing: c.boxSizing,
      fontSize: c.fontSize,
    };
  }, selector);
}

test('控件盒模型测量', async ({ page }) => {
  test.setTimeout(120_000);
  await page.goto('/login');
  await page.locator('.ant-form').waitFor({ state: 'visible', timeout: 20_000 });

  const res: Record<string, unknown> = {};
  res.loginUserMsgs = await measure(page, 'input[placeholder=用户名]');
  res.loginUserWrap = await measure(page, '.ant-input-affix-wrapper');
  res.loginSubmit = await measure(page, 'button[type=submit]');

  // 登录后测文件页搜索框
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('Init@123');
  await page.getByPlaceholder('验证码').fill('0000');
  await page.locator('button[type=submit]').click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20_000 });
  await page.locator('.ant-table').first().waitFor({ state: 'visible', timeout: 20_000 });

  res.table = await page.evaluate(() => {
    const t = document.querySelector('.ant-table');
    const body = document.querySelector('.ant-table-body');
    const ths = Array.from(document.querySelectorAll('.ant-table-thead th')).map((th) => ({
      text: (th.textContent || '').trim(),
      w: Math.round((th as HTMLElement).getBoundingClientRect().width),
    }));
    return {
      viewportW: window.innerWidth,
      tableScrollW: t ? t.scrollWidth : null,
      tableClientW: t ? t.clientWidth : null,
      bodyScrollW: body ? body.scrollWidth : null,
      bodyClientW: body ? body.clientWidth : null,
      colCount: ths.length,
      cols: ths,
      hasTableHScroll: t ? t.scrollWidth > t.clientWidth : null,
    };
  });

  console.log('=== MEASURE ===');
  console.log(JSON.stringify(res, null, 2));
});