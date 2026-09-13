import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Card } from '@/shared/components/Card';
import { Button } from '@/shared/components/Button';
import { Skeleton } from '@/shared/components/Skeleton';
import { ErrorState } from '@/shared/components/ErrorState';
import { getPricing } from '../models/api';
import { WalletCard } from './WalletCard';
import { LedgerTable } from './LedgerTable';
import { RechargeDialog } from './RechargeDialog';

/**
 * 计费与额度页（docs §6.6）。
 * 此前计费能力只通过顶栏钱包抽屉 + 模型管理页内嵌可达，没有独立路由；
 * 这里补齐 /admin/billing 专属页面，把钱包、账单、计费规则聚合到一起。
 */
export function AdminBillingPage(): JSX.Element {
  const [rechargeOpen, setRechargeOpen] = useState(false);

  const pricingQ = useQuery({ queryKey: ['pricing'], queryFn: getPricing });

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold text-ink">计费与额度</h1>
        <Button size="sm" variant="secondary" onClick={() => setRechargeOpen(true)}>
          充值
        </Button>
      </div>

      <Card title="计费规则（单次调用单价）">
        {pricingQ.isLoading ? (
          <Skeleton className="h-20 w-full" />
        ) : pricingQ.isError ? (
          <ErrorState message="计费规则加载失败" onRetry={() => pricingQ.refetch()} />
        ) : !pricingQ.data || pricingQ.data.length === 0 ? (
          <p className="text-sm text-ink-faint">暂无计费规则。</p>
        ) : (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {pricingQ.data.map((p) => (
              <div key={p.bizType} className="rounded-md bg-surface-2 px-3 py-2.5">
                <p className="text-2xs uppercase tracking-wide text-ink-faint">{p.bizType}</p>
                <p className="mt-1 font-mono text-lg text-ink">{p.unitCredit}</p>
                {p.description && <p className="text-2xs text-ink-faint">{p.description}</p>}
              </div>
            ))}
          </div>
        )}
      </Card>

      <div className="space-y-4">
        <WalletCard />
        <LedgerTable />
      </div>

      <RechargeDialog open={rechargeOpen} onClose={() => setRechargeOpen(false)} />
    </div>
  );
}
