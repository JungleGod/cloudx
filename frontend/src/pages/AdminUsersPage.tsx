import { useEffect, useState, useCallback } from 'react';
import { Table, Tag, Button, Select, message, Spin, Alert } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { listUsers, setUserRole, toggleUserStatus, type AdminUser } from '../api/auth';

export default function AdminUsersPage() {
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchUsers = useCallback(async () => {
    setLoading(true);
    try {
      const res = await listUsers();
      setUsers(res.data);
    } catch (err: any) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetchUsers(); }, [fetchUsers]);

  const handleRoleChange = async (userId: number, newRole: string) => {
    try {
      await setUserRole(userId, newRole);
      message.success('角色已更新');
      fetchUsers();
    } catch { /* handled */ }
  };

  const handleStatusToggle = async (userId: number, newStatus: number) => {
    try {
      await toggleUserStatus(userId, newStatus);
      message.success('状态已更新');
      fetchUsers();
    } catch { /* handled */ }
  };

  if (error) {
    return <Alert type="warning" message="加载失败" description={error} showIcon />;
  }

  const columns = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 80 },
    { title: '用户名', dataIndex: 'username', key: 'username' },
    { title: '邮箱', dataIndex: 'email', key: 'email' },
    {
      title: '角色', dataIndex: 'role', key: 'role', width: 140,
      render: (_: any, record: AdminUser) => (
        <Select
          size="small"
          value={record.role}
          style={{ width: 100 }}
          onChange={(val) => handleRoleChange(record.id, val)}
          options={[
            { value: 'user', label: '普通用户' },
            { value: 'admin', label: '管理员' },
          ]}
        />
      ),
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (val: number) => (
        val === 1 ? <Tag color="green">正常</Tag> : <Tag color="red">禁用</Tag>
      ),
    },
    {
      title: '注册时间', dataIndex: 'createdAt', key: 'createdAt', width: 180,
      render: (val: string) => val?.substring(0, 19),
    },
    {
      title: '操作', key: 'action', width: 100,
      render: (_: any, record: AdminUser) => (
        record.username === 'admin' ? (
          <Tag color="blue">超级管理员</Tag>
        ) : record.status === 1 ? (
          <Button size="small" danger onClick={() => handleStatusToggle(record.id, 0)}>禁用</Button>
        ) : (
          <Button size="small" type="primary" onClick={() => handleStatusToggle(record.id, 1)}>启用</Button>
        )
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>👥 用户管理</h2>
        <Button icon={<ReloadOutlined />} onClick={fetchUsers}>刷新</Button>
      </div>
      <Table
        columns={columns}
        dataSource={users}
        rowKey="id"
        loading={loading}
        pagination={false}
      />
    </div>
  );
}