/** 额度账本 / 钱包 / 充值。docs §5.1 /billing/*。 */
export interface WalletVO {
  availableCredit: number;
  balanceCredit: number;
  frozenCredit: number;
  totalGranted: number;
  totalConsumed: number;
}

export interface LedgerVO {
  id: string;
  bizType: string;
  amount: number;
  balanceAfter: number;
  remark?: string;
  createdAt?: string;
}

export interface LedgerPage {
  total: number;
  pageNum: number;
  pageSize: number;
  records: LedgerVO[];
}

export interface RechargeRequest {
  amount: number;
  idempotencyKey?: string;
  remark?: string;
}

/** 网关调用治理指标。docs §5.1 /gateway/metrics。 */
export interface MetricsVO {
  owner: string;
  replay: number;
  localFallback: number;
  retry: number;
  failure: number;
  settleFailure: number;
}

export interface RunRecord {
  runId: string;
  bizType: string;
  status: string;
  latencyMs?: number;
  createdAt?: string;
}

export interface RunPage {
  total: number;
  pageNum: number;
  pageSize: number;
  records: RunRecord[];
}
