import type { ParseStatus } from '@/types/common';

/** 流水线单步。docs §4.4 / §6.3。 */
export interface PipelineStep {
  name: string;
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
  detail?: string;
  elapsedMs?: number;
}

/** JD 上传结果。docs §5.1 /decision/jd/upload。 */
export interface JdUploadVO {
  assetId: string;
  parseStatus: ParseStatus;
  preview?: string;
  hardRequirements?: string[];
  skills?: string[];
  charCount?: number;
  errorMsg?: string;
}

/** 快速预览结果。docs §5.1 /decision/preview。 */
export interface PreviewVO {
  score: number;
  scoreBand: 'HIGH' | 'MID' | 'LOW';
  conclusion: string;
  coveredSkills: string[];
  missingSkills: string[];
  weakPointsHit: string[];
  risks: string[];
  advice: string;
  steps: PipelineStep[];
  costCredit: number;
  flightMode: boolean;
}

/** 完整分析结果（latestAnalysis）。 */
export interface AnalysisVO {
  score: number;
  scoreBand: 'HIGH' | 'MID' | 'LOW';
  conclusion: string;
  risks: string[];
  weakPoints: string[];
  advice: string;
  steps: PipelineStep[];
  createdAt?: string;
}

/** 一次分析会话详情。docs §5.1 /decision/sessions/{id}。 */
export interface SessionDetailVO {
  id: string;
  title?: string;
  jobTitle?: string;
  resumeAssetId?: string;
  jdAssetId?: string;
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
  createdAt?: string;
  analyses: AnalysisVO[];
  messages: DecisionMessage[];
  latestAnalysis?: AnalysisVO;
}

/** 会话列表简要。 */
export interface SessionBrief {
  id: string;
  title?: string;
  jobTitle?: string;
  status: string;
  createdAt?: string;
  score?: number;
}

/** 追问 RAG 命中。 */
export interface RAGHit {
  documentId: string;
  title: string;
  content: string;
  score?: number;
}

/** 追问响应。docs §5.1 /decision/sessions/{id}/ask。 */
export interface AskVO {
  answer: string;
  hits: RAGHit[];
  recallGap?: string;
  messages: DecisionMessage[];
  costCredit: number;
  flightMode: boolean;
}

/** 决策消息。 */
export interface DecisionMessage {
  role: 'user' | 'assistant';
  content: string;
  createdAt?: string;
}

/** 分析请求。 */
export interface AnalyzeRequest {
  sessionId?: string;
  resumeAssetId: string;
  jdAssetId?: string;
  jdText?: string;
  jobTitle?: string;
  title?: string;
}
