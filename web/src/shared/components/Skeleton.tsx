import { type HTMLAttributes } from 'react';

export interface SkeletonProps extends HTMLAttributes<HTMLDivElement> {
  /** 预设高度类，如 h-12 / h-40 */
  className?: string;
}

/**
 * 加载骨架。surface-2 浅底 + animate-pulse（docs §3.7）。
 * 按内容块尺寸占位，避免整屏 spinner。
 */
export function Skeleton({ className = 'h-4 w-full', ...rest }: SkeletonProps): JSX.Element {
  return (
    <div
      aria-hidden
      className={['bg-surface-2 rounded-md animate-pulse', className].filter(Boolean).join(' ')}
      {...rest}
    />
  );
}

/** 多行文本骨架快捷组合（行高 h-4，末行收窄）。 */
export function SkeletonText({ lines = 3 }: { lines?: number }): JSX.Element {
  return (
    <div className="space-y-2">
      {Array.from({ length: lines }).map((_, i) => (
        <Skeleton key={i} className={i === lines - 1 ? 'h-4 w-2/3' : 'h-4 w-full'} />
      ))}
    </div>
  );
}
