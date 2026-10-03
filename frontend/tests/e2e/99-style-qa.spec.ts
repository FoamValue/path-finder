import { test, expect, type Page } from '@playwright/test';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { login, INIT_PASSWORD, rowOf, visibleDialog } from './helpers';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(HERE, 'style-qa-shots');
fs.mkdirSync(OUT, { recursive: true });

type Finding = { name: string; ok: boolean; note?: unknown };
const findings: Finding[] = [];
const report = (name: string, ok: boolean, note?: unknown) => findings.push({ name, ok, note });

async function shot(page: Page, name: string) {
  await page.screenshot({ path: path.join(OUT, name) });
}

// ---------- 附件工具 ----------
const FIXES = path.join(HERE, 'fixtures', 'fixtures');
function fx(name: string) {
  const ext = path.extname(name).slice(1);
  const mime: Record<string, string> = { png: 'image/png', xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', md: 'text/markdown' };
  return { name, mimeType: mime[ext], buffer: fs.readFileSync(path.join(FIXES, name)) };
}

async function upload(page: Page, file: { name: string; mimeType: string; buffer: Buffer }) {
  await page.getByRole('button', { name: '上传文件' }).click();
  const dialog = page.locator('.ant-modal-content:visible').filter({ hasText: '上传文件' });
  await expect(dialog).toBeVisible();
  await dialog.locator('input[type=file]').setInputFiles(file);
  await expect(page.getByText('上传完成').last()).toBeVisible({ timeout: 90_000 });
  await page.locator('.ant-modal-content:visible').getByRole('button', { name: /关\s*闭/ }).click();
  await expect(dialog).toBeHidden();
}

async function openPreview(page: Page, fileName: string) {
  await rowOf(page, fileName).getByRole('button', { name: '预览' }).click();
  const dlg = visibleDialog(page).filter({ hasText: '预览' });
  await expect(dlg).toBeVisible({ timeout: 20_000 });
  return dlg;
}
async function closePreview(page: Page, dlg: ReturnType<typeof visibleDialog>) {
  await dlg.locator('.ant-modal-close').click();
  await expect(dlg).toBeHidden();
}

test('QA：页面控件样式巡检', async ({ page }) => {
  test.setTimeout(240_000);

  // ---------- 0) 登录页控件 ----------
  await page.goto('/login');
  await expect(page.locator('.ant-form')).toBeVisible({ timeout: 20_000 });
  const ls = await page.evaluate(`JSON.stringify((() => {
    // AntD v5 Input 外层 .ant-input-affix-wrapper 承载边框/内边距，内层裸 input 仅 22px 行盒；
    // 视觉高度须取外层控件盒（affix-wrapper / button）的 offsetHeight。
    const csq = (s) => { const el = document.querySelector(s); if(!el) return null; const box = el.closest('.ant-input-affix-wrapper') || el; const c = getComputedStyle(box); return { h: c.height, fs: c.fontSize, radius: c.borderRadius, box: box.className }; };
    return { user: csq('input[placeholder=用户名]'), pass: csq('input[type=password]'), captcha: csq('input[placeholder=验证码]'), submit: csq('button[type=submit]') };
  })())`);
  try {
    const s = JSON.parse(ls);
    const sh = parseFloat(s.submit?.h || '0'), uh = parseFloat(s.user?.h || '0');
    report('登录按钮高度 ~32px', Math.abs(sh - 32) < 5, { sh, uh });
    report('登录输入框/按钮同高', Math.abs(sh - uh) < 5, { sh, uh });
    report('登录页含验证码输入框', !!s.captcha);
  } catch { report('登录页样式解析异常', false, ls); }
  await shot(page, '01-login.png');

  // ---------- 1) 登录 + 上传三个有效 fixture ----------
  await login(page, 'admin', INIT_PASSWORD);
  await expect(page.locator('.ant-table').first()).toBeVisible({ timeout: 20_000 });
  const stem = `qa${Date.now()}`;
  const up = {
    img: { ...fx('demo.png'), name: `${stem}-img.png` },
    xls: { ...fx('demo.xlsx'), name: `${stem}-sheet.xlsx` },
    md: { ...fx('demo.md'), name: `${stem}-note.md` },
  };
  for (const f of Object.values(up)) await upload(page, f);
  await shot(page, '02-filelist.png');

  // ---------- 2) 文件页控件 ----------
  const list = await page.evaluate(`JSON.stringify((() => {
    const csq = (s) => { const el = document.querySelector(s); if(!el) return null; const c = getComputedStyle(el); return { w: c.width, h: c.height, fs: c.fontSize, bg: c.backgroundColor }; };
    return {
      upload: csq('.ant-btn-primary'),
      search: csq('.ant-input-affix-wrapper'),
      head: csq('.ant-table-thead th'),
      page: csq('.ant-pagination-item'),
      hasHScroll: (() => { const t = document.querySelector('.ant-table'); return t ? t.scrollWidth > t.clientWidth : null })()
    };
  })())`);
  try {
    const s = JSON.parse(list);
    const uh = parseFloat(s.upload?.h || '0'), ih = parseFloat(s.search?.h || '0');
    report('上传按钮高度 ~32px', uh === 0 || Math.abs(uh - 32) < 5, { uh });
    report('上传/搜索高度协调', ih === 0 || Math.abs(uh - ih) < 5, { uh, ih });
    report('表格无异常横向滚动条', s.hasHScroll !== true, { hasHScroll: s.hasHScroll });
    report('表头有背景色', !!s.head?.bg && s.head.bg !== 'rgba(0, 0, 0, 0)', { bg: s.head?.bg });
  } catch { report('文件页样式解析异常', false, list); }

  // ---------- 3) 图片预览：放大后宽度增大、容器可平移 ----------
  const imgDlg = await openPreview(page, up.img.name);
  await imgDlg.locator('.pf-preview-img-wrap img').first().waitFor({ state: 'visible' });
  await page.waitForFunction(() => {
    const el = document.querySelector('.pf-preview-img-wrap img');
    return !!el && el.complete && el.naturalWidth > 0 && /width:\s*\d+px/.test(el.getAttribute('style') || '');
  }, undefined, { timeout: 20_000 });
  const w0 = await imgDlg.locator('.pf-preview-img-wrap img').evaluate((el) => el.clientWidth);
  for (let i = 0; i < 6; i++) await imgDlg.getByRole('button', { name: '放大' }).click();
  await page.waitForFunction((t) => {
    const el = document.querySelector('.pf-preview-img-wrap img');
    return !!el && Math.abs(el.clientWidth - Math.round(t * 2.5)) <= 2;
  }, await imgDlg.locator('.pf-preview-img-wrap img').evaluate((el) => el.naturalWidth), { timeout: 20_000 });
  const w1 = await imgDlg.locator('.pf-preview-img-wrap img').evaluate((el) => el.clientWidth);
  const pan = await imgDlg.locator('.pf-preview-img-wrap').evaluate((el) => ({ sw: el.scrollWidth, cw: el.clientWidth }));
  report('图片放大后宽度增大(576→1440)', w1 > w0, { w0, w1 });
  report('放大后可左右平移(容器可滚动)', pan.sw > pan.cw, { sw: pan.sw, cw: pan.cw });
  report('缩放控件齐全', (await imgDlg.getByRole('button', { name: '放大' }).count() > 0) && (await imgDlg.getByRole('button', { name: '缩小' }).count() > 0));
  await shot(page, '03-img-preview.png');
  await closePreview(page, imgDlg);

  // ---------- 4) XLSX：表头不吸顶 + 数字右/文本左对齐 ----------
  const xDlg = await openPreview(page, up.xls.name);
  await expect(xDlg.locator('.pf-preview-xlsx thead th').first()).toBeVisible({ timeout: 20_000 });
  const xo = await xDlg.evaluate((root) => {
    const q = (s: string) => root.querySelector(s);
    const th = q('.pf-preview-xlsx thead th');
    const tdStr = q('.pf-preview-xlsx td.str');
    const tdNum = q('.pf-preview-xlsx td.num');
    const c = (el: Element | null) => (el ? getComputedStyle(el) : null);
    return { thPos: c(th)?.position, thSticky: c(th)?.position === 'sticky', caption: !!q('.pf-sheet-caption'), str: c(tdStr)?.textAlign, num: c(tdNum)?.textAlign };
  });
  report('XLSX 表头不吸顶(static)，且已随行滚动', xo.thPos !== 'sticky', { thPos: xo.thPos, thSticky: xo.thSticky });
  report('XLSX 文本左对齐 / 数字右对齐', xo.str === 'left' && xo.num === 'right', { str: xo.str, num: xo.num });
  report('XLSX 工作表标题存在', xo.caption === true);
  await xDlg.screenshot({ path: path.join(OUT, '04-xlsx-preview.png') });
  await closePreview(page, xDlg);

  // ---------- 5) Markdown：渲染为文档结构(h1/ul)，非源码 pre ----------
  const mDlg = await openPreview(page, up.md.name);
  await expect(mDlg.locator('.pf-preview-doc h1').first()).toBeVisible({ timeout: 20_000 });
  const mo = await mDlg.evaluate((root) => {
    const q = (s: string) => root.querySelector(s);
    return { h1: !!q('.pf-preview-doc h1'), ul: !!q('.pf-preview-doc ul'), pre: !!q('.pf-preview-doc pre'), bq: !!q('.pf-preview-doc blockquote') };
  });
  report('MD 渲染为标题 h1', mo.h1 === true, mo);
  report('MD 渲染为列表 ul', mo.ul === true, mo);
  await mDlg.screenshot({ path: path.join(OUT, '05-md-preview.png') });
  await closePreview(page, mDlg);

  // ---------- 汇总 ----------
  console.log('=== STYLE-QA-FINDINGS ===');
  console.log(JSON.stringify(findings, null, 2));
});