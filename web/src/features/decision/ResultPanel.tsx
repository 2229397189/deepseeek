import { Badge } from '@/shared/components/Badge';
import type { Tone } from '@/types/common';
import type { AnalysisVO } from './types';

const BAND: Record<string, { label: string; tone: Tone }> = {
  HIGH: { label: '高匹配', tone: 'ok' },
  MID: { label: '可争取', tone: 'amber' },
  LOW: { label: '差距明显', tone: 'danger' },
};

function Section({ title, children }: { title: string; children: React.ReactNode }): JSX.Element {
  return (
    <div>
      <h4 className="mb-2 text-sm font-semibold text-ink">{title}</h4>
      {children}
    </div>
  );
}

/** 分析结果面板：综合匹配分 / 风险 / 薄弱点 / 结论动作。docs §6.3。 */
export function ResultPanel({ analysis }: { analysis: AnalysisVO }): JSX.Element {
  const band = BAND[analysis.scoreBand] ?? BAND.LOW;
  const score = Math.round(analysis.score);

  return (
    <div className="space-y-5">
      <div className="flex items-center gap-4">
        <div className="flex h-20 w-20 shrink-0 flex-col items-center justify-center rounded-lg bg-surface-2">
          <span className="font-mono text-3xl font-semibold leading-none text-ink">{score}</span>
          <span className="mt-1 text-2xs text-ink-faint">综合匹配分</span>
        </div>
        <div className="min-w-0 flex-1">
          <Badge tone={band.tone}>{band.label}</Badge>
          <p className="mt-2 text-base text-ink-soft">{analysis.conclusion}</p>
        </div>
      </div>

      {analysis.risks.length > 0 && (
        <Section title="风险">
          <ul className="list-inside list-disc space-y-1 text-sm text-ink-soft">
            {analysis.risks.map((r, i) => (
              <li key={i} className="text-danger">
                {r}
              </li>
            ))}
          </ul>
        </Section>
      )}

      {analysis.weakPoints.length > 0 && (
        <Section title="薄弱点">
          <div className="flex flex-wrap gap-1.5">
            {analysis.weakPoints.map((w, i) => (
              <Badge key={i} tone="danger">
                {w}
              </Badge>
            ))}
          </div>
        </Section>
      )}

      <Section title="结论动作">
        <p className="whitespace-pre-wrap text-base text-ink-soft">{analysis.advice}</p>
      </Section>
    </div>
  );
}
