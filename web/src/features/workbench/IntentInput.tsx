import type { FormEvent } from 'react';
import { Plus, Mic, ArrowUp } from 'lucide-react';
import { Textarea } from '@/shared/components/Textarea';
import { ModelPicker } from './ModelPicker';

interface IntentInputProps {
  value: string;
  onChange: (value: string) => void;
  onSubmit: () => void;
  loading: boolean;
}

/** 工作台大输入卡：文本区 + 底部行（模型选择 / 加号 / 黑色麦克风 / 发送圆钮）。 */
export function IntentInput({ value, onChange, onSubmit, loading }: IntentInputProps): JSX.Element {
  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (value.trim() && !loading) onSubmit();
  };

  return (
    <form onSubmit={handleSubmit}>
      <div className="rounded-2xl border border-line bg-surface p-3 shadow-card transition-colors duration-base focus-within:border-ink/30">
        <Textarea
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder="发岗位链接、贴 JD、贴简历，或直接说你想评估什么…"
          rows={4}
          className="border-0 shadow-none focus:shadow-none"
        />
        <div className="mt-2 flex items-center justify-between gap-2 px-1">
          <div className="flex items-center gap-2">
            <ModelPicker />
            <button
              type="button"
              aria-label="添加附件"
              className="flex h-8 w-8 items-center justify-center rounded-full text-ink-soft transition-colors hover:bg-surface-2"
            >
              <Plus size={18} />
            </button>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              aria-label="语音输入"
              className="flex h-9 w-9 items-center justify-center rounded-full bg-ink text-white transition-colors hover:bg-ink-soft"
            >
              <Mic size={17} />
            </button>
            <button
              type="submit"
              disabled={!value.trim() || loading}
              aria-label="发送"
              className="flex h-9 w-9 items-center justify-center rounded-full bg-ink text-white transition-colors hover:bg-ink-soft disabled:opacity-40"
            >
              <ArrowUp size={18} />
            </button>
          </div>
        </div>
      </div>
    </form>
  );
}
