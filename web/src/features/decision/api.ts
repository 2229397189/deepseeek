import { del, get, post } from '@/lib/apiClient';
import type {
  AnalyzeRequest,
  AskVO,
  DecisionMessage,
  JdUploadVO,
  SessionBrief,
  SessionDetailVO,
} from './types';

/** POST /decision/jd/upload (multipart file) */
export function uploadJd(file: File): Promise<JdUploadVO> {
  return upload<JdUploadVO>('/api/decision/jd/upload', file);
}

/** POST /decision/jd/upload 文本直传（best-effort 兼容）。 */
export function uploadJdText(text: string): Promise<JdUploadVO> {
  return post<JdUploadVO>('/api/decision/jd/upload', { text });
}

/** POST /decision/analyze (同步，≤120s) */
export function analyze(req: AnalyzeRequest): Promise<SessionDetailVO> {
  return post<SessionDetailVO>('/api/decision/analyze', req, { timeoutMs: 120_000 });
}

/** GET /decision/sessions */
export function listSessions(): Promise<SessionBrief[]> {
  return get<SessionBrief[]>('/api/decision/sessions');
}

/** GET /decision/sessions/{id} */
export function getSession(id: string): Promise<SessionDetailVO> {
  return get<SessionDetailVO>(`/api/decision/sessions/${id}`);
}

/** POST /decision/sessions/{id}/ask */
export function askSession(id: string, question: string): Promise<AskVO> {
  return post<AskVO>(`/api/decision/sessions/${id}/ask`, { question } as {
    question: string;
  } & Partial<DecisionMessage>);
}

/** DELETE /decision/sessions/{id} */
export function deleteSession(id: string): Promise<void> {
  return del<void>(`/api/decision/sessions/${id}`);
}
