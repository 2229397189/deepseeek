import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Send } from 'lucide-react';
import { Button } from '@/shared/components/Button';
import { Textarea } from '@/shared/components/Textarea';
import { Badge } from '@/shared/components/Badge';
import { Skeleton } from '@/shared/components/Skeleton';
import { useUiStore } from '@/store/uiStore';
import { askSession } from './api';
import type { DecisionMessage } from './types';

interface AskBoxProps {
  sessionId: string;
}

/** 针对该 JD 的 RAG 追问。docs §6.3。 */
export function AskBox({ sessionId }: AskBoxProps): JSX.Element {
  const [question, setQuestion] = useState('');
  const [messages, setMessages] = useState<DecisionMessage[]>([]);
  const pushToast = useUiStore((s) => s.pushToast);

  const mut = useMutation({
    mutationFn: (q: string) => askSession(sessionId, q),
    onSuccess: (res) => setMessages(res.messages),
    onError: (err: Error) => pushToast({ tone: 'danger', message: err.message }),
  });

  const send = () => {
    const q = question.trim();
    if (!q || mut.isPending) return;
    mut.mutate(q);
    setQuestion('');
  };

  return (
    <div className="space-y-3">
      <h4 className="text-sm font-semibold text-ink">针对该 JD 追问</h4>

      <div className="max-h-64 space-y-3 overflow-auto rounded-md bg-surface-2 p-3">
        {messages.length === 0 && (
          <p className="text-xs text-ink-faint">例如：「这个岗位最看重哪三项能力？」</p>
        )}
        {messages.map((m, i) => (
          <div
            key={i}
            className={m.role === 'user' ? 'text-right' : 'text-left'}
          >
            <span
              className={[
                'inline-block max-w-[85%] rounded-md px-3 py-2 text-sm',
                m.role === 'user'
                  ? 'bg-brand text-white'
                  : 'bg-surface text-ink shadow-card',
              ].join(' ')}
            >
              {m.content}
            </span>
          </div>
        ))}
        {mut.isPending && <Skeleton className="h-10 w-2/3" />}
      </div>

      {messages.some((m) => m.role === 'assistant') && (
        <div className="flex flex-wrap gap-1.5">
          <Badge tone="info">RAG 命中已展示</Badge>
        </div>
      )}

      <div className="flex items-end gap-2">
        <Textarea
          rows={2}
          placeholder="输入追问内容…"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              send();
            }
          }}
        />
        <Button size="sm" loading={mut.isPending} onClick={send}>
          <Send size={15} /> 发送
        </Button>
      </div>
    </div>
  );
}
