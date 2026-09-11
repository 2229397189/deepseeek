import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { RefreshCw, ZoomIn, ZoomOut, Maximize2 } from 'lucide-react';
import { Card } from '@/shared/components/Card';
import { Button } from '@/shared/components/Button';
import { Badge } from '@/shared/components/Badge';
import { Drawer } from '@/shared/components/Drawer';
import { Skeleton } from '@/shared/components/Skeleton';
import { ErrorState } from '@/shared/components/ErrorState';
import { EmptyState } from '@/shared/components/EmptyState';
import { formatDateTime } from '@/lib/format';
import { useUiStore } from '@/store/uiStore';
import { getGraphEdges, getGraphNodes, getNodeEvidence } from './api';
import type { Evidence, GraphEdge, GraphNode } from './types';
import { GraphCanvas } from './GraphCanvas';
import { GraphLegend } from './GraphLegend';

/** 知识图谱页。docs §6.5。 */
export function GraphPage(): JSX.Element {
  const pushToast = useUiStore((s) => s.pushToast);
  const [zoom, setZoom] = useState(1);
  const [selected, setSelected] = useState<GraphNode | null>(null);
  const [evidence, setEvidence] = useState<Evidence[]>([]);
  const [evidenceOpen, setEvidenceOpen] = useState(false);
  const [evidenceLoading, setEvidenceLoading] = useState(false);

  const query = useQuery({
    queryKey: ['graph'],
    queryFn: async (): Promise<{ nodes: GraphNode[]; edges: GraphEdge[] }> => {
      const [nodes, edges] = await Promise.all([getGraphNodes(), getGraphEdges()]);
      return { nodes, edges };
    },
  });

  const openNode = async (node: GraphNode) => {
    setSelected(node);
    setEvidence([]);
    setEvidenceOpen(true);
    setEvidenceLoading(true);
    try {
      const ev = await getNodeEvidence(node.id);
      setEvidence(ev);
    } catch (err) {
      pushToast({ tone: 'danger', message: (err as Error).message });
    } finally {
      setEvidenceLoading(false);
    }
  };

  const nodes = query.data?.nodes ?? [];
  const edges = query.data?.edges ?? [];

  if (query.isLoading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-10 w-40" />
        <Skeleton className="h-[640px] w-full" />
      </div>
    );
  }

  if (query.isError) {
    return <ErrorState message="图谱加载失败" onRetry={() => query.refetch()} />;
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold text-ink">Agent 可视化 · 知识图谱</h1>
        <div className="flex items-center gap-2">
          <Button size="sm" variant="secondary" onClick={() => query.refetch()}>
            <RefreshCw size={15} /> 刷新
          </Button>
          <Button size="sm" variant="secondary" onClick={() => setZoom((z) => Math.min(2, z + 0.1))}>
            <ZoomIn size={15} />
          </Button>
          <Button size="sm" variant="secondary" onClick={() => setZoom((z) => Math.max(0.4, z - 0.1))}>
            <ZoomOut size={15} />
          </Button>
          <Button
            size="sm"
            variant="secondary"
            onClick={() => {
              setZoom(1);
              setSelected(null);
            }}
          >
            <Maximize2 size={15} /> 重置
          </Button>
        </div>
      </div>

      {nodes.length === 0 ? (
        <EmptyState
          title="暂无图谱数据"
          description="先完善简历或完成一次模拟面试，系统会自动构建候选人能力图谱"
        />
      ) : (
        <>
          <Card bodyPadding={false}>
            <GraphCanvas
              nodes={nodes}
              edges={edges}
              selectedId={selected?.id ?? null}
              onSelect={openNode}
              zoom={zoom}
            />
          </Card>
          <GraphLegend />
        </>
      )}

      <Drawer
        open={evidenceOpen}
        onClose={() => setEvidenceOpen(false)}
        title={selected ? `证据 · ${selected.label}` : '证据'}
      >
        <div className="space-y-4">
          {selected && (
            <div className="flex items-center gap-2">
              <Badge tone={selected.status === 'WEAK' ? 'danger' : 'brand'}>
                {selected.type}
              </Badge>
              <span className="font-mono text-xs text-ink-faint">{selected.id}</span>
              <span className="text-xs text-ink-faint">层级 {selected.layer}</span>
            </div>
          )}
          {evidenceLoading ? (
            <Skeleton className="h-24 w-full" />
          ) : evidence.length === 0 ? (
            <p className="text-sm text-ink-faint">暂无证据来源。</p>
          ) : (
            <ul className="space-y-3">
              {evidence.map((ev, i) => (
                <li key={i} className="rounded-md border border-line bg-surface-2 p-3">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-medium text-ink">{ev.source}</span>
                    <span className="font-mono text-2xs text-ink-faint">
                      {formatDateTime(ev.createdAt ?? '')}
                    </span>
                  </div>
                  <p className="mt-1 text-sm text-ink-soft">{ev.excerpt}</p>
                </li>
              ))}
            </ul>
          )}
        </div>
      </Drawer>
    </div>
  );
}
