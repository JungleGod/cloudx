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
}

export async function login(params: LoginParams) {
  return client.post<any, { data: { token: string; userId: number; username: string } }>(
    '/user/login',
    params,
  );
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