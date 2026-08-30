import type { ApiResponse } from './types';

const TOKEN_KEY = 'pf_token';

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY);
}

export class ApiError extends Error {
  code: number;

  constructor(code: number, message: string) {
    super(message);
    this.code = code;
  }
}

async function handleResponse<T>(resp: Response): Promise<T> {
  if (resp.status === 401) {
    clearToken();
    if (!location.pathname.startsWith('/login')) {
      location.href = '/login';
    }
    throw new ApiError(401, '未登录或会话已失效');
  }
  if (resp.status === 403) {
    const body = await resp.text();
    throw new ApiError(403, body || '无权限');
  }
  const text = await resp.text();
  let json: ApiResponse<T>;
  try {
    json = JSON.parse(text);
  } catch {
    throw new ApiError(resp.status, text || '响应解析失败');
  }
  if (json.code !== 0) {
    throw new ApiError(json.code, json.message || '请求失败');
  }
  return json.data;
}

export async function request<T>(url: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    ...(options.headers as Record<string, string>),
  };
  const token = getToken();
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }
  let body = options.body;
  if (body && typeof body === 'object' && !(body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(body);
  }
  const resp = await fetch(url, { ...options, headers, body });
  return handleResponse<T>(resp);
}

export const get = <T>(url: string): Promise<T> => request<T>(url);
export const post = <T>(url: string, body?: unknown): Promise<T> =>
  request<T>(url, { method: 'POST', body: body as BodyInit });
export const put = <T>(url: string, body?: unknown): Promise<T> =>
  request<T>(url, { method: 'PUT', body: body as BodyInit });
export const del = <T>(url: string): Promise<T> => request<T>(url, { method: 'DELETE' });
