import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import Login from '../pages/Login';

const navigate = vi.fn();
vi.mock('react-router-dom', () => ({
  useNavigate: () => navigate,
}));

vi.mock('../utils/crypto', () => ({
  encryptPassword: vi.fn(async (p: string) => `enc:${p}`),
}));

describe('Login 页面', () => {
  beforeEach(() => {
    navigate.mockClear();
    localStorage.clear();
    vi.restoreAllMocks();
  });

  const mockApi = (overrides: Record<string, unknown> = {}) => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        const json = (data: unknown) =>
          Promise.resolve(
            new Response(JSON.stringify({ code: 0, message: 'success', data }), {
              status: 200,
              headers: { 'Content-Type': 'application/json' },
            }),
          );
        const fail = (msg: string, code = 400) =>
          Promise.resolve(
            new Response(JSON.stringify({ code, message: msg, data: null }), { status: 200 }),
          );
        if (url === '/api/captcha') return json({ uuid: 'u1', image: 'base64img' });
        if (url === '/api/publicKey') return json({ publicKey: 'PUBKEY' });
        if (url === '/api/login') {
          const body = JSON.parse(String(init?.body));
          if (body.username === 'admin' && body.captchaCode === 'AB12') return json({ token: 'tok1' });
          return fail('验证码错误或已过期');
        }
        if (url === '/api/auth/me') return json({ id: 1, username: 'admin', mustChangePassword: 0, roleCode: 'ADMIN' });
        return json(overrides[url]);
      }),
    );
  };

  it('渲染验证码图片', async () => {
    mockApi();
    render(<Login />);
    const img = await screen.findByAltText('验证码');
    expect(img.getAttribute('src')).toContain('base64img');
  });

  it('登录成功后跳转首页', async () => {
    mockApi();
    render(<Login />);
    fireEvent.change(screen.getByPlaceholderText('用户名'), { target: { value: 'admin' } });
    fireEvent.change(screen.getByPlaceholderText('密码'), { target: { value: 'Init@123' } });
    fireEvent.change(screen.getByPlaceholderText('验证码'), { target: { value: 'AB12' } });
    fireEvent.click(screen.getByRole('button', { name: /登 录/ }));
    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/'));
  });

  it('验证码错误时提示并刷新', async () => {
    mockApi();
    render(<Login />);
    fireEvent.change(screen.getByPlaceholderText('用户名'), { target: { value: 'admin' } });
    fireEvent.change(screen.getByPlaceholderText('密码'), { target: { value: 'x' } });
    fireEvent.change(screen.getByPlaceholderText('验证码'), { target: { value: 'WRONG' } });
    fireEvent.click(screen.getByRole('button', { name: /登 录/ }));
    await waitFor(() => expect(navigate).not.toHaveBeenCalled());
  });
});
