/** 模型管理 / 计费规则。docs §5.1 /admin/models, /admin/pricing。 */
export interface ModelConfig {
  id: string;
  name: string;
  provider: string;
  baseUrl: string;
  model: string;
  enabled: boolean;
  createdAt?: string;
  /** 仅下发一次，编辑时不应回填。 */
  apiKey?: string;
}

export interface ModelCreateRequest {
  name: string;
  provider: string;
  baseUrl: string;
  model: string;
  apiKey: string;
  enabled: boolean;
}

export type ModelUpdateRequest = Partial<ModelCreateRequest>;

export interface ModelTestResult {
  ok: boolean;
  latencyMs?: number;
  error?: string;
}

export interface PricingRule {
  bizType: string;
  unitCredit: number;
  description?: string;
}
