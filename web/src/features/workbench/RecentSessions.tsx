import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { MessagesSquare, ClipboardList, ArrowUpRight } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { Card } from '@/shared/components/Card';
import { Badge } from '@/shared/components/Badge';
import { Skeleton } from '@/shared/components/Skeleton';
import { EmptyState } from '@/shared/components/EmptyState';
import { formatDateTime, formatScore } from '@/lib/format';
import { listInterviewSessions } from '@/features/interview/api';
import { listSessions as listDecisionSessions } from '@/features/decision/api';
import type { InterviewSession } from '@/features/interview/types';
import type { SessionBrief } from '@/features/decision/types';

type RecentItem = {
  key: string;
  kind: 'interview' | 'jd';
  title: string;
  status: string;
  createdAt?: string;
  score?: number;
  to: string;
};

const INTERVIEW_STATUS: Record<string, { label: string; tone: 'neutral' | 'info' | 'ok' }> = {
  CREATED: { label: '未开始', tone: 'neutral' },
  IN_PROGRESS: { label: '进行中', tone: 'info' },
  FINISHED: { label: '已完成', tone: 'ok' },
};

const JD_STATUS: Record<string, { label: string; tone: 'neutral' | 'info' | 'ok' | 'danger' }> = {
  PENDING: { label: '排队中', tone: 'neutral' },
  RUNNING: { label: '分析中', tone: 'info' },
  SUCCEEDED: { label: '已完成', tone: 'ok' },
  FAILED: { label: '失败', tone: 'danger' },
};

function statusBadge(item: RecentItem): { label: string; tone: 'neutral' | 'info' | 'ok' | 'danger' } {
  if (item.kind === 'interview') {
    return INTERVIEW_STATUS[item.status] ?? { label: item.status, tone: 'neutral' };
  }
  return JD_STATUS[item.status] ?? { label: item.status, tone: 'neutral' };
}

const KIND_META: Record<RecentItem['kind'], { icon: LucideIcon; tint: string; label: string }> = {
  interview: { icon: MessagesSquare, tint: 'bg-ok-soft text-ok', label: '模拟面试' },
  jd: { icon: ClipboardList, tint: 'bg-info-soft text-info', label: 'JD 分析' },
};

/**
 * 工作台「最近」区：合并拉取最近面试与 JD 分析会话，按时间倒序展示，
 * 点击跳转到对应会话。docs §6.1（评估记录增强）。
 */
export function RecentSessions(): JSX.Element {
  const navigate = useNavigate();
  const q = useQuery({
    queryKey: ['recent-sessions'],
    queryFn: async (): Promise<RecentItem[]> => {
      const [ivs, jds] = await Promise.all([listInterviewSessions(), listDecisionSessions()]);
      const items: RecentItem[] = [
        ...ivs.map((s: InterviewSession) => ({
          key: `iv-${s.sessionId}`,
          kind: 'interview' as const,
          title: s.jobTitle ?? '模拟面试',
          status: s.status,
          createdAt: s.createdAt,
          score: s.score,
          to: `/interview/${s.sessionId}`,
        })),
        ...jds.map((s: SessionBrief) => ({
          key: `jd-${s.id}`,
          kind: 'jd' as const,
          title: s.jobTitle ?? s.title ?? 'JD 分析',
          status: s.status,
          createdAt: s.createdAt,
          to: '/decision',
        })),
      ];
      items.sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''));
      return items.slice(0, 6);
    },
  });

  return (
    <Card title="最近评估" extra={<span className="text-xs text-ink-faint">面试 + JD 分析</span>}>
      {q.isLoading && (
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} className="h-20 w-full" />
          ))}
        </div>
      )}
      {q.isError && (
        <p className="text-xs text-danger">最近评估加载失败：{(q.error as Error).message}</p>
      )}
      {q.isSuccess && q.data.length === 0 && (
        <EmptyState title="还没有评估记录" description="从上方入口开始第一场简历优化、JD 分析或模拟面试" />
      )}
      {q.isSuccess && q.data.length > 0 && (
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
          {q.data.map((item) => {
            const meta = KIND_META[item.kind];
            const Icon = meta.icon;
            const badge = statusBadge(item);
            return (
              <button
                key={item.key}
                type="button"
                onClick={() => navigate(item.to)}
                className="group flex items-center gap-3 rounded-md border border-line bg-surface-2 px-4 py-3 text-left transition-colors duration-base hover:border-brand"
              >
                <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-md ${meta.tint}`}>
                  <Icon size={18} />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="flex items-center gap-2">
                    <span className="truncate text-md font-medium text-ink">{item.title}</span>
                    <Badge tone={badge.tone}>{badge.label}</Badge>
                  </span>
                  <span className="mt-0.5 flex items-center gap-2 text-xs text-ink-faint">
                    <span>{meta.label}</span>
                    {item.createdAt && <span className="font-mono">{formatDateTime(item.createdAt)}</span>}
                    {typeof item.score === 'number' && (
                      <span className="font-mono text-ink-soft">得分 {formatScore(item.score)}</span>
                    )}
                  </span>
                </span>
                <ArrowUpRight size={16} className="shrink-0 text-ink-faint transition-colors group-hover:text-brand" />
              </button>
            );
          })}
        </div>
      )}
    </Card>
  );
}
