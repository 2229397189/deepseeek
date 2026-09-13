import { type ReactNode } from 'react';

export type TagTone = 'brand' | 'info' | 'ok' | 'warn' | 'danger' | 'amber' | 'neutral';

export interface TagProps {
  tone?: TagTone;
  /** 可关闭时显示的叉，触发 onClose */
  closable?: boolean;
  onClose?: () => void;
  children: ReactNode;
  className?: string;
}

const TAG_TONE_CLASS: Record<TagTone, string> = {
  brand: 'bg-brand-soft text-brand',
  info: 'bg-info-soft text-info',
  ok: 'bg-ok-soft text-ok',
  warn: 'bg-warn-soft text-warn',
  danger: 'bg-danger-soft text-danger',
  amber: 'bg-warn-soft text-amber',
  neutral: 'bg-surface-2 text-ink-soft',
};

/** 标签胶囊：圆角 md(8px)，text-xs。可关闭（如已选技能/筛选条件）。 */
export function Tag({ tone = 'neutral', closable = false, onClose, children, className = '' }: TagProps): JSX.Element {
  const classes = [
    'inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-xs font-medium leading-none',
    TAG_TONE_CLASS[tone],
    className,
  ].join(' ');
  return (
    <span className={classes}>
      {children}
      {closable && (
        <button
          type="button"
          onClick={onClose}
          aria-label="移除"
          className="ml-0.5 rounded-sm text-current/70 hover:text-current hover:bg-black/5 transition-colors duration-fast"
        >
          <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden>
            <path d="M1 1l8 8M9 1l-8 8" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
          </svg>
        </button>
      )}
    </span>
  );
}
