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

/** Provider 展示顺序（合并视图分组用）。 */
const PROVIDER_ORDER: string[] = ['DeepSeek', 'deepseek', 'OpenAI', 'OpenAI 兼容'];

/** API Key 脱敏：仅展示末 4 位，形如 ****1234。 */
function maskApiKey(key?: string): string {
  if (!key) return '—';
  return `****${key.slice(-4)}`;
}

/** 合并视图按 provider 分组排序的权重。 */
function providerRank(provider: string): number {
  const idx = PROVIDER_ORDER.indexOf(provider);
  return idx === -1 ? PROVIDER_ORDER.length : idx;
}

/** 分段控件样式（模型管理 Tab 切换）。 */
const SEG_BASE = 'px-3 py-1.5 text-sm font-medium rounded-sm transition-colors duration-fast';
const SEG_ACTIVE = 'bg-surface text-ink shadow-card';
const SEG_IDLE = 'text-ink-soft hover:text-ink';

/** 模型管理 + 计费。docs §6.6。 */
export function AdminModelsPage(): JSX.Element {
  const qc = useQueryClient();
  const pushToast = useUiStore((s) => s.pushToast);
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<ModelConfig | null>(null);
  const [deleting, setDeleting] = useState<ModelConfig | null>(null);
  const [tab, setTab] = useState<'list' | 'merged'>('list');

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

  /** 合并视图列：聚合所有模型，按 provider 分组，展示 base_url / model / api_key(脱敏) / 状态。 */
  const mergedColumns = [
    {
      key: 'provider',
      header: 'Provider',
      render: (row: ModelConfig) => <Badge tone="info">{row.provider}</Badge>,
    },
    {
      key: 'baseUrl',
      header: 'Base URL',
      render: (row: ModelConfig) => (
        <span className="font-mono text-xs text-ink-soft">{row.baseUrl}</span>
      ),
    },
    {
      key: 'model',
      header: '模型',
      render: (row: ModelConfig) => <span className="font-mono text-sm">{row.model}</span>,
    },
    {
      key: 'apiKey',
      header: 'API Key',
      render: (row: ModelConfig) => (
        <span className="font-mono text-sm text-ink-soft">{maskApiKey(row.apiKey)}</span>
      ),
    },
    {
      key: 'status',
      header: '状态',
      render: (row: ModelConfig) => (
        <Badge tone={row.enabled ? 'ok' : 'neutral'}>{row.enabled ? '启用' : '停用'}</Badge>
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

      <div className="inline-flex rounded-md bg-surface-2 p-1">
        <button
          type="button"
          onClick={() => setTab('list')}
          className={`${SEG_BASE} ${tab === 'list' ? SEG_ACTIVE : SEG_IDLE}`}
        >
          模型列表
        </button>
        <button
          type="button"
          onClick={() => setTab('merged')}
          className={`${SEG_BASE} ${tab === 'merged' ? SEG_ACTIVE : SEG_IDLE}`}
        >
          合并模型
        </button>
      </div>

      {tab === 'list' ? (
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
      ) : (
        <Card title="合并模型视图" bodyPadding={false}>
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
              />
            </div>
          ) : (
            <Table
              columns={mergedColumns}
              data={[...modelsQ.data].sort((a, b) => providerRank(a.provider) - providerRank(b.provider))}
              rowKey={(r) => r.id}
            />
          )}
        </Card>
      )}

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
    </div>
  );
}
