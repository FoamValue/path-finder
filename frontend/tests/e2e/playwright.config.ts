import { defineConfig } from '@playwright/test';

/**
 * PathFinder E2E 骨架（对应 PLAN PF-405 / TESTCASES TC-E2E-001~003）。
 *
 * 运行前置：
 *   1. 启动后端（mvn spring-boot:run）与前端 dev（npm run dev，:8000）；
 *   2. npx playwright install chromium   # 首次安装浏览器；
 *   3. npm run test:e2e 或 npm run test:e2e:headed。
 *
 * 说明：登录含图片验证码，完整主流程需测试环境提供验证码绕过 / 种子账号，
 * 可配置 E2E_CAPTCHA=off 之类后端测试开关后放开 tests/full-flow.spec.ts。
 */
export default defineConfig({
  testDir: './',
  testMatch: /.*\.spec\.ts/,
  timeout: 30_000,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:8000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
});
