import { del, get, post } from '@/lib/apiClient';
import type {
  InterviewReport,
  InterviewSession,
  InterviewSessionDetail,
  InterviewTurn,
  MessageVO,
  StartInterviewRequest,
} from './types';

/** POST /interview/start */
export function startInterview(req: StartInterviewRequest): Promise<InterviewSession> {
  return post<InterviewSession>('/api/interview/start', req);
}

/** GET /interview/sessions */
export function listInterviewSessions(): Promise<InterviewSession[]> {
  return get<InterviewSession[]>('/api/interview/sessions');
}

/** GET /interview/sessions/{id} */
export function getInterviewSession(id: string): Promise<InterviewSessionDetail> {
  return get<InterviewSessionDetail>(`/api/interview/sessions/${id}`);
}

/** POST /interview/sessions/{id}/answer */
export function answerInterview(id: string, content: string): Promise<InterviewTurn> {
  return post<InterviewTurn>(`/api/interview/sessions/${id}/answer`, { content });
}

/** POST /interview/sessions/{id}/finish */
export function finishInterview(id: string): Promise<InterviewReport> {
  return post<InterviewReport>(`/api/interview/sessions/${id}/finish`, { id });
}

/** DELETE /interview/sessions/{id} —— 归档（逻辑删除）。 */
export function deleteInterviewSession(id: string): Promise<void> {
  return del<void>(`/api/interview/sessions/${id}`);
}

/** POST /interview/sessions/{id}/next —— NEXT 阶段：依据出题计划主动推进到下一题 */
export function nextInterviewQuestion(id: string): Promise<MessageVO> {
  return post<MessageVO>(`/api/interview/sessions/${id}/next`, {});
}

/** GET /interview/sessions/{id}/report */
export function getInterviewReport(id: string): Promise<InterviewReport> {
  return get<InterviewReport>(`/api/interview/sessions/${id}/report`);
}

/** POST /interview/transcribe {audio base64} -> {text} */
export function transcribe(audio: string, format?: string): Promise<{ text: string }> {
  return post<{ text: string }>('/api/interview/transcribe', { audio, format });
}

/** 类型再导出，便于组件直接引用 MessageVO 等。 */
export type { MessageVO };
