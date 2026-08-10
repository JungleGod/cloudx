import { useState } from 'react';
import { Form, Input, Button, message } from 'antd';
import { UserOutlined, LockOutlined, MailOutlined } from '@ant-design/icons';
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
    <div>
      <div style={{ marginBottom: 32 }}>
        <h2 style={{ fontSize: 24, fontWeight: 600, margin: '0 0 4px', color: '#18181b' }}>创建账号</h2>
        <p style={{ color: '#71717a', margin: 0, fontSize: 14 }}>
          已有账号？<Link to="/login" style={{ fontWeight: 500 }}>立即登录 →</Link>
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
          rules={[{ required: true, message: '请输入密码', min: 6 }]}
          style={{ marginBottom: 20 }}
        >
          <Input.Password
            prefix={<LockOutlined />}
            placeholder="至少6位密码"
            style={{ height: 44, borderRadius: 6 }}
          />
        </Form.Item>

        <Form.Item
          label="邮箱"
          name="email"
          style={{ marginBottom: 24 }}
        >
          <Input
            prefix={<MailOutlined />}
            placeholder="选填"
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
            注册
          </Button>
        </Form.Item>
      </Form>
    </div>
  );
}
