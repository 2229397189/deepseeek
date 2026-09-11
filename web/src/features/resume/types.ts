import type { ParseStatus } from '@/types/common';

/** 简历档案（解析后的结构化字段）。docs §5.1 /resume/{assetId}。 */
export interface ResumeProfile {
  summary?: string;
  skills?: string[];
  experiences?: ResumeExperience[];
  projects?: ResumeProject[];
  education?: ResumeEducation[];
  weakPoints?: string[];
  experienceYears?: number;
}

export interface ResumeExperience {
  company?: string;
  title?: string;
  period?: string;
  description?: string;
}

export interface ResumeProject {
  name?: string;
  role?: string;
  period?: string;
  description?: string;
}

export interface ResumeEducation {
  school?: string;
  major?: string;
  degree?: string;
  period?: string;
}

/** 简历完整 VO。 */
export interface ResumeVO {
  assetId: string;
  fileName?: string;
  originalName?: string;
  parseStatus: ParseStatus;
  errorMsg?: string;
  updatedAt?: string;
  profile: ResumeProfile;
  /** Markdown 正文。 */
  body?: string;
}

/** 简历列表简要。 */
export interface AssetBrief {
  assetId: string;
  originalName?: string;
  parseStatus: ParseStatus;
  updatedAt?: string;
}

/** 润色请求。 */
export interface PolishRequest {
  assetId: string;
  selectedText: string;
  mode?: 'POLISH' | 'CUSTOMIZE';
  instruction?: string;
  jobTitle?: string;
  jdText?: string;
}

/** 润色结果。 */
export interface PolishVO {
  runId: string;
  mode: string;
  original: string;
  polished: string;
  reasons: string[];
  matchedSkills: string[];
  costCredit: number;
  flightMode: boolean;
}
