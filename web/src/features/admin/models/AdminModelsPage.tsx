import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Plus, Pencil, Trash2, FlaskConical } from 'lucide-react';
import { Card } from '@/shared/components/Card';
import { Button } from '@/shared/components/Button';
import { Table } from '@/shared/components/Table';
import { Badge } from '@/shared/components/Badge';
import { Dialog } from '@/shared/components/Dialog';
import { Skeleton } from '@/shared/components/Skeleton';
import { ErrorState } from '@/shared/components/ErrorState';
import { EmptyState } from '@/shared/components/EmptyState';
import { formatDateTime } from '@/lib/format';
import { useUiStore } from '@/store/uiStore';
import { deleteModel, getPricing, listModels, testModel } from './api';
import { getGatewayMetrics } from '../billing/api';
import type { ModelConfig } from './types';
import { ModelFormDrawer } from './ModelFormDrawer';
import { WalletCard } from '../billing/WalletCard';
import { LedgerTable } from '../billing/LedgerTable';
import { RechargeDialog } from '../billing/RechargeDialog';

/** 模型管理 + 计费。docs §6.6。 */
export function AdminModelsPage(): JSX.Element {
  const qc = useQueryClient();
  const pushToast = useUiStore((s) => s.pushToast);
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<ModelConfig | null>(null);
  const [deleting, setDeleting] = useState<ModelConfig | null>(null);
  const [rechargeOpen, setRechargeOpen] = useState(false);

  const modelsQ = useQuery({ queryKey: ['models'], queryFn: listModels });
  const pricingQ = useQuery({ queryKey: ['pricing'], queryFn: getPricing });
  const metricsQ = useQuery({ queryKey: ['gatewayMetrics'], queryFn: getGatewayMetrics });

  const testMut = useMutation({
    mutationFn: (id: string) => testModel(id),
    onSuccess: (res) => {
      if (res.ok) {
        pushToast({ tone: 'ok', message: `连通性正常，耗时 ${res.latencyMs ?? 0}ms` });
      } else {
        pushToast({ tone: 'danger', message: res.error ?? '连通性测试失败' });
      }
    },
    onError: (err: Error) => pushToast({ tone: 'danger', message: err.message }),
  });

  const deleteMut = useMutation({
    mutationFn: (id: string) => deleteModel(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['models'] });
      pushToast({ tone: 'ok', message: '模型已删除' });
      setDeleting(null);
    },
    onError: (err: Error) => pushToast({ tone: 'danger', message: err.message }),
  });

  const columns = [
    { key: 'name', header: '名称' },
    {
      key: 'provider',
      header: 'Provider',
      render: (row: ModelConfig) => <Badge tone="info">{row.provider}</Badge>,
    },
    {
      key: 'model',
      header: '模型',
      render: (row: ModelConfig) => <span className="font-mono text-sm">{row.model}</span>,
    },
    {
      key: 'baseUrl',
      header: 'Base URL',
      render: (row: ModelConfig) => (
        <span className="font-mono text-xs text-ink-soft">{row.baseUrl}</span>
      ),
    },
    {
      key: 'enabled',
      header: '状态',
      render: (row: ModelConfig) => (
        <Badge tone={row.enabled ? 'ok' : 'neutral'}>{row.enabled ? '启用' : '停用'}</Badge>
      ),
    },
    {
      key: 'createdAt',
      header: '创建时间',
      render: (row: ModelConfig) => (
        <span className="font-mono text-xs text-ink-soft">
          {formatDateTime(row.createdAt ?? '')}
        </span>
      ),
    },
    {
      key: 'op',
      header: '操作',
      render: (row: ModelConfig) => (
        <div className="flex gap-1">
          <Button size="sm" variant="ghost" onClick={() => testMut.mutate(row.id)}>
            <FlaskConical size={14} /> 测试
          </Button>
          <Button
            size="sm"
            variant="ghost"
            onClick={() => {
              setEditing(row);
              setFormOpen(true);
            }}
          >
            <Pencil size={14} /> 编辑
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setDeleting(row)}>
            <Trash2 size={14} /> 删除
          </Button>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold text-ink">模型管理</h1>
        <Button
          onClick={() => {
            setEditing(null);
            setFormOpen(true);
          }}
        >
          <Plus size={15} /> 新增模型
        </Button>
      </div>

      <Card title="模型列表" bodyPadding={false}>
        {modelsQ.isLoading ? (
          <div className="p-6">
            <Skeleton className="h-40 w-full" />
          </div>
        ) : modelsQ.isError ? (
          <div className="p-6">
            <ErrorState message="模型列表加载失败" onRetry={() => modelsQ.refetch()} />
          </div>
        ) : !modelsQ.data || modelsQ.data.length === 0 ? (
          <div className="p-6">
            <EmptyState
              title="还没有模型"
              description="接入 DeepSeek / OpenAI 兼容模型以驱动分析与面试"
              action={
                <Button
                  onClick={() => {
                    setEditing(null);
                    setFormOpen(true);
                  }}
                >
                  <Plus size={15} /> 添加第一个模型
                </Button>
              }
            />
          </div>
        ) : (
          <Table columns={columns} data={modelsQ.data} rowKey={(r) => r.id} />
        )}
      </Card>

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

      <Card title="调用治理（single-flight 收益）">
        {metricsQ.isLoading ? (
          <Skeleton className="h-20 w-full" />
        ) : metricsQ.isError ? (
          <p className="text-sm text-ink-faint">治理指标暂不可用。</p>
        ) : metricsQ.data ? (
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
            {[
              ['owner', metricsQ.data.owner],
              ['replay', metricsQ.data.replay],
              ['localFallback', metricsQ.data.localFallback],
              ['retry', metricsQ.data.retry],
              ['failure', metricsQ.data.failure],
              ['settleFailure', metricsQ.data.settleFailure],
            ].map(([k, v]) => (
              <div key={k} className="rounded-md bg-surface-2 px-3 py-2.5">
                <p className="text-2xs uppercase tracking-wide text-ink-faint">{k}</p>
                <p className="mt-1 font-mono text-lg text-ink">{String(v)}</p>
              </div>
            ))}
          </div>
        ) : null}
      </Card>

      <div className="flex items-center justify-between">
        <h2 className="text-md font-semibold text-ink">额度与账单</h2>
        <Button size="sm" variant="secondary" onClick={() => setRechargeOpen(true)}>
          充值
        </Button>
      </div>
      <div className="space-y-4">
        <WalletCard />
        <LedgerTable />
      </div>

      <ModelFormDrawer open={formOpen} onClose={() => setFormOpen(false)} model={editing} />

      <Dialog
        open={!!deleting}
        onClose={() => setDeleting(null)}
        title="删除模型"
        confirmText="删除"
        confirmVariant="danger"
        confirmLoading={deleteMut.isPending}
        onConfirm={() => deleting && deleteMut.mutate(deleting.id)}
      >
        确认删除模型「{deleting?.name}」？该操作不可撤销。
      </Dialog>

      <RechargeDialog open={rechargeOpen} onClose={() => setRechargeOpen(false)} />
    </div>
  );
}
