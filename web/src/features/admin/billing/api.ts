import { get, post } from '@/lib/apiClient';
import type { LedgerPage, RechargeRequest, RunPage, WalletVO } from './types';

/** GET /billing/wallet */
export function getWallet(): Promise<WalletVO> {
  return get<WalletVO>('/api/billing/wallet');
}

/** GET /billing/ledger ?pageNum=&pageSize= */
export function getLedger(pageNum = 1, pageSize = 20): Promise<LedgerPage> {
  return get<LedgerPage>('/api/billing/ledger', { pageNum, pageSize });
}

/** POST /billing/recharge */
export function recharge(req: RechargeRequest): Promise<WalletVO> {
  return post<WalletVO>('/api/billing/recharge', req);
}

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
