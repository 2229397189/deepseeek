import type { GraphEdge, GraphNode, GraphStatus } from './types';

const SPACE_W = 900;
const SPACE_H = 640;

function nodeColor(type: string, status?: GraphStatus): string {
  if (status === 'WEAK') return '#D14343';
  switch (type) {
    case 'CANDIDATE':
      return '#1E4A33';
    case 'RESUME':
      return '#2C6E9B';
    case 'JOB':
      return '#B7791F';
    case 'INTERVIEW':
      return '#2F855A';
    default:
      return '#2F6F4E';
  }
}

interface Position {
  x: number;
  y: number;
  layer: number;
}

/** 确定性径向布局：候选人居中，简历/求职/面试成环，技能/项目/岗位在外层。docs §6.5。 */
function computeLayout(nodes: GraphNode[]): Record<string, Position> {
  const cx = SPACE_W / 2;
  const cy = SPACE_H / 2;
  const byLayer: Record<number, GraphNode[]> = {};
  for (const n of nodes) {
    (byLayer[n.layer] ??= []).push(n);
  }
  const radius: Record<number, number> = { 0: 0, 1: 175, 2: 325 };
  const pos: Record<string, Position> = {};
  Object.entries(byLayer).forEach(([ls, ns]) => {
    const layer = Number(ls);
    const r = radius[layer] ?? 175 * layer;
    if (layer === 0) {
      if (ns[0]) pos[ns[0].id] = { x: cx, y: cy, layer };
      return;
    }
    ns.forEach((n, i) => {
      const angle = (Math.PI * 2 * i) / ns.length - Math.PI / 2;
      pos[n.id] = {
        x: cx + r * Math.cos(angle),
        y: cy + r * Math.sin(angle),
        layer,
      };
    });
  });
  return pos;
}

interface GraphCanvasProps {
  nodes: GraphNode[];
  edges: GraphEdge[];
  selectedId: string | null;
  onSelect: (node: GraphNode) => void;
  zoom: number;
}

/** 图谱画布（确定性径向布局，无重 WebGL 依赖）。docs §6.5 / §8-6。 */
export function GraphCanvas({
  nodes,
  edges,
  selectedId,
  onSelect,
  zoom,
}: GraphCanvasProps): JSX.Element {
  const pos = computeLayout(nodes);

  return (
    <div className="relative h-[640px] w-full overflow-hidden rounded-lg border border-line bg-surface-2">
      <div
        className="absolute left-1/2 top-1/2"
        style={{
          width: SPACE_W,
          height: SPACE_H,
          transform: `translate(-50%, -50%) scale(${zoom})`,
          transformOrigin: 'center',
        }}
      >
        <svg width={SPACE_W} height={SPACE_H} className="absolute inset-0">
          {edges.map((e, i) => {
            const s = pos[e.source];
            const t = pos[e.target];
            if (!s || !t) return null;
            return (
              <line
                key={i}
                x1={s.x}
                y1={s.y}
                x2={t.x}
                y2={t.y}
                stroke="rgba(28,27,26,0.18)"
                strokeWidth={1}
              />
            );
          })}
        </svg>

        {nodes.map((n) => {
          const p = pos[n.id];
          if (!p) return null;
          const color = nodeColor(n.type, n.status);
          const selected = n.id === selectedId;
          const size = n.layer === 0 ? 88 : n.layer === 1 ? 66 : 54;
          return (
            <button
              key={n.id}
              type="button"
              onClick={() => onSelect(n)}
              className="absolute flex -translate-x-1/2 -translate-y-1/2 flex-col items-center justify-center rounded-full border-2 text-center transition-transform duration-base hover:scale-105"
              style={{
                left: p.x,
                top: p.y,
                width: size,
                height: size,
                borderColor: color,
                background: selected ? color : '#FFFFFF',
                color: selected ? '#FFFFFF' : color,
                boxShadow: '0 1px 3px rgba(28,27,26,0.12)',
              }}
            >
              <span className="px-1 text-2xs font-medium leading-tight">{n.label}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
