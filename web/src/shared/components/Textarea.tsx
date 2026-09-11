import { type TextareaHTMLAttributes, forwardRef, useId } from 'react';

export interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  /** 错误提示文案 */
  error?: string;
}

/**
 * 多行文本框。与 Input 同态：surface 底 + line 描边，聚焦 brand + 环。
 */
export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea(
  { error, className = '', disabled = false, id, rows = 4, ...rest }: TextareaProps,
  ref,
): JSX.Element {
  const autoId = useId();
  const textareaId = id ?? autoId;
  const stateClass = error
    ? 'border-danger'
    : 'border-line focus:border-brand focus:shadow-focus';
  const classes = [
    'w-full px-3 py-2 rounded-md bg-surface border text-base resize-y',
    'transition-colors duration-base ease-smooth',
    'outline-none placeholder:text-ink-faint',
    stateClass,
    disabled ? 'bg-surface-2 text-ink-faint cursor-not-allowed' : 'text-ink',
    className,
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <div className="w-full">
      <textarea
        ref={ref}
        id={textareaId}
        disabled={disabled}
        rows={rows}
        className={classes}
        {...rest}
      />
      {error && (
        <p className="mt-1 text-danger text-xs" role="alert">
          {error}
        </p>
      )}
    </div>
  );
});
