import { get, post, put, upload } from '@/lib/apiClient';
import type { AssetBrief, PolishRequest, PolishVO, ResumeVO } from './types';

/** POST /resume/upload (multipart file) */
export function uploadResume(file: File): Promise<ResumeVO> {
  return upload<ResumeVO>('/api/resume/upload', file);
}

/** GET /resume/list */
export function listResumes(): Promise<AssetBrief[]> {
  return get<AssetBrief[]>('/api/resume/list');
}

/** GET /resume/{assetId} */
export function getResume(assetId: string): Promise<ResumeVO> {
  return get<ResumeVO>(`/api/resume/${assetId}`);
}

/** POST /resume/polish */
export function polishResume(req: PolishRequest): Promise<PolishVO> {
  return post<PolishVO>('/api/resume/polish', req);
}

/** PUT /resume/{assetId} 保存正文（待后端落地，best-effort）。 */
export function saveResumeBody(assetId: string, body: string): Promise<ResumeVO> {
  return put<ResumeVO>(`/api/resume/${assetId}`, { body });
}
