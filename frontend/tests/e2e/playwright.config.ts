import { defineConfig } from '@playwright/test';

/**
 * PathFinder E2E（对应 PLAN PF-405 / TESTCASES TC-E2E-001~003）。
 *
 * 运行前置：
 *   1. 启动本地 Docker E2E 栈（见 scripts/run-e2e-docker.sh）：
 *      server 连接 pathfinder_test 库 + CAPTCHA_ENABLED=false 验证码绕过
 *      + ADMIN_BOOTSTRAP_PASSWORD 确定性种子账号，nginx 暴露 80/443；
 *   2. npm run test:e2e            # 无头（本机 Chrome）
 *   3. npm run test:e2e:headed     # 有头调试
 *
 * 说明：证书为本地自签（certs/），故开启 ignoreHTTPSErrors；浏览器使用本机
 * Google Chrome（channel: 'chrome'）。默认 baseURL https://localhost，本地
 * dev（:8000 + 直连 8080）可通过 E2E_BASE_URL=http://localhost:8000 覆盖。
 */
export default defineConfig({
  testDir: './',
  testMatch: /.*\.spec\.ts/,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL || 'https://localhost',
    ignoreHTTPSErrors: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chrome', use: { browserName: 'chromium', channel: 'chrome' } }],
});
