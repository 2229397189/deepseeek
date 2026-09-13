import { useEffect } from 'react';
import { CheckCircle2, XCircle, AlertTriangle, Info, X } from 'lucide-react';
import { type ToastItem } from '@/store/uiStore';

/** 语义色 -> 左竖条色 + 图标 */
const TONE_STYLE: Record<ToastItem['tone'], { bar: string; icon: JSX.Element }> = {
  ok: { bar: 'bg-ok', icon: <CheckCircle2 size={16} className="text-ok" /> },
  danger: { bar: 'bg-danger', icon: <XCircle size={16} className="text-danger" /> },
  warn: { bar: 'bg-warn', icon: <AlertTriangle size={16} className="text-warn" /> },
  info: { bar: 'bg-info', icon: <Info size={16} className="text-info" /> },
};

/**
 * 单条 Toast。右上角堆叠，单列最大宽 360px；
 * 左侧语义色竖条 + 自动消失（默认 4s）+ 可手动关闭（docs §3.7）。
 */
export function Toast({ toast, onClose }: { toast: ToastItem; onClose: () => void }): JSX.Element {
  const duration = toast.duration ?? 4000;
  useEffect(() => {
    if (duration <= 0) return;
    const t = window.setTimeout(onClose, duration);
    return () => window.clearTimeout(t);
  }, [duration, onClose]);

  const tone = TONE_STYLE[toast.tone];
  return (
    <div
      role="status"
      className="relative flex items-start gap-2 w-[360px] max-w-[calc(100vw-32px)] bg-surface border border-line shadow-pop rounded-md pl-3 pr-2 py-2.5 overflow-hidden"
    >
      <span className={`absolute left-0 top-0 h-full w-1 ${tone.bar}`} aria-hidden />
      <span className="mt-0.5 shrink-0">{tone.icon}</span>
      <p className="flex-1 text-base text-ink leading-5 break-words">{toast.message}</p>
      <button
        type="button"
        onClick={onClose}
        aria-label="关闭"
        className="shrink-0 text-ink-faint hover:text-ink transition-colors duration-fast"
      >
        <X size={14} />
      </button>
    </div>
  );
}
