import { describe, it, expect } from 'vitest';
import { previewKind } from './FileList';

describe('previewKind 文件类型 → 预览方式映射', () => {
  it('图片类', () => {
    expect(previewKind('a.png')).toBe('image');
    expect(previewKind('A.JPG')).toBe('image');
    expect(previewKind('photo.webp')).toBe('image');
  });

  it('PDF / 视频 / 音频', () => {
    expect(previewKind('guide.pdf')).toBe('pdf');
    expect(previewKind('clip.mp4')).toBe('video');
    expect(previewKind('voice.mp3')).toBe('audio');
  });

  it('docx / xlsx 走本地只读渲染', () => {
    expect(previewKind('report.docx')).toBe('docx');
    expect(previewKind('data.xlsx')).toBe('xlsx');
  });

  it('文本/代码类', () => {
    expect(previewKind('README.md')).toBe('text');
    expect(previewKind('app.log')).toBe('text');
    expect(previewKind('main.ts')).toBe('text');
  });

  it('暂不支持的旧 Office / 压缩包返回 null', () => {
    expect(previewKind('old.doc')).toBeNull();
    expect(previewKind('old.xls')).toBeNull();
    expect(previewKind('deck.ppt')).toBeNull();
    expect(previewKind('bundle.zip')).toBeNull();
  });

  it('无文件名或空串返回 null', () => {
    expect(previewKind('')).toBeNull();
    expect(previewKind(undefined as unknown as string)).toBeNull();
  });
});