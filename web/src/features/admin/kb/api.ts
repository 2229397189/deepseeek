import { get, post, upload } from '@/lib/apiClient';
import type { KbDocument, KbHit, KbListParams } from './types';

/** GET /kb/documents ?title=&documentId= */
export function listDocuments(params: KbListParams = {}): Promise<KbDocument[]> {
  return get<KbDocument[]>('/api/kb/documents', params as Record<string, unknown>);
}

/** POST /kb/documents (multipart file) */
export function uploadDocument(file: File): Promise<KbDocument> {
  return upload<KbDocument>('/api/kb/documents', file);
}

/** GET /kb/search ?q=&topK= */
export function searchKb(q: string, topK = 10): Promise<KbHit[]> {
  return get<KbHit[]>('/api/kb/search', { q, topK });
}

/** POST /kb/reindex -> {accepted} */
export function reindexKb(): Promise<{ accepted: boolean }> {
  return post<{ accepted: boolean }>('/api/kb/reindex');
}
