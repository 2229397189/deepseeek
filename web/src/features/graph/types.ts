/** 知识图谱节点 / 边 / 证据。docs §5.2 /graph/*。 */
export type GraphNodeType =
  | 'CANDIDATE'
  | 'RESUME'
  | 'JOB'
  | 'INTERVIEW'
  | 'SKILL'
  | 'PROJECT'
  | 'POSITION';

export type GraphStatus = 'WEAK' | 'NORMAL';

export interface GraphNode {
  id: string;
  label: string;
  type: GraphNodeType;
  status?: GraphStatus;
  /** 层级：0=候选人中心，1=简历/求职/面试，2=技能/项目/岗位。 */
  layer: number;
}

export interface GraphEdge {
  source: string;
  target: string;
  relation: string;
}

export interface Evidence {
  source: string;
  excerpt: string;
  createdAt?: string;
}
