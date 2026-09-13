import { useEffect, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { Button, type ButtonVariant } from './Button';

export interface DialogProps {
  open: boolean;
  onClose: () => void;
  title?: ReactNode;
  children?: ReactNode;
  /** 确认按钮文案 */
  confirmText?: string;
  /** 取消按钮文案（为 null 则不渲染取消） */
  cancelText?: string | null;
  confirmVariant?: ButtonVariant;
  onConfirm?: () => void;
  /** 确认按钮加载态 */
  confirmLoading?: boolean;
  /** 自定义底部（覆盖默认按钮） */
  footer?: ReactNode;
}

/**
 * 居中弹窗。max-w-md + surface + 轻 pop 阴影；标题 text-lg semibold；
 * 确认/取消用 主/次 按钮，危险操作用 danger 变体（docs §3.7）。
 */
export function Dialog({
  open,
  onClose,
  title,
  children,
  confirmText = '确认',
  cancelText = '取消',
  confirmVariant = 'primary',
  onConfirm,
  confirmLoading = false,
  footer,
}: DialogProps): JSX.Element {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = '';
    };
  }, [open, onClose]);

  if (!open) return <></>;

  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4" role="dialog" aria-modal="true">
      <div
        className="absolute inset-0 bg-black/30 transition-opacity duration-base ease-smooth"
        onClick={onClose}
        aria-hidden
      />
      <div className="relative bg-surface rounded-lg shadow-pop w-full max-w-md">
        {title && (
          <div className="px-6 pt-5 pb-3">
            <h2 className="text-lg font-semibold text-ink">{title}</h2>
          </div>
        )}
        <div className="px-6 pb-5 text-base text-ink-soft">{children}</div>
        <div className="px-6 py-4 border-t border-line flex justify-end gap-2">
          {footer ?? (
            <>
              {cancelText !== null && (
                <Button variant="secondary" size="sm" onClick={onClose} disabled={confirmLoading}>
                  {cancelText}
                </Button>
              )}
              <Button variant={confirmVariant} size="sm" onClick={onConfirm} loading={confirmLoading}>
                {confirmText}
              </Button>
            </>
          )}
        </div>
      </div>
    </div>,
    document.body,
  );
}
