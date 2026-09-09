import client from './client';

export interface ChatResult {
  reply: string;
  model: string;
  strategy: string;
  failover: boolean;
}

export interface ModelStatus {
  [modelName: string]: string; // 兼容旧格式
}

/** 模型详细信息（v2） */
export interface ModelDetail {
  name: string;
  status: string;           // UP / DOWN
  circuit: string;           // CLOSED / OPEN / HALF_OPEN
  failures: number;
  baseUrl: string;
  tags: string[];
  fallback: string;
  priority: number;
  maxTokens: number;
  timeoutSeconds: number;
  keyCount: number;
}

export interface HistoryMessage {
  role: 'user' | 'assistant';
  content: string;
  model?: string; // assistant 消息来源模型
}

export interface StreamCallbacks {
  onToken: (token: string) => void;
  onDone: (reply: string) => void;
  onError: (error: string) => void;
}

export async function sendMessage(
  message: string, taskType?: string | null, history?: HistoryMessage[], model?: string | null,
) {
  return client.post<any, { data: ChatResult }>('/chat', {
    message,
    taskType: taskType || null,
    history: history || [],
    model: model || null,
  });
}

/**
 * 流式发送消息 — 通过 SSE 逐 token 返回
 * 返回 abort 函数用于取消请求
 */
export function sendMessageStream(
  message: string,
  callbacks: StreamCallbacks,
  taskType?: string | null,
  history?: HistoryMessage[],
  model?: string | null,
): () => void {
  const abortController = new AbortController();

  const token = localStorage.getItem('token');
  fetch('/api/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({
      message,
      taskType: taskType || null,
      history: history || [],
      model: model || null,
    }),
    signal: abortController.signal,
  })
    .then(async (response) => {
      if (!response.ok) {
        const text = await response.text();
        callbacks.onError(text || `HTTP ${response.status}`);
        return;
      }
      const reader = response.body?.getReader();
      if (!reader) {
        callbacks.onError('浏览器不支持流式读取');
        return;
      }
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
            const data = line.substring(5).trim();
            handleSSEEvent(currentEvent, data, callbacks);
            currentEvent = '';
          }
          // 空行表示事件结束，重置
        }
      }
    })
    .catch((err) => {
      if (err.name === 'AbortError') return;
      callbacks.onError(err.message || '网络请求失败');
    });

  return () => abortController.abort();
}

/** 根据 SSE event 类型处理数据 */
function handleSSEEvent(event: string, data: string, callbacks: StreamCallbacks) {
  if (event === 'done') {
    try {
      const obj = JSON.parse(data);
      callbacks.onDone(obj.reply || '');
    } catch {
      callbacks.onDone(data);
    }
  } else if (event === 'error') {
    callbacks.onError(data);
  } else {
    // token 事件
    callbacks.onToken(data);
  }
}

/** 多模态对话 — 发送图片 + 文字 */
export async function sendMessageMultimodal(
  message: string, images: string[], taskType?: string | null, model?: string | null,
) {
  return client.post<any, { data: ChatResult }>('/chat/multimodal', {
    message,
    images,
    taskType: taskType || null,
    model: model || null,
  });
}

export async function getModelStatus() {
  return client.get<any, { data: ModelDetail[] }>('/models');
}