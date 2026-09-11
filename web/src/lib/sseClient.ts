import { getToken } from '@/lib/tokenStore';
import { errorMessage } from '@/lib/errorMessages';
import type { BizType } from '@/types/common';

/**
 * SSE 消费封装（fetch + ReadableStream，不用 EventSource）。
 * docs §4.4 / §5.2。
 *
 * 上游 agent-service 流式端点为 POST，需 JSON 体 + lq-token 头；
 * 原生 EventSource 仅支持 GET，故用 fetch 读取 response.body。
 *
 * 事件协议：
 *  - {type:'step', step:{name,status,detail,elapsedMs}}  流水线单步
 *  - {type:'result', status:'SUCCEEDED'|'FAILED', output, ...}  终态
 *  - {type:'token', token}  增量文本（当前协议未使用，保留）
 */

export interface AgentStep {
  name: string;
  status: string;
  detail?: string;
  elapsedMs?: number;
}

export interface AgentResult {
  status: 'SUCCEEDED' | 'FAILED';
  output?: unknown;
  promptTokens?: number;
  outputTokens?: number;
  latencyMs?: number;
  errorCode?: string;
  errorMsg?: string;
}

export interface AgentStreamRequest {
  bizType: BizType;
  bizId?: string;
  stage?: string;
  specHash?: string;
  payload: Record<string, unknown>;
  /** 由调用方或本封装生成，用于断线重连去重。 */
  clientRunKey?: string;
}

export interface StreamCallbacks {
  onStep?: (step: AgentStep) => void;
  onResult?: (output: unknown) => void;
  onToken?: (text: string) => void;
  onError?: (errorCode?: string, errorMsg?: string) => void;
}

export interface StreamHandle {
  /** 主动中断（AbortController.abort）。 */
  close: () => void;
}

const BASE: string = import.meta.env.VITE_API_BASE ?? '';
const RECONNECT_DELAYS = [1000, 2000, 4000];
const MAX_RECONNECT = RECONNECT_DELAYS.length;

interface RawEvent {
  type: string;
  step?: AgentStep;
  status?: string;
  output?: unknown;
  promptTokens?: number;
  outputTokens?: number;
  latencyMs?: number;
  errorCode?: string;
  errorMsg?: string;
  token?: string;
}

export function connectAgentStream(
  req: AgentStreamRequest,
  callbacks: StreamCallbacks,
): StreamHandle {
  let aborted = false;
  let controller: AbortController | null = null;
  let receivedResult = false;
  let attempt = 0;

  const clientRunKey =
    req.clientRunKey ??
    `run_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`;

  const dispatch = (raw: string): void => {
    const trimmed = raw.trim();
    if (!trimmed.startsWith('data:')) return;
    const json = trimmed.slice(trimmed.indexOf('data:') + 5).trim();
    if (!json) return;
    let evt: RawEvent;
    try {
      evt = JSON.parse(json) as RawEvent;
    } catch {
      return;
    }
    if (evt.type === 'step' && evt.step) {
      callbacks.onStep?.(evt.step);
    } else if (evt.type === 'token' && typeof evt.token === 'string') {
      callbacks.onToken?.(evt.token);
    } else if (evt.type === 'result') {
      receivedResult = true;
      if (evt.status === 'FAILED') {
        callbacks.onError?.(evt.errorCode, evt.errorMsg);
      } else {
        callbacks.onResult?.(evt.output);
      }
    }
  };

  const run = async (): Promise<void> => {
    if (aborted) return;
    controller = new AbortController();
    const token = getToken();
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (token) headers['lq-token'] = token;

    try {
      const res = await fetch(`${BASE}/api/agent/stream`, {
        method: 'POST',
        headers,
        body: JSON.stringify({ ...req, clientRunKey }),
        signal: controller.signal,
      });
      if (!res.ok || !res.body) {
        throw new Error(`stream init failed: ${res.status}`);
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      // eslint-disable-next-line no-constant-condition
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let sep: number;
        while ((sep = buffer.indexOf('\n\n')) !== -1) {
          const chunk = buffer.slice(0, sep);
          buffer = buffer.slice(sep + 2);
          dispatch(chunk);
          if (receivedResult) return;
        }
      }
    } catch (err) {
      if (aborted) return;
      if (!receivedResult && attempt < MAX_RECONNECT) {
        const delay = RECONNECT_DELAYS[attempt];
        attempt += 1;
        await new Promise((r) => window.setTimeout(r, delay));
        return run();
      }
      callbacks.onError?.('STREAM_ERROR', errorMessage(-1) ?? '连接中断');
    }
  };

  void run();

  return {
    close: () => {
      aborted = true;
      controller?.abort();
    },
  };
}
