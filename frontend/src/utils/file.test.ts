import { describe, expect, it } from 'vitest';
import { formatSize } from '../utils/file';

describe('formatSize', () => {
  it('formats bytes', () => {
    expect(formatSize(512)).toBe('512 B');
  });

  it('formats KB', () => {
    expect(formatSize(2048)).toBe('2.0 KB');
  });

  it('formats MB', () => {
    expect(formatSize(5 * 1024 * 1024)).toBe('5.0 MB');
  });

  it('formats GB', () => {
    expect(formatSize(2 * 1024 * 1024 * 1024)).toBe('2.00 GB');
  });
});
