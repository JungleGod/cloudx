import { useState } from 'react';
import { Card, Form, Input, Button, message, Divider } from 'antd';
import { UserOutlined, LockOutlined, ApiOutlined } from '@ant-design/icons';
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
      auth.login(res.data.token, res.data.username);
      message.success(`欢迎回来，${res.data.username}`);
      navigate('/dashboard', { replace: true });
    } catch {
      // error handled by interceptor
    } finally {
      setLoading(false);
    }
  };

  return (
    <Card style={{ width: 400, boxShadow: '0 2px 8px rgba(0,0,0,0.1)' }}>
      <div style={{ textAlign: 'center', marginBottom: 32 }}>
        <ApiOutlined style={{ fontSize: 48, color: '#1677ff' }} />
        <h1 style={{ margin: '12px 0 4px', fontSize: 24 }}>CloudX</h1>
        <p style={{ color: '#999' }}>AI 网关平台</p>
      </div>
      <Form size="large" onFinish={onFinish} autoComplete="off">
        <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
          <Input prefix={<UserOutlined />} placeholder="用户名" />
        </Form.Item>
        <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
          <Input.Password prefix={<LockOutlined />} placeholder="密码" />
        </Form.Item>
        <Form.Item>
          <Button type="primary" htmlType="submit" loading={loading} block>
            登录
          </Button>
        </Form.Item>
      </Form>
      <Divider />
      <div style={{ textAlign: 'center' }}>
        还没有账号？<Link to="/register">立即注册</Link>
      </div>
    </Card>
  );
}