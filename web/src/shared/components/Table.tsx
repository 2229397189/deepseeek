import { type ReactNode } from 'react';
import { Skeleton } from './Skeleton';

/** 列定义（docs §3.7：表头 surface-2 + uppercase tracking；数字列 font-mono text-right） */
export interface Column<T> {
  key: string;
  header: ReactNode;
  align?: 'left' | 'right' | 'center';
  /** 数字列：右对齐 + 等宽字体 */
  numeric?: boolean;
  render?: (row: T, index: number) => ReactNode;
  className?: string;
  headerClassName?: string;
}

export interface TableProps<T> {
  columns: Column<T>[];
  data: T[];
  rowKey: (row: T, index: number) => string | number;
  /** 加载态：渲染骨架行 */
  loading?: boolean;
  /** 骨架行数 */
  skeletonRows?: number;
  /** 空数据展示 */
  empty?: ReactNode;
  /** 表底分页器/汇总（由调用方控制分页） */
  footer?: ReactNode;
  onRowClick?: (row: T) => void;
  className?: string;
}

/**
 * 通用数据表。表头浅底 + 小写字距；行 1px line 分隔 + hover 浅底。
 * 数字列统一右对齐等宽，强化「仪表读数」观感。
 */
export function Table<T>({
  columns,
  data,
  rowKey,
  loading = false,
  skeletonRows = 5,
  empty,
  footer,
  onRowClick,
  className = '',
}: TableProps<T>): JSX.Element {
  const alignClass = (c: Column<T>) =>
    c.align === 'right' || c.numeric ? 'text-right font-mono' : c.align === 'center' ? 'text-center' : 'text-left';

  return (
    <div className={['w-full overflow-x-auto', className].filter(Boolean).join(' ')}>
      <table className="w-full text-base border-collapse">
        <thead>
          <tr>
            {columns.map((c) => (
              <th
                key={c.key}
                className={[
                  'px-4 py-2.5 bg-surface-2 text-ink-soft text-xs uppercase tracking-wide font-medium whitespace-nowrap',
                  alignClass(c),
                  c.headerClassName ?? '',
                ].join(' ')}
              >
                {c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {loading
            ? Array.from({ length: skeletonRows }).map((_, i) => (
                <tr key={`sk-${i}`} className="border-b border-line">
                  {columns.map((c) => (
                    <td key={c.key} className={['px-4 py-3', alignClass(c)].join(' ')}>
                      <Skeleton className="h-4 w-full max-w-[120px]" />
                    </td>
                  ))}
                </tr>
              ))
            : data.map((row, idx) => (
                <tr
                  key={rowKey(row, idx)}
                  onClick={onRowClick ? () => onRowClick(row) : undefined}
                  className={[
                    'border-b border-line',
                    onRowClick ? 'cursor-pointer hover:bg-surface-2' : 'hover:bg-surface-2',
                  ].join(' ')}
                >
                  {columns.map((c) => (
                    <td
                      key={c.key}
                      className={['px-4 py-3 text-ink', alignClass(c), c.className ?? ''].join(' ')}
                    >
                      {c.render ? c.render(row, idx) : (row as Record<string, ReactNode>)[c.key]}
                    </td>
                  ))}
                </tr>
              ))}
        </tbody>
      </table>
      {!loading && data.length === 0 && empty && (
        <div className="py-10">{empty}</div>
      )}
      {footer && <div className="mt-3 flex items-center justify-between">{footer}</div>}
    </div>
  );
}
