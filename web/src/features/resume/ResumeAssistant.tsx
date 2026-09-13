import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Sparkles, Wand2, ArrowRight, Undo2 } from 'lucide-react';
import { Button } from '@/shared/components/Button';
import { Textarea } from '@/shared/components/Textarea';
import { Badge } from '@/shared/components/Badge';
import { SkeletonText } from '@/shared/components/Skeleton';
import { useUiStore } from '@/store/uiStore';
import { polishResume } from './api';
import type { PolishVO } from './types';

interface ResumeAssistantProps {
  assetId: string;
  selectedText: string;
  jobTitle?: string;
  onApply: (polished: string) => void;
  onRevert: () => void;
}

/** 右栏：选中文本 -> 润色 / 按岗位定制 -> 对比 -> 应用 / 回退。docs §6.2。 */
export function ResumeAssistant({
  assetId,
  selectedText,
  jobTitle,
  onApply,
  onRevert,
}: ResumeAssistantProps): JSX.Element {
  const [mode, setMode] = useState<'POLISH' | 'CUSTOMIZE'>('POLISH');
  const [instruction, setInstruction] = useState('');
  const [result, setResult] = useState<PolishVO | null>(null);
  const [applied, setApplied] = useState(false);
  const pushToast = useUiStore((s) => s.pushToast);

  const mutation = useMutation({
    mutationFn: () =>
      polishResume({
        assetId,
        selectedText,
        mode,
        instruction: instruction || undefined,
        jobTitle,
      }),
    onSuccess: (r) => {
      setResult(r);
      setApplied(false);
    },
    onError: (err: Error) => pushToast({ tone: 'danger', message: err.message }),
  });

  const canRun = selectedText.trim().length > 0 && !mutation.isPending;

  return (
    <div className="flex h-full flex-col">
      <div className="border-b border-line px-3 py-2">
        <p className="text-xs text-ink-soft">
          已选文本
          <span className="ml-1 font-mono text-ink-faint">
            {selectedText.length > 0 ? `${selectedText.length} 字` : '（在左栏选中）'}
          </span>
        </p>
        {selectedText && (
          <p className="mt-1 line-clamp-2 rounded bg-surface-2 px-2 py-1 text-xs text-ink-soft">
            {selectedText}
          </p>
        )}
      </div>

      <div className="space-y-3 border-b border-line p-3">
        <div className="flex gap-2">
          <Button
            size="sm"
            variant={mode === 'POLISH' ? 'primary' : 'secondary'}
            onClick={() => setMode('POLISH')}
          >
            <Wand2 size={14} /> 润色
          </Button>
          <Button
            size="sm"
            variant={mode === 'CUSTOMIZE' ? 'primary' : 'secondary'}
            onClick={() => setMode('CUSTOMIZE')}
          >
            <Sparkles size={14} /> 按岗位定制
          </Button>
        </div>
        {mode === 'CUSTOMIZE' && (
          <Textarea
            rows={2}
            placeholder="附加要求（可选），如：突出团队协作与后端架构经验"
            value={instruction}
            onChange={(e) => setInstruction(e.target.value)}
          />
        )}
        <Button block size="sm" loading={mutation.isPending} disabled={!canRun} onClick={() => mutation.mutate()}>
          {mutation.isPending ? '生成中…' : '生成建议'}
        </Button>
      </div>

      <div className="min-h-0 flex-1 overflow-auto p-3">
        {mutation.isPending && <SkeletonText lines={5} />}
        {!mutation.isPending && !result && (
          <p className="text-xs text-ink-faint">在左栏选中一段文字，点击上方按钮获取 AI 优化建议。</p>
        )}
        {result && (
          <div className="space-y-3">
            <div>
              <p className="mb-1 text-2xs font-semibold uppercase tracking-wide text-ink-faint">
                优化前
              </p>
              <p className="rounded-md bg-surface-2 p-2 text-xs text-ink-soft">{result.original}</p>
            </div>
            <div className="flex justify-center">
              <ArrowRight size={16} className="text-brand" />
            </div>
            <div>
              <p className="mb-1 text-2xs font-semibold uppercase tracking-wide text-ink-faint">
                优化后
              </p>
              <p className="rounded-md border border-brand-soft bg-brand-soft/40 p-2 text-xs text-ink">
                {result.polished}
              </p>
            </div>

            {result.reasons.length > 0 && (
              <div>
                <p className="mb-1 text-2xs font-semibold uppercase tracking-wide text-ink-faint">
                  优化点
                </p>
                <ul className="list-inside list-disc space-y-0.5 text-xs text-ink-soft">
                  {result.reasons.map((r, i) => (
                    <li key={i}>{r}</li>
                  ))}
                </ul>
              </div>
            )}

            {result.matchedSkills.length > 0 && (
              <div className="flex flex-wrap gap-1.5">
                {result.matchedSkills.map((s) => (
                  <Badge key={s} tone="ok">
                    {s}
                  </Badge>
                ))}
              </div>
            )}

            <div className="flex gap-2 pt-1">
              <Button
                size="sm"
                disabled={applied}
                onClick={() => {
                  onApply(result.polished);
                  setApplied(true);
                  pushToast({ tone: 'ok', message: '已应用优化，记得保存正文' });
                }}
              >
                应用
              </Button>
              <Button size="sm" variant="secondary" disabled={!applied} onClick={onRevert}>
                <Undo2 size={14} /> 回退
              </Button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
