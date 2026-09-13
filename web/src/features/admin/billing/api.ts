import { get } from '@/lib/apiClient';
import type { MetricsVO, RunPage } from './types';

/** GET /gateway/metrics */
export function getGatewayMetrics(): Promise<MetricsVO> {
  return get<MetricsVO>('/api/gateway/metrics');
}

/** GET /gateway/runs ?pageNum=&pageSize=&bizType=&status= */
export function getGatewayRuns(params: {
  pageNum?: number;
  pageSize?: number;
  bizType?: string;
  status?: string;
}): Promise<RunPage> {
  return get<RunPage>('/api/gateway/runs', params as Record<string, unknown>);
}
