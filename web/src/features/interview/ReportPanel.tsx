/**
 * 面试报告面板。docs §6.4 / P1-14 雷达图、P1-15 每题分、P1-16 导出。
 * 纯前端组件，无第三方图表库（雷达图手绘 SVG）。
 */
import { useCallback, useMemo } from 'react';
import { ArrowLeft, FileDown, Printer } from 'lucide-react';
import { Card } from '@/shared/components/Card';
import { Button } from '@/shared/components/Button';
import { Badge } from '@/shared/components/Badge';
import { Tag } from '@/shared/components/Tag';
import { formatDateTime, formatScore } from '@/lib/format';
import type { InterviewReport, MessageVO } from './types';

/** 达标分数线（用于每题分语义着色，与 docs §3.7 一致：达标=ok 绿 / 薄弱=danger 红）。 */
const PASS_SCORE = 60;

/* ------------------------------------------------------------------ */
/* 雷达图（纯 SVG 手绘）                                                */
/* ------------------------------------------------------------------ */

const RADAR_SIZE = 280;
const RADAR_CENTER = 140;
const RADAR_RADIUS = 92;
const RADAR_LEVELS = 4;

/** 极坐标 -> 笛卡尔坐标（以中心为原点）。 */
function polar(center: number, radius: number, angleDeg: number): [number, number] {
  const rad = (angleDeg * Math.PI) / 180;
  return [center + radius * Math.cos(rad), center + radius * Math.sin(rad)];
}

interface RadarChartProps {
  dimensions: Record<string, number>;
}

/**
 * 雷达图：按维度数 N 均分角度，绘制同心网格圈 + 轴线 + 数据多边形
 * （brand 色半透明填充）+ 顶点圆点 + 轴标签（维度名 + 数值）。
 * 无维度时不渲染。维度值按 0-100 归一化（若最大值 > 100 则按 max 归一）。
 */
function RadarChart({ dimensions }: RadarChartProps): JSX.Element | null {
  const entries = Object.entries(dimensions ?? {});
  const n = entries.length;
  if (n === 0) return null;

  const maxVal = Math.max(100, ...entries.map(([, v]) => Number(v) || 0));
  const angleAt = (i: number) => -90 + (360 / n) * i;

  // 同心网格圈（RADAR_LEVELS 圈）
  const rings = Array.from({ length: RADAR_LEVELS }, (_, level) => {
    const r = (RADAR_RADIUS * (level + 1)) / RADAR_LEVELS;
    return entries
      .map((_, i) => polar(RADAR_CENTER, r, angleAt(i)).join(','))
      .join(' ');
  });

  // 轴线（中心 -> 顶点）
  const axes = entries.map((_, i) => polar(RADAR_CENTER, RADAR_RADIUS, angleAt(i)));

  // 数据多边形顶点
  const dataPoints = entries.map(([, v], i) => {
    const ratio = Math.min(1, (Number(v) || 0) / maxVal);
    return polar(RADAR_CENTER, RADAR_RADIUS * ratio, angleAt(i));
  });
  const dataPolygon = dataPoints.map((p) => p.join(',')).join(' ');

  // 轴标签（维度名 + 数值），按左右位置选择对齐
  const labels = entries.map(([name, v], i) => {
    const [lx, ly] = polar(RADAR_CENTER, RADAR_RADIUS + 14, angleAt(i));
    const anchor: 'middle' | 'end' | 'start' =
      Math.abs(lx - RADAR_CENTER) < 1 ? 'middle' : lx < RADAR_CENTER ? 'end' : 'start';
    return { name, value: v, x: lx, y: ly, anchor };
  });

  return (
    <svg
      viewBox={`0 0 ${RADAR_SIZE} ${RADAR_SIZE}`}
      className="mx-auto block h-auto w-[260px] overflow-visible"
      role="img"
      aria-label="维度雷达图"
    >
      {/* 网格圈 */}
      {rings.map((points, idx) => (
        <polygon
          key={`ring-${idx}`}
          points={points}
          fill="none"
          stroke="var(--color-line)"
          strokeWidth={1}
        />
      ))}

      {/* 轴线 */}
      {axes.map((a, i) => (
        <line
          key={`axis-${i}`}
          x1={RADAR_CENTER}
          y1={RADAR_CENTER}
          x2={a[0]}
          y2={a[1]}
          stroke="var(--color-line)"
          strokeWidth={1}
        />
      ))}

      {/* 数据多边形 */}
      <polygon
        points={dataPolygon}
        fill="var(--color-brand)"
        fillOpacity={0.22}
        stroke="var(--color-brand)"
        strokeWidth={2}
        strokeLinejoin="round"
      />

      {/* 顶点圆点 */}
      {dataPoints.map((p, i) => (
        <circle
          key={`dot-${i}`}
          cx={p[0]}
          cy={p[1]}
          r={3}
          fill="var(--color-brand)"
          stroke="#fff"
          strokeWidth={1}
        />
      ))}

      {/* 轴标签 */}
      {labels.map((l, i) => (
        <text
          key={`label-${i}`}
          x={l.x}
          y={l.y}
          textAnchor={l.anchor}
          dominantBaseline="middle"
          style={{ fontSize: 10, fontFamily: 'var(--font-mono)', fill: 'var(--color-ink-soft)' }}
        >
          {l.name}
          <tspan dx={4} style={{ fill: 'var(--color-ink-faint)' }}>
            {l.value}
          </tspan>
        </text>
      ))}
    </svg>
  );
}

/* ------------------------------------------------------------------ */
/* 报告面板                                                            */
/* ------------------------------------------------------------------ */

export interface ReportPanelProps {
  report: InterviewReport;
  messages: MessageVO[];
  onBack?: () => void;
}

/**
 * 面试报告面板：综合评分 + 维度雷达图 + 维度条 + 每题得分列表 + 薄弱点 + 建议，
 * 并提供「导出 PDF」（window.print）与「导出 MD」（Blob 下载）能力。
 */
export function ReportPanel({ report, messages, onBack }: ReportPanelProps): JSX.Element {
  // 每题得分：筛选 assistant 且携带 questionScore 的消息
  const questionRows = useMemo(() => {
    return messages
      .filter((m) => m.role === 'assistant' && typeof m.questionScore === 'number')
      .map((m, i) => ({
        index: m.questionIndex ?? i + 1,
        score: m.questionScore as number,
        keywords: m.hitKeywords ?? [],
        isFollowUp: Boolean(m.isFollowUp),
      }));
  }, [messages]);

  // 拼装 markdown 报告
  const buildMarkdown = useCallback((): string => {
    const lines: string[] = [];
    lines.push('# 面试报告');
    lines.push('');
    lines.push(`会话 ID：${report.sessionId}`);
    if (report.createdAt) lines.push(`生成时间：${formatDateTime(report.createdAt)}`);
    lines.push('');
    lines.push('## 综合评分');
    lines.push('');
    lines.push(`**${formatScore(report.score)}** / 100`);
    lines.push('');
    lines.push('## 维度得分');
    lines.push('');
    lines.push('| 维度 | 得分 |');
    lines.push('| --- | --- |');
    for (const [name, v] of Object.entries(report.dimensions ?? {})) {
      lines.push(`| ${name} | ${v} |`);
    }
    lines.push('');
    lines.push('## 每题得分');
    lines.push('');
    if (questionRows.length > 0) {
      lines.push('| 题号 | 得分 | 命中关键词 | 追问 |');
      lines.push('| --- | --- | --- | --- |');
      for (const r of questionRows) {
        const kw = r.keywords.join('、') || '—';
        const fu = r.isFollowUp ? '是' : '否';
        lines.push(`| #${r.index} | ${r.score} | ${kw} | ${fu} |`);
      }
    } else {
      lines.push('（无逐题评分数据）');
    }
    lines.push('');
    lines.push('## 薄弱点');
    lines.push('');
    if (report.weakPoints.length > 0) {
      for (const w of report.weakPoints) lines.push(`- ${w}`);
    } else {
      lines.push('（无）');
    }
    lines.push('');
    lines.push('## 建议');
    lines.push('');
    lines.push(report.suggestion || '（无）');
    lines.push('');
    return lines.join('\n');
  }, [report, questionRows]);

  // 导出 MD：Blob + <a download>
  const handleExportMd = useCallback(() => {
    const md = buildMarkdown();
    const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `interview-report-${report.sessionId}.md`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }, [buildMarkdown, report.sessionId]);

  // 导出 PDF：触发浏览器打印（配合下方 @media print 样式仅打印报告区）
  const handleExportPdf = useCallback(() => {
    window.print();
  }, []);

  const dimensions = Object.entries(report.dimensions ?? {});

  return (
    <Card
      title="面试报告"
      extra={
        <div className="flex items-center gap-2">
          <Button variant="secondary" size="sm" onClick={handleExportMd}>
            <FileDown size={15} /> 导出 MD
          </Button>
          <Button variant="secondary" size="sm" onClick={handleExportPdf}>
            <Printer size={15} /> 导出 PDF
          </Button>
        </div>
      }
    >
      {/* 打印区域：@media print 时仅显示此块 */}
      <div id="report-print-area" className="space-y-6">
        {/* 综合评分 + 建议 */}
        <div className="flex items-center gap-4">
          <div className="flex h-20 w-20 shrink-0 flex-col items-center justify-center rounded-lg bg-brand-soft">
            <span className="font-mono text-3xl font-semibold text-brand">
              {formatScore(report.score)}
            </span>
            <span className="text-2xs text-ink-faint">综合评分</span>
          </div>
          <p className="text-base text-ink-soft">{report.suggestion}</p>
        </div>

        {/* 雷达图 + 维度条 */}
        <div className="grid gap-6 md:grid-cols-2">
          <div className="flex items-center justify-center rounded-md border border-line bg-surface p-2">
            <RadarChart dimensions={report.dimensions ?? {}} />
          </div>
          <div>
            <h4 className="mb-3 text-sm font-semibold text-ink">维度得分</h4>
            {dimensions.length > 0 ? (
              <ul className="space-y-2.5">
                {dimensions.map(([name, v]) => {
                  const pct = Math.max(0, Math.min(100, Number(v) || 0));
                  return (
                    <li key={name} className="flex items-center gap-3">
                      <span className="w-20 shrink-0 truncate text-xs text-ink-soft">{name}</span>
                      <div className="h-2 flex-1 overflow-hidden rounded-full bg-surface-2">
                        <div
                          className="h-full rounded-full bg-brand"
                          style={{ width: `${pct}%` }}
                        />
                      </div>
                      <span className="w-8 text-right font-mono text-xs text-ink">{v}</span>
                    </li>
                  );
                })}
              </ul>
            ) : (
              <p className="text-xs text-ink-faint">暂无维度数据</p>
            )}
          </div>
        </div>

        {/* 每题得分列表 */}
        <div>
          <h4 className="mb-3 text-sm font-semibold text-ink">每题得分</h4>
          {questionRows.length > 0 ? (
            <div className="divide-y divide-line rounded-md border border-line">
              <div className="grid grid-cols-[56px_72px_1fr_56px] items-center gap-2 bg-surface-2 px-3 py-2 text-2xs font-medium text-ink-faint">
                <span>题号</span>
                <span>得分</span>
                <span>命中关键词</span>
                <span className="text-right">追问</span>
              </div>
              {questionRows.map((row, i) => (
                <div
                  key={i}
                  className="grid grid-cols-[56px_72px_1fr_56px] items-center gap-2 px-3 py-2.5 text-xs"
                >
                  <span className="font-mono text-ink-soft">#{row.index}</span>
                  <span
                    className={`font-mono font-semibold ${
                      row.score >= PASS_SCORE ? 'text-ok' : 'text-danger'
                    }`}
                  >
                    {row.score}
                  </span>
                  <span className="flex flex-wrap gap-1">
                    {row.keywords.length > 0 ? (
                      row.keywords.map((k, j) => (
                        <Tag key={j} tone="brand">
                          {k}
                        </Tag>
                      ))
                    ) : (
                      <span className="text-ink-faint">—</span>
                    )}
                  </span>
                  <span className="text-right">
                    {row.isFollowUp ? (
                      <Badge tone="amber">追问</Badge>
                    ) : (
                      <span className="text-ink-faint">—</span>
                    )}
                  </span>
                </div>
              ))}
            </div>
          ) : (
            <p className="text-xs text-ink-faint">暂无逐题评分数据</p>
          )}
        </div>

        {/* 薄弱点 */}
        {report.weakPoints.length > 0 && (
          <div>
            <h4 className="mb-3 text-sm font-semibold text-ink">薄弱点</h4>
            <div className="flex flex-wrap gap-1.5">
              {report.weakPoints.map((w, i) => (
                <Badge key={i} tone="danger">
                  {w}
                </Badge>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* 返回按钮（打印时隐藏） */}
      {onBack && (
        <div className="mt-6 no-print">
          <Button variant="secondary" size="sm" onClick={onBack}>
            <ArrowLeft size={15} /> 返回面试列表
          </Button>
        </div>
      )}

      {/* 打印友好样式：仅打印 #report-print-area */}
      <style>{`
        @media print {
          body * { visibility: hidden; }
          #report-print-area, #report-print-area * { visibility: visible; }
          #report-print-area {
            position: absolute;
            left: 0;
            top: 0;
            width: 100%;
            padding: 0;
            margin: 0;
          }
          .no-print { display: none !important; }
        }
      `}</style>
    </Card>
  );
}
