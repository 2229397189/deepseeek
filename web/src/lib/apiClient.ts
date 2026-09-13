import { ApiError } from '@/types/common';
import { getToken } from '@/lib/tokenStore';
import { errorMessage } from '@/lib/errorMessages';
import { useAuthStore } from '@/store/authStore';

/**
 * API 客户端封装。docs §4.2。
 * - Base URL：import.meta.env.VITE_API_BASE ?? ''（开发走 /api 代理）
 * - 注入 lq-token 头
 * - 解包 Result<T>：code===0 返回 data，否则抛 ApiError
 * - 401001 清登录态 + 跳 /login（通过回调注入，避免依赖 router）
 * - 网络错误 -> ApiError(-1)；AbortController 超时（默认 30s）
 */

const BASE: string = import.meta.env.VITE_API_BASE ?? '';
const DEFAULT_TIMEOUT_MS = 30_000;

type UnauthorizedHandler = () => void;
let unauthorizedHandler: UnauthorizedHandler | null = null;

/** 注册 401 跳转处理器（由 main.tsx 注入 router.navigate）。 */
export function registerUnauthorizedHandler(fn: UnauthorizedHandler): void {
  unauthorizedHandler = fn;
}

interface RequestExtra {
  timeoutMs?: number;
  signal?: AbortSignal;
  headers?: Record<string, string>;
}

interface RequestConfig {
  method: string;
  path: string;
  params?: Record<string, unknown>;
  body?: BodyInit | null;
  headers?: Record<string, string>;
  timeoutMs?: number;
  signal?: AbortSignal;
}

function buildUrl(path: string, params?: Record<string, unknown>): string {
  const base = `${BASE}${path}`;
  if (!params || Object.keys(params).length === 0) return base;
  const qs = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null) continue;
    qs.append(key, String(value));
  }
  const query = qs.toString();
  return query ? `${base}?${query}` : base;
}

interface Envelope<T> {
  code: number;
  message: string;
  data: T;
  timestamp?: number;
}

async function parseEnvelope<T>(response: Response): Promise<T> {
  const text = await response.text();
  if (!text) {
    if (response.ok) return undefined as unknown as T;
    throw new ApiError(response.status, errorMessage(response.status) ?? '请求失败');
  }
  let payload: Envelope<T>;
  try {
    payload = JSON.parse(text) as Envelope<T>;
  } catch {
    throw new ApiError(response.status, '响应解析失败');
  }
  if (typeof payload.code !== 'number') {
    throw new ApiError(response.status, '响应格式异常');
  }
  if (payload.code === 0) return payload.data;
  if (payload.code === 401001) {
    useAuthStore.getState().logout();
    unauthorizedHandler?.();
  }
  throw new ApiError(
    payload.code,
    errorMessage(payload.code) ?? payload.message ?? '请求失败',
  );
}

function buildJsonBody(
  body: unknown,
  opts?: RequestExtra,
): Pick<RequestConfig, 'body' | 'headers' | 'timeoutMs' | 'signal'> {
  const hasBody = body !== undefined;
  return {
    body: hasBody ? JSON.stringify(body) : null,
    headers: hasBody
      ? { 'Content-Type': 'application/json', ...(opts?.headers ?? {}) }
      : { ...(opts?.headers ?? {}) },
    timeoutMs: opts?.timeoutMs,
    signal: opts?.signal,
  };
}

async function request<T>(config: RequestConfig): Promise<T> {
  const url = buildUrl(config.path, config.params);
  const controller = new AbortController();
  const timeoutMs = config.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  const timer = window.setTimeout(() => controller.abort(), timeoutMs);
  const signal = config.signal ?? controller.signal;

  const headers: Record<string, string> = { ...(config.headers ?? {}) };
  const token = getToken();
  if (token) headers['lq-token'] = token;

  let response: Response;
  try {
    response = await fetch(url, {
      method: config.method,
      headers,
      body: config.body ?? null,
      signal,
    });
  } catch (err) {
    window.clearTimeout(timer);
    if (err instanceof DOMException && err.name === 'AbortError') {
      throw new ApiError(-1, '请求超时，请稍后重试');
    }
    throw new ApiError(-1, errorMessage(-1) ?? '网络异常，请检查连接');
  }
  window.clearTimeout(timer);
  return parseEnvelope<T>(response);
}

/** GET 请求。 */
export function get<T>(
  path: string,
  params?: Record<string, unknown>,
  opts?: RequestExtra,
): Promise<T> {
  return request<T>({ method: 'GET', path, params, ...opts });
}

/** POST 请求（JSON 体，无体则无 Content-Type）。 */
export function post<T>(
  path: string,
  body?: unknown,
  opts?: RequestExtra,
): Promise<T> {
  return request<T>({ method: 'POST', path, ...buildJsonBody(body, opts) });
}

/** PUT 请求（JSON 体）。 */
export function put<T>(
  path: string,
  body?: unknown,
  opts?: RequestExtra,
): Promise<T> {
  return request<T>({ method: 'PUT', path, ...buildJsonBody(body, opts) });
}

/** 文件上传（multipart/form-data）。 */
export function upload<T>(
  path: string,
  file: File,
  field = 'file',
  extra?: Record<string, string>,
): Promise<T> {
  const fd = new FormData();
  fd.append(field, file);
  if (extra) {
    for (const [k, v] of Object.entries(extra)) fd.append(k, v);
  }
  return request<T>({ method: 'POST', path, body: fd });
}

/** DELETE 请求。 */
export function del<T>(
  path: string,
  params?: Record<string, unknown>,
  opts?: RequestExtra,
): Promise<T> {
  return request<T>({ method: 'DELETE', path, params, ...opts });
}
