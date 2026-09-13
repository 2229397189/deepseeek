import { useQuery } from '@tanstack/react-query';
import { Card } from '@/shared/components/Card';
import { Skeleton } from '@/shared/components/Skeleton';
import { ErrorState } from '@/shared/components/ErrorState';
import { formatCredit } from '@/lib/format';
import { getWallet } from './api';

function Metric({
  label,
  value,
  highlight,
}: {
  label: string;
  value: string;
  highlight?: boolean;
}): JSX.Element {
  return (
    <div className="rounded-md bg-surface-2 px-3 py-2.5">
      <p className="text-2xs uppercase tracking-wide text-ink-faint">{label}</p>
      <p
        className={[
          'mt-1 font-mono text-lg',
          highlight ? 'text-brand' : 'text-ink',
        ].join(' ')}
      >
        {value}
      </p>
    </div>
  );
}

/** 钱包卡片。docs §6.6 / §1（额度入口）。 */
export function WalletCard(): JSX.Element {
  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['wallet'],
    queryFn: getWallet,
  });

  return (
    <Card title="钱包余额">
      {isLoading ? (
        <Skeleton className="h-20 w-full" />
      ) : isError ? (
        <ErrorState message="钱包加载失败" onRetry={() => refetch()} />
      ) : data ? (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          <Metric label="可用额度" value={formatCredit(data.availableCredit)} highlight />
          <Metric label="账户余额" value={formatCredit(data.balanceCredit)} />
          <Metric label="冻结额度" value={formatCredit(data.frozenCredit)} />
          <Metric label="累计发放" value={formatCredit(data.totalGranted)} />
          <Metric label="累计消耗" value={formatCredit(data.totalConsumed)} />
        </div>
      ) : null}
    </Card>
  );
}
