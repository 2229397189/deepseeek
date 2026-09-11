import { type InputHTMLAttributes, forwardRef, useId } from 'react';

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  /** 错误提示文案（存在时切换 danger 描边） */
  error?: string;
  /** 左侧辅助文字（如单位） */
  prefix?: string;
  /** 右侧辅助文字 */
  suffix?: string;
}

/**
 * 受控输入框。聚焦 brand 描边 + 聚焦环；错误态 danger 描边。
 * disabled 用 surface-2 浅底 + 弱化文字（docs §3.7）。
 */
export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { error, prefix, suffix, className = '', disabled = false, id, ...rest }: InputProps,
  ref,
): JSX.Element {
  const autoId = useId();
  const inputId = id ?? autoId;
  const stateClass = error
    ? 'border-danger'
    : 'border-line focus:border-brand focus:shadow-focus';
  const wrapperClass = [
    'flex items-center h-9 px-3 rounded-md bg-surface border text-base',
    'transition-colors duration-base ease-smooth',
    stateClass,
    disabled ? 'bg-surface-2 text-ink-faint cursor-not-allowed' : 'text-ink',
    className,
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <div className="w-full">
      <div className={wrapperClass}>
        {prefix && <span className="mr-2 text-ink-faint text-sm shrink-0">{prefix}</span>}
        <input
          ref={ref}
          id={inputId}
          disabled={disabled}
          className="w-full bg-transparent outline-none placeholder:text-ink-faint disabled:cursor-not-allowed"
          {...rest}
        />
        {suffix && <span className="ml-2 text-ink-faint text-sm shrink-0">{suffix}</span>}
      </div>
      {error && (
        <p className="mt-1 text-danger text-xs" role="alert">
          {error}
        </p>
      )}
    </div>
  );
});
