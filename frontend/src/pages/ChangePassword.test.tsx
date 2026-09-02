import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import ChangePassword from '../pages/ChangePassword';

const mocks = vi.hoisted(() => ({
  navigate: vi.fn(),
  post: vi.fn(async () => undefined),
  clearToken: vi.fn(),
  encryptPassword: vi.fn(async (p: string) => `enc:${p}`),
}));

vi.mock('react-router-dom', () => ({ useNavigate: () => mocks.navigate }));
vi.mock('../utils/crypto', () => ({ encryptPassword: mocks.encryptPassword }));
vi.mock('../api/client', () => ({ post: mocks.post, clearToken: mocks.clearToken }));

describe('修改密码页（TC-LOGIN-019/020 前端侧）', () => {
  beforeEach(() => {
    mocks.navigate.mockClear();
    mocks.post.mockClear();
    mocks.clearToken.mockClear();
    localStorage.clear();
    vi.restoreAllMocks();
  });

  function renderPage() {
    const view = render(<ChangePassword />);
    const inputs = () => document.querySelectorAll('input[type="password"]');
    const fill = (oldPwd: string, newPwd: string, confirm: string) => {
      fireEvent.change(inputs()[0], { target: { value: oldPwd } });
      fireEvent.change(inputs()[1], { target: { value: newPwd } });
      fireEvent.change(inputs()[2], { target: { value: confirm } });
    };
    const submit = () => fireEvent.click(screen.getByRole('button', { name: /确认修改/ }));
    return { view, fill, submit };
  }

  it('渲染改密说明与必填表单', () => {
    renderPage();
    expect(screen.getByText('修改密码')).toBeTruthy();
    expect(screen.getByText(/首次登录或密码被重置后/)).toBeTruthy();
    expect(document.querySelectorAll('input[type="password"]').length).toBe(3);
  });

  it('两次密码不一致：本地校验拦截且不提交', async () => {
    const { fill, submit } = renderPage();
    fill('Init@123', 'newpass123', 'newpass124');
    submit();
    expect(await screen.findByText('两次输入的密码不一致')).toBeTruthy();
    expect(mocks.post).not.toHaveBeenCalled();
  });

  it('新密码不足 8 位：提示且不提交', async () => {
    const { fill, submit } = renderPage();
    fill('Init@123', 'short', 'short');
    submit();
    expect(await screen.findByText('新密码至少 8 位')).toBeTruthy();
    expect(mocks.post).not.toHaveBeenCalled();
  });

  it('必填为空：本地校验拦截', async () => {
    const { submit } = renderPage();
    submit();
    expect(await screen.findByText('请输入原密码')).toBeTruthy();
    expect(mocks.post).not.toHaveBeenCalled();
  });

  it('校验通过：加密后提交，清 token 并回登录页', async () => {
    const { fill, submit } = renderPage();
    fill('Init@123', 'newpass123', 'newpass123');
    submit();

    await waitFor(() =>
      expect(mocks.post).toHaveBeenCalledWith('/api/changePassword', {
        oldPassword: 'enc:Init@123',
        newPassword: 'enc:newpass123',
      }),
    );
    expect(mocks.encryptPassword).toHaveBeenCalledWith('Init@123');
    expect(mocks.encryptPassword).toHaveBeenCalledWith('newpass123');
    await waitFor(() => expect(mocks.clearToken).toHaveBeenCalled());
    expect(mocks.navigate).toHaveBeenCalledWith('/login');
  });

  it('提交失败：展示后端错误信息，不跳转', async () => {
    mocks.post.mockRejectedValueOnce(new Error('原密码不正确'));
    const { fill, submit } = renderPage();
    fill('Init@123', 'newpass123', 'newpass123');
    submit();
    expect(await screen.findByText('原密码不正确')).toBeTruthy();
    expect(mocks.navigate).not.toHaveBeenCalled();
  });
});
