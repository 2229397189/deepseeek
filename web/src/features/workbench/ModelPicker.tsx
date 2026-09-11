import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Check, ChevronDown } from 'lucide-react';
import { useUiStore } from '@/store/uiStore';
import { getAvailableModels } from './api';

/** 模型下拉：默认 deepseek-chat（来自 GET /models/available）。docs §6.1。 */
export function ModelPicker(): JSX.Element {
  const value = useUiStore((s) => s.modelPickerValue);
  const setValue = useUiStore((s) => s.setModelPickerValue);
  const { data } = useQuery({ queryKey: ['availableModels'], queryFn: getAvailableModels });
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  const options = data?.models ?? [];
  const current = options.find((o) => o.id === value) ?? {
    id: value,
    label: value,
    provider: '',
  };

  // 若当前值不在列表且后端给出默认，则切换为默认。
  useEffect(() => {
    if (data?.default && !options.some((o) => o.id === value)) {
      setValue(data.default);
    }
  }, [data, options, value, setValue]);

  useEffect(() => {
    if (!open) return;
    const onClick = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onClick);
    return () => document.removeEventListener('mousedown', onClick);
  }, [open]);

  return (
    <div className="relative" ref={ref}>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex h-9 items-center gap-2 rounded-md border border-line bg-surface px-3 text-base text-ink transition-colors duration-base hover:bg-surface-2"
      >
        <span className="text-ink-soft">模型</span>
        <span className="font-mono text-sm">{current.label}</span>
        <ChevronDown size={14} className="text-ink-faint" />
      </button>
      {open && (
        <div className="absolute right-0 top-11 z-50 w-56 rounded-md border border-line bg-surface py-1 shadow-pop">
          {options.length === 0 && (
            <p className="px-3 py-2 text-xs text-ink-faint">暂无可用模型</p>
          )}
          {options.map((o) => (
            <button
              key={o.id}
              type="button"
              onClick={() => {
                setValue(o.id);
                setOpen(false);
              }}
              className="flex w-full items-center justify-between px-3 py-2 text-base text-ink-soft transition-colors hover:bg-surface-2"
            >
              <span className="flex flex-col items-start">
                <span className="font-mono text-sm text-ink">{o.label}</span>
                <span className="text-2xs text-ink-faint">{o.provider}</span>
              </span>
              {o.id === value && <Check size={15} className="text-brand" />}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
