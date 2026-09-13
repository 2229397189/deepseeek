/** 工作台意图识别 / 模型可用。docs §5.2 /workspace/intent, /models/available。 */
export type IntentKind = 'JD' | 'RESUME' | 'INTERVIEW' | 'UNKNOWN';

export interface IntentResult {
  intent: IntentKind;
  targetRoute: string;
  suggestions: string[];
}

export interface ModelOption {
  id: string;
  label: string;
  provider: string;
}

export interface AvailableModels {
  models: ModelOption[];
  default: string;
}
