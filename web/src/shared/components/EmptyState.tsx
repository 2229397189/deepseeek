import { type ReactNode } from 'react';
import { Inbox } from 'lucide-react';

export interface EmptyStateProps {
  /** 主文案（text-md text-ink-soft） */
  title: ReactNode;
  /** 次文案（text-xs） */
  description?: ReactNode;
  /** 线性图标，默认 Inbox */
  icon?: ReactNode;
  /** 主操作按钮 */
  action?: ReactNode;
  className?: string;
}

/**
 * 空态。居中蓝图网格容器（.bg-blueprint）+ 线性图标 + 主/次文案 + 可选主按钮。
 * 呼应「工程化、可观测」隐喻（docs §3.7）。
 */
export function EmptyState({
  title,
  description,
  icon,
  action,
  className = '',
}: EmptyStateProps): JSX.Element {
  return (
    <div
      className={[
        'bg-blueprint flex flex-col items-center justify-center text-center rounded-lg',
        'px-6 py-16 border border-line',
        className,
      ].join(' ')}
    >
      <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-md bg-brand-soft text-brand">
        {icon ?? <Inbox size={22} aria-hidden />}
      </div>
      <p className="text-md font-medium text-ink-soft">{title}</p>
      {description && <p className="mt-1 max-w-sm text-xs text-ink-faint">{description}</p>}
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}
