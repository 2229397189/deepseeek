import { useRef, useState } from 'react';
import { Textarea } from '@/shared/components/Textarea';
import { renderMarkdown } from './markdown';

interface MarkdownEditorProps {
  value: string;
  onChange: (value: string) => void;
  onSelectText: (text: string) => void;
}

/** 左栏：Markdown 编辑（受控 textarea）+ 即时渲染切换。docs §6.2。 */
export function MarkdownEditor({
  value,
  onChange,
  onSelectText,
}: MarkdownEditorProps): JSX.Element {
  const [tab, setTab] = useState<'edit' | 'preview'>('edit');
  const taRef = useRef<HTMLTextAreaElement>(null);

  const handleSelect = () => {
    const ta = taRef.current;
    if (!ta) return;
    const sel = ta.value.slice(ta.selectionStart, ta.selectionEnd);
    onSelectText(sel);
  };

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-1 border-b border-line px-2">
        {(['edit', 'preview'] as const).map((t) => (
          <button
            key={t}
            type="button"
            onClick={() => setTab(t)}
            className={[
              'px-3 py-2 text-sm transition-colors duration-base',
              tab === t
                ? 'border-b-2 border-brand font-medium text-brand'
                : 'text-ink-soft hover:text-ink',
            ].join(' ')}
          >
            {t === 'edit' ? 'Markdown' : '即时渲染'}
          </button>
        ))}
      </div>
      <div className="min-h-0 flex-1 overflow-auto">
        {tab === 'edit' ? (
          <Textarea
            ref={taRef}
            value={value}
            onChange={(e) => onChange(e.target.value)}
            onSelect={handleSelect}
            onClick={handleSelect}
            onKeyUp={handleSelect}
            className="h-full min-h-[420px] resize-none rounded-none border-0 font-mono text-sm leading-6 focus:shadow-none"
          />
        ) : (
          <div
            className="prose-resume p-4 text-base text-ink"
            dangerouslySetInnerHTML={{ __html: renderMarkdown(value) }}
          />
        )}
      </div>
    </div>
  );
}
