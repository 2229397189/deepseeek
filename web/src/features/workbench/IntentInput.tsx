import { useRef, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { Plus, Mic, ArrowUp, Loader2 } from 'lucide-react';
import { Textarea } from '@/shared/components/Textarea';
import { ModelPicker } from './ModelPicker';
import { uploadResume } from '@/features/resume/api';
import { useUiStore } from '@/store/uiStore';

interface IntentInputProps {
  value: string;
  onChange: (value: string) => void;
  onSubmit: () => void;
  loading: boolean;
}

/** 工作台大输入卡：文本区 + 底部行（模型选择 / 附件上传 / 黑色麦克风 / 发送圆钮）。 */
export function IntentInput({ value, onChange, onSubmit, loading }: IntentInputProps): JSX.Element {
  const navigate = useNavigate();
  const pushToast = useUiStore((s) => s.pushToast);
  const fileRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (value.trim() && !loading) onSubmit();
  };

  // 附件按钮：选择文件即上传为简历，成功后跳简历中心（解析由后端异步完成）
  const uploadMut = useMutation({
    mutationFn: (file: File) => uploadResume(file),
    onSuccess: (res) => {
      pushToast({ tone: 'ok', message: `简历《${res.originalName ?? '未命名'}》已上传，解析中` });
      navigate(`/resume/${res.assetId}`);
    },
    onError: (err: Error) => pushToast({ tone: 'danger', message: err.message }),
    onSettled: () => setUploading(false),
  });

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (f) {
      setUploading(true);
      uploadMut.mutate(f);
    }
    e.target.value = '';
  };

  return (
    <form onSubmit={handleSubmit}>
      <div className="rounded-2xl border border-line bg-surface p-3 shadow-card transition-colors duration-base focus-within:border-ink/30">
        <Textarea
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder="发岗位链接、贴 JD、贴简历，或直接说你想评估什么…"
          rows={4}
          className="border-0 shadow-none focus:shadow-none"
        />
        <div className="mt-2 flex items-center justify-between gap-2 px-1">
          <div className="flex items-center gap-2">
            <ModelPicker />
            <input
              ref={fileRef}
              type="file"
              accept=".md,.txt,.pdf,.docx"
              className="hidden"
              onChange={handleFileChange}
            />
            <button
              type="button"
              aria-label="上传简历"
              title="上传简历（md / txt / pdf / docx）"
              disabled={uploading}
              onClick={() => fileRef.current?.click()}
              className="flex h-8 w-8 items-center justify-center rounded-full text-ink-soft transition-colors hover:bg-surface-2 disabled:opacity-50"
            >
              {uploading ? <Loader2 size={18} className="animate-spin" /> : <Plus size={18} />}
            </button>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              aria-label="语音输入"
              className="flex h-9 w-9 items-center justify-center rounded-full bg-ink text-white transition-colors hover:bg-ink-soft"
            >
              <Mic size={17} />
            </button>
            <button
              type="submit"
              disabled={!value.trim() || loading}
              aria-label="发送"
              className="flex h-9 w-9 items-center justify-center rounded-full bg-ink text-white transition-colors hover:bg-ink-soft disabled:opacity-40"
            >
              <ArrowUp size={18} />
            </button>
          </div>
        </div>
      </div>
    </form>
  );
}
