import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Card } from '@/shared/components/Card';
import { Table } from '@/shared/components/Table';
import { Badge } from '@/shared/components/Badge';
import { Button } from '@/shared/components/Button';
import { Skeleton } from '@/shared/components/Skeleton';
import { ErrorState } from '@/shared/components/ErrorState';
import { formatCredit, formatDateTime } from '@/lib/format';
import { getLedger } from './api';
import type { LedgerVO } from './types';

/** 额度流水表（分页，对齐 /billing/ledger 的 pageNum/pageSize）。docs §6.6。 */
export function LedgerTable(): JSX.Element {
  const [page, setPage] = useState(1);
  const pageSize = 10;

  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['ledger', page],
    queryFn: () => getLedger(page, pageSize),
  });

  const columns = [
    {
      key: 'bizType',
      header: '业务',
      render: (row: LedgerVO) => <Badge tone="brand">{row.bizType}</Badge>,
    },
    {
      key: 'amount',
      header: '变动',
      numeric: true,
      render: (row: LedgerVO) => (
        <span className={row.amount >= 0 ? 'text-ok' : 'text-danger'}>
          {row.amount >= 0 ? '+' : ''}
          {formatCredit(row.amount)}
        </span>
      ),
    },
    {
      key: 'balanceAfter',
      header: '余额',
      numeric: true,
      render: (row: LedgerVO) => formatCredit(row.balanceAfter),
    },
    { key: 'remark', header: '备注', render: (row: LedgerVO) => row.remark ?? '-' },
    {
      key: 'createdAt',
      header: '时间',
      render: (row: LedgerVO) => (
        <span className="font-mono text-xs text-ink-soft">
          {formatDateTime(row.createdAt ?? '')}
        </span>
      ),
    },
  ];

  return (
    <Card title="额度流水">
      {isLoading ? (
        <Skeleton className="h-40 w-full" />
      ) : isError ? (
        <ErrorState message="流水加载失败" onRetry={() => refetch()} />
      ) : data ? (
        <>
          <Table columns={columns} data={data.records} rowKey={(r) => r.id} />
          <div className="mt-3 flex items-center justify-between">
            <span className="text-xs text-ink-faint">共 {data.total} 条</span>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="secondary"
                disabled={page <= 1}
                onClick={() => setPage((p) => Math.max(1, p - 1))}
              >
                上一页
              </Button>
              <Button
                size="sm"
                variant="secondary"
                disabled={page * pageSize >= data.total}
                onClick={() => setPage((p) => p + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        </>
      ) : null}
    </Card>
  );
}
