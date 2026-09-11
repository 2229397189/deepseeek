import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Plus, MessagesSquare } from 'lucide-react';
import { Card } from '@/shared/components/Card';
import { Button } from '@/shared/components/Button';
import { Table } from '@/shared/components/Table';
import { Badge } from '@/shared/components/Badge';
import { EmptyState } from '@/shared/components/EmptyState';
import { Skeleton } from '@/shared/components/Skeleton';
import { ErrorState } from '@/shared/components/ErrorState';
import { formatDateTime } from '@/lib/format';
import { useUiStore } from '@/store/uiStore';
import { listInterviewSessions, startInterview } from './api';
import type { InterviewSession } from './types';

/** AI 面试列表页。docs §6.4。 */
export function InterviewListPage(): JSX.Element {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const pushToast = useUiStore((s) => s.pushToast);
  const resumeAssetId = useUiStore((s) => s.selectedResumeAssetId);

  const listQ = useQuery({
    queryKey: ['interviewSessions'],
    queryFn: listInterviewSessions,
  });

  const startMut = useMutation({
    mutationFn: () => startInterview({ resumeAssetId: resumeAssetId ?? '' }),
    onSuccess: (res) => {
      qc.invalidateQueries({ queryKey: ['interviewSessions'] });
      navigate(`/interview/${res.sessionId}`);
    },
    onError: (err: Error) => pushToast({ tone: 'danger', message: err.message }),
  });

  const start = () => {
    if (!resumeAssetId) {
      pushToast({ tone: 'warn', message: '请先在「简历中心」选择一份简历' });
      navigate('/resume');
      return;
    }
    startMut.mutate();
  };

  const columns = [
    {
      key: 'jobTitle',
      header: '岗位',
      render: (row: InterviewSession) => row.jobTitle ?? '未命名面试',
    },
    {
      key: 'createdAt',
      header: '日期',
      render: (row: InterviewSession) => (
        <span className="font-mono text-xs text-ink-soft">
          {formatDateTime(row.createdAt ?? '')}
        </span>
      ),
    },
    {
      key: 'score',
      header: '得分',
      numeric: true,
      render: (row: InterviewSession) =>
        typeof row.score === 'number' ? (
          <span className="font-mono">{row.score}</span>
        ) : (
          <span className="text-ink-faint">—</span>
        ),
    },
    {
      key: 'status',
      header: '状态',
      render: (row: InterviewSession) => {
        const tone =
          row.status === 'FINISHED'
            ? 'ok'
            : row.status === 'IN_PROGRESS'
              ? 'brand'
              : 'neutral';
        const label =
          row.status === 'FINISHED'
            ? '已结束'
            : row.status === 'IN_PROGRESS'
              ? '进行中'
              : '已创建';
        return <Badge tone={tone}>{label}</Badge>;
      },
    },
    {
      key: 'op',
      header: '操作',
      render: (row: InterviewSession) => (
        <Button size="sm" variant="ghost" onClick={() => navigate(`/interview/${row.sessionId}`)}>
          进入
        </Button>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold text-ink">模拟面试</h1>
        <Button onClick={start} loading={startMut.isPending}>
          <Plus size={15} /> 开始第一场模拟面试
        </Button>
      </div>

      {listQ.isLoading ? (
        <Skeleton className="h-40 w-full" />
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
        <Card bodyPadding={false}>
          <Table
            columns={columns}
            data={listQ.data}
            rowKey={(r) => r.sessionId}
            onRowClick={(r) => navigate(`/interview/${r.sessionId}`)}
          />
        </Card>
      )}
    </div>
  );
}
