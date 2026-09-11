import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { FileText, ClipboardList, MessagesSquare, ArrowRight } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { Card } from '@/shared/components/Card';
import { Badge } from '@/shared/components/Badge';
import { IntentInput } from './IntentInput';
import { ModelPicker } from './ModelPicker';
import { MockToggle } from './MockToggle';
import { detectIntent } from './api';
import { useUiStore } from '@/store/uiStore';
import { useAuthStore } from '@/store/authStore';
import type { IntentResult } from './types';

const QUICK: {
  title: string;
  desc: string;
  icon: LucideIcon;
  to: string;
  tone: 'brand' | 'info' | 'ok';
}[] = [
  { title: '简历优化', desc: '上传 / 编辑简历，AI 润色与岗位定制', icon: FileText, to: '/resume', tone: 'brand' },
  { title: 'JD 分析', desc: '上传职位描述，评估匹配度与薄弱点', icon: ClipboardList, to: '/decision', tone: 'info' },
  { title: '模拟面试', desc: '基于简历与岗位的 AI 逐题面试', icon: MessagesSquare, to: '/interview', tone: 'ok' },
];

/** 工作台首页。docs §6.1。 */
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

  return (
    <div className="space-y-8">
      <section>
        <p className="text-sm text-ink-faint">
          {username ? `你好，${username}` : '欢迎使用'} · 工程化求职评估控制台
        </p>
        <h1 className="mt-1 text-2xl font-semibold text-ink">今天想准备哪一场？</h1>
      </section>

      <section className="space-y-3">
        <IntentInput
          value={text}
          onChange={setText}
          onSubmit={() => intentMut.mutate(text)}
          loading={intentMut.isPending}
        />
        <div className="flex flex-wrap items-center gap-3">
          <ModelPicker />
          <MockToggle />
          <span className="text-xs text-ink-faint">
            提示：粘贴职位链接、JD 正文或简历内容，系统自动识别意图
          </span>
        </div>
      </section>

      <section className="grid grid-cols-1 gap-4 md:grid-cols-3">
        {QUICK.map((q) => (
          <Card
            key={q.to}
            className="cursor-pointer transition-colors duration-base hover:border-brand"
            onClick={() => navigate(q.to)}
          >
            <div className="flex items-start justify-between">
              <div
                className={[
                  'flex h-10 w-10 items-center justify-center rounded-md',
                  q.tone === 'brand'
                    ? 'bg-brand-soft text-brand'
                    : q.tone === 'info'
                      ? 'bg-info-soft text-info'
                      : 'bg-ok-soft text-ok',
                ].join(' ')}
              >
                <q.icon size={20} />
              </div>
              <ArrowRight size={16} className="text-ink-faint" />
            </div>
            <h3 className="mt-3 text-md font-semibold text-ink">{q.title}</h3>
            <p className="mt-1 text-xs text-ink-soft">{q.desc}</p>
          </Card>
        ))}
      </section>

      <section>
        <Badge tone="neutral">评估记录</Badge>
        <p className="mt-2 text-xs text-ink-faint">
          历史 JD 分析与模拟面试记录可在对应页面查看。
        </p>
      </section>
    </div>
  );
}
