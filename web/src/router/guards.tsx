import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/store/authStore';

/**
 * 鉴权守卫：未登录跳 /login（带回跳来源）。
 * docs §2 路由表 / §8-9。
 */
export function RequireAuth({ children }: { children: ReactNode }): JSX.Element {
  const loggedIn = useAuthStore((s) => s.loggedIn);
  const location = useLocation();
  if (!loggedIn) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return <>{children}</>;
}

/**
 * 管理员守卫：仅 role==='admin' 可进；role 未设置（演示用户）放行。
 * docs §8-9：admin 路由角色门禁未明确，采用「显式非 admin 才拦截」的宽松策略。
 */
export function RequireAdmin({ children }: { children: ReactNode }): JSX.Element {
  const role = useAuthStore((s) => s.user?.role);
  if (role && role !== 'admin') {
    return <Navigate to="/" replace />;
  }
  return <>{children}</>;
}
