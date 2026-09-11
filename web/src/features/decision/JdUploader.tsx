import { useRef, useState } from 'react';
import type { ChangeEvent } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Upload, FileText } from 'lucide-react';
import { Button } from '@/shared/components/Button';
import { Textarea } from '@/shared/components/Textarea';
import { Skeleton } from '@/shared/components/Skeleton';
import { useUiStore } from '@/store/uiStore';
import { uploadJd, uploadJdText } from './api';
import type { JdUploadVO } from './types';

interface JdUploaderProps {
  onUploaded: (jd: JdUploadVO) => void;
}

/** JD 上传 / 粘贴。docs §6.3。 */
export function JdUploader({ onUploaded }: JdUploaderProps): JSX.Element {
  const [tab, setTab] = useState<'upload' | 'paste'>('upload');
  const [text, setText] = useState('');
  const pushToast = useUiStore((s) => s.pushToast);
  const fileRef = useRef<HTMLInputElement>(null);

  const uploadMut = useMutation({
    mutationFn: (file: File) => uploadJd(file),
    onSuccess: (res) => {
      onUploaded(res);
      pushToast({ tone: 'ok', message: 'JD 已上传' });
    },
    onError: (err: Error) => pushToast({ tone: 'danger', message: err.message }),
  });

  const pasteMut = useMutation({
    mutationFn: (t: string) => uploadJdText(t),
    onSuccess: (res) => {
      onUploaded(res);
      pushToast({ tone: 'ok', message: 'JD 已提交' });
    },
    onError: (err: Error) => pushToast({ tone: 'danger', message: err.message }),
  });

  const onFile = (e: ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (f) uploadMut.mutate(f);
    e.target.value = '';
  };

  const loading = uploadMut.isPending || pasteMut.isPending;

  return (
    <div className="space-y-3">
      <div className="flex gap-1 text-sm">
        {(['upload', 'paste'] as const).map((t) => (
          <button
            key={t}
            type="button"
            onClick={() => setTab(t)}
            className={[
              'rounded px-3 py-1.5 transition-colors duration-base',
              tab === t ? 'bg-brand-soft text-brand font-medium' : 'text-ink-soft hover:bg-surface-2',
            ].join(' ')}
          >
            {t === 'upload' ? '上传文件' : '粘贴文本'}
          </button>
        ))}
      </div>

      {tab === 'upload' ? (
        <button
          type="button"
          onClick={() => fileRef.current?.click()}
          disabled={loading}
          className="flex w-full flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-line-strong bg-surface-2 px-4 py-8 text-ink-soft transition-colors duration-base hover:border-brand hover:text-brand"
        >
          {loading ? (
            <Skeleton className="h-6 w-6 rounded-full" />
          ) : (
            <Upload size={22} />
          )}
          <span className="text-sm">点击上传 JD（pdf / docx / md / txt）</span>
        </button>
      ) : (
        <div className="space-y-2">
          <Textarea
            rows={6}
            placeholder="在此粘贴职位描述正文…"
            value={text}
            onChange={(e) => setText(e.target.value)}
          />
          <Button size="sm" loading={loading} disabled={text.trim().length === 0} onClick={() => pasteMut.mutate(text)}>
            <FileText size={15} /> 提交文本
          </Button>
        </div>
      )}
      <input
        ref={fileRef}
        type="file"
        accept=".pdf,.doc,.docx,.md,.txt"
        className="hidden"
        onChange={onFile}
      />
    </div>
  );
}
