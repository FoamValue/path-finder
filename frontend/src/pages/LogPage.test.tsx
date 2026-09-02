import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import LogPage from '../pages/LogPage';

const mocks = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('../api/client', () => ({ get: mocks.get }));

const logs = [
  { id: 1, operatorName: 'admin', operationType: 'LOGIN', targetName: '-', detail: '登录成功', success: 1, createdAt: '2026-09-01T08:00:00' },
  { id: 2, operatorName: 'zhangsan', operationType: 'DELETE', targetName: '合同.pdf', detail: '软删除，进入回收站', success: 1, createdAt: '2026-09-01T09:00:00' },
  { id: 3, operatorName: 'lisi', operationType: 'DOWNLOAD', targetName: '报表.xlsx', detail: '下载', success: 0, createdAt: '2026-09-02T10:00:00' },
];

function mockList() {
  mocks.get.mockImplementation((url: string) => {
    if (url.includes('/api/log/page')) {
      return Promise.resolve({ list: logs, total: logs.length });
    }
    return Promise.reject(new Error('unhandled: ' + url));
  });
}

describe('审计日志页（TC-AUDIT-006 前端）', () => {
  beforeEach(() => {
    mocks.get.mockReset();
    mockList();
  });

  it('渲染日志行：类型/操作人/结果标签与时间', async () => {
    render(<LogPage />);
    expect(await screen.findByText('admin')).toBeTruthy();
    expect(screen.getByText('LOGIN')).toBeTruthy();
    expect(screen.getByText('DELETE')).toBeTruthy();
    expect(screen.getAllByText('成功').length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByText('失败').length).toBe(1);
    expect(screen.getAllByText('2026-09-01 08:00:00').length).toBeGreaterThan(0);
    expect(screen.getByText('共 3 条')).toBeTruthy();
  });

  it('操作人搜索携带 operatorName 参数并回到第一页', async () => {
    render(<LogPage />);
    await screen.findByText('admin');

    const search = screen.getByPlaceholderText('操作人');
    fireEvent.change(search, { target: { value: 'zhangsan' } });
    const searchBtn = document.querySelector('.ant-input-search-button') as HTMLElement;
    fireEvent.click(searchBtn);

    await waitFor(() => {
      const calls = mocks.get.mock.calls.map((c) => String(c[0]));
      expect(calls.some((u) => u.includes('/api/log/page') && u.includes('operatorName=zhangsan'))).toBe(true);
    });
  });
});
