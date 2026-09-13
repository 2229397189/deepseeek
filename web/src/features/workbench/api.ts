import { get, post } from '@/lib/apiClient';
import type { AvailableModels, IntentResult } from './types';

/** POST /workspace/intent {text} -> 意图识别结果。 */
export function detectIntent(text: string): Promise<IntentResult> {
  return post<IntentResult>('/api/workspace/intent', { text });
}

/** GET /models/available -> 可用模型列表（默认 deepseek-chat）。 */
export function getAvailableModels(): Promise<AvailableModels> {
  return get<AvailableModels>('/api/models/available');
}
