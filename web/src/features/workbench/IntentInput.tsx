import type { FormEvent } from 'react';
import { Sparkles } from 'lucide-react';
import { Button } from '@/shared/components/Button';
import { Textarea } from '@/shared/components/Textarea';

interface IntentInputProps {
  value: string;
  onChange: (value: string) => void;
  onSubmit: () => void;
  loading: boolean;
}

/** 工作台大输入框。占位文案见 docs §6.1。 */
export function IntentInput({ value, onChange, onSubmit, loading }: IntentInputProps): JSX.Element {
  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (value.trim() && !loading) onSubmit();
  };

  return (
    <form onSubmit={handleSubmit}>
      <div className="rounded-lg border border-line bg-surface p-2 shadow-card transition-colors duration-base focus-within:border-brand focus-within:shadow-focus">
        <Textarea
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder="发职位链接、贴 JD、贴简历，或直接说你评估什么"
          rows={4}
          className="border-0 shadow-none focus:shadow-none"
        />
        <div className="mt-2 flex items-center justify-between px-1">
          <span className="text-2xs text-ink-faint">
            系统将自动识别意图并路由到对应页面
          </span>
          <Button type="submit" loading={loading}>
            <Sparkles size={15} />
            {loading ? '识别中…' : '开始'}
          </Button>
        </div>
      </div>
    </form>
  );
}
