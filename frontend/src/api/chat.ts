import client from './client';

export interface ChatResult {
  reply: string;
  model: string;
  strategy: string;
  failover: boolean;
}

export interface ModelStatus {
  [modelName: string]: string;
}

export interface HistoryMessage {
  role: 'user' | 'assistant';
  content: string;
}

export async function sendMessage(message: string, taskType?: string | null, history?: HistoryMessage[]) {
  return client.post<any, { data: ChatResult }>('/chat', {
    message,
    taskType: taskType || null,
    history: history || [],
  });
}

export async function getModelStatus() {
  return client.get<any, { data: ModelStatus }>('/models');
}