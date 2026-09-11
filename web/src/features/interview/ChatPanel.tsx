import { useEffect, useRef } from 'react';
import { Send } from 'lucide-react';
import { Button } from '@/shared/components/Button';
import { Textarea } from '@/shared/components/Textarea';
import { VoiceInput } from './VoiceInput';
import type { MessageVO } from './types';

interface ChatPanelProps {
  messages: MessageVO[];
  value: string;
  onChange: (value: string) => void;
  onSend: () => void;
  sending: boolean;
}

/** 面试对话区：AI 逐题提问 + 用户回答 + 语音转写。docs §6.4。 */
export function ChatPanel({ messages, value, onChange, onSend, sending }: ChatPanelProps): JSX.Element {
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  return (
    <div className="flex h-full flex-col">
      <div className="min-h-0 flex-1 space-y-3 overflow-auto p-4">
        {messages.length === 0 && (
          <p className="py-8 text-center text-xs text-ink-faint">
            AI 面试官将率先抛出第一个问题，请认真作答。
          </p>
        )}
        {messages.map((m, i) => (
          <div key={i} className={m.role === 'user' ? 'text-right' : 'text-left'}>
            <span
              className={[
                'inline-block max-w-[85%] whitespace-pre-wrap rounded-md px-3 py-2 text-sm',
                m.role === 'user'
                  ? 'bg-brand text-white'
                  : 'bg-surface text-ink shadow-card',
              ].join(' ')}
            >
              {m.content}
            </span>
          </div>
        ))}
        <div ref={endRef} />
      </div>

      <div className="border-t border-line p-3">
        <div className="flex items-end gap-2">
          <Textarea
            rows={2}
            placeholder="输入你的回答，或点击麦克风语音转写…"
            value={value}
            onChange={(e) => onChange(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                onSend();
              }
            }}
          />
          <div className="flex flex-col gap-2">
            <VoiceInput value={value} onChange={onChange} disabled={sending} />
            <Button size="sm" loading={sending} onClick={onSend}>
              <Send size={15} />
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
