import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import FileList from '../pages/FileList';

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

const me = { id: 1, username: 'admin', realName: '系统管理员', roleCode: 'ADMIN', deptId: 1, mustChangePassword: 0 };
const deptTree = [
  { id: 1, parentId: 0, name: '研发部', sortOrder: 0, status: 1, children: [] },
];
const files = [
  { id: 1, originalName: '合同.pdf', fileType: 'pdf', fileSize: 2048, spaceType: 'PERSONAL', deptId: null, ownerName: '张三', creatorName: '张三', createdAt: '2026-09-01T10:00:00', diskStatus: 'READY', status: 'READY' },
  { id: 2, originalName: '报表.xlsx', fileType: 'xlsx', fileSize: 4096, spaceType: 'DEPT', deptId: 1, ownerName: '李四', creatorName: '李四', createdAt: '2026-09-01T11:00:00', diskStatus: 'MISSING', status: 'READY' },
  { id: 3, originalName: '设计稿.png', fileType: 'png', fileSize: 8192, spaceType: 'PUBLIC', deptId: null, ownerName: '王五', creatorName: '王五', createdAt: '2026-09-02T09:00:00', diskStatus: 'UPDATED', status: 'READY' },
];

function mockGetByUrl() {
  mocks.get.mockImplementation((url: string) => {
    if (url.includes('/api/auth/me')) return Promise.resolve(me);
    if (url.includes('/api/dept/tree')) return Promise.resolve(deptTree);
    if (url.includes('/api/user/page')) return Promise.resolve({ list: [{ id: 9, username: 'zhangsan', realName: '张三' }], total: 1 });
    if (url.includes('/api/file/page')) {
      return Promise.resolve({ list: files, total: files.length });
    }
    if (url.includes('/downloadToken')) return Promise.resolve({ token: 'dl-1' });
    return Promise.reject(new Error('unhandled: ' + url));
  });
}

describe('文件管理页（TC-UI-006 列表分页与筛选）', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    mocks.get.mockReset();
    mocks.post.mockClear();
    mocks.put.mockClear();
    mocks.del.mockClear();
    mocks.messageSuccess.mockClear();
    mocks.messageError.mockClear();
    mocks.messageWarning.mockClear();
    mockGetByUrl();
  });

  it('渲染文件行与 MISSING/UPDATED 磁盘状态角标', async () => {
    render(<FileList />);
    expect(await screen.findByText('合同.pdf')).toBeTruthy();
    expect(screen.getByText('报表.xlsx')).toBeTruthy();
    expect(screen.getByText('设计稿.png')).toBeTruthy();
    expect(screen.getByText('目录文件已被删除')).toBeTruthy();
    expect(screen.getByText('源文件已被更新')).toBeTruthy();
    expect(screen.getByText('共 3 个文件')).toBeTruthy();
  });

  it('未勾选时批量操作按钮禁用', async () => {
    render(<FileList />);
    await screen.findByText('合同.pdf');
    expect(screen.getByText('批量下载').closest('button')?.hasAttribute('disabled')).toBe(true);
    expect(screen.getByText('批量归属').closest('button')?.hasAttribute('disabled')).toBe(true);
    expect(screen.getByText('批量删除').closest('button')?.hasAttribute('disabled')).toBe(true);
  });

  it('关键字搜索触发后端查询且携带 keyword 参数', async () => {
    render(<FileList />);
    await screen.findByText('合同.pdf');

    const searchInput = screen.getByPlaceholderText('按文件名搜索');
    fireEvent.change(searchInput, { target: { value: '合同' } });
    const searchBtn = document.querySelector('.ant-input-search-button') as HTMLElement;
    fireEvent.click(searchBtn);

    await waitFor(() => {
      const calls = mocks.get.mock.calls.map((c) => String(c[0]));
      expect(calls.some((u) => u.includes('/api/file/page') && u.includes('keyword=%E5%90%88%E5%90%8C'))).toBe(true);
    });
  });

  it('重命名：弹窗预填原名，提交调用 rename 并刷新列表', async () => {
    render(<FileList />);
    await screen.findByText('合同.pdf');

    fireEvent.click(screen.getAllByText('重命名')[0]);
    const input = await screen.findByDisplayValue('合同.pdf');
    fireEvent.change(input, { target: { value: '新合同.pdf' } });
    fireEvent.click(screen.getByRole('button', { name: 'OK' }));

    await waitFor(() =>
      expect(mocks.put).toHaveBeenCalledWith('/api/file/1/rename', { newName: '新合同.pdf' }),
    );
    expect(mocks.messageSuccess).toHaveBeenCalledWith('重命名成功');
  });

  it('重命名：名称为空时本地校验拦截不提交', async () => {
    render(<FileList />);
    await screen.findByText('合同.pdf');
    fireEvent.click(screen.getAllByText('重命名')[0]);
    const input = await screen.findByDisplayValue('合同.pdf');
    fireEvent.change(input, { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'OK' }));

    expect(await screen.findByText('请输入新文件名')).toBeTruthy();
    expect(mocks.put).not.toHaveBeenCalled();
  });

  it('ADMIN 点击行内归属：打开归属弹窗并加载可移交用户（TC-UI-007）', async () => {
    render(<FileList />);
    await screen.findByText('合同.pdf');
    fireEvent.click(screen.getAllByText('归属')[0]);

    expect(await screen.findByText(/修改归属 - 合同\.pdf/)).toBeTruthy();
    expect(screen.getByText('移交归属人（可选）')).toBeTruthy();
    await waitFor(() => expect(mocks.get).toHaveBeenCalledWith(expect.stringContaining('/api/user/page?pageSize=100')));
  });
});
