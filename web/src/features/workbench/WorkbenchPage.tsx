import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { History, MessageSquare, BarChart3, Pencil, ChevronRight, FileText } from 'lucide-react';
import { IntentInput } from './IntentInput';
import { MockToggle } from './MockToggle';
import { detectIntent } from './api';
import { listResumes } from '@/features/resume/api';
import { useUiStore } from '@/store/uiStore';
import { useAuthStore } from '@/store/authStore';
import type { IntentResult } from './types';

/** 右侧浮动工具条（参考图：竖排圆形按钮）。 */
function FloatingToolbar(): JSX.Element {
  const navigate = useNavigate();
  const icons = [
    { icon: History, label: '历史会话', to: '/interview' },
    { icon: MessageSquare, label: '消息', to: '' },
    { icon: BarChart3, label: '数据统计', to: '/profile' },
    { icon: Pencil, label: '批注', to: '' },
  ];
  return (
    <div className="fixed right-5 top-1/2 z-30 flex -translate-y-1/2 flex-col gap-3">
      {icons.map(({ icon: Icon, label, to }) => (
        <button
          key={label}
          type="button"
          aria-label={label}
          title={label}
          onClick={() => to && navigate(to)}
          className="flex h-10 w-10 items-center justify-center rounded-full border border-line bg-surface text-ink-soft shadow-pop transition-colors hover:text-ink"
        >
          <Icon size={18} />
        </button>
      ))}
      <button
        type="button"
        aria-label="收起"
        className="flex h-10 w-10 items-center justify-center rounded-full border border-line bg-surface text-ink-faint shadow-pop transition-colors hover:text-ink"
      >
        <ChevronRight size={18} />
      </button>
    </div>
  );
}

/** 工作台首页。参考图：居中 hero + 大输入卡 + 浮动工具条 + Mock 开关。docs §6.1。 */
export function WorkbenchPage(): JSX.Element {
  const navigate = useNavigate();
  const [text, setText] = useState('');
  const pushToast = useUiStore((s) => s.pushToast);
  const username = useAuthStore((s) => s.user?.nickname ?? s.user?.username);

  const intentMut = useMutation({
    mutationFn: (input: string) => detectIntent(input),
    onSuccess: (res: IntentResult) => {
      pushToast({ tone: 'ok', message: `识别为「${res.intent}」意图` });
      navigate(res.targetRoute || '/resume');
    },
    onError: (err: Error) => pushToast({ tone: 'danger', message: err.message }),
  });

  const resumeQ = useQuery({
    queryKey: ['latest-resume-chip'],
    queryFn: listResumes,
    select: (list) => list[0],
  });
  const latestResume = resumeQ.data;

  return (
    <div className="relative mx-auto flex min-h-[calc(100vh-var(--content-pad)*2)] max-w-[760px] flex-col items-center justify-center py-10">
      <h1 className="text-center text-3xl font-bold tracking-tight text-ink">今天想准备哪一场？</h1>
      <p className="mt-3 text-center text-md text-ink-soft">
        {username ? `${username}，` : ''}发岗位链接、贴 JD 或简历，也可以先说说你想评估什么…
      </p>

      {latestResume && (
        <button
          type="button"
          onClick={() => navigate(`/resume/${latestResume.assetId}`)}
          className="mt-5 flex items-center gap-2 rounded-full border border-line bg-surface px-3 py-1.5 text-base text-ink-soft shadow-card transition-colors hover:bg-surface-2"
        >
          <FileText size={15} />
          简历《{latestResume.originalName?.replace(/\.(md|pdf)$/i, '') ?? '未命名'}》
        </button>
      )}

      <div className="mt-6 w-full">
        <IntentInput
          value={text}
          onChange={setText}
          onSubmit={() => intentMut.mutate(text)}
          loading={intentMut.isPending}
        />
      </div>

      <div className="mt-4 flex w-full items-center justify-between">
        <span className="text-xs text-ink-faint">允许估 AI 主观，请自行判断。</span>
        <MockToggle />
      </div>

      <FloatingToolbar />
    </div>
  );
}
