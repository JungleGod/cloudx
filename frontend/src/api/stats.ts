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