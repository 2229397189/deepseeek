import { createPortal } from 'react-dom';
import { useUiStore } from '@/store/uiStore';
import { Toast } from './Toast';

/**
 * 全局 Toast 容器。从 uiStore 读取 Toast 队列，右上角堆叠渲染。
 * 由 main.tsx 包裹全应用（docs §4.1 / §3.7）。
 */
export function ToastProvider({ children }: { children: React.ReactNode }): JSX.Element {
  const toasts = useUiStore((s) => s.toasts);
  const dismissToast = useUiStore((s) => s.dismissToast);

  return (
    <>
      {children}
      {createPortal(
        <div className="fixed top-4 right-4 z-[60] flex flex-col gap-2 items-end pointer-events-none">
          <div className="flex flex-col gap-2 items-end pointer-events-auto">
            {toasts.map((t) => (
              <Toast key={t.id} toast={t} onClose={() => dismissToast(t.id)} />
            ))}
          </div>
        </div>,
        document.body,
      )}
    </>
  );
}
