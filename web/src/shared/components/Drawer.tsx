import { useEffect, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';

export interface DrawerProps {
  open: boolean;
  onClose: () => void;
  title?: ReactNode;
  /** 默认宽 480px（模型表单等） */
  width?: number;
  children: ReactNode;
  footer?: ReactNode;
}

/**
 * 右侧滑入抽屉。遮罩 bg-black/30；进出 translate-x + dur-base ease-smooth；
 * ESC / 点遮罩关闭；打开时锁定 body 滚动（docs §3.7）。
 */
export function Drawer({ open, onClose, title, width = 480, children, footer }: DrawerProps): JSX.Element {
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
    <div className="fixed inset-0 z-50" role="dialog" aria-modal="true">
      <div
        className="absolute inset-0 bg-black/30 transition-opacity duration-base ease-smooth"
        onClick={onClose}
        aria-hidden
      />
      <div
        className="absolute right-0 top-0 h-full bg-surface shadow-pop flex flex-col"
        style={{
          width,
          maxWidth: '100vw',
          transform: open ? 'translateX(0)' : 'translateX(100%)',
          transition: 'transform var(--dur-base) var(--ease-smooth)',
        }}
      >
        <div className="flex items-center justify-between px-6 h-14 border-b border-line shrink-0">
          <h2 className="text-lg font-semibold text-ink">{title}</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="关闭"
            className="text-ink-faint hover:text-ink transition-colors duration-fast"
          >
            <X size={18} />
          </button>
        </div>
        <div className="flex-1 overflow-y-auto px-6 py-5">{children}</div>
        {footer && (
          <div className="px-6 py-4 border-t border-line flex justify-end gap-2 shrink-0">{footer}</div>
        )}
      </div>
    </div>,
    document.body,
  );
}
