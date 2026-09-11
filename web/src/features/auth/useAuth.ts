import { useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { login as loginApi, logout as logoutApi, register as registerApi } from './api';
import { useAuthStore } from '@/store/authStore';
import { useUiStore } from '@/store/uiStore';
import type { LoginRequest, RegisterRequest } from './types';

/** 登录 mutation：成功后写入 authStore 并回跳来源页。 */
export function useLogin() {
  const navigate = useNavigate();
  const setAuth = useAuthStore((s) => s.setAuth);
  const pushToast = useUiStore((s) => s.pushToast);

  return useMutation({
    mutationFn: (req: LoginRequest) => loginApi(req),
    onSuccess: (res) => {
      setAuth(res.token, res.user);
      pushToast({ tone: 'ok', message: `欢迎回来，${res.user.nickname ?? res.user.username}` });
      navigate('/');
    },
    onError: (err: Error) => {
      pushToast({ tone: 'danger', message: err.message });
    },
  });
}

/** 注册 mutation。 */
export function useRegister() {
  const pushToast = useUiStore((s) => s.pushToast);
  return useMutation({
    mutationFn: (req: RegisterRequest) => registerApi(req),
    onSuccess: () => {
      pushToast({ tone: 'ok', message: '注册成功，请登录' });
    },
    onError: (err: Error) => {
      pushToast({ tone: 'danger', message: err.message });
    },
  });
}

/** 登出：清状态 + 跳登录页。 */
export function useLogout() {
  const navigate = useNavigate();
  const logoutStore = useAuthStore((s) => s.logout);
  const pushToast = useUiStore((s) => s.pushToast);

  return () => {
    logoutApi().catch(() => undefined);
    logoutStore();
    pushToast({ tone: 'info', message: '已退出登录' });
    navigate('/login');
  };
}
