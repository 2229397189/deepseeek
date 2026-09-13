/** 用户能力画像 / 长期记忆。docs §5.2 /profile/*。 */
export type MemoryStatus = 'PENDING' | 'CONFIRMED' | 'CORRECTED';

export interface CapabilityTag {
  tag: string;
  category: string;
  level: string;
  source: string;
  confidence: number;
}

export interface LongTermMemory {
  id: string;
  content: string;
  status: MemoryStatus;
  createdAt?: string;
}
