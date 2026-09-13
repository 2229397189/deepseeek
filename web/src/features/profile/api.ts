import { get, post } from '@/lib/apiClient';
import type { CapabilityTag, LongTermMemory } from './types';

/** GET /profile/tags */
export function getCapabilityTags(): Promise<CapabilityTag[]> {
  return get<CapabilityTag[]>('/api/profile/tags');
}

/** GET /profile/memories */
export function getMemories(): Promise<LongTermMemory[]> {
  return get<LongTermMemory[]>('/api/profile/memories');
}

/** POST /profile/memories/{id}/confirm */
export function confirmMemory(id: string): Promise<void> {
  return post<void>(`/api/profile/memories/${id}/confirm`);
}

/** POST /profile/memories/{id}/correct */
export function correctMemory(id: string, correctedContent: string): Promise<void> {
  return post<void>(`/api/profile/memories/${id}/correct`, { correctedContent });
}
