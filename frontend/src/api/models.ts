import client from './client';

/** 模型配置（脱敏返回体，不含明文 key） */
export interface ModelConfig {
  id: number;
  name: string;
  provider: string;
  baseUrl: string;
  modelName: string;
  hasApiKey: boolean;
  apiKeyMasked: string;
  tags: string;
  priority: number;
  maxFailures: number;
  fallback: string | null;
  temperature: number;
  maxTokens: number;
  timeoutSeconds: number;
  frequencyPenalty: number;
  presencePenalty: number;
  inputPrice: number;
  outputPrice: number;
  status: number;
  createdAt: string;
  updatedAt: string;
}

/** 获取模型列表 */
export async function listModels() {
  return client.get<any, { data: ModelConfig[] }>('/admin/models');
}

/** 创建模型（body 含明文 apiKey） */
export async function createModel(data: Record<string, any>) {
  return client.post<any, { data: ModelConfig }>('/admin/models', data);
}

/** 更新模型（apiKey 留空=保持不变） */
export async function updateModel(id: number, data: Record<string, any>) {
  return client.put<any, { data: ModelConfig }>(`/admin/models/${id}`, data);
}

/** 删除模型 */
export async function deleteModel(id: number) {
  return client.delete(`/admin/models/${id}`);
}
