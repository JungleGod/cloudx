import client from './client';

export interface LoginParams {
  username: string;
  password: string;
}

export interface RegisterParams {
  username: string;
  password: string;
  email?: string;
}

export interface UserInfo {
  userId: number;
  username: string;
  email: string;
  role: string;
}

export async function login(params: LoginParams) {
  return client.post<any, { data: { token: string; userId: number; username: string; role: string } }>(
    '/user/login',
    params,
  );
}

export interface AdminUser {
  id: number;
  username: string;
  email: string;
  role: string;
  status: number;
  monthlyQuota: number | null;
  monthUsed: number;
  createdAt: string;
}

export async function listUsers() {
  return client.get<any, { data: AdminUser[] }>('/admin/users');
}

export async function setUserRole(userId: number, role: string) {
  return client.put<any, any>(`/admin/users/${userId}/role`, { role });
}

export async function toggleUserStatus(userId: number, status: number) {
  return client.put<any, any>(`/admin/users/${userId}/status`, { status });
}

export async function setUserQuota(userId: number, quota: number | null) {
  return client.put<any, any>(`/admin/users/${userId}/quota`, { quota });
}

export async function register(params: RegisterParams) {
  return client.post<any, { data: { userId: number; username: string } }>(
    '/user/register',
    params,
  );
}

export async function getMe() {
  return client.get<any, { data: UserInfo }>('/user/me');
}