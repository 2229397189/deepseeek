import { useCallback, useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Sparkles, FileText } from 'lucide-react';
import { Card } from '@/shared/components/Card';
import { Button } from '@/shared/components/Button';
import { Badge, type Tone } from '@/shared/components/Badge';
import { Skeleton } from '@/shared/components/Skeleton';
import { ErrorState } from '@/shared/components/ErrorState';
import { EmptyState } from '@/shared/components/EmptyState';
import { formatDateTime } from '@/lib/format';
import { useUiStore } from '@/store/uiStore';
import { listResumes } from '@/features/resume/api';
import { listSessions, getSession } from './api';
import type { JdUploadVO, SessionDetailVO, SessionBrief, PipelineStep, AnalysisVO } from './types';
import { JdUploader } from './JdUploader';
import { PipelineStepper } from './PipelineStepper';
import { ResultPanel } from './ResultPanel';
import { AskBox } from './AskBox';
import {
  connectAgentStream,
  type AgentStep,
  type StreamHandle,
} from '@/lib/sseClient';

/** 会话状态 → Badge 语义色 + 中文标签（docs §3.7：绿=完成、红=失败、琥珀=待确认、蓝=进行中）。 */
const SESSION_STATUS_META: Record<string, { tone: Tone; label: string }> = {
  PENDING: { tone: 'amber', label: '待确认' },
  RUNNING: { tone: 'info', label: '进行中' },
  SUCCEEDED: { tone: 'ok', label: '已完成' },
  FAILED: { tone: 'danger', label: '失败' },
};

function sessionStatusMeta(status: string): { tone: Tone; label: string } {
  return SESSION_STATUS_META[status] ?? { tone: 'neutral', label: status || '未知' };
}

/** JD 分析页。docs §6.3。SSE 流式调用改造。 */
export function DecisionPage(): JSX.Element {
  const pushToast = useUiStore((s) => s.pushToast);
  const storeAssetId = useUiStore((s) => s.selectedResumeAssetId);
  const [resumeAssetId, setResumeAssetId] = useState<string>(storeAssetId ?? '');
  const [jd, setJd] = useState<JdUploadVO | null>(null);
  const [session, setSession] = useState<SessionDetailVO | null>(null);

  // SSE streaming state
  const [steps, setSteps] = useState<PipelineStep[]>([]);
  const [running, setRunning] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const streamRef = useRef<StreamHandle | null>(null);

  // Session history
  const [selectedSessionId, setSelectedSessionId] = useState<string | null>(null);
  const sessionsQ = useQuery({ queryKey: ['decision-sessions'], queryFn: listSessions });
  const resumesQ = useQuery({ queryKey: ['resumes'], queryFn: listResumes });

  useEffect(() => {
    if (storeAssetId) setResumeAssetId(storeAssetId);
  }, [storeAssetId]);

  // Cleanup stream on unmount
  useEffect(() => () => { streamRef.current?.close(); }, []);

  /** Load a historical session */
  const loadSession = useCallback(async (id: string) => {
    try {
      const detail = await getSession(id);
      setSession(detail);
      setSelectedSessionId(id);
      setSteps(detail.latestAnalysis?.steps ?? []);
      setRunning(false);
      setErrorMsg(null);
    } catch (err) {
      pushToast({ tone: 'danger', message: (err as Error).message });
    }
  }, [pushToast]);

  /** Start SSE-based analysis */
  const startAnalysis = useCallback(() => {
    if (!resumeAssetId || !jd || jd.parseStatus !== 'SUCCEEDED') return;
    if (running) return;

    // Reset state
    setSteps([]);
    setSession(null);
    setRunning(true);
    setErrorMsg(null);
    setSelectedSessionId(null);

    // Close previous stream if any
    streamRef.current?.close();

    const handle = connectAgentStream(
      {
        bizType: 'DECIDE',
        payload: {
          resumeAssetId,
          jdAssetId: jd.assetId,
        },
      },
      {
        onStep: (step: AgentStep) => {
          setSteps((prev) => {
            const idx = prev.findIndex((s) => s.name === step.name);
            const mapped: PipelineStep = {
              name: step.name,
              status: step.status as PipelineStep['status'],
              detail: step.detail,
              elapsedMs: step.elapsedMs,
            };
            if (idx >= 0) {
              const next = [...prev];
              next[idx] = mapped;
              return next;
            }
            return [...prev, mapped];
          });
        },
        onResult: (output: unknown) => {
          setRunning(false);
          // output should be SessionDetailVO or AnalysisVO
          const result = output as SessionDetailVO;
          if (result?.id) {
            setSession(result);
            if (result.latestAnalysis?.steps) {
              setSteps(result.latestAnalysis.steps);
            }
          } else {
            // Treat as AnalysisVO embedded in output
            const analysis = output as AnalysisVO;
            setSession({
              id: '',
              status: 'SUCCEEDED',
              analyses: [analysis],
              messages: [],
              latestAnalysis: analysis,
            });
            if (analysis.steps) {
              setSteps(analysis.steps);
            }
          }
          pushToast({ tone: 'ok', message: '分析完成' });
          // Refresh session list
          void sessionsQ.refetch();
        },
        onError: (errorCode?: string, msg?: string) => {
          setRunning(false);
          const errText = msg ?? errorCode ?? '分析失败';
          setErrorMsg(errText);
          pushToast({ tone: 'danger', message: errText });
        },
      },
    );

    streamRef.current = handle;
  }, [resumeAssetId, jd, running, pushToast, sessionsQ]);

  const canAnalyze = !!resumeAssetId && !!jd && jd.parseStatus === 'SUCCEEDED' && !running;
  const jdFailed = jd?.parseStatus === 'FAILED';

  return (
    <div className="flex gap-4">
      {/* Left sidebar: session history */}
      <div className="hidden w-56 shrink-0 space-y-2 overflow-auto lg:block">
        <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-ink-faint">
          历史会话
        </h3>
        {sessionsQ.isLoading ? (
          <div className="space-y-2">
            <Skeleton className="h-16 w-full" />
            <Skeleton className="h-16 w-full" />
          </div>
        ) : sessionsQ.isError ? (
          <ErrorState message="加载失败" onRetry={() => sessionsQ.refetch()} />
        ) : (sessionsQ.data ?? []).length === 0 ? (
          <EmptyState
            title="暂无历史会话"
            description="上传或粘贴 JD 并分析后，记录会显示在这里。"
            className="!px-3 !py-10"
          />
        ) : (
          (sessionsQ.data ?? []).map((s: SessionBrief) => {
            const meta = sessionStatusMeta(s.status);
            return (
              <button
                key={s.id}
                type="button"
                onClick={() => loadSession(s.id)}
                className={[
                  'w-full rounded-md border px-3 py-2 text-left transition-colors',
                  selectedSessionId === s.id
                    ? 'border-brand/40 bg-brand/10'
                    : 'border-transparent hover:bg-surface-2',
                ].join(' ')}
              >
                <div className="flex items-center justify-between gap-2">
                  <p className="truncate text-sm font-medium text-ink">
                    {s.title ?? s.jobTitle ?? '未命名'}
                  </p>
                  <Badge tone={meta.tone}>{meta.label}</Badge>
                </div>
                <p className="mt-1 font-mono text-2xs text-ink-faint">
                  {s.createdAt ? formatDateTime(s.createdAt) : '—'}
                  {typeof s.score === 'number' && ` · ${s.score}分`}
                </p>
              </button>
            );
          })
        )}
      </div>

      {/* Main content */}
      <div className="min-w-0 flex-1">
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          {/* Left column: inputs */}
          <div className="space-y-4">
            <Card title="职位描述（JD）">
              <JdUploader onUploaded={setJd} />
              {jd && (
                <div className="mt-3 rounded-md bg-surface-2 p-3">
                  <div className="mb-1 flex items-center gap-2">
                    <Badge tone={jd.parseStatus === 'SUCCEEDED' ? 'ok' : 'danger'}>
                      {jd.parseStatus === 'SUCCEEDED' ? '解析成功' : '解析失败'}
                    </Badge>
                    {jd.skills && jd.skills.length > 0 && (
                      <span className="text-xs text-ink-faint">
                        {jd.skills.length} 项技能识别
                      </span>
                    )}
                  </div>
                  {jdFailed ? (
                    <p className="text-xs text-danger">{jd.errorMsg ?? 'JD 解析失败，请重试或换一种格式'}</p>
                  ) : (
                    <p className="line-clamp-3 text-xs text-ink-soft">
                      {jd.preview ?? '（无预览）'}
                    </p>
                  )}
                </div>
              )}
            </Card>

            <Card title="选择简历">
              {resumesQ.isLoading ? (
                <Skeleton className="h-9 w-full" />
              ) : resumesQ.isError ? (
                <ErrorState message="简历列表加载失败" onRetry={() => resumesQ.refetch()} />
              ) : (
                <div className="space-y-2">
                  <select
                    value={resumeAssetId}
                    onChange={(e) => setResumeAssetId(e.target.value)}
                    className="h-9 w-full rounded-md border border-line bg-surface px-3 text-base text-ink outline-none focus:border-brand focus:shadow-focus"
                  >
                    <option value="">— 请选择简历 —</option>
                    {(resumesQ.data ?? []).map((r) => (
                      <option key={r.assetId} value={r.assetId}>
                        {r.originalName ?? r.assetId}
                      </option>
                    ))}
                  </select>
                  <p className="text-xs text-ink-faint">
                    也可在「简历中心」选择默认简历，将自动带入。当前选中：
                    <span className="font-mono text-ink-soft">
                      {resumeAssetId || '无'}
                    </span>
                  </p>
                </div>
              )}
              <div className="mt-4">
                <Button block loading={running} disabled={!canAnalyze} onClick={startAnalysis}>
                  <Sparkles size={15} /> 开始分析
                </Button>
                {jdFailed && (
                  <p className="mt-2 text-xs text-danger">请先成功上传 JD 再开始分析。</p>
                )}
              </div>
            </Card>
          </div>

          {/* Right column: results */}
          <div className="space-y-4">
            <Card title="分析流水线">
              {running || steps.length > 0 ? (
                <PipelineStepper steps={steps} running={running} />
              ) : (
                <p className="text-xs text-ink-faint">
                  上传 JD 并选择简历后，点击「开始分析」查看流水线进度与结果。
                </p>
              )}
              {errorMsg && (
                <div className="mt-2 rounded-md bg-danger/10 px-3 py-2 text-sm text-danger">
                  {errorMsg}
                  <Button
                    size="sm"
                    variant="ghost"
                    className="ml-2"
                    onClick={startAnalysis}
                  >
                    重试
                  </Button>
                </div>
              )}
            </Card>

            {session?.latestAnalysis && (
              <Card title="匹配结果">
                <ResultPanel analysis={session.latestAnalysis} />
              </Card>
            )}

            {session && (
              <Card title="追问">
                <AskBox sessionId={session.id} />
              </Card>
            )}

            {!session && !running && steps.length === 0 && (
              <Card>
                <div className="flex flex-col items-center gap-2 py-8 text-center text-ink-faint">
                  <FileText size={20} />
                  <p className="text-sm">尚无分析结果</p>
                </div>
              </Card>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
