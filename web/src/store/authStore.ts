import { create } from 'zustand';
import { clearToken, getToken, setToken } from '@/lib/tokenStore';
import type { UserVO } from '@/features/auth/types';

interface AuthState {
  user: UserVO | null;
  token: string | null;
  loggedIn: boolean;
  /** 登录成功后写入 token + 用户信息。 */
  setAuth: (token: string, user: UserVO) => void;
  /** 刷新当前用户信息（如 /auth/me）。 */
  setUser: (user: UserVO) => void;
  /** 登出：清 token + 状态。 */
  logout: () => void;
  /** 应用启动：若 localStorage 有 token 则恢复登录态。 */
  hydrate: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  token: null,
  loggedIn: false,

  setAuth: (token: string, user: UserVO) => {
    setToken(token);
    set({ token, user, loggedIn: true });
  },

  setUser: (user: UserVO) => set({ user }),

  logout: () => {
    clearToken();
    set({ token: null, user: null, loggedIn: false });
  },

  hydrate: () => {
    const token = getToken();
    if (token) set({ token, loggedIn: true });
  },
}));
