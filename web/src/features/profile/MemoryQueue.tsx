import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Check, X, PenLine, Undo2 } from 'lucide-react';
import { Button } from '@/shared/components/Button';
import { Badge } from '@/shared/components/Badge';
import { Textarea } from '@/shared/components/Textarea';
import { EmptyState } from '@/shared/components/EmptyState';
import { formatDateTime } from '@/lib/format';
import { useUiStore } from '@/store/uiStore';
import { confirmMemory, correctMemory } from './api';
import type { LongTermMemory } from './types';

/** 待确认长期记忆队列（证据对照 + 确认 / 拒绝 / 纠正 / 撤回）。docs §6.9。 */
export function MemoryQueue({ memories }: { memories: LongTermMemory[] }): JSX.Element {
  const qc = useQueryClient();
  const pushToast = useUiStore((s) => s.pushToast);
  const [corrected, setCorrected] = useState<Record<string, string>>({});
  const [dismissed, setDismissed] = useState<Record<string, boolean>>({});

  const confirmMut = useMutation({
    mutationFn: (id: string) => confirmMemory(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['memories'] });
      pushToast({ tone: 'ok', message: '已确认，长期记忆已生效' });
    },
    onError: (err: Error) => pushToast({ tone: 'danger', message: err.message }),
  });

  const correctMut = useMutation({
    mutationFn: ({ id, content }: { id: string; content: string }) => correctMemory(id, content),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['memories'] });
      pushToast({ tone: 'ok', message: '已提交纠正后的表述' });
    },
    onError: (err: Error) => pushToast({ tone: 'danger', message: err.message }),
  });

  const pending = memories.filter((m) => m.status === 'PENDING' && !dismissed[m.id]);
  const handled = memories.filter((m) => m.status !== 'PENDING');
  const dismissedItems = memories.filter((m) => m.status === 'PENDING' && dismissed[m.id]);

  if (memories.length === 0) {
    return (
      <EmptyState
        title="暂无长期记忆"
        description="AI 会在简历解析与模拟面试中沉淀你的长期记忆，待你确认或纠正"
      />
    );
  }

  return (
    <div className="space-y-4">
      {pending.length === 0 && handled.length === 0 && dismissedItems.length === 0 && (
        <p className="text-sm text-ink-faint">暂无待确认项。</p>
      )}

      {pending.map((m) => (
        <div key={m.id} className="rounded-lg border border-line bg-surface p-4">
          <div className="mb-2 flex items-center justify-between">
            <Badge tone="amber">待确认</Badge>
            <span className="font-mono text-2xs text-ink-faint">
              {formatDateTime(m.createdAt ?? '')}
            </span>
          </div>

          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
            <div>
              <p className="mb-1 text-2xs font-semibold uppercase tracking-wide text-ink-faint">
                证据 / 原始表述
              </p>
              <p className="rounded-md bg-surface-2 p-2 text-sm text-ink-soft">{m.content}</p>
            </div>
            <div>
              <p className="mb-1 text-2xs font-semibold uppercase tracking-wide text-ink-faint">
                纠正后的表述
              </p>
              <Textarea
                rows={3}
                placeholder="如需纠正，请在此填写准确表述后点击「纠正」"
                value={corrected[m.id] ?? ''}
                onChange={(e) => setCorrected((prev) => ({ ...prev, [m.id]: e.target.value }))}
              />
            </div>
          </div>

          <div className="mt-3 flex flex-wrap gap-2">
            <Button size="sm" loading={confirmMut.isPending} onClick={() => confirmMut.mutate(m.id)}>
              <Check size={15} /> 确认
            </Button>
            <Button
              size="sm"
              variant="secondary"
              loading={correctMut.isPending}
              disabled={(corrected[m.id] ?? '').trim().length === 0}
              onClick={() =>
                correctMut.mutate({ id: m.id, content: (corrected[m.id] ?? '').trim() })
              }
            >
              <PenLine size={15} /> 纠正
            </Button>
            <Button
              size="sm"
              variant="ghost"
              onClick={() => {
                setDismissed((prev) => ({ ...prev, [m.id]: true }));
                pushToast({ tone: 'info', message: '已拒绝（本地暂不展示）' });
              }}
            >
              <X size={15} /> 拒绝
            </Button>
          </div>
        </div>
      ))}

      {handled.map((m) => (
        <div key={m.id} className="rounded-lg border border-line bg-surface p-4">
          <div className="mb-2 flex items-center justify-between">
            <Badge tone={m.status === 'CONFIRMED' ? 'ok' : 'info'}>
              {m.status === 'CONFIRMED' ? '已确认' : '已纠正'}
            </Badge>
            <span className="font-mono text-2xs text-ink-faint">
              {formatDateTime(m.createdAt ?? '')}
            </span>
          </div>
          <p className="text-sm text-ink-soft">{m.content}</p>
        </div>
      ))}

      {dismissedItems.map((m) => (
        <div
          key={m.id}
          className="flex items-center justify-between rounded-lg border border-dashed border-line bg-surface-2 p-3"
        >
          <span className="text-xs text-ink-faint">已拒绝：{m.content}</span>
          <Button
            size="sm"
            variant="ghost"
            onClick={() => setDismissed((prev) => ({ ...prev, [m.id]: false }))}
          >
            <Undo2 size={14} /> 撤回
          </Button>
        </div>
      ))}
    </div>
  );
}
