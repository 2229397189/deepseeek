import { type ReactNode } from 'react';

/** 语义色调（docs §3.7：薄弱=danger 红 / 达标=ok / 待确认=amber / 普通=info|brand） */
export type Tone = 'brand' | 'info' | 'ok' | 'warn' | 'danger' | 'amber' | 'neutral';

export interface BadgeProps {
  tone?: Tone;
  children: ReactNode;
  className?: string;
}

const TONE_CLASS: Record<Tone, string> = {
  brand: 'bg-brand-soft text-brand',
  info: 'bg-info-soft text-info',
  ok: 'bg-ok-soft text-ok',
  warn: 'bg-warn-soft text-warn',
  danger: 'bg-danger-soft text-danger',
  amber: 'bg-warn-soft text-amber',
  neutral: 'bg-surface-2 text-ink-soft',
};

/** 小标签：圆角 sm(4px)，text-2xs。用于状态/维度标记。 */
export function Badge({ tone = 'neutral', children, className = '' }: BadgeProps): JSX.Element {
  const classes = [
    'inline-flex items-center rounded-sm px-1.5 py-0.5 text-2xs font-medium leading-none',
    TONE_CLASS[tone],
    className,
  ].join(' ');
  return <span className={classes}>{children}</span>;
}
