import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import DeptPage from '../pages/DeptPage';

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(async () => undefined),
  put: vi.fn(async () => undefined),
  del: vi.fn(async () => undefined),
  messageSuccess: vi.fn(),
  messageError: vi.fn(),
}));

vi.mock('../api/client', () => ({ get: mocks.get, post: mocks.post, put: mocks.put, del: mocks.del }));
vi.mock('antd', async (importOriginal) => {
  const antd = await importOriginal<typeof import('antd')>();
  return { ...antd, message: { ...antd.message, success: mocks.messageSuccess, error: mocks.messageError } };
});

const tree = [
  {
    id: 1,
    parentId: 0,
    name: '研发部',
    sortOrder: 0,
    status: 1,
    children: [{ id: 2, parentId: 1, name: '前端组', sortOrder: 1, status: 1, children: [] }],
  },
];

function buttonsOf(near: Element): HTMLElement[] {
  return Array.from((near ?? document).querySelectorAll('button')).filter(
    (b) => b.textContent && /[^\s]/.test(b.textContent),
  );
}

function buttonByText(near: Element, text: string): HTMLElement | undefined {
  return buttonsOf(near).find((b) => b.textContent!.replace(/\s/g, '') === text);
}

function rowOf(text: string): HTMLElement {
  return screen.getByText(text).closest('tr') as HTMLElement;
}

async function clickModalOk() {
  fireEvent.click(screen.getByRole('button', { name: 'OK' }));
}

describe('部门管理页（TC-ORG-001~005 前端）', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    mocks.get.mockReset();
    mocks.post.mockClear();
    mocks.put.mockClear();
    mocks.del.mockClear();
    mocks.messageSuccess.mockClear();
    mocks.messageError.mockClear();
    mocks.get.mockResolvedValue(tree);
  });

  it('展开后渲染部门树父子行', async () => {
    render(<DeptPage />);
    expect(await screen.findByText('研发部')).toBeTruthy();

    const expand = rowOf('研发部').querySelector('.ant-table-row-expand-icon') as HTMLElement;
    fireEvent.click(expand);
    expect(await screen.findByText('前端组')).toBeTruthy();
    expect(screen.getByText('新增根部门')).toBeTruthy();
  });

  it('编辑部门：预填名称并 PUT 保存', async () => {
    render(<DeptPage />);
    await screen.findByText('研发部');

    fireEvent.click(buttonByText(rowOf('研发部'), '编辑')!);
    expect(await screen.findByDisplayValue('研发部')).toBeTruthy();

    const input = screen.getByPlaceholderText('如：研发部');
    fireEvent.change(input, { target: { value: '研发一部' } });
    await clickModalOk();

    await waitFor(() =>
      expect(mocks.put).toHaveBeenCalledWith('/api/dept/1', expect.objectContaining({ name: '研发一部' })),
    );
    expect(mocks.messageSuccess).toHaveBeenCalledWith('部门已更新');
  });

  it('新增根部门：携带 parentId=0 提交 POST', async () => {
    render(<DeptPage />);
    await screen.findByText('研发部');

    fireEvent.click(screen.getByText('新增根部门').closest('button')!);
    expect(await screen.findByText(/新增部门（上级：根组织）/)).toBeTruthy();

    const input = screen.getByPlaceholderText('如：研发部');
    fireEvent.change(input, { target: { value: '测试部' } });
    await clickModalOk();

    await waitFor(() =>
      expect(mocks.post).toHaveBeenCalledWith('/api/dept', expect.objectContaining({ name: '测试部', parentId: 0 })),
    );
    expect(mocks.messageSuccess).toHaveBeenCalledWith('部门已创建');
  });

  it('新增子部门：上级部门透传', async () => {
    render(<DeptPage />);
    await screen.findByText('研发部');

    fireEvent.click(buttonByText(rowOf('研发部'), '新增子部门')!);
    expect(await screen.findByText(/新增部门（上级：研发部）/)).toBeTruthy();
  });
});
