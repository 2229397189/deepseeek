import { CheckCircle2, XCircle, Loader2, Circle } from 'lucide-react';
import type { Tone } from '@/types/common';
import { formatDuration } from '@/lib/format';
import type { PipelineStep } from './types';

const STEP_LABELS: Record<string, string> = {
  parse_jd: '解析 JD',
  load_profile: '加载简历画像',
  match_score: '匹配打分',
  '生成建议': '生成建议',
};

function statusTone(status: PipelineStep['status']): Tone {
  switch (status) {
    case 'SUCCEEDED':
      return 'ok';
    case 'FAILED':
      return 'danger';
    case 'RUNNING':
      return 'brand';
    default:
      return 'neutral';
  }
}

function StatusIcon({ status }: { status: PipelineStep['status'] }): JSX.Element {
  if (status === 'SUCCEEDED') return <CheckCircle2 size={18} className="text-ok" />;
  if (status === 'FAILED') return <XCircle size={18} className="text-danger" />;
  if (status === 'RUNNING')
    return <Loader2 size={18} className="animate-spin text-brand" />;
  return <Circle size={18} className="text-ink-faint" />;
}

interface PipelineStepperProps {
  steps: PipelineStep[];
  running: boolean;
}

/** 流水线步骤条。docs §6.3（parse_jd→load_profile→match_score→生成建议）。 */
export function PipelineStepper({ steps, running }: PipelineStepperProps): JSX.Element {
  return (
    <ol className="space-y-3">
      {steps.map((step, i) => {
        const tone = statusTone(step.status);
        return (
          <li key={`${step.name}-${i}`} className="flex items-start gap-3">
            <div className="mt-0.5">
              <StatusIcon status={step.status} />
            </div>
            <div className="min-w-0 flex-1">
              <div className="flex items-center justify-between gap-2">
                <p className="text-base font-medium text-ink">
                  {STEP_LABELS[step.name] ?? step.name}
                </p>
                {typeof step.elapsedMs === 'number' && (
                  <span className="font-mono text-xs text-ink-faint">
                    {formatDuration(step.elapsedMs)}
                  </span>
                )}
              </div>
              {step.detail && (
                <p className={tone === 'danger' ? 'text-xs text-danger' : 'text-xs text-ink-soft'}>
                  {step.detail}
                </p>
              )}
            </div>
          </li>
        );
      })}
      {running && steps.length === 0 && (
        <li className="flex items-center gap-2 text-sm text-ink-soft">
          <Loader2 size={16} className="animate-spin text-brand" /> 流水线运行中…
        </li>
      )}
    </ol>
  );
}
