import { type HTMLAttributes, type ReactNode } from 'react';

export interface CardProps extends HTMLAttributes<HTMLDivElement> {
  /** 分区标题（可选，渲染为 text-md font-semibold） */
  title?: ReactNode;
  /** 标题右侧操作区 */
  extra?: ReactNode;
  /** 是否带内边距（默认 p-6） */
  bodyPadding?: boolean;
}

/**
 * 卡片容器。bg-surface + 1px line 描边 + 轻阴影；hover 不浮起（保持冷静，docs §3.7）。
 */
export function Card({
  title,
  extra,
  bodyPadding = true,
  className = '',
  children,
  ...rest
}: CardProps): JSX.Element {
  const classes = [
    'bg-surface border border-line rounded-lg shadow-card',
    className,
  ]
    .filter(Boolean)
    .join(' ');
  return (
    <div className={classes} {...rest}>
      {(title || extra) && (
        <div className="flex items-center justify-between px-6 pt-5 pb-3 border-b border-line">
          {title && <h3 className="text-md font-semibold text-ink">{title}</h3>}
          {extra && <div className="flex items-center gap-2">{extra}</div>}
        </div>
      )}
      <div className={bodyPadding ? 'p-6' : ''}>{children}</div>
    </div>
  );
}
