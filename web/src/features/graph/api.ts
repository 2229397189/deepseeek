import { get } from '@/lib/apiClient';
import type { Evidence, GraphEdge, GraphNode } from './types';

/** GET /graph/nodes */
export function getGraphNodes(): Promise<GraphNode[]> {
  return get<GraphNode[]>('/api/graph/nodes');
}

/** GET /graph/edges */
export function getGraphEdges(): Promise<GraphEdge[]> {
  return get<GraphEdge[]>('/api/graph/edges');
}

/** GET /graph/nodes/{id}/evidence */
export function getNodeEvidence(id: string): Promise<Evidence[]> {
  return get<Evidence[]>(`/api/graph/nodes/${id}/evidence`);
}
