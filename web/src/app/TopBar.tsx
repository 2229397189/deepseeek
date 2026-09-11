import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { CreditCard, ChevronDown, LogOut, User as UserIcon } from 'lucide-react';
import { useUiStore } from '@/store/uiStore';
import { useAuthStore } from '@/store/authStore';
import { useLogout } from '@/features/auth/useAuth';
import { Button } from '@/shared/components/Button';
import { BillingDrawer } from '@/features/admin/billing/BillingDrawer';

const BREADCRUMB: Record<string, string> = {
  '': '工作台',
  resume: '简历中心',
  decision: 'JD分析',
  interview: 'AI面试',
  graph: 'Agent可视化',
  profile: '用户能力追踪',
  admin: '系统管理',
  models: '模型管理',
  kb: '知识库管理',
};

function useBreadcrumb(): string[] {
  const { pathname } = useLocation();
  const segments = pathname.split('/').filter(Boolean);
  if (segments.length === 0) return ['工作台'];
  return segments.map((seg) => BREADCRUMB[seg] ?? seg);
}

/** 顶栏：面包屑 + 模型指示 + 额度按钮 + 用户菜单。docs §1 / §3.4。 */
export function TopBar(): JSX.Element {
  const crumbs = useBreadcrumb();
  const modelValue = useUiStore((s) => s.modelPickerValue);
  const user = useAuthStore((s) => s.user);
  const logout = useLogout();
  const navigate = useNavigate();
  const [billingOpen, setBillingOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!menuOpen) return;
    const onClick = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenuOpen(false);
    };
    document.addEventListener('mousedown', onClick);
    return () => document.removeEventListener('mousedown', onClick);
  }, [menuOpen]);

  const initial = (user?.nickname ?? user?.username ?? 'U').slice(0, 1).toUpperCase();

  return (
    <header className="flex h-14 shrink-0 items-center justify-between border-b border-line bg-surface px-6">
      <nav className="flex items-center gap-2 text-base text-ink-soft" aria-label="面包屑">
        {crumbs.map((c, i) => (
          <span key={`${c}-${i}`} className="flex items-center gap-2">
            {i > 0 && <span className="text-ink-faint">/</span>}
            <span className={i === crumbs.length - 1 ? 'font-medium text-ink' : ''}>{c}</span>
          </span>
        ))}
      </nav>

      <div className="flex items-center gap-3">
        <span className="hidden items-center gap-1.5 rounded-md bg-surface-2 px-2.5 py-1 text-xs text-ink-soft sm:inline-flex">
          <span className="h-1.5 w-1.5 rounded-full bg-brand" />
          <span className="font-mono">{modelValue}</span>
        </span>

        <Button variant="secondary" size="sm" onClick={() => setBillingOpen(true)}>
          <CreditCard size={15} />
          额度
        </Button>

        <div className="relative" ref={menuRef}>
          <button
            type="button"
            onClick={() => setMenuOpen((v) => !v)}
            className="flex items-center gap-2 rounded-md px-1.5 py-1 transition-colors hover:bg-surface-2"
          >
            <span className="flex h-7 w-7 items-center justify-center rounded-full bg-brand-soft text-sm font-semibold text-brand">
              {initial}
            </span>
            <span className="hidden text-base text-ink md:inline">
              {user?.nickname ?? user?.username ?? '用户'}
            </span>
            <ChevronDown size={14} className="text-ink-faint" />
          </button>
          {menuOpen && (
            <div className="absolute right-0 top-11 z-50 w-44 rounded-md border border-line bg-surface py-1 shadow-pop">
              <button
                type="button"
                onClick={() => {
                  setMenuOpen(false);
                  navigate('/profile');
                }}
                className="flex w-full items-center gap-2 px-3 py-2 text-base text-ink-soft transition-colors hover:bg-surface-2"
              >
                <UserIcon size={15} /> 能力画像
              </button>
              <button
                type="button"
                onClick={() => {
                  setMenuOpen(false);
                  logout();
                }}
                className="flex w-full items-center gap-2 px-3 py-2 text-base text-danger transition-colors hover:bg-surface-2"
              >
                <LogOut size={15} /> 退出登录
              </button>
            </div>
          )}
        </div>
      </div>

      <BillingDrawer open={billingOpen} onClose={() => setBillingOpen(false)} />
    </header>
  );
}
