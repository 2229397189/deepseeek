import { useRef } from 'react';
import type { ChangeEvent, ReactNode } from 'react';
import { Upload, RefreshCw } from 'lucide-react';
import { Card } from '@/shared/components/Card';
import { Button } from '@/shared/components/Button';
import { Input } from '@/shared/components/Input';
import { Skeleton } from '@/shared/components/Skeleton';
import { EmptyState } from '@/shared/components/EmptyState';
import { formatDateTime } from '@/lib/format';
import type { KbDocument } from './types';

interface DocPreviewProps {
  documents: KbDocument[];
  loading: boolean;
  selectedId: string | null;
  onSelect: (documentId: string) => void;
  content: string | null;
  titleFilter: string;
  onTitleFilterChange: (v: string) => void;
  docIdFilter: string;
  onDocIdFilterChange: (v: string) => void;
  onUpload: (file: File) => void;
  uploading: boolean;
  onReindex: () => void;
  reindexing: boolean;
  /** P2-23 需要在正文中高亮的检索词（已提交的检索 query）。 */
  highlightQuery?: string;
}

/**
 * P2-23 把正文里命中检索词的片段包成 <mark>。
 * 用 indexOf 切片而非正则：不必转义用户输入，也不会因元字符抛错。
 */
function highlightNodes(content: string, query: string): ReactNode {
  const q = query.trim();
  if (!q) return content;
  const lower = content.toLowerCase();
  const lq = q.toLowerCase();
  const out: ReactNode[] = [];
  let i = 0;
  while (i < content.length) {
    const idx = lower.indexOf(lq, i);
    if (idx === -1) {
      out.push(content.slice(i));
      break;
    }
    if (idx > i) out.push(content.slice(i, idx));
    out.push(
      <mark key={idx} className="rounded bg-brand-soft px-0.5 text-ink">
        {content.slice(idx, idx + q.length)}
      </mark>,
    );
    i = idx + q.length;
  }
  return out;
}

/** 统计命中次数（同样基于 indexOf，避免正则）。 */
function countMatches(content: string, query: string): number {
  const q = query.trim().toLowerCase();
  if (!q) return 0;
  const lower = content.toLowerCase();
  let count = 0;
  let idx = lower.indexOf(q);
  while (idx !== -1) {
    count += 1;
    idx = lower.indexOf(q, idx + q.length);
  }
  return count;
}

/** 文档列表 + 过滤（标题 / document-id）+ 正文预览 + 上传 + 重建索引。docs §6.7。 */
export function DocPreview({
  documents,
  loading,
  selectedId,
  onSelect,
  content,
  titleFilter,
  onTitleFilterChange,
  docIdFilter,
  onDocIdFilterChange,
  onUpload,
  uploading,
  onReindex,
  reindexing,
  highlightQuery = '',
}: DocPreviewProps): JSX.Element {
  const fileRef = useRef<HTMLInputElement>(null);
  const matchCount = content ? countMatches(content, highlightQuery) : 0;

  const onFile = (e: ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (f) onUpload(f);
    e.target.value = '';
  };

  return (
    <Card title="知识库文档">
      <div className="space-y-3">
        <div className="flex flex-wrap items-center gap-2">
          <div className="min-w-[140px] flex-1">
            <Input
              placeholder="按标题过滤"
              value={titleFilter}
              onChange={(e) => onTitleFilterChange(e.target.value)}
            />
          </div>
          <div className="min-w-[140px] flex-1">
            <Input
              placeholder="按 document-id 过滤"
              value={docIdFilter}
              onChange={(e) => onDocIdFilterChange(e.target.value)}
            />
          </div>
        </div>

        <div className="flex items-center gap-2">
          <Button size="sm" variant="secondary" onClick={() => fileRef.current?.click()} loading={uploading}>
            <Upload size={15} /> 上传文档
          </Button>
          <Button size="sm" variant="secondary" onClick={onReindex} loading={reindexing}>
            <RefreshCw size={15} /> 重建索引
          </Button>
          <input ref={fileRef} type="file" className="hidden" onChange={onFile} />
        </div>

        <div className="max-h-64 overflow-auto rounded-md border border-line">
          {loading ? (
            <div className="p-3">
              <Skeleton className="h-40 w-full" />
            </div>
          ) : documents.length === 0 ? (
            <div className="p-3">
              <EmptyState title="暂无文档" description="上传文档或执行检索以开始" />
            </div>
          ) : (
            <ul className="divide-y divide-line">
              {documents.map((d) => (
                <li key={d.documentId}>
                  <button
                    type="button"
                    onClick={() => onSelect(d.documentId)}
                    className={[
                      'w-full px-3 py-2 text-left transition-colors hover:bg-surface-2',
                      d.documentId === selectedId ? 'bg-brand-soft' : '',
                    ].join(' ')}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="truncate text-sm text-ink">{d.title}</span>
                      <span className="shrink-0 font-mono text-2xs text-ink-faint">
                        {d.chunkCount} 块
                      </span>
                    </div>
                    <div className="flex items-center justify-between">
                      <span className="font-mono text-2xs text-ink-faint">{d.documentId}</span>
                      <span className="font-mono text-2xs text-ink-faint">
                        {formatDateTime(d.updatedAt ?? '')}
                      </span>
                    </div>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div>
          <div className="mb-1 flex items-center justify-between gap-2">
            <p className="text-2xs font-semibold uppercase tracking-wide text-ink-faint">
              正文预览
            </p>
            {content && highlightQuery.trim() && (
              <span className="font-mono text-2xs text-ink-faint">命中 {matchCount} 处</span>
            )}
          </div>
          <div className="max-h-56 overflow-auto whitespace-pre-wrap rounded-md bg-surface-2 p-3 text-xs text-ink-soft">
            {content
              ? highlightNodes(content, highlightQuery)
              : '选择一篇文档或检索结果以查看正文。'}
          </div>
        </div>
      </div>
    </Card>
  );
}
