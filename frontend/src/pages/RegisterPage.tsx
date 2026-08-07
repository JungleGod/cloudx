import { useState } from 'react';
import { Card, Form, Input, Button, message, Divider } from 'antd';
import { UserOutlined, LockOutlined, MailOutlined, ApiOutlined } from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import { register } from '../api/auth';

export default function RegisterPage() {
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  const onFinish = async (values: { username: string; password: string; email?: string }) => {
    setLoading(true);
    try {
      await register(values);
      message.success('注册成功，请登录');
      navigate('/login');
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
        <p style={{ color: '#999' }}>创建新账号</p>
      </div>
      <Form size="large" onFinish={onFinish} autoComplete="off">
        <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
          <Input prefix={<UserOutlined />} placeholder="用户名" />
        </Form.Item>
        <Form.Item name="password" rules={[{ required: true, message: '请输入密码', min: 6 }]}>
          <Input.Password prefix={<LockOutlined />} placeholder="至少6位密码" />
        </Form.Item>
        <Form.Item name="email">
          <Input prefix={<MailOutlined />} placeholder="邮箱（选填）" />
        </Form.Item>
        <Form.Item>
          <Button type="primary" htmlType="submit" loading={loading} block>
            注册
          </Button>
        </Form.Item>
      </Form>
      <Divider />
      <div style={{ textAlign: 'center' }}>
        已有账号？<Link to="/login">立即登录</Link>
      </div>
    </Card>
  );
}