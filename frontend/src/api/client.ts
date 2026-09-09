import axios from 'axios';
import { message } from 'antd';

const client = axios.create({
  baseURL: '/api',
  timeout: 30000,
});

// 请求拦截器：自动带 Token
client.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// 响应拦截器：统一错误处理
client.interceptors.response.use(
  (res) => {
    const { code, msg } = res.data;
    if (code !== 200) {
      message.error(msg || '请求失败');
      return Promise.reject(new Error(msg));
    }
    return res.data;
  },
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('token');
      localStorage.removeItem('username');
      window.location.href = '/login';
    }
    message.error(err.response?.data?.msg || '网络错误');
    return Promise.reject(err);
  },
);

export default client;