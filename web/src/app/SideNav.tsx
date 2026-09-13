import { useState } from 'react';
import { NavLink } from 'react-router-dom';
import {
  LayoutDashboard,
  FileText,
  ClipboardList,
  MessagesSquare,
  Share2,
  LineChart,
  Cpu,
  Database,
  Wallet,
  PanelLeftClose,
  PanelLeftOpen,
  ChevronRight,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useUiStore } from '@/store/uiStore';

interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
  end?: boolean;
}

const TOP_ITEMS: NavItem[] = [
  { to: '/', label: '工作台', icon: LayoutDashboard, end: true },
  { to: '/resume', label: '简历中心', icon: FileText },
  { to: '/decision', label: 'JD分析', icon: ClipboardList },
  { to: '/interview', label: 'AI面试', icon: MessagesSquare },
  { to: '/graph', label: 'Agent可视化', icon: Share2 },
  { to: '/profile', label: '用户能力追踪', icon: LineChart },
];

const ADMIN_ITEMS: NavItem[] = [
  { to: '/admin/models', label: '模型管理', icon: Cpu },
  { to: '/admin/kb', label: '知识库管理', icon: Database },
  { to: '/admin/billing', label: '计费与额度', icon: Wallet },
];

function NavRow({ item, collapsed }: { item: NavItem; collapsed: boolean }): JSX.Element {
  return (
    <NavLink
      to={item.to}
      end={item.end}
      className={({ isActive }) =>
        [
          'group relative flex items-center gap-3 h-9 rounded-md px-3 text-base transition-colors duration-base ease-smooth',
          isActive
            ? 'bg-brand-soft text-brand font-medium'
            : 'text-ink-soft hover:bg-surface-2',
          collapsed ? 'justify-center px-0' : '',
        ].join(' ')
      }
    >
      {({ isActive }) => (
        <>
          {isActive && (
            <span className="absolute left-0 top-1/2 h-5 w-[3px] -translate-y-1/2 rounded-r bg-brand" />
          )}
          <item.icon size={18} className="shrink-0" />
          {!collapsed && <span className="truncate">{item.label}</span>}
        </>
      )}
    </NavLink>
  );
}

/** 左侧一级导航（7 组：6 平级 + 系统管理分组）。docs §1 / §3.4。 */
export function SideNav(): JSX.Element {
  const collapsed = useUiStore((s) => s.sidebarCollapsed);
  const toggle = useUiStore((s) => s.toggleSidebar);
  const [adminOpen, setAdminOpen] = useState(true);

  return (
    <aside
      className="flex h-full shrink-0 flex-col border-r border-line bg-surface"
      style={{ width: collapsed ? 'var(--nav-width-collapsed)' : 'var(--nav-width)' }}
    >
      <div className="flex h-14 items-center gap-2 border-b border-line px-4">
        <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-brand text-sm font-bold text-white">
          C
        </div>
        {!collapsed && <span className="text-md font-semibold text-ink">Chiron Agent</span>}
      </div>

      <nav className="flex-1 overflow-y-auto px-3 py-4">
        <div className="space-y-1">
          {TOP_ITEMS.map((it) => (
            <NavRow key={it.to} item={it} collapsed={collapsed} />
          ))}
        </div>

        <div className="mt-4">
          {!collapsed && (
            <button
              type="button"
              onClick={() => setAdminOpen((v) => !v)}
              className="flex w-full items-center justify-between rounded-md px-3 py-1.5 text-2xs font-semibold uppercase tracking-wide text-ink-faint transition-colors hover:text-ink-soft"
            >
              <span>系统管理</span>
              <ChevronRight
                size={14}
                className={
                  adminOpen
                    ? 'rotate-90 transition-transform duration-base'
                    : 'transition-transform duration-base'
                }
              />
            </button>
          )}
          {(collapsed || adminOpen) && (
            <div className="mt-1 space-y-1">
              {ADMIN_ITEMS.map((it) => (
                <NavRow key={it.to} item={it} collapsed={collapsed} />
              ))}
            </div>
          )}
        </div>
      </nav>

      <div className="border-t border-line p-3">
        <button
          type="button"
          onClick={toggle}
          aria-label={collapsed ? '展开侧栏' : '折叠侧栏'}
          className="flex h-9 w-full items-center gap-3 rounded-md px-3 text-base text-ink-soft transition-colors hover:bg-surface-2"
        >
          {collapsed ? <PanelLeftOpen size={18} /> : <PanelLeftClose size={18} />}
          {!collapsed && <span>收起</span>}
        </button>
      </div>
    </aside>
  );
}
