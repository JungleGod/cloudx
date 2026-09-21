import client from './client';

export interface TodayStats {
  calls: number;
  tokens: number;
  cost: number;
}

export interface ModelStats {
  model: string;
  calls: number;
  tokens: number;
  cost: number;
}

export interface DailyStats {
  date: string;
  calls: number;
  tokens: number;
  cost: number;
}

export interface QuotaInfo {
  unlimited: boolean;
  month: string;
  quota: number | null;
  used: number | null;
  remaining: number | null;
  exceeded: boolean;
}

export async function getTodayStats(userId?: number) {
  return client.get<any, { data: TodayStats }>('/stats/today', {
    params: userId ? { userId } : undefined,
  });
}

export async function getStatsByModel(days: number = 7) {
  return client.get<any, { data: ModelStats[] }>('/stats/by-model', {
    params: { days },
  });
}

export async function getStatsDaily(days: number = 30) {
  return client.get<any, { data: DailyStats[] }>('/stats/daily', {
    params: { days },
  });
}

export async function getQuota() {
  return client.get<any, { data: QuotaInfo }>('/stats/quota');
}

// ==================== 管理员：全平台调用记录 ====================

export interface CallLogItem {
  id: number;
  userId: number;
  username: string;
  model: string;
  requestedModel?: string;
  tokensInput: number;
  tokensOutput: number;
  tokensTotal: number;
  cost: number | null;
  latencyMs: number;
  status: string;
  errorMsg?: string;
  createdAt: string;
  requestPreview?: string;
  responsePreview?: string;
  requestBody?: string;
  responseBody?: string;
}

export interface CallLogPage {
  records: CallLogItem[];
  total: number;
}

export interface CallLogQuery {
  page: number;
  size: number;
  userId?: number;
  model?: string;
  status?: string;
  start?: string;
  end?: string;
}

export async function listCallLogs(params: CallLogQuery) {
  return client.get<any, { data: CallLogPage }>('/admin/calllogs', { params });
}

export async function getCallLog(id: number) {
  return client.get<any, { data: CallLogItem }>(`/admin/calllogs/${id}`);
}

// ==================== 管理员：Agent 执行审计 ====================

/** 一次 Agent 执行（按 session_id 聚合，含多轮 LLM/工具调用） */
export interface AgentLogSession {
  sessionId: string;
  userId: number;
  username: string;
  agentId: number | null;
  agentName: string;
  steps: number;
  tokensInput: number;
  tokensOutput: number;
  errorSteps: number;
  status: string;
  startedAt: string;
  endedAt: string;
}

export interface AgentLogSessionPage {
  records: AgentLogSession[];
  total: number;
}

export interface AgentLogStep {
  id: number;
  iteration: number;
  stepType: string;
  model?: string;
  toolName?: string;
  tokensInput: number;
  tokensOutput: number;
  latencyMs: number;
  status: string;
  errorMsg?: string;
  createdAt: string;
  requestPreview?: string;
  responsePreview?: string;
}

export interface AgentLogQuery {
  page: number;
  size: number;
  userId?: number;
  agentId?: number;
  start?: string;
  end?: string;
}

export async function listAgentLogs(params: AgentLogQuery) {
  return client.get<any, { data: AgentLogSessionPage }>('/admin/agent-logs', { params });
}

export async function getAgentLogDetail(sessionId: string) {
  return client.get<any, {
    data: { sessionId: string; agentName: string; username: string; steps: AgentLogStep[] };
  }>(`/admin/agent-logs/${sessionId}`);
}

// ==================== 管理员：月度账单汇总 ====================

export interface BillSummary {
  calls: number;
  successCalls: number;
  tokensInput: number;
  tokensOutput: number;
  cost: number;
}

export interface BillUserRow {
  userId: number;
  username: string;
  calls: number;
  tokensTotal: number;
  cost: number;
}

export interface MonthBill {
  month: string;
  summary: BillSummary;
  byUser: BillUserRow[];
  byModel: ModelStats[];
  daily: DailyStats[];
}

export async function getMonthBill(month?: string) {
  return client.get<any, { data: MonthBill }>('/admin/bills', {
    params: month ? { month } : undefined,
  });
}