import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import {
  ArrowLeft,
  PhoneOff,
  Video,
  PenTool,
  Loader2,
  CheckCircle2,
  FileText,
} from 'lucide-react';
import { Card } from '@/shared/components/Card';
import { Button } from '@/shared/components/Button';
import { Badge } from '@/shared/components/Badge';
import { Skeleton } from '@/shared/components/Skeleton';
import { ErrorState } from '@/shared/components/ErrorState';
import { useUiStore } from '@/store/uiStore';
import { answerInterview, finishInterview, getInterviewSession } from './api';
import type { InterviewReport, MessageVO } from './types';
import { ChatPanel } from './ChatPanel';
import { CameraPanel } from './CameraPanel';
import { ScratchPad } from './ScratchPad';

/** 准备面试流水线步骤。 */
interface PrepStep {
  label: string;
  done: boolean;
}

const PREP_STEPS: string[] = [
  '创建面试房间',
  '接通面试官',
  '解析简历并出题',
  '准备第一题',
];

/** 面试室：对话 / 语音 / 摄像头 / 构思板 / 报告。docs §6.4。 */
export function InterviewRoomPage(): JSX.Element {
  const { sessionId = '' } = useParams();
  const navigate = useNavigate();
  const pushToast = useUiStore((s) => s.pushToast);

  const [messages, setMessages] = useState<MessageVO[]>([]);
  const [input, setInput] = useState('');
  const [report, setReport] = useState<InterviewReport | null>(null);
  const [cameraOn, setCameraOn] = useState(false);
  const [scratchOpen, setScratchOpen] = useState(false);

  // Preparation pipeline animation
  const [preparing, setPreparing] = useState(true);
  const [prepIndex, setPrepIndex] = useState(0);

  const detailQ = useQuery({
    queryKey: ['interviewSession', sessionId],
    queryFn: () => getInterviewSession(sessionId),
    enabled: !!sessionId,
  });

  useEffect(() => {
    if (detailQ.data) {
      setMessages(detailQ.data.messages ?? []);
      if (detailQ.data.report) setReport(detailQ.data.report);
      // Skip preparation if session already has messages
      if ((detailQ.data.messages ?? []).length > 0 || detailQ.data.report) {
        setPreparing(false);
        setPrepIndex(PREP_STEPS.length);
      }
    }
  }, [detailQ.data]);

  // Animate preparation steps
  useEffect(() => {
    if (!preparing || prepIndex >= PREP_STEPS.length) {
      if (prepIndex >= PREP_STEPS.length) setPreparing(false);
      return;
    }
    const timer = window.setTimeout(() => {
      setPrepIndex((i) => i + 1);
    }, 600 + Math.random() * 400);
    return () => clearTimeout(timer);
  }, [preparing, prepIndex]);

  const answerMut = useMutation({
    mutationFn: (content: string) => answerInterview(sessionId, content),
    onSuccess: (turn) => {
      setMessages((prev) => [...prev, turn.userMessage, turn.aiMessage]);
    },
    onError: (err: Error) => pushToast({ tone: 'danger', message: err.message }),
  });

  const finishMut = useMutation({
    mutationFn: () => finishInterview(sessionId),
    onSuccess: (rep) => {
      setReport(rep);
      pushToast({ tone: 'ok', message: '面试已结束，报告已生成' });
    },
    onError: (err: Error) => pushToast({ tone: 'danger', message: err.message }),
  });

  const send = () => {
    const content = input.trim();
    if (!content || answerMut.isPending) return;
    setInput('');
    answerMut.mutate(content);
  };

  // Compute question count & running score from messages
  const { questionCount, runningScore } = useMemo(() => {
    const aiMessages = messages.filter((m) => m.role === 'assistant');
    return {
      questionCount: aiMessages.length,
      runningScore: report?.score ?? 0,
    };
  }, [messages, report]);

  const isFinished = !!report;
  const isInProgress = !isFinished && !preparing;
  const sessionDetail = detailQ.data;
  const resumeName = sessionDetail?.sessionId
    ? (detailQ.data as Record<string, unknown>)?.resumeName as string | undefined
    : undefined;

  if (detailQ.isLoading) {
    return (
      <div className="grid h-[80vh] grid-cols-1 gap-4 lg:grid-cols-3">
        <Skeleton className="h-full lg:col-span-2" />
        <Skeleton className="h-full" />
      </div>
    );
  }

  if (detailQ.isError) {
    return <ErrorState message="面试会话加载失败" onRetry={() => detailQ.refetch()} />;
  }

  return (
    <div className="space-y-3">
      {/* Status bar */}
      <div className="flex items-center justify-between rounded-lg bg-surface-2 px-4 py-2">
        <div className="flex items-center gap-4">
          <button
            type="button"
            onClick={() => navigate('/interview')}
            className="flex items-center gap-1 text-sm text-ink-soft transition-colors hover:text-ink"
          >
            <ArrowLeft size={16} /> 返回列表
          </button>

          {isInProgress && (
            <Badge tone="brand">
              <span className="flex items-center gap-1">
                <span className="relative flex h-2 w-2">
                  <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-brand opacity-75" />
                  <span className="relative inline-flex h-2 w-2 rounded-full bg-brand" />
                </span>
                面试进行中
              </span>
            </Badge>
          )}
          {isFinished && <Badge tone="ok">面试已结束</Badge>}
          {preparing && <Badge tone="amber">准备中</Badge>}

          {isInProgress && (
            <>
              <span className="text-sm text-ink-soft">
                当前题号 <span className="font-mono font-semibold text-ink">{questionCount}</span>
              </span>
              <span className="text-sm text-ink-soft">
                当前总分 <span className="font-mono font-semibold text-ink">{runningScore}</span>
              </span>
            </>
          )}
        </div>

        <div className="flex items-center gap-2">
          {resumeName && (
            <Badge tone="info">
              <FileText size={12} className="mr-1" />
              已上传简历: {resumeName}
            </Badge>
          )}
          <Button size="sm" variant="secondary" onClick={() => setScratchOpen((v) => !v)}>
            <PenTool size={15} /> 构思板
          </Button>
          <Button size="sm" variant="secondary" onClick={() => setCameraOn((v) => !v)}>
            <Video size={15} /> 摄像头
          </Button>
          {!isFinished && (
            <Button
              size="sm"
              variant="danger"
              loading={finishMut.isPending}
              disabled={preparing}
              onClick={() => finishMut.mutate()}
            >
              <PhoneOff size={15} /> 结束面试
            </Button>
          )}
        </div>
      </div>

      {/* Preparation pipeline animation */}
      {preparing && (
        <Card>
          <div className="space-y-3 py-4">
            <h3 className="text-sm font-semibold text-ink">准备面试…</h3>
            <ol className="space-y-2">
              {PREP_STEPS.map((label, i) => (
                <li key={label} className="flex items-center gap-3">
                  {i < prepIndex ? (
                    <CheckCircle2 size={18} className="text-ok" />
                  ) : i === prepIndex ? (
                    <Loader2 size={18} className="animate-spin text-brand" />
                  ) : (
                    <div className="h-[18px] w-[18px] rounded-full border border-line" />
                  )}
                  <span
                    className={
                      i < prepIndex
                        ? 'text-sm text-ink'
                        : i === prepIndex
                          ? 'text-sm font-medium text-brand'
                          : 'text-sm text-ink-faint'
                    }
                  >
                    {label}
                  </span>
                </li>
              ))}
            </ol>
          </div>
        </Card>
      )}

      {/* Main content */}
      {report ? (
        <ReportCard report={report} onBack={() => navigate('/interview')} />
      ) : !preparing ? (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
          <Card className="h-[78vh] lg:col-span-2" bodyPadding={false}>
            <ChatPanel
              messages={messages}
              value={input}
              onChange={setInput}
              onSend={send}
              sending={answerMut.isPending}
            />
          </Card>
          <div className="space-y-4">
            {cameraOn && (
              <Card title="摄像头">
                <CameraPanel on={cameraOn} onToggle={() => setCameraOn((v) => !v)} />
              </Card>
            )}
            {scratchOpen && (
              <Card title="构思板">
                <ScratchPad />
              </Card>
            )}
            {!cameraOn && !scratchOpen && (
              <Card>
                <p className="py-6 text-center text-xs text-ink-faint">
                  点击「摄像头」开启本地预览，或「构思板」记录要点。
                </p>
              </Card>
            )}
          </div>
        </div>
      ) : null}
    </div>
  );
}

function ReportCard({
  report,
  onBack,
}: {
  report: InterviewReport;
  onBack: () => void;
}): JSX.Element {
  const dimensions = Object.entries(report.dimensions ?? {});
  const maxScore = Math.max(1, ...dimensions.map(([, v]) => v));
  return (
    <Card title="面试报告">
      <div className="space-y-5">
        <div className="flex items-center gap-4">
          <div className="flex h-20 w-20 shrink-0 flex-col items-center justify-center rounded-lg bg-brand-soft">
            <span className="font-mono text-3xl font-semibold text-brand">
              {Math.round(report.score)}
            </span>
            <span className="text-2xs text-ink-faint">综合评分</span>
          </div>
          <p className="text-base text-ink-soft">{report.suggestion}</p>
        </div>

        {dimensions.length > 0 && (
          <div>
            <h4 className="mb-2 text-sm font-semibold text-ink">维度得分</h4>
            <div className="space-y-2">
              {dimensions.map(([name, score]) => (
                <div key={name} className="flex items-center gap-3">
                  <span className="w-24 shrink-0 truncate text-xs text-ink-soft">{name}</span>
                  <div className="h-2 flex-1 overflow-hidden rounded-full bg-surface-2">
                    <div
                      className="h-full rounded-full bg-brand"
                      style={{ width: `${(score / maxScore) * 100}%` }}
                    />
                  </div>
                  <span className="w-8 text-right font-mono text-xs text-ink">{score}</span>
                </div>
              ))}
            </div>
          </div>
        )}

        {report.weakPoints.length > 0 && (
          <div>
            <h4 className="mb-2 text-sm font-semibold text-ink">薄弱点</h4>
            <div className="flex flex-wrap gap-1.5">
              {report.weakPoints.map((w, i) => (
                <Badge key={i} tone="danger">
                  {w}
                </Badge>
              ))}
            </div>
          </div>
        )}

        <Button onClick={onBack}>
          <ArrowLeft size={15} /> 返回面试列表
        </Button>
      </div>
    </Card>
  );
}
