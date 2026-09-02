import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import Recycle from '../pages/Recycle';

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(async () => undefined),
  del: vi.fn(async () => undefined),
  messageSuccess: vi.fn(),
  messageError: vi.fn(),
  messageWarning: vi.fn(),
}));

vi.mock('../api/client', () => ({ get: mocks.get, post: mocks.post, del: mocks.del }));
vi.mock('antd', async (importOriginal) => {
  const antd = await importOriginal<typeof import('antd')>();
  return {
    ...antd,
    message: {
      ...antd.message,
      success: mocks.messageSuccess,
      error: mocks.messageError,
      warning: mocks.messageWarning,
    },
  };
});

const items = [
  { id: 11, fileId: 1, originalName: '旧合同.pdf', fileType: 'pdf', fileSize: 2048, deletedBy: 1, deletedAt: '2026-09-01T09:00:00', expireAt: '2026-10-01T09:00:00', spaceType: 'PERSONAL' },
  { id: 12, fileId: 2, originalName: '旧报表.xlsx', fileType: 'xlsx', fileSize: 5120, deletedBy: 1, deletedAt: '2026-09-01T10:00:00', expireAt: '2026-10-01T10:00:00', spaceType: 'DEPT' },
];

function mockByUrl(role: string) {
  mocks.get.mockImplementation((url: string) => {
    if (url.includes('/api/auth/me')) {
      return Promise.resolve({ id: 1, username: 'admin', realName: '系统管理员', roleCode: role, deptId: 1, mustChangePassword: 0 });
    }
    if (url.includes('/api/recycle/page')) return Promise.resolve({ list: items, total: items.length });
    return Promise.reject(new Error('unhandled: ' + url));
  });
}

describe('回收站页（TC-FILE-009/010/012 前端交互）', () => {
  beforeEach(() => {
    mocks.get.mockReset();
    mocks.post.mockReset();
    mocks.del.mockClear();
    mocks.messageSuccess.mockClear();
    mocks.messageError.mockClear();
    mocks.messageWarning.mockClear();
    mocks.post.mockResolvedValue({ message: '操作成功' });
    mockByUrl('ADMIN');
  });

  it('渲染回收站记录与日期格式', async () => {
    render(<Recycle />);
    expect(await screen.findByText('旧合同.pdf')).toBeTruthy();
    expect(screen.getByText('旧报表.xlsx')).toBeTruthy();
    expect(screen.getByText('2026-09-01 09:00:00')).toBeTruthy();
    expect(screen.getByText('共 2 条')).toBeTruthy();
  });

  it('行内恢复：调用 restore 并刷新列表', async () => {
    render(<Recycle />);
    await screen.findByText('旧合同.pdf');

    const allButtons = Array.from(document.querySelectorAll('button'));
    const restoreBtns = allButtons.filter((b) => b.textContent?.replace(/\s/g, '') === '恢复');
    expect(restoreBtns.length, '应存在行内恢复按钮').toBeGreaterThan(0);
    fireEvent.click(restoreBtns[0]);

    await waitFor(() => expect(mocks.post).toHaveBeenCalledWith('/api/recycle/1/restore'));
    expect(mocks.messageSuccess).toHaveBeenCalledWith('已恢复');
    // 恢复后应重新请求列表
    await waitFor(() =>
      expect(mocks.get.mock.calls.filter((c) => String(c[0]).includes('/api/recycle/page')).length).toBeGreaterThan(1),
    );
  });

  it('ADMIN 可见「物理清除」操作，确认后调用 purge', async () => {
    render(<Recycle />);
    await screen.findByText('旧合同.pdf');
    expect(screen.getAllByText('物理清除').length).toBeGreaterThan(0);

    fireEvent.click(screen.getAllByText('物理清除')[0]);
    fireEvent.click(screen.getByRole('button', { name: 'OK' }));
    await waitFor(() => expect(mocks.del).toHaveBeenCalledWith('/api/recycle/1/purge'));
    expect(mocks.messageSuccess).toHaveBeenCalledWith('已物理清除');
  });

  it('非 ADMIN（USER）不可见物理清除按钮', async () => {
    mockByUrl('USER');
    render(<Recycle />);
    await screen.findByText('旧合同.pdf');
    expect(screen.queryByText('物理清除')).toBeNull();
  });

  it('勾选记录后可批量恢复', async () => {
    render(<Recycle />);
    await screen.findByText('旧合同.pdf');

    const checkbox = document.querySelector('input[type="checkbox"]') as HTMLInputElement;
    fireEvent.click(checkbox);

    const btn = screen.getByText('批量恢复').closest('button')!;
    await waitFor(() => expect(btn.hasAttribute('disabled')).toBe(false));
    fireEvent.click(btn);

    await waitFor(() =>
      expect(mocks.post).toHaveBeenCalledWith('/api/recycle/batchRestore', expect.objectContaining({ fileIds: expect.any(Array) })),
    );
    expect(mocks.messageSuccess).toHaveBeenCalledWith('操作成功');
  });
});
