import { del, get, post, put } from '@/lib/apiClient';
import type {
  ModelConfig,
  ModelCreateRequest,
  ModelTestResult,
  ModelUpdateRequest,
  PricingRule,
} from './types';

/** GET /admin/models */
export function listModels(): Promise<ModelConfig[]> {
  return get<ModelConfig[]>('/api/admin/models');
}

/** POST /admin/models */
export function createModel(req: ModelCreateRequest): Promise<ModelConfig> {
  return post<ModelConfig>('/api/admin/models', req);
}

/** PUT /admin/models/{id} */
export function updateModel(id: string, req: ModelUpdateRequest): Promise<ModelConfig> {
  return put<ModelConfig>(`/api/admin/models/${id}`, req);
}

/** DELETE /admin/models/{id} */
export function deleteModel(id: string): Promise<void> {
  return del<void>(`/api/admin/models/${id}`);
}

/** POST /admin/models/{id}/test */
export function testModel(id: string): Promise<ModelTestResult> {
  return post<ModelTestResult>(`/api/admin/models/${id}/test`);
}

/** GET /admin/pricing */
export function getPricing(): Promise<PricingRule[]> {
  return get<PricingRule[]>('/api/admin/pricing');
}
