import { QueryClient } from '@tanstack/react-query';

/**
 * TanStack Query 客户端配置。docs §4.1 / §4.2。
 * - retry: 失败重试 1 次（网络抖动自愈）
 * - staleTime: 30s，避免频繁重取
 * - refetchOnWindowFocus: 关闭，避免无谓请求
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 30_000,
      refetchOnWindowFocus: false,
      refetchOnReconnect: true,
    },
    mutations: {
      retry: 0,
    },
  },
});
