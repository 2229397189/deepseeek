/** 知识库管理（混合检索）。docs §5.2 /kb/*。 */
export interface KbDocument {
  documentId: string;
  title: string;
  chunkCount: number;
  updatedAt?: string;
}

export interface KbHit {
  documentId: string;
  title: string;
  content: string;
  score: number;
  vectorScore: number;
  ftsScore: number;
}

export interface KbListParams {
  title?: string;
  documentId?: string;
}
