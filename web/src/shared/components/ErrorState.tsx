import { type ReactNode } from 'react';
import { AlertTriangle } from 'lucide-react';
import { Button } from './Button';

export interface ErrorStateProps {
  /** 错误简述（已用 errorMessages 映射为中文） */
  message?: ReactNode;
  /** 重试回调 */
  onRetry?: () => void;
  retryText?: string;
  className?: string;
}

/**
 * 错误态。居中图标 + danger 简述 + 重试按钮（触发 query 重取）。
 * message 应已由 errorMessages 映射，避免展示英文枚举（docs §3.7）。
 */
export function ErrorState({
  message = '加载失败，请稍后重试',
  onRetry,
  retryText = '重试',
  className = '',
}: ErrorStateProps): JSX.Element {
  return (
    <div
      className={[
        'flex flex-col items-center justify-center text-center rounded-lg',
        'px-6 py-16 border border-line bg-surface',
        className,
      ].join(' ')}
    >
      <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-md bg-danger-soft text-danger">
        <AlertTriangle size={22} aria-hidden />
      </div>
      <p className="text-md font-medium text-danger">{message}</p>
      {onRetry && (
        <div className="mt-5">
          <Button variant="secondary" size="sm" onClick={onRetry}>
            {retryText}
          </Button>
        </div>
      )}
    </div>
  );
}
