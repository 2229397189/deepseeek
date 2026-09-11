import { type ButtonHTMLAttributes, forwardRef } from 'react';

/** 按钮变体（docs §3.7） */
export type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'ghost';
/** 按钮尺寸（docs §3.7） */
export type ButtonSize = 'sm' | 'md' | 'lg';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** 是否占满父容器宽度 */
  block?: boolean;
  /** 加载态：禁用并展示省略脉冲 */
  loading?: boolean;
}

const VARIANT_CLASS: Record<ButtonVariant, string> = {
  primary: 'bg-brand text-white hover:bg-brand-hover active:bg-brand-strong',
  secondary: 'bg-surface border border-line text-ink hover:bg-surface-2',
  danger: 'bg-danger text-white hover:bg-[#B53737] active:bg-[#9E2F2F]',
  ghost: 'text-ink-soft hover:bg-surface-2',
};

const SIZE_CLASS: Record<ButtonSize, string> = {
  sm: 'h-7 px-3 text-xs',
  md: 'h-9 px-4 text-base',
  lg: 'h-11 px-6 text-md',
};

/**
 * 通用按钮。遵循设计系统：主色森林绿、紧凑圆角 md(8px)、动效 base。
 * 禁用态统一 opacity-50 + cursor-not-allowed。
 */
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    variant = 'primary',
    size = 'md',
    block = false,
    loading = false,
    disabled = false,
    className = '',
    children,
    ...rest
  }: ButtonProps,
  ref,
): JSX.Element {
  const isDisabled = disabled || loading;
  const classes = [
    'inline-flex items-center justify-center gap-2 rounded-md font-medium',
    'transition-colors duration-base ease-smooth select-none',
    'focus-visible:outline-none focus-visible:shadow-focus',
    'disabled:opacity-50 disabled:cursor-not-allowed',
    VARIANT_CLASS[variant],
    SIZE_CLASS[size],
    block ? 'w-full' : '',
    className,
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <button
      ref={ref}
      type={rest.type ?? 'button'}
      className={classes}
      disabled={isDisabled}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading && (
        <span
          aria-hidden
          className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-r-transparent opacity-70"
        />
      )}
      {children}
    </button>
  );
});
