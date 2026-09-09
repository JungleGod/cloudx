import client from './client';

export interface ApiKeyVO {
  id: number;
  accessKey: string;
  secretKey: string;
  name: string;
  status: number;
  quotaDaily: number;
  quotaTotal: number;
  expiredAt: string;
  createdAt: string;
}

export async function listKeys() {
  return client.get<any, { data: ApiKeyVO[] }>('/keys');
}

export async function createKey(name: string) {
  return client.post<any, { data: ApiKeyVO }>('/keys', { name });
}

export async function toggleKey(id: number, enable: boolean) {
  return client.put<any, { data: null }>(`/keys/${id}/toggle`, { enable });
}