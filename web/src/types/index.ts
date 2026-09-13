/**
 * 统一类型再导出。feature 内就近引用 ./types，跨 feature 走 @/types。
 * docs/01-前端架构与设计系统.md §4.5。
 */
export type {
  Result,
  Page,
  BizType,
  ParseStatus,
  RunStatus,
  Tone,
} from './common';
export { ApiError } from './common';
