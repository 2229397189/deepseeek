import { useEffect, useRef, useState } from 'react';
import type { ChangeEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Upload, FileDown, Save, FileText } from 'lucide-react';
import { Card } from '@/shared/components/Card';
import { Button } from '@/shared/components/Button';
import { Badge } from '@/shared/components/Badge';
import { EmptyState } from '@/shared/components/EmptyState';
import { Skeleton } from '@/shared/components/Skeleton';
import { ErrorState } from '@/shared/components/ErrorState';
import { useUiStore } from '@/store/uiStore';
import { getResume, listResumes, saveResumeBody, uploadResume } from './api';
import type { AssetBrief } from './types';
import { MarkdownEditor } from './MarkdownEditor';
import { ResumePreview } from './ResumePreview';
import { ResumeAssistant } from './ResumeAssistant';

/** P2-19 简历中心视图模式：并排 / 编辑 / 预览。 */
const MODE_OPTIONS: { key: 'split' | 'edit' | 'preview'; label: string }[] = [
  { key: 'split', label: '并排' },
  { key: 'edit', label: '编辑' },
  { key: 'preview', label: '预览' },
];

/** 简历中心：三栏（编辑 / 预览 / 助手）+ 双模式切换。docs §6.2。 */
export function ResumeCenterPage(): JSX.Element {
  const qc = useQueryClient();
  const pushToast = useUiStore((s) => s.pushToast);
  const selectedAssetId = useUiStore((s) => s.selectedResumeAssetId);
  const setSelectedAssetId = useUiStore((s) => s.setSelectedResumeAssetId);

  const [body, setBody] = useState('');
  const [prevBody, setPrevBody] = useState<string | null>(null);
  const [selectedText, setSelectedText] = useState('');
  const [loadedAssetId, setLoadedAssetId] = useState<string | null>(null);
  /** P2-19 当前视图模式，默认并排（保持原有三栏体验）。 */
  const [mode, setMode] = useState<'split' | 'edit' | 'preview'>('split');
  const previewRef = useRef<HTMLDivElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const listQ = useQuery({ queryKey: ['resumes'], queryFn: listResumes });
  const assetId = selectedAssetId ?? listQ.data?.[0]?.assetId ?? null;
  const resumeQ = useQuery({
    queryKey: ['resume', assetId],
    queryFn: () => getResume(assetId as string),
    enabled: !!assetId,
  });

  useEffect(() => {
    if (resumeQ.data && assetId && loadedAssetId !== assetId) {
      setBody(resumeQ.data.body ?? '');
      setLoadedAssetId(assetId);
      setSelectedText('');
    }
  }, [resumeQ.data, assetId, loadedAssetId]);

  const uploadMut = useMutation({
    mutationFn: (file: File) => uploadResume(file),
    onSuccess: (res) => {
      qc.invalidateQueries({ queryKey: ['resumes'] });
      setSelectedAssetId(res.assetId);
      pushToast({ tone: 'ok', message: '简历已上传' });
    },
    onError: (err: Error) => pushToast({ tone: 'danger', message: err.message }),
  });

  const saveMut = useMutation({
    mutationFn: () => saveResumeBody(assetId as string, body),
    onSuccess: () => pushToast({ tone: 'ok', message: '正文已保存' }),
    onError: (err: Error) => pushToast({ tone: 'danger', message: err.message }),
  });

  const onFile = (e: ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (f) uploadMut.mutate(f);
    e.target.value = '';
  };

  const handleExport = async () => {
    // 纯「编辑」模式下预览未挂载，无法取到导出节点
    if (!previewRef.current) {
      pushToast({ tone: 'warn', message: '请先切换到「并排」或「预览」模式再导出' });
      return;
    }
    try {
      const html2pdf = (await import('html2pdf.js')).default;
      html2pdf()
        .set({
          margin: 10,
          filename: 'resume.pdf',
          image: { type: 'jpeg', quality: 0.98 },
          html2canvas: { scale: 2 },
          jsPDF: { unit: 'mm', format: 'a4' },
        })
        .from(previewRef.current)
        .save();
    } catch {
      pushToast({ tone: 'danger', message: '导出失败，请重试' });
    }
  };

  const handleApply = (polished: string) => {
    setPrevBody(body);
    if (selectedText) {
      const idx = body.indexOf(selectedText);
      if (idx >= 0) {
        setBody(body.slice(0, idx) + polished + body.slice(idx + selectedText.length));
        return;
      }
    }
    setBody(`${body}\n\n${polished}`);
  };

  const handleRevert = () => {
    if (prevBody !== null) setBody(prevBody);
  };

  if (listQ.isLoading) {
    return (
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        {[0, 1, 2].map((i) => (
          <Card key={i} className="h-[70vh]">
            <Skeleton className="h-full w-full" />
          </Card>
        ))}
      </div>
    );
  }

  if (listQ.isError) {
    return <ErrorState message="简历列表加载失败" onRetry={() => listQ.refetch()} />;
  }

  if (!listQ.data || listQ.data.length === 0) {
    return (
      <EmptyState
        title="还没有简历"
        description="上传第一份简历，开始 AI 润色与岗位定制"
        icon={<FileText size={22} />}
        action={
          <Button onClick={() => fileRef.current?.click()}>
            <Upload size={15} /> 上传简历
          </Button>
        }
      />
    );
  }

  const assets: AssetBrief[] = listQ.data;

  return (
    <div className="space-y-4">
      <input
        ref={fileRef}
        type="file"
        accept=".pdf,.doc,.docx,.md,.txt"
        className="hidden"
        onChange={onFile}
      />

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <span className="text-sm text-ink-soft">当前简历</span>
          <select
            value={assetId ?? ''}
            onChange={(e) => setSelectedAssetId(e.target.value)}
            className="h-9 rounded-md border border-line bg-surface px-3 text-base text-ink outline-none focus:border-brand focus:shadow-focus"
          >
            {assets.map((a) => (
              <option key={a.assetId} value={a.assetId}>
                {a.originalName ?? a.assetId}
              </option>
            ))}
          </select>
          {resumeQ.data?.profile.experienceYears != null && (
            <Badge tone="brand">{resumeQ.data.profile.experienceYears} 年经验</Badge>
          )}
          {/* P2-19 视图模式切换 */}
          <div className="inline-flex rounded-md bg-surface-2 p-1">
            {MODE_OPTIONS.map((opt) => (
              <button
                key={opt.key}
                type="button"
                onClick={() => setMode(opt.key)}
                className={`rounded px-2.5 py-1 text-xs transition-colors ${
                  mode === opt.key
                    ? 'bg-surface text-ink shadow-sm'
                    : 'text-ink-faint hover:text-ink-soft'
                }`}
              >
                {opt.label}
              </button>
            ))}
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Button size="sm" variant="secondary" onClick={() => saveMut.mutate()} loading={saveMut.isPending}>
            <Save size={15} /> 保存信息
          </Button>
          <Button size="sm" variant="secondary" onClick={() => saveMut.mutate()} loading={saveMut.isPending}>
            <Save size={15} /> 保存正文
          </Button>
          <Button size="sm" onClick={handleExport}>
            <FileDown size={15} /> 导出 PDF
          </Button>
          <Button size="sm" variant="ghost" onClick={() => fileRef.current?.click()}>
            <Upload size={15} /> 上传
          </Button>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        {mode === 'split' ? (
          <>
            <Card className="h-[72vh]" bodyPadding={false}>
              <MarkdownEditor value={body} onChange={setBody} onSelectText={setSelectedText} />
            </Card>
            <Card className="h-[72vh]" bodyPadding={false}>
              <ResumePreview ref={previewRef} markdown={body} />
            </Card>
          </>
        ) : (
          <Card className="h-[72vh] lg:col-span-2" bodyPadding={false}>
            {mode === 'edit' ? (
              <MarkdownEditor value={body} onChange={setBody} onSelectText={setSelectedText} />
            ) : (
              <ResumePreview ref={previewRef} markdown={body} />
            )}
          </Card>
        )}
        <Card className="h-[72vh]" bodyPadding={false}>
          {assetId ? (
            <ResumeAssistant
              assetId={assetId}
              selectedText={selectedText}
              onApply={handleApply}
              onRevert={handleRevert}
            />
          ) : (
            <div className="p-4 text-xs text-ink-faint">请先选择一份简历</div>
          )}
        </Card>
      </div>
    </div>
  );
}
