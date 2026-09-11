import { useEffect, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Sparkles, FileText } from 'lucide-react';
import { Card } from '@/shared/components/Card';
import { Button } from '@/shared/components/Button';
import { Badge } from '@/shared/components/Badge';
import { Skeleton } from '@/shared/components/Skeleton';
import { ErrorState } from '@/shared/components/ErrorState';
import { useUiStore } from '@/store/uiStore';
import { listResumes } from '@/features/resume/api';
import { analyze } from './api';
import type { JdUploadVO, SessionDetailVO } from './types';
import { JdUploader } from './JdUploader';
import { PipelineStepper } from './PipelineStepper';
import { ResultPanel } from './ResultPanel';
import { AskBox } from './AskBox';

/** JD 分析页。docs §6.3。 */
export function DecisionPage(): JSX.Element {
  const pushToast = useUiStore((s) => s.pushToast);
  const storeAssetId = useUiStore((s) => s.selectedResumeAssetId);
  const [resumeAssetId, setResumeAssetId] = useState<string>(storeAssetId ?? '');
  const [jd, setJd] = useState<JdUploadVO | null>(null);
  const [session, setSession] = useState<SessionDetailVO | null>(null);

  const resumesQ = useQuery({ queryKey: ['resumes'], queryFn: listResumes });

  useEffect(() => {
    if (storeAssetId) setResumeAssetId(storeAssetId);
  }, [storeAssetId]);

  const analyzeMut = useMutation({
    mutationFn: () =>
      analyze({ resumeAssetId, jdAssetId: jd?.assetId }),
    onSuccess: (res) => {
      setSession(res);
      pushToast({ tone: 'ok', message: '分析完成' });
    },
    onError: (err: Error) => pushToast({ tone: 'danger', message: err.message }),
  });

  const canAnalyze = !!resumeAssetId && !!jd && jd.parseStatus === 'SUCCEEDED' && !analyzeMut.isPending;
  const jdFailed = jd?.parseStatus === 'FAILED';

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      {/* 左：输入 */}
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
            <Button block loading={analyzeMut.isPending} disabled={!canAnalyze} onClick={() => analyzeMut.mutate()}>
              <Sparkles size={15} /> 开始分析
            </Button>
            {jdFailed && (
              <p className="mt-2 text-xs text-danger">请先成功上传 JD 再开始分析。</p>
            )}
          </div>
        </Card>
      </div>

      {/* 右：结果 */}
      <div className="space-y-4">
        <Card title="分析流水线">
          {analyzeMut.isPending ? (
            <PipelineStepper steps={[]} running />
          ) : session?.latestAnalysis ? (
            <PipelineStepper steps={session.latestAnalysis.steps} running={false} />
          ) : (
            <p className="text-xs text-ink-faint">
              上传 JD 并选择简历后，点击「开始分析」查看流水线进度与结果。
            </p>
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

        {!session && !analyzeMut.isPending && (
          <Card>
            <div className="flex flex-col items-center gap-2 py-8 text-center text-ink-faint">
              <FileText size={20} />
              <p className="text-sm">尚无分析结果</p>
            </div>
          </Card>
        )}
      </div>
    </div>
  );
}
