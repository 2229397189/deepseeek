import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Plus, MessagesSquare, ArrowRight, Trash2 } from 'lucide-react';
import { Card } from '@/shared/components/Card';
import { Button } from '@/shared/components/Button';
import { Badge } from '@/shared/components/Badge';
import { Dialog } from '@/shared/components/Dialog';
import { EmptyState } from '@/shared/components/EmptyState';
import { Skeleton } from '@/shared/components/Skeleton';
import { ErrorState } from '@/shared/components/ErrorState';
import { formatDateTime, formatScore } from '@/lib/format';
import { useUiStore } from '@/store/uiStore';
import { deleteInterviewSession, listInterviewSessions, startInterview } from './api';
import { listResumes } from '../resume/api';
import { ModelPicker } from '../workbench/ModelPicker';
import type { InterviewSession } from './types';

function statusMeta(row: InterviewSession): { tone: 'neutral' | 'brand' | 'ok'; label: string } {
  if (row.status === 'FINISHED') return { tone: 'ok', label: '已结束' };
  if (row.status === 'IN_PROGRESS') return { tone: 'brand', label: '进行中' };
  return { tone: 'neutral', label: '已创建' };
}

/** AI 面试列表页（卡片布局 + 归档）。docs §6.4。 */
export function InterviewListPage(): JSX.Element {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const pushToast = useUiStore((s) => s.pushToast);
  const resumeAssetId = useUiStore((s) => s.selectedResumeAssetId);
  const setResumeAssetId = useUiStore((s) => s.setSelectedResumeAssetId);
  const modelValue = useUiStore((s) => s.modelPickerValue);

  const [archiveId, setArchiveId] = useState<string | null>(null);

  const listQ = useQuery({
    queryKey: ['interviewSessions'],
    queryFn: listInterviewSessions,
  });

  const resumesQ = useQuery({
    queryKey: ['resumes-for-interview'],
    queryFn: listResumes,
  });

  const startMut = useMutation({
    mutationFn: () =>
      startInterview({ resumeAssetId: resumeAssetId ?? '', model: modelValue }),
    onSuccess: (res) => {
      qc.invalidateQueries({ queryKey: ['interviewSessions'] });
      navigate(`/interview/${res.sessionId}`);
    },
    onError: (err: Error) => pushToast({ tone: 'danger', message: err.message }),
  });

  const archiveMut = useMutation({
    mutationFn: (id: string) => deleteInterviewSession(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['interviewSessions'] });
      pushToast({ tone: 'ok', message: '面试会话已归档' });
    },
    onError: (err: Error) => pushToast({ tone: 'danger', message: err.message }),
  });

  const start = () => {
    if (!resumeAssetId) {
      pushToast({ tone: 'warn', message: '请先选择一份简历再开始模拟面试' });
      return;
    }
    startMut.mutate();
  };

  const confirmArchive = () => {
    if (!archiveId) return;
    archiveMut.mutate(archiveId);
    setArchiveId(null);
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-lg font-semibold text-ink">模拟面试</h1>
        <div className="flex flex-wrap items-center gap-3">
          {/* P1-12 简历选择（驱动出题方向） */}
          <select
            value={resumeAssetId ?? ''}
            onChange={(e) => setResumeAssetId(e.target.value || null)}
            className="h-9 rounded-md border border-line bg-surface px-3 text-base text-ink outline-none transition-colors hover:bg-surface-2"
            aria-label="选择简历"
          >
            <option value="">选择简历…</option>
            {(resumesQ.data ?? []).map((r) => (
              <option key={r.assetId} value={r.assetId}>
                {r.originalName ?? r.assetId}
              </option>
            ))}
          </select>
          {/* P1-13 模型选择（复用工作台 ModelPicker，写入 uiStore.modelPickerValue） */}
          <ModelPicker />
          <Button onClick={start} loading={startMut.isPending}>
            <Plus size={15} /> 开始模拟面试
          </Button>
        </div>
      </div>

      {listQ.isLoading ? (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <Skeleton key={i} className="h-32 w-full" />
          ))}
        </div>
      ) : listQ.isError ? (
        <ErrorState message="面试列表加载失败" onRetry={() => listQ.refetch()} />
      ) : !listQ.data || listQ.data.length === 0 ? (
        <EmptyState
          title="还没有模拟面试"
          description="基于你的简历发起一场 AI 逐题面试，结束后生成能力报告"
          icon={<MessagesSquare size={22} />}
          action={
            <Button onClick={start} loading={startMut.isPending}>
              <Plus size={15} /> 开始模拟面试
            </Button>
          }
        />
      ) : (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
          {listQ.data.map((row) => {
            const meta = statusMeta(row);
            return (
              <Card key={row.sessionId} className="flex flex-col">
                <div className="flex items-start justify-between gap-2">
                  <h3 className="truncate text-md font-semibold text-ink">{row.jobTitle ?? '未命名面试'}</h3>
                  <Badge tone={meta.tone}>{meta.label}</Badge>
                </div>
                <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-ink-faint">
                  <span className="font-mono">{formatDateTime(row.createdAt ?? '')}</span>
                  {typeof row.score === 'number' && (
                    <span className="font-mono text-ink-soft">得分 {formatScore(row.score)}</span>
                  )}
                </div>
                <div className="mt-4 flex items-center justify-between border-t border-line pt-3">
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => setArchiveId(row.sessionId)}
                    aria-label="归档"
                  >
                    <Trash2 size={14} /> 归档
                  </Button>
                  <Button
                    size="sm"
                    variant="secondary"
                    onClick={() => navigate(`/interview/${row.sessionId}`)}
                  >
                    进入 <ArrowRight size={14} />
                  </Button>
                </div>
              </Card>
            );
          })}
        </div>
      )}

      <Dialog
        open={archiveId !== null}
        onClose={() => setArchiveId(null)}
        title="归档这场面试？"
        confirmText="归档"
        confirmVariant="danger"
        confirmLoading={archiveMut.isPending}
        onConfirm={confirmArchive}
      >
        归档后该面试会话将从列表移除，历史消息与报告仍保留，可后续找回。
      </Dialog>
    </div>
  );
}
