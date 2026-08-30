import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getToken, setToken, clearToken, ApiError, request } from '../api/client';

describe('token storage', () => {
  beforeEach(() => localStorage.clear());

  it('setToken / getToken / clearToken round-trip', () => {
    expect(getToken()).toBeNull();
    setToken('abc');
    expect(getToken()).toBe('abc');
    clearToken();
    expect(getToken()).toBeNull();
  });
});

describe('request', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('sends Bearer token and parses data', async () => {
    setToken('t1');
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
    expect(init.headers.Authorization).toBe('Bearer t1');
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

  it('clears token and throws ApiError on 401', async () => {
    setToken('expired');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 401 })));
    await expect(request('/api/file/page')).rejects.toBeInstanceOf(ApiError);
    expect(getToken()).toBeNull();
  });
});
