import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { ArrowLeft, PhoneOff, Video, PenTool } from 'lucide-react';
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

  const detailQ = useQuery({
    queryKey: ['interviewSession', sessionId],
    queryFn: () => getInterviewSession(sessionId),
    enabled: !!sessionId,
  });

  useEffect(() => {
    if (detailQ.data) {
      setMessages(detailQ.data.messages ?? []);
      if (detailQ.data.report) setReport(detailQ.data.report);
    }
  }, [detailQ.data]);

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
    setMessages((prev) => [...prev, { role: 'user', content }]);
    setInput('');
    answerMut.mutate(content);
  };

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
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <button
          type="button"
          onClick={() => navigate('/interview')}
          className="flex items-center gap-1 text-sm text-ink-soft transition-colors hover:text-ink"
        >
          <ArrowLeft size={16} /> 返回列表
        </button>
        <div className="flex items-center gap-2">
          <Button size="sm" variant="secondary" onClick={() => setScratchOpen((v) => !v)}>
            <PenTool size={15} /> 构思板
          </Button>
          <Button size="sm" variant="secondary" onClick={() => setCameraOn((v) => !v)}>
            <Video size={15} /> 摄像头
          </Button>
          <Button size="sm" variant="danger" loading={finishMut.isPending} onClick={() => finishMut.mutate()}>
            <PhoneOff size={15} /> 结束面试
          </Button>
        </div>
      </div>

      {report ? (
        <ReportCard report={report} onBack={() => navigate('/interview')} />
      ) : (
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
      )}
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
