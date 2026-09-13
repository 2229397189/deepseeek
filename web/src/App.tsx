import { QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router-dom';
import { router } from '@/router';
import { queryClient } from '@/lib/queryClient';
import { ToastProvider } from '@/shared/components/ToastProvider';

/**
 * 顶层组件：QueryClientProvider + RouterProvider + 全局 Toast 容器 + 401 兜底。
 * docs §2 / §4.1。
 */
export default function App(): JSX.Element {
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <RouterProvider router={router} />
      </ToastProvider>
    </QueryClientProvider>
  );
}
