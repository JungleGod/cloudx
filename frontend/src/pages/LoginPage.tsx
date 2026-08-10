import { useState } from 'react';
import { Form, Input, Button, message } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import { login } from '../api/auth';
import { useAuth } from '../contexts/AuthContext';

export default function LoginPage() {
  const [loading, setLoading] = useState(false);
  const auth = useAuth();
  const navigate = useNavigate();

  const onFinish = async (values: { username: string; password: string }) => {
    setLoading(true);
    try {
      const res = await login(values);
      auth.login(res.data.token, res.data.username, res.data.role || 'user');
      message.success(`欢迎回来，${res.data.username}`);
      navigate('/dashboard', { replace: true });
    } catch {
      // error handled by interceptor
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <div style={{ marginBottom: 32 }}>
        <h2 style={{ fontSize: 24, fontWeight: 600, margin: '0 0 4px', color: '#18181b' }}>登录 CloudX</h2>
        <p style={{ color: '#71717a', margin: 0, fontSize: 14 }}>
          还没有账号？<Link to="/register" style={{ fontWeight: 500 }}>立即注册 →</Link>
        </p>
      </div>

      <Form size="large" onFinish={onFinish} autoComplete="off" layout="vertical">
        <Form.Item
          label="用户名"
          name="username"
          rules={[{ required: true, message: '请输入用户名' }]}
          style={{ marginBottom: 20 }}
        >
          <Input
            prefix={<UserOutlined />}
            placeholder="请输入用户名"
            style={{ height: 44, borderRadius: 6 }}
          />
        </Form.Item>

        <Form.Item
          label="密码"
          name="password"
          rules={[{ required: true, message: '请输入密码' }]}
          style={{ marginBottom: 24 }}
        >
          <Input.Password
            prefix={<LockOutlined />}
            placeholder="请输入密码"
            style={{ height: 44, borderRadius: 6 }}
          />
        </Form.Item>

        <Form.Item style={{ marginBottom: 0 }}>
          <Button
            type="primary"
            htmlType="submit"
            loading={loading}
            block
            style={{ height: 44, borderRadius: 6, fontWeight: 500, fontSize: 15 }}
          >
            登录
          </Button>
        </Form.Item>
      </Form>
    </div>
  );
}
