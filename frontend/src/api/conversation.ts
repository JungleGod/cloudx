import client from './client';

export interface Conversation {
  id: number;
  title: string;
  model: string | null;
  agentId: number | null;
  messageCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface ConversationMessage {
  id?: number;
  conversationId?: number;
  role: 'user' | 'assistant';
  content: string;
  metadata?: string | null;
  model?: string | null;
  tokens?: number | null;
  createdAt?: string;
}

/** 获取会话列表 */
export async function listConversations(): Promise<Conversation[]> {
  const res = await client.get<any, { data: Conversation[] }>('/conversations');
  return res.data;
}

/** 创建新会话 */
export async function createConversation(title?: string, model?: string, agentId?: number): Promise<Conversation> {
  const res = await client.post<any, { data: Conversation }>('/conversations', { title, model, agentId });
  return res.data;
}

/** 更新会话标题 */
export async function updateConversation(id: number, title: string): Promise<void> {
  await client.put(`/conversations/${id}`, { title });
}

/** 删除会话 */
export async function deleteConversation(id: number): Promise<void> {
  await client.delete(`/conversations/${id}`);
}

/** 获取会话的所有消息 */
export async function getMessages(conversationId: number): Promise<ConversationMessage[]> {
  const res = await client.get<any, { data: ConversationMessage[] }>(`/conversations/${conversationId}/messages`);
  return res.data;
}

/** 追加一条消息 */
export async function appendMessage(
  conversationId: number,
  msg: { role: string; content: string; metadata?: string | null; model?: string | null; tokens?: number | null },
): Promise<void> {
  await client.post(`/conversations/${conversationId}/messages`, msg);
}

/** 清空会话消息 */
export async function clearMessages(conversationId: number): Promise<void> {
  await client.delete(`/conversations/${conversationId}/messages`);
}
