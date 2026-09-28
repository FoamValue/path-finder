import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearToken, ApiError, request } from '../api/client';

describe('clearToken（Cookie 会话下为空操作）', () => {
  it('clearToken 调用不抛异常（HttpOnly Cookie 由服务端清除）', () => {
    expect(() => clearToken()).not.toThrow();
  });
});

describe('request', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('带 Cookie 凭据发送请求并解析数据（无 Authorization 头）', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ code: 0, message: 'success', data: { id: 1 } }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const data = await request<{ id: number }>('/api/file/page');
    expect(data.id).toBe(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/file/page');
    expect(init.credentials).toBe('include');
    expect(init.headers?.Authorization).toBeUndefined();
  });

  it('throws ApiError on business error code', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ code: 400, message: '参数错误', data: null }), { status: 200 }),
      ),
    );
    await expect(request('/api/login')).rejects.toThrow('参数错误');
  });

  it('throws ApiError on 401 会话失效', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 401 })));
    await expect(request('/api/file/page')).rejects.toBeInstanceOf(ApiError);
  });
});

describe('request 错误码映射（TC-UI-008 友好提示）', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  const okJson = (code: number, message: string) =>
    vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ code, message, data: null }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );

  it('组件错误码 507/416 原样透传（前端据此提示中文）', async () => {
    vi.stubGlobal('fetch', okJson(507, '存储空间不足'));
    const e507 = (await request('/api/file/page').catch((e) => e)) as ApiError;
    expect(e507).toBeInstanceOf(ApiError);
    expect(e507.code).toBe(507);
    expect(e507.message).toBe('存储空间不足');

    vi.stubGlobal('fetch', okJson(416, 'Range 不可满足'));
    const e416 = (await request('/api/file/page').catch((e) => e)) as ApiError;
    expect(e416.code).toBe(416);
  });

  it('403 解析后端 JSON 提示文案', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ message: '仅系统管理员可查看审计日志' }), {
          status: 403,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    );
    const e = (await request('/api/log/page').catch((err) => err)) as ApiError;
    expect(e.code).toBe(403);
    expect(e.message).toBe('仅系统管理员可查看审计日志');
  });

  it('403 非 JSON 响应回退默认文案', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('Forbidden', { status: 403 })));
    const e = (await request('/api/user').catch((err) => err)) as ApiError;
    expect(e.code).toBe(403);
    expect(e.message).toBe('无权限执行该操作');
  });

  it('网络异常包装为友好提示', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('fetch failed')));
    const e = (await request('/api/file/page').catch((err) => err)) as ApiError;
    expect(e).toBeInstanceOf(ApiError);
    expect(e.code).toBe(0);
    expect(e.message).toContain('网络请求失败');
  });
});

describe('401 会话失效跳转', () => {
  const originalLocation = window.location;

  function stubLocation(pathname: string) {
    const fake = { pathname, href: '' };
    Object.defineProperty(window, 'location', { configurable: true, value: fake });
    return fake;
  }

  afterEach(() => {
    Object.defineProperty(window, 'location', { configurable: true, value: originalLocation });
  });

  it('登录页收到 401 不重复跳转', async () => {
    const fake = stubLocation('/login');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 401 })));
    await expect(request('/api/file/page')).rejects.toBeInstanceOf(ApiError);
    expect(fake.href).toBe('');
  });

  it('非登录页收到 401 跳转登录页', async () => {
    const fake = stubLocation('/recycle');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 401 })));
    await expect(request('/api/file/page')).rejects.toBeInstanceOf(ApiError);
    expect(fake.href).toBe('/login');
  });
});