/** AI 面试相关 DTO。docs §5.2 /interview/*。 */
export interface InterviewSession {
  sessionId: string;
  jobTitle?: string;
  status: 'CREATED' | 'IN_PROGRESS' | 'FINISHED';
  createdAt?: string;
  score?: number;
}

export interface MessageVO {
  role: 'user' | 'assistant' | 'system';
  content: string;
  createdAt?: string;
}

export interface InterviewSessionDetail {
  sessionId: string;
  status: 'CREATED' | 'IN_PROGRESS' | 'FINISHED';
  score?: number;
  messages: MessageVO[];
  report?: InterviewReport;
}

/** 一次作答的往返。docs §5.2 /interview/sessions/{id}/answer。 */
export interface InterviewTurn {
  userMessage: MessageVO;
  aiMessage: MessageVO;
  transcript?: string;
}

export interface InterviewReport {
  sessionId: string;
  score: number;
  dimensions: Record<string, number>;
  weakPoints: string[];
  suggestion: string;
  createdAt?: string;
}

export interface StartInterviewRequest {
  resumeAssetId: string;
  jobTitle?: string;
  model?: string;
}
