import { useState } from 'react';
import { NavLink, useLocation, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  FileText,
  ClipboardList,
  MessagesSquare,
  Share2,
  LineChart,
  Settings,
  PanelLeftClose,
  PanelLeftOpen,
  ChevronRight,
  User as UserIcon,
  LogOut,
  Compass,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useUiStore } from '@/store/uiStore';
import { useAuthStore } from '@/store/authStore';
import { useLogout } from '@/features/auth/useAuth';
import { listInterviewSessions } from '@/features/interview/api';
import { listSessions as listDecisionSessions } from '@/features/decision/api';

interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
  end?: boolean;
}

const NESTED: Record<string, { label: string; items: NavItem[] }> = {
  求职能力: {
    label: '求职能力',
    items: [
      { to: '/', label: '求职中心', icon: Compass, end: true },
      { to: '/resume', label: '简历中心', icon: FileText },
      { to: '/decision', label: 'JD 分析', icon: ClipboardList },
      { to: '/interview', label: 'AI 测评', icon: MessagesSquare },
    ],
  },
  'Agent 可视化': {
    label: 'Agent 可视化',
    items: [{ to: '/graph', label: '知识图谱', icon: Share2 }],
  },
  用户能力追踪: {
    label: '用户能力追踪',
    items: [
      { to: '/interview', label: '面试表现', icon: MessagesSquare },
      { to: '/profile', label: '能力画像', icon: LineChart },
    ],
  },
  系统管理: {
    label: '系统管理',
    items: [
      { to: '/admin/models', label: '模型管理', icon: Settings },
      { to: '/admin/kb', label: '知识库', icon: Settings },
    ],
  },
};

function NavRow({ item, collapsed }: { item: NavItem; collapsed: boolean }): JSX.Element {
  return (
    <NavLink
      to={item.to}
      end={item.end}
      className={({ isActive }) =>
        [
          'group relative flex items-center gap-3 h-9 rounded-md px-3 text-base transition-colors duration-base ease-smooth',
          isActive
            ? 'bg-accent-soft text-accent font-medium'
            : 'text-ink-soft hover:bg-surface-2',
          collapsed ? 'justify-center px-0' : '',
        ].join(' ')
      }
    >
      {({ isActive }) => (
        <>
          {isActive && (
            <span className="absolute left-0 top-1/2 h-5 w-[3px] -translate-y-1/2 rounded-r bg-accent" />
          )}
          <item.icon size={18} className="shrink-0" />
          {!collapsed && <span className="truncate">{item.label}</span>}
        </>
      )}
    </NavLink>
  );
}

function SessionList(): JSX.Element {
  const navigate = useNavigate();
  const q = useQuery({
    queryKey: ['sidebar-sessions'],
    queryFn: async () => {
      const [ivs, jds] = await Promise.all([listInterviewSessions(), listDecisionSessions()]);
      const items = [
        ...ivs.map((s: { sessionId: string; jobTitle?: string; status: string }) => ({
          key: `iv-${s.sessionId}`,
          title: s.jobTitle ?? '模拟面试',
          status: s.status,
          to: `/interview/${s.sessionId}`,
        })),
        ...jds.map((s: { id: string; jobTitle?: string; title?: string; status: string }) => ({
          key: `jd-${s.id}`,
          title: s.jobTitle ?? s.title ?? 'JD 分析',
          status: s.status,
          to: '/decision',
        })),
      ];
      return items.slice(0, 6);
    },
  });

  const labelOf = (st: string) =>
    ['IN_PROGRESS', 'RUNNING'].includes(st) ? '进行中'
      : ['FINISHED', 'SUCCEEDED'].includes(st) ? '已完成'
        : st === 'WAITING_USER_INPUT' ? 'WAITING_USER_INPUT'
          : '未完成';
  const dotOf = (st: string) =>
    ['IN_PROGRESS', 'RUNNING'].includes(st) ? 'bg-accent'
      : ['FINISHED', 'SUCCEEDED'].includes(st) ? 'bg-ok'
        : 'bg-ink-faint';

  if (q.isLoading || q.data?.length === 0) return <></>;
  return (
    <div className="mt-1 space-y-0.5">
      {q.data?.map((it) => (
        <button
          key={it.key}
          type="button"
          onClick={() => navigate(it.to)}
          className="flex w-full items-center gap-2 rounded-md px-3 py-1.5 text-left text-2xs text-ink-soft transition-colors hover:bg-surface-2"
        >
          <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${dotOf(it.status)}`} />
          <span className="truncate">{it.title}</span>
          <span className="ml-auto shrink-0 text-ink-faint">{labelOf(it.status)}</span>
        </button>
      ))}
    </div>
  );
}

/** 左侧导航：分组折叠 + 我的会话 + 底部用户卡。 */
export function SideNav(): JSX.Element {
  const collapsed = useUiStore((s) => s.sidebarCollapsed);
  const toggle = useUiStore((s) => s.toggleSidebar);
  const user = useAuthStore((s) => s.user);
  const logout = useLogout();
  const navigate = useNavigate();
  const location = useLocation();
  const [open, setOpen] = useState<Record<string, boolean>>({
    求职能力: true,
    'Agent 可视化': false,
    系统管理: false,
    用户能力追踪: false,
  });

  const initial = (user?.nickname ?? user?.username ?? 'U').slice(0, 1).toUpperCase();
  const activeGroup = Object.entries(NESTED).find(([, g]) =>
    g.items.some((i) => location.pathname.startsWith(i.to)),
  )?.[0];
  const [menuOpen, setMenuOpen] = useState(false);

  return (
    <aside
      className="flex h-full shrink-0 flex-col border-r border-line bg-surface"
      style={{ width: collapsed ? 'var(--nav-width-collapsed)' : 'var(--nav-width)' }}
    >
      <div className="flex h-14 items-center gap-2 border-b border-line px-4">
        <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-ink text-sm font-bold text-white">
          C
        </div>
        {!collapsed && <span className="text-md font-semibold text-ink">Chiron Agent</span>}
        {!collapsed && (
          <button
            type="button"
            onClick={toggle}
            aria-label="折叠侧栏"
            className="ml-auto text-ink-faint transition-colors hover:text-ink"
          >
            <PanelLeftClose size={16} />
          </button>
        )}
      </div>

      <nav className="flex-1 overflow-y-auto px-3 py-4">
        <div className="space-y-0.5">
          {Object.entries(NESTED).map(([group, g]) => {
            const isOpen = open[group] || activeGroup === group;
            return (
              <div key={group} className="mt-1 first:mt-0">
                <button
                  type="button"
                  onClick={() => setOpen((v) => ({ ...v, [group]: !v[group] }))}
                  className="flex w-full items-center justify-between rounded-md px-3 py-1.5 text-base font-medium text-ink transition-colors hover:bg-surface-2"
                >
                  <span>{group}</span>
                  <ChevronRight
                    size={14}
                    className={isOpen ? 'rotate-90 transition-transform duration-base text-ink-faint' : 'transition-transform duration-base text-ink-faint'}
                  />
                </button>
                {isOpen && (
                  <div className="mt-0.5 space-y-0.5">
                    {g.items.map((it) => (
                      <NavRow key={`${group}-${it.to}-${it.label}`} item={it} collapsed={collapsed} />
                    ))}
                  </div>
                )}
              </div>
            );
          })}
        </div>

        {!collapsed && (
          <div className="mt-5">
            <p className="px-3 pb-1 text-2xs font-semibold uppercase tracking-wide text-ink-faint">我的会话</p>
            <SessionList />
          </div>
        )}
      </nav>

      {!collapsed && (
        <div className="relative border-t border-line p-3">
          <button
            type="button"
            onClick={() => setMenuOpen((v) => !v)}
            className="flex w-full items-center gap-3 rounded-md px-1.5 py-1.5 transition-colors hover:bg-surface-2"
          >
            <span className="flex h-8 w-8 items-center justify-center rounded-full bg-ink text-sm font-semibold text-white">
              {initial}
            </span>
            <span className="min-w-0 flex-1 text-left">
              <span className="block truncate text-base text-ink">{user?.nickname ?? user?.username ?? '用户'}</span>
              <span className="block truncate font-mono text-2xs text-ink-faint">{user?.email ?? user?.id ?? ''}</span>
            </span>
          </button>
          {menuOpen && (
            <div className="absolute bottom-16 left-3 right-3 z-50 rounded-md border border-line bg-surface py-1 shadow-pop">
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
      )}
      {collapsed && (
        <div className="border-t border-line p-3">
          <button
            type="button"
            onClick={toggle}
            aria-label="展开侧栏"
            className="flex h-9 w-full items-center justify-center rounded-md text-ink-faint transition-colors hover:bg-surface-2"
          >
            <PanelLeftOpen size={18} />
          </button>
        </div>
      )}
    </aside>
  );
}
