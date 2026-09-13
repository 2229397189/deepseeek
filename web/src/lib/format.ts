/**
 * 格式化工具：额度 / 日期 / 百分比 / 字节。docs §3.1 主张 3（数据一律等宽）。
 */

/** 整数额度，千分位。 */
export function formatCredit(value: number): string {
  if (!Number.isFinite(value)) return '0';
  return Math.round(value).toLocaleString('en-US');
}

/** 通用数字格式化。 */
export function formatNumber(value: number, fractionDigits = 0): string {
  if (!Number.isFinite(value)) return '-';
  return value.toLocaleString('en-US', {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  });
}

/** 百分比（value 为 0-100 的数值，直接拼接 %）。 */
export function formatPercent(value: number, fractionDigits = 0): string {
  if (!Number.isFinite(value)) return '-';
  return `${value.toFixed(fractionDigits)}%`;
}

/** 评分（0-100 整数）。 */
export function formatScore(value: number): string {
  if (!Number.isFinite(value)) return '-';
  return String(Math.round(value));
}

function toDate(input: string | number | Date): Date | null {
  const d = input instanceof Date ? input : new Date(input);
  return Number.isNaN(d.getTime()) ? null : d;
}

/** YYYY-MM-DD */
export function formatDate(input: string | number | Date): string {
  const d = toDate(input);
  if (!d) return '-';
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${m}-${day}`;
}

/** YYYY-MM-DD HH:mm */
export function formatDateTime(input: string | number | Date): string {
  const d = toDate(input);
  if (!d) return '-';
  const date = formatDate(d);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${date} ${hh}:${mm}`;
}

/** 人类可读字节。 */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  const val = bytes / Math.pow(1024, i);
  return `${i === 0 ? String(val) : val.toFixed(1)} ${units[i]}`;
}

/** 耗时（毫秒 -> 可读）。 */
export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return '0ms';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}
