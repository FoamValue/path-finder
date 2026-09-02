import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import StoragePage from '../pages/StoragePage';

const mocks = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('../api/client', () => ({ get: mocks.get }));

const info = {
  total: 500 * 1024 * 1024 * 1024,
  used: 100 * 1024 * 1024 * 1024,
  available: 400 * 1024 * 1024 * 1024,
  ratio: 0.2,
  alert: false,
};

describe('存储监控页（TC-ST-003/004 前端看板）', () => {
  beforeEach(() => {
    mocks.get.mockReset();
  });

  it('渲染容量统计卡片与使用率', async () => {
    mocks.get.mockResolvedValue(info);
    render(<StoragePage />);

    expect(await screen.findByText('总容量')).toBeTruthy();
    expect(screen.getByText('500.00 GB')).toBeTruthy();
    expect(screen.getByText('100.00 GB')).toBeTruthy();
    expect(screen.getByText('400.00 GB')).toBeTruthy();
  });

  it('使用率 <85% 不出现告警条', async () => {
    mocks.get.mockResolvedValue({ ...info, alert: false });
    render(<StoragePage />);
    await screen.findByText('总容量');
    expect(screen.queryByText(/磁盘使用率已达 85% 阈值/)).toBeNull();
  });

  it('使用率达 85% 显示告警条', async () => {
    mocks.get.mockResolvedValue({ ...info, ratio: 0.92, alert: true });
    render(<StoragePage />);
    expect(await screen.findByText(/磁盘使用率已达 85% 阈值，请及时扩容或清理/)).toBeTruthy();
  });

  it('数据加载前不渲染空态页面', () => {
    mocks.get.mockReturnValue(new Promise(() => undefined));
    const { container } = render(<StoragePage />);
    expect(container.querySelector('.ant-card')).toBeNull();
  });
});
