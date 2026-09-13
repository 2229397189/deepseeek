import { useState } from 'react';
import type { FormEvent } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Button } from '@/shared/components/Button';
import { Input } from '@/shared/components/Input';
import { Card } from '@/shared/components/Card';
import { useLogin, useRegister } from './useAuth';

/** 登录 / 注册页（AppShell 之外）。docs §1 / §6。 */
export function LoginPage(): JSX.Element {
  const navigate = useNavigate();
  const location = useLocation();
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [nickname, setNickname] = useState('');

  const loginMut = useLogin();
  const registerMut = useRegister();

  const from = (location.state as { from?: string } | null)?.from ?? '/';
  const loading = loginMut.isPending || registerMut.isPending;

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (mode === 'login') {
      loginMut.mutate(
        { username, password },
        { onSuccess: () => navigate(from, { replace: true }) },
      );
    } else {
      registerMut.mutate(
        { username, password, nickname: nickname || undefined },
        { onSuccess: () => setMode('login') },
      );
    }
  };

  return (
    <div className="bg-blueprint flex min-h-screen items-center justify-center p-6">
      <Card className="w-full max-w-sm">
        <div className="mb-6 text-center">
          <div className="mx-auto mb-3 flex h-11 w-11 items-center justify-center rounded-lg bg-brand text-lg font-bold text-white">
            C
          </div>
          <h1 className="text-lg font-semibold text-ink">Chiron Agent</h1>
          <p className="mt-1 text-xs text-ink-faint">求职评估与模拟面试平台</p>
        </div>

        <div className="mb-4 flex rounded-md bg-surface-2 p-0.5 text-sm">
          <button
            type="button"
            onClick={() => setMode('login')}
            className={[
              'flex-1 rounded py-1.5 transition-colors duration-base',
              mode === 'login' ? 'bg-surface text-ink shadow-card' : 'text-ink-soft',
            ].join(' ')}
          >
            登录
          </button>
          <button
            type="button"
            onClick={() => setMode('register')}
            className={[
              'flex-1 rounded py-1.5 transition-colors duration-base',
              mode === 'register' ? 'bg-surface text-ink shadow-card' : 'text-ink-soft',
            ].join(' ')}
          >
            注册
          </button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-3">
          <Input
            placeholder="用户名（4-32 位字母数字下划线）"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
          />
          <Input
            type="password"
            placeholder="密码（8-64 位）"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
          />
          {mode === 'register' && (
            <Input
              placeholder="昵称（可选）"
              value={nickname}
              onChange={(e) => setNickname(e.target.value)}
            />
          )}
          <Button type="submit" block loading={loading}>
            {mode === 'login' ? '登录' : '注册并登录'}
          </Button>
        </form>
      </Card>
    </div>
  );
}
