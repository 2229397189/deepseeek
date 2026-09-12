import { get, post, put, upload } from '@/lib/apiClient';
import type { AssetBrief, PolishRequest, PolishVO, ResumeVO, VersionVO } from './types';

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

/** PUT /resume/{assetId} 保存正文。 */
export function saveResumeBody(assetId: string, body: string): Promise<ResumeVO> {
  return put<ResumeVO>(`/api/resume/${assetId}`, { body });
}

/** GET /resume/{assetId}/versions 版本列表。 */
export function listVersions(assetId: string): Promise<VersionVO[]> {
  return get<VersionVO[]>(`/api/resume/${assetId}/versions`);
}

/** POST /resume/{assetId}/versions/{v}/rollback 回滚。 */
export function rollbackVersion(assetId: string, versionNo: number): Promise<ResumeVO> {
  return post<ResumeVO>(`/api/resume/${assetId}/versions/${versionNo}/rollback`);
}

/** GET /resume/{assetId}/export 导出正文。 */
export function exportBody(assetId: string, format = 'md'): Promise<string> {
  return get<string>(`/api/resume/${assetId}/export?format=${format}`);
}

