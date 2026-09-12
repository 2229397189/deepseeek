import { useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useUiStore } from '@/store/uiStore';
import { formatDateTime, formatNumber } from '@/lib/format';
import { Card } from '@/shared/components/Card';
import { Skeleton } from '@/shared/components/Skeleton';
import { listDocuments, reindexKb, searchKb, uploadDocument } from './api';
import type { KbHit } from './types';
import { HybridSearch } from './HybridSearch';
import { DocPreview } from './DocPreview';

/** 概览统计小卡片（数据一律 font-mono，docs §3.1）。 */
function StatCard({ label, value, hint }: { label: string; value: ReactNode; hint?: string }): JSX.Element {
  return (
    <Card>
      <p className="text-2xs uppercase tracking-wide text-ink-faint">{label}</p>
      <p className="mt-1 font-mono text-2xl text-ink">{value}</p>
      {hint && <p className="mt-0.5 text-2xs text-ink-faint">{hint}</p>}
    </Card>
  );
}

/** 知识库管理：混合检索 + 文档预览/上传/重建索引。docs §6.7。 */
export function AdminKbPage(): JSX.Element {
  const qc = useQueryClient();
  const pushToast = useUiStore((s) => s.pushToast);

  const [query, setQuery] = useState('');
  const [submitted, setSubmitted] = useState('');
  const [topK, setTopK] = useState(10);
  const [titleFilter, setTitleFilter] = useState('');
  const [docIdFilter, setDocIdFilter] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const documentsQ = useQuery({
    queryKey: ['kbDocuments', titleFilter, docIdFilter],
    queryFn: () =>
      listDocuments({
        title: titleFilter || undefined,
        documentId: docIdFilter || undefined,
      }),
  });

  // 概览统计：全部从 listDocuments() 列表推导，不依赖专门的 stats 端点。
  const docs = documentsQ.data ?? [];
  const totalDocs = docs.length;
  const totalChunks = docs.reduce((sum: number, d) => sum + (d.chunkCount ?? 0), 0);
  const lastIndexedAt = docs.reduce((max: number, d) => {
    const t = d.updatedAt ? new Date(d.updatedAt).getTime() : NaN;
    return Number.isNaN(t) ? max : Math.max(max, t);
  }, NaN);

  const searchQ = useQuery({
    queryKey: ['kbSearch', submitted, topK],
    queryFn: () => searchKb(submitted, topK),
    enabled: submitted.trim().length > 0,
  });

  const uploadMut = useMutation({
    mutationFn: (file: File) => uploadDocument(file),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['kbDocuments'] });
      pushToast({ tone: 'ok', message: '文档已上传' });
    },
    onError: (err: Error) => pushToast({ tone: 'danger', message: err.message }),
  });

  const reindexMut = useMutation({
    mutationFn: () => reindexKb(),
    onSuccess: (res) => {
      pushToast({
        tone: res.accepted ? 'ok' : 'warn',
        message: res.accepted ? '重建索引任务已受理' : '重建索引未受理，请稍后再试',
      });
    },
    onError: (err: Error) => pushToast({ tone: 'danger', message: err.message }),
  });

  const hits: KbHit[] = searchQ.data ?? [];
  const selectedContent = selectedId
    ? (hits.find((h) => h.documentId === selectedId)?.content ?? null)
    : null;

  return (
    <div className="space-y-4">
      <h1 className="text-lg font-semibold text-ink">知识库管理</h1>

      <Card title="概览统计">
        {documentsQ.isLoading ? (
          <Skeleton className="h-16 w-full" />
        ) : documentsQ.isError ? (
          <p className="text-sm text-ink-faint">统计暂不可用</p>
        ) : (
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <StatCard label="文档总数" value={formatNumber(totalDocs)} />
            <StatCard label="切片/向量总数" value={formatNumber(totalChunks)} hint="按 chunkCount 求和" />
            <StatCard label="总存储大小" value="—" hint="后端未返回大小字段" />
            <StatCard
              label="最近索引时间"
              value={Number.isNaN(lastIndexedAt) ? '—' : formatDateTime(lastIndexedAt)}
              hint="按文档 updatedAt"
            />
          </div>
        )}
      </Card>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <DocPreview
          documents={documentsQ.data ?? []}
          loading={documentsQ.isLoading}
          selectedId={selectedId}
          onSelect={setSelectedId}
          content={selectedContent}
          titleFilter={titleFilter}
          onTitleFilterChange={setTitleFilter}
          docIdFilter={docIdFilter}
          onDocIdFilterChange={setDocIdFilter}
          onUpload={(file) => uploadMut.mutate(file)}
          uploading={uploadMut.isPending}
          onReindex={() => reindexMut.mutate()}
          reindexing={reindexMut.isPending}
        />

        <HybridSearch
          query={query}
          onQueryChange={setQuery}
          topK={topK}
          onTopKChange={setTopK}
          hits={hits}
          loading={searchQ.isFetching}
          onSearch={() => setSubmitted(query)}
          onSelectDoc={setSelectedId}
        />
      </div>
    </div>
  );
}
