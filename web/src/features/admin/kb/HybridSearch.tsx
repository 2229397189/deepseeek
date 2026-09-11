import { Card } from '@/shared/components/Card';
import { Button } from '@/shared/components/Button';
import { Input } from '@/shared/components/Input';
import { Badge } from '@/shared/components/Badge';
import { Skeleton } from '@/shared/components/Skeleton';
import type { KbHit } from './types';

interface HybridSearchProps {
  query: string;
  onQueryChange: (value: string) => void;
  topK: number;
  onTopKChange: (value: number) => void;
  hits: KbHit[];
  loading: boolean;
  onSearch: () => void;
  onSelectDoc: (documentId: string) => void;
}

/** 混合检索（向量 + 全文，展示 vectorScore / ftsScore）。docs §6.7。 */
export function HybridSearch({
  query,
  onQueryChange,
  topK,
  onTopKChange,
  hits,
  loading,
  onSearch,
  onSelectDoc,
}: HybridSearchProps): JSX.Element {
  return (
    <Card title="混合检索（向量 + 全文）">
      <div className="flex flex-wrap items-end gap-2">
        <div className="min-w-[200px] flex-1">
          <Input
            placeholder="检索 Java"
            value={query}
            onChange={(e) => onQueryChange(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') onSearch();
            }}
          />
        </div>
        <div className="w-28">
          <Input
            type="number"
            min={1}
            max={50}
            value={topK}
            onChange={(e) => onTopKChange(Number(e.target.value))}
            suffix="条"
          />
        </div>
        <Button loading={loading} onClick={onSearch}>
          检索
        </Button>
      </div>

      <div className="mt-4 space-y-2">
        {loading ? (
          <Skeleton className="h-32 w-full" />
        ) : hits.length === 0 ? (
          <p className="py-6 text-center text-xs text-ink-faint">
            输入关键词开始检索，例如「检索 Java」
          </p>
        ) : (
          hits.map((hit, i) => (
            <button
              key={`${hit.documentId}-${i}`}
              type="button"
              onClick={() => onSelectDoc(hit.documentId)}
              className="w-full rounded-md border border-line bg-surface p-3 text-left transition-colors hover:border-brand"
            >
              <div className="flex items-center justify-between gap-2">
                <span className="truncate text-sm font-medium text-ink">{hit.title}</span>
                <span className="shrink-0 font-mono text-xs text-brand">
                  {hit.score.toFixed(4)}
                </span>
              </div>
              <p className="mt-1 line-clamp-2 text-xs text-ink-soft">{hit.content}</p>
              <div className="mt-2 flex flex-wrap items-center gap-1.5">
                <Badge tone="info">向量 {hit.vectorScore.toFixed(4)}</Badge>
                <Badge tone="amber">全文 {hit.ftsScore.toFixed(4)}</Badge>
                <span className="font-mono text-2xs text-ink-faint">{hit.documentId}</span>
              </div>
            </button>
          ))
        )}
      </div>
    </Card>
  );
}
