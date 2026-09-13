/**
 * 通用类型定义（跨 feature 共享）。
 * 设计系统 / 契约来源：docs/01-前端架构与设计系统.md §4.5。
 */

/** 统一响应体：BFF 所有接口返回 {code,message,data,timestamp}，code=0 成功。 */
export interface Result<T> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
}

/** 分页响应（Offset 分页，与 /billing/ledger 对齐）。 */
export interface Page<T> {
  total: number;
  pageNum: number;
  pageSize: number;
  records: T[];
}

/** 业务类型（SSE / 指标口径）。 */
export type BizType =
  | 'DECIDE'
  | 'INTERVIEW'
  | 'RESUME_PARSE'
  | 'RAG_SEARCH'
  | 'DECIDE_PREVIEW';

/** 解析状态（JD / 简历上传后）。 */
export type ParseStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';

/** 运行态（调用治理面板）。 */
export type RunStatus = 'SUCCESS' | 'FAILURE' | 'RETRY' | 'REPLAY' | 'LOCAL_FALLBACK';

/** 语义色调（与 shared 组件一致）。 */
export type Tone = 'brand' | 'info' | 'ok' | 'warn' | 'danger' | 'amber' | 'neutral';

/** 统一异常：携带 BFF 错误码。 */
export class ApiError extends Error {
  public readonly code: number;

  constructor(code: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    // 保持原型链（ES5 继承修复）
    Object.setPrototypeOf(this, ApiError.prototype);
  }
}
