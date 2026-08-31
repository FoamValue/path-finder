import type { ApiResponse } from './types';
import { logger } from '../utils/logger';

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

async function handleResponse<T>(resp: Response, url: string): Promise<T> {
  const text = await resp.text();
  logger.info(`HTTP ${resp.status} <- ${url}`, text.slice(0, 300));
  if (resp.status === 401) {
    logger.warn('401 未登录或会话失效，清除 token 并跳转登录页');
    clearToken();
    if (!location.pathname.startsWith('/login')) {
      location.href = '/login';
    }
    throw new ApiError(401, '未登录或会话已失效');
  }
  if (resp.status === 403) {
    logger.error('403 无权限', text);
    let msg = '无权限执行该操作';
    try {
      const j = JSON.parse(text);
      if (j && j.message) msg = j.message;
    } catch {
      /* 非 JSON 响应时使用默认文案 */
    }
    throw new ApiError(403, msg);
  }
  let json: ApiResponse<T>;
  try {
    json = JSON.parse(text);
  } catch {
    logger.error('响应解析失败', text.slice(0, 300));
    throw new ApiError(resp.status, text || '响应解析失败');
  }
  if (json.code !== 0) {
    logger.error(`业务错误 code=${json.code} msg=${json.message}`);
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
  const method = options.method ?? 'GET';
  logger.info(`HTTP ${method} -> ${url}`);
  try {
    const resp = await fetch(url, { ...options, headers, body });
    return await handleResponse<T>(resp, url);
  } catch (e) {
    // 业务错误（ApiError）已携带具体原因，直接透传；仅真正的网络异常才包装提示
    if (e instanceof ApiError) {
      throw e;
    }
    logger.error(`网络请求失败: ${method} ${url}`, e);
    throw new ApiError(0, '网络请求失败，请检查网络或后端服务');
  }
}

export const get = <T>(url: string): Promise<T> => request<T>(url);
export const post = <T>(url: string, body?: unknown): Promise<T> =>
  request<T>(url, { method: 'POST', body: body as BodyInit });
export const put = <T>(url: string, body?: unknown): Promise<T> =>
  request<T>(url, { method: 'PUT', body: body as BodyInit });
export const del = <T>(url: string): Promise<T> => request<T>(url, { method: 'DELETE' });
