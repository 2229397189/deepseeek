/**
 * Token 持久化（localStorage）。docs §8-7 默认 localStorage。
 * token 名与请求头一致：lq-token。
 */
const TOKEN_KEY = 'lq-token';

export function getToken(): string | null {
  try {
    return window.localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setToken(token: string): void {
  try {
    window.localStorage.setItem(TOKEN_KEY, token);
  } catch {
    /* 忽略隐私模式等写入失败 */
  }
}

export function clearToken(): void {
  try {
    window.localStorage.removeItem(TOKEN_KEY);
  } catch {
    /* 忽略 */
  }
}
