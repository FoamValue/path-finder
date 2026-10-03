import { test, expect, type Page } from '@playwright/test';
import { fileURLToPath } from 'url';
import path from 'path';
import fs from 'fs';
import { ADMIN, login, rowOf, visibleDialog } from './helpers';

/**
 * TC-E2E-PREVIEW 文件在线预览（本地只读渲染，仅预览不可修改附件）。
 *
 * 覆盖：MD/TXT（<pre>）、PDF（pdf.js → canvas）、DOCX（mammoth → HTML）、
 * XLSX（SheetJS → table）。附件用真实/可解析的固定测试文件
 * （fixtures/gen-fixtures.py 生成），验证前端预览渲染而非仅弹窗出现。
 */
const FIXTURES = path.join(path.dirname(fileURLToPath(import.meta.url)), 'fixtures', 'fixtures');

function fixture(name: string): { name: string; mimeType: string; buffer: Buffer } {
  const ext = path.extname(name).slice(1);
  const mime: Record<string, string> = {
    md: 'text/markdown',
    txt: 'text/plain',
    png: 'image/png',
    pdf: 'application/pdf',
    docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  };
  return { name, mimeType: mime[ext], buffer: fs.readFileSync(path.join(FIXTURES, name)) };
}

async function upload(page: Page, file: { name: string; mimeType: string; buffer: Buffer }) {
  await page.getByRole('button', { name: '上传文件' }).click();
  const dialog = page.locator('.ant-modal-content:visible').filter({ hasText: '上传文件' });
  await expect(dialog).toBeVisible();
  await dialog.locator('input[type=file]').setInputFiles(file);
  // 连续上传时上一次的“上传完成”消息气泡仍在屏上，取最新一个避免严格模式多元素冲突
  await expect(page.getByText('上传完成').last()).toBeVisible({ timeout: 90_000 });
  await page
    .locator('.ant-modal-content:visible')
    .getByRole('button', { name: /关\s*闭/ })
    .click();
}

async function openPreview(page: Page, fileName: string): Promise<void> {
  await rowOf(page, fileName).getByRole('button', { name: '预览' }).click();
  await expect(visibleDialog(page).filter({ hasText: '预览' })).toBeVisible();
}

async function closePreview(page: Page): Promise<void> {
  const dialog = visibleDialog(page).filter({ hasText: '预览' });
  await dialog.locator('.ant-modal-close').click();
  await expect(dialog).toBeHidden();
}

test('在线预览：MD 渲染 Markdown、PDF 渲染 canvas、DOCX/DOC、XLSX 表格、图片缩放可平移', async ({ page }) => {
  test.setTimeout(180_000);
  const stem = `preview-${Date.now()}`;
  const files = {
    md: { ...fixture('demo.md'), name: `${stem}-note.md` },
    pdf: { ...fixture('demo.pdf'), name: `${stem}-guide.pdf` },
    docx: { ...fixture('demo.docx'), name: `${stem}-report.docx` },
    xlsx: { ...fixture('demo.xlsx'), name: `${stem}-sheet.xlsx` },
    png: { ...fixture('demo.png'), name: `${stem}-pic.png` },
  };

  await login(page, ADMIN.username, ADMIN.password);
  await page.waitForURL((u) => u.pathname === '/');

  // 1) 上传 5 类文件
  for (const f of Object.values(files)) {
    await upload(page, f);
  }

  // 2) MD：渲染为 Markdown（出现 h1 与列表项），而非原样 <pre> 源码
  await openPreview(page, files.md.name);
  const mdDlg = visibleDialog(page).filter({ hasText: '预览' });
  await expect(mdDlg.locator('h1', { hasText: '文件预览演示' })).toBeVisible({ timeout: 20_000 });
  await expect(mdDlg.locator('li', { hasText: '要点一' })).toBeVisible();
  await expect(mdDlg.locator('pre', { hasText: '# 文件预览演示' })).toHaveCount(0);
  await closePreview(page);

  // 3) PDF：弹窗内应渲染出 canvas 页面
  await openPreview(page, files.pdf.name);
  await expect(visibleDialog(page).filter({ hasText: '预览' }).locator('canvas').first()).toBeVisible({
    timeout: 20_000,
  });
  await closePreview(page);

  // 4) DOCX：mammoth 转出的 HTML 应包含文档文本
  await openPreview(page, files.docx.name);
  await expect(visibleDialog(page).filter({ hasText: '预览' }).getByText('DOCX 预览验证成功')).toBeVisible({
    timeout: 20_000,
  });
  await closePreview(page);

  // 5) XLSX：首行作为表头(th)，含工作表标题与单元格文本
  await openPreview(page, files.xlsx.name);
  const xlsDlg = visibleDialog(page).filter({ hasText: '预览' });
  await expect(xlsDlg.getByText('工作表：Sheet1')).toBeVisible({ timeout: 20_000 });
  await expect(xlsDlg.locator('thead th').first()).toBeVisible();
  await expect(xlsDlg.getByText('XLSX 预览验证')).toBeVisible();
  await closePreview(page);

  // 6) 图片：放大后宽度增大，滚动容器可横纵平移
  await openPreview(page, files.png.name);
  const imgDlg = visibleDialog(page).filter({ hasText: '预览' });
  const img = imgDlg.locator('.pf-preview-img-wrap img');
  await expect(img).toBeVisible({ timeout: 20_000 });
  // 等图片真实加载且 React 已把宽度从 '100%' 占位提交为像素值，再取初始宽度
  await page.waitForFunction(() => {
    const el = document.querySelector('.pf-preview-img-wrap img') as HTMLImageElement | null;
    return !!el && el.complete && el.naturalWidth > 0 && /width:\s*\d+px/.test(el.getAttribute('style') || '');
  });
  const w0 = await img.evaluate((el) => el.clientWidth);
  // 连续放大 6 次（1 → 2.5 倍）
  for (let i = 0; i < 6; i++) {
    await imgDlg.getByRole('button', { name: '放大' }).click();
  }
  // 等待宽度过渡(0.15s)完成并稳定到 naturalWidth*2.5，避免在动画中途测量到不完整宽度
  await page.waitForFunction((target) => {
    const el = document.querySelector('.pf-preview-img-wrap img') as HTMLImageElement | null;
    return !!el && Math.abs(el.clientWidth - Math.round(target * 2.5)) <= 2;
  }, await img.evaluate((el) => el.naturalWidth));
  const w1 = await img.evaluate((el) => el.clientWidth);
  expect(w1).toBeGreaterThan(w0);
  // 滚动容器内容宽度超过可见宽度，说明放大后可左右/上下滚动查看
  const scrollable = await imgDlg
    .locator('.pf-preview-img-wrap')
    .evaluate((el) => ({ sw: el.scrollWidth, cw: el.clientWidth }));
  expect(scrollable.sw).toBeGreaterThan(scrollable.cw);
});