import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import UserPage from '../pages/UserPage';

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(async () => undefined),
  put: vi.fn(async () => undefined),
  del: vi.fn(async () => undefined),
  messageSuccess: vi.fn(),
  messageError: vi.fn(),
  messageWarning: vi.fn(),
}));

vi.mock('../api/client', () => ({ get: mocks.get, post: mocks.post, put: mocks.put, del: mocks.del }));
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

const deptTree = [{ id: 1, parentId: 0, name: '研发部', sortOrder: 0, status: 1, children: [] }];
const users = [
  { id: 1, username: 'zhangsan', realName: '张三', deptId: 1, deptName: '研发部', roleCode: 'ADMIN', status: 1, mustChangePassword: 0, createdAt: '2026-08-01T10:00:00' },
  { id: 2, username: 'lisi', realName: '李四', deptId: 1, deptName: '研发部', roleCode: 'USER', status: 0, mustChangePassword: 1, createdAt: '2026-08-02T10:00:00' },
];

function buttonByText(near: Element, text: string): HTMLElement | undefined {
  return Array.from((near ?? document).querySelectorAll('button')).find(
    (b) => b.textContent!.replace(/\s/g, '') === text,
  );
}

function rowOf(text: string): HTMLElement {
  return screen.getByText(text).closest('tr') as HTMLElement;
}

describe('用户管理页（TC-ORG-007~013 前端）', () => {
  beforeEach(() => {
    mocks.get.mockReset();
    mocks.post.mockClear();
    mocks.put.mockClear();
    mocks.del.mockClear();
    mocks.messageSuccess.mockClear();
    mocks.get.mockImplementation((url: string) => {
      if (url.includes('/api/dept/tree')) return Promise.resolve(deptTree);
      if (url.includes('/api/user/page')) return Promise.resolve({ list: users, total: users.length });
      return Promise.reject(new Error('unhandled: ' + url));
    });
  });

  it('渲染用户行：角色中文标签、启停用状态、部门名', async () => {
    render(<UserPage />);
    expect(await screen.findByText('zhangsan')).toBeTruthy();
    expect(screen.getByText('张三')).toBeTruthy();
    expect(screen.getByText('系统管理员')).toBeTruthy();
    expect(screen.getByText('普通员工')).toBeTruthy();
    expect(screen.getAllByText('研发部').length).toBeGreaterThan(0);
    expect(screen.getAllByText('启用').length).toBeGreaterThan(0);
    expect(screen.getByText('停用')).toBeTruthy(); // 李四的状态标签
    expect(screen.getByText('共 2 条')).toBeTruthy();
  });

  it('点击「停用」调用 status PUT', async () => {
    render(<UserPage />);
    await screen.findByText('zhangsan');

    fireEvent.click(buttonByText(rowOf('张三'), '停用')!);
    await waitFor(() => expect(mocks.put).toHaveBeenCalledWith('/api/user/1/status?status=0'));
    expect(mocks.messageSuccess).toHaveBeenCalledWith('已更新状态');
  });

  it('点击「启用」调用 status PUT 置 1', async () => {
    render(<UserPage />);
    await screen.findByText('zhangsan');

    fireEvent.click(buttonByText(rowOf('李四'), '启用')!);
    await waitFor(() => expect(mocks.put).toHaveBeenCalledWith('/api/user/2/status?status=1'));
  });

  it('重置密码调用 PUT', async () => {
    render(<UserPage />);
    await screen.findByText('zhangsan');

    fireEvent.click(buttonByText(rowOf('张三'), '重置密码')!);
    await waitFor(() => expect(mocks.put).toHaveBeenCalledWith('/api/user/1/resetPassword'));
    expect(mocks.messageSuccess).toHaveBeenCalledWith('密码已重置为 Init@123');
  });

  it('删除用户：Popconfirm 确认后调用 DELETE', async () => {
    render(<UserPage />);
    await screen.findByText('zhangsan');

    const row = rowOf('张三');
    const deleteBtn = Array.from(row.querySelectorAll('button')).find((b) =>
      b.className.includes('dangerous'),
    )!;
    expect(deleteBtn, '行内应存在危险删除按钮').toBeTruthy();
    fireEvent.click(deleteBtn);
    fireEvent.click(screen.getByRole('button', { name: 'OK' }));

    await waitFor(() => expect(mocks.del).toHaveBeenCalledWith('/api/user/1'));
    expect(mocks.messageSuccess).toHaveBeenCalledWith('已删除');
  });

  it('新增用户：打开“新增用户”弹窗', async () => {
    render(<UserPage />);
    await screen.findByText('zhangsan');

    fireEvent.click(screen.getByText('新增用户').closest('button')!);
    await waitFor(() =>
      expect(document.querySelector('.ant-modal-title')?.textContent).toBe('新增用户'),
    );
  });

  it('编辑用户：打开弹窗且用户名输入框只读', async () => {
    render(<UserPage />);
    await screen.findByText('zhangsan');

    fireEvent.click(buttonByText(rowOf('张三'), '编辑')!);
    expect(await screen.findByText('编辑用户')).toBeTruthy();
    const inputs = Array.from(document.querySelectorAll('input'));
    const usernameInput = inputs.find((i) => (i as HTMLInputElement).value === 'zhangsan') as HTMLInputElement;
    expect(usernameInput.disabled).toBe(true);
  });
});
