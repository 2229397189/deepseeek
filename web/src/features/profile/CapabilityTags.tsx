import { Badge } from '@/shared/components/Badge';
import { EmptyState } from '@/shared/components/EmptyState';
import type { Tone } from '@/types/common';
import type { CapabilityTag } from './types';

function levelTone(level: string): Tone {
  if (/弱|WEAK|欠缺|不足/i.test(level)) return 'danger';
  if (/优秀|强|熟练|GOOD|掌握/i.test(level)) return 'ok';
  return 'brand';
}

/** 能力标签云（java_basic / JD 未覆盖 等）。docs §6.9 / §1。 */
export function CapabilityTags({ tags }: { tags: CapabilityTag[] }): JSX.Element {
  if (tags.length === 0) {
    return (
      <EmptyState
        title="暂无能力标签"
        description="完成一次简历解析或模拟面试后，系统会自动沉淀你的能力画像"
      />
    );
  }

  return (
    <div className="flex flex-wrap gap-2">
      {tags.map((t) => (
        <span
          key={`${t.tag}-${t.category}`}
          className="inline-flex items-center gap-1.5 rounded-md border border-line bg-surface px-2.5 py-1.5"
        >
          <Badge tone={levelTone(t.level)}>{t.level}</Badge>
          <span className="text-sm text-ink">{t.tag}</span>
          <span className="text-2xs text-ink-faint">{t.category}</span>
          <span className="font-mono text-2xs text-ink-faint">
            {(t.confidence * 100).toFixed(0)}%
          </span>
          <span className="text-2xs text-ink-faint">· {t.source}</span>
        </span>
      ))}
    </div>
  );
}
