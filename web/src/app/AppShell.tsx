import { useEffect } from 'react';
import { Outlet } from 'react-router-dom';
import { SideNav } from './SideNav';
import { TopBar } from './TopBar';
import { useAuthStore } from '@/store/authStore';
import { getMe } from '@/features/auth/api';

/** 应用外壳：左导航 + 顶栏 + 内容区（Outlet）。docs §2 / §3.4。 */
export function AppShell(): JSX.Element {
  const token = useAuthStore((s) => s.token);
  const user = useAuthStore((s) => s.user);
  const setUser = useAuthStore((s) => s.setUser);

  // 启动后若仅有 token 无 user，拉取 /auth/me 补全。docs §8-7。
  useEffect(() => {
    if (token && !user) {
      getMe()
        .then(setUser)
        .catch(() => undefined);
    }
  }, [token, user, setUser]);

  return (
    <div className="flex h-full w-full overflow-hidden">
      <SideNav />
      <div className="flex min-w-0 flex-1 flex-col">
        <TopBar />
        <main className="flex-1 overflow-y-auto bg-paper" style={{ padding: 'var(--content-pad)' }}>
          <div className="mx-auto w-full" style={{ maxWidth: 'var(--content-max)' }}>
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  );
}
