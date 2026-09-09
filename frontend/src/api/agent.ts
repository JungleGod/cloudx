import client from './client';

export interface AgentDefinition {
  id: number;
  name: string;
  description: string;
  systemPrompt: string;
  model: string | null;
  temperature: number;
  maxIterations: number;
  status: number;
  createdBy: number;
  createdAt: string;
  updatedAt: string;
}

export interface ToolDefinition {
  id: number;
  name: string;
  description: string;
  category: 'built-in' | 'http' | 'internal-api';
  parametersSchema: any;
  requiredRole: 'public' | 'user' | 'admin';
  timeoutMs: number;
  retryCount: number;
  enabled: boolean;
}

export interface AgentExecuteResult {
  answer: string;
  iterations: number;
  toolSteps: ToolStep[];
  totalTokens: number;
  elapsedMs: number;
  interrupted: boolean;
}

export interface ToolStep {
  iteration: number;
  toolName: string;
  arguments: string;
  result: string;
  success: boolean;
  elapsedMs: number;
}

// ==================== Agent 执行 ====================

/** 同步执行 Agent */
export async function executeAgent(params: {
  message: string;
  agentId?: number;
  agentName?: string;
  systemPrompt?: string;
  model?: string;
  role?: string;
  history?: { role: string; content: string }[];
}) {
  return client.post<any, { data: AgentExecuteResult }>('/agents/execute', {
    ...params,
    userId: getUserId(),
  });
}

/** Agent 流式回调事件类型 */
export type AgentEventType =
  | 'thinking'
  | 'tool_call_start'
  | 'tool_call_args'
  | 'tool_call_executing'
  | 'tool_result'
  | 'token'
  | 'done'
  | 'error';

export interface AgentStreamEvent {
  toolName?: string;
  callId?: string;
  delta?: string;
  arguments?: string;
  result?: string;
  success?: boolean;
  elapsedMs?: number;
  token?: string;
  content?: string;
  message?: string;
}

export interface AgentStreamCallbacks {
  onThinking?: () => void;
  onToolCallStart?: (toolName: string, callId: string) => void;
  onToolCallArgs?: (callId: string, delta: string) => void;
  onToolCallExecuting?: (toolName: string, args: string) => void;
  onToolResult?: (toolName: string, result: string, success: boolean, elapsedMs: number) => void;
  onToken?: (token: string) => void;
  onDone?: (content: string) => void;
  onError?: (error: string) => void;
}

/** 流式执行 Agent（SSE）— 返回 abort 函数 */
export function executeAgentStream(
  params: {
    message: string;
    agentId?: number;
    agentName?: string;
    systemPrompt?: string;
    model?: string;
    role?: string;
    history?: { role: string; content: string }[];
  },
  callbacks: AgentStreamCallbacks,
): () => void {
  const abortController = new AbortController();
  const token = localStorage.getItem('token');

  fetch('/api/agents/execute/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ ...params, userId: getUserId(), stream: true }),
    signal: abortController.signal,
  })
    .then(async (response) => {
      if (!response.ok) {
        const text = await response.text();
        callbacks.onError?.(text || `HTTP ${response.status}`);
        return;
      }
      const reader = response.body?.getReader();
      if (!reader) { callbacks.onError?.('浏览器不支持流式读取'); return; }

      const decoder = new TextDecoder();
      let buffer = '';
      let currentEvent = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (line.startsWith('event:')) {
            currentEvent = line.substring(6).trim();
          } else if (line.startsWith('data:')) {
            const dataStr = line.substring(5).trim();
            try {
              const data = JSON.parse(dataStr);
              dispatchAgentEvent(currentEvent as AgentEventType, data, callbacks);
            } catch {
              // ignore parse errors
            }
            currentEvent = '';
          }
        }
      }
    })
    .catch((err) => {
      if (err.name === 'AbortError') return;
      callbacks.onError?.(err.message || '网络请求失败');
    });

  return () => abortController.abort();
}

function dispatchAgentEvent(event: AgentEventType, data: AgentStreamEvent, cb: AgentStreamCallbacks) {
  switch (event) {
    case 'thinking':        cb.onThinking?.(); break;
    case 'tool_call_start': cb.onToolCallStart?.(data.toolName || '', data.callId || ''); break;
    case 'tool_call_args':  cb.onToolCallArgs?.(data.callId || '', data.delta || ''); break;
    case 'tool_call_executing': cb.onToolCallExecuting?.(data.toolName || '', data.arguments || ''); break;
    case 'tool_result':     cb.onToolResult?.(data.toolName || '', data.result || '', data.success ?? false, data.elapsedMs ?? 0); break;
    case 'token':           cb.onToken?.(data.token || ''); break;
    case 'done':            cb.onDone?.(data.content || ''); break;
    case 'error':           cb.onError?.(data.message || 'Agent 执行错误'); break;
  }
}

// ==================== Agent CRUD ====================

/** 获取 Agent 列表 */
export async function listAgents() {
  return client.get<any, { data: AgentDefinition[] }>('/agents');
}

/** 创建 Agent */
export async function createAgent(data: Partial<AgentDefinition>) {
  return client.post<any, { data: AgentDefinition }>('/agents', data);
}

/** 更新 Agent */
export async function updateAgent(id: number, data: Partial<AgentDefinition>) {
  return client.put<any, { data: AgentDefinition }>(`/agents/${id}`, data);
}

/** 删除 Agent */
export async function deleteAgent(id: number) {
  return client.delete(`/agents/${id}`);
}

// ==================== 工具 ====================

/** 获取 Agent 绑定的工具 */
export async function getAgentTools(agentId: number, role?: string) {
  return client.get<any, { data: { tools: ToolDefinition[]; count: number } }>(
    `/agents/${agentId}/tools?role=${role || 'user'}`
  );
}

/** 获取所有可用工具 */
export async function getAllTools(role?: string) {
  return client.get<any, { data: { tools: ToolDefinition[]; count: number } }>(
    `/admin/tools?role=${role || 'admin'}`
  );
}

/** 热重载工具 */
export async function reloadTools() {
  return client.post('/admin/tools/reload');
}

function getUserId(): number | undefined {
  try {
    const token = localStorage.getItem('token');
    if (!token) return undefined;
    const payload = JSON.parse(atob(token.split('.')[1]));
    return payload.userId || payload.sub;
  } catch {
    return undefined;
  }
}
