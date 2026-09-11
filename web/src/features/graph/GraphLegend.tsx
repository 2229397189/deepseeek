/** 图谱图例。docs §6.5 / §1（候选人/三层/技能项目岗位/分层/关联/已失效）。 */
export function GraphLegend(): JSX.Element {
  const items: { color: string; label: string; ring?: boolean }[] = [
    { color: '#1E4A33', label: '候选人（中心）' },
    { color: '#2C6E9B', label: '简历' },
    { color: '#B7791F', label: '求职 (JD)' },
    { color: '#2F855A', label: '面试' },
    { color: '#2F6F4E', label: '技能 / 项目 / 岗位（外层）' },
    { color: '#D14343', label: '薄弱 / 已失效（红）' },
  ];

  return (
    <div className="flex flex-wrap items-center gap-x-5 gap-y-2 rounded-lg border border-line bg-surface px-4 py-3">
      {items.map((it) => (
        <span key={it.label} className="flex items-center gap-1.5 text-xs text-ink-soft">
          <span
            className="inline-block h-3 w-3 rounded-full"
            style={{ background: it.color }}
          />
          {it.label}
        </span>
      ))}
      <span className="flex items-center gap-1.5 text-xs text-ink-soft">
        <span className="inline-block h-0.5 w-4" style={{ background: 'rgba(28,27,26,0.3)' }} />
        关联
      </span>
      <span className="text-xs text-ink-faint">三层（简历 / 求职 / 面试）环绕候选人，外层挂技能项目岗位</span>
    </div>
  );
}
