import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useUiStore } from '@/store/uiStore';
import { listDocuments, reindexKb, searchKb, uploadDocument } from './api';
import type { KbHit } from './types';
import { HybridSearch } from './HybridSearch';
import { DocPreview } from './DocPreview';

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
