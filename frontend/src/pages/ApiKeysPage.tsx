import { useEffect, useState, useCallback } from 'react';
import { Table, Button, Tag, Modal, Input, message, Space, Alert, Typography } from 'antd';
import { PlusOutlined, ReloadOutlined, CopyOutlined } from '@ant-design/icons';
import { listKeys, createKey, toggleKey, type ApiKeyVO } from '../api/keys';
import dayjs from 'dayjs';

const { Text } = Typography;

export default function ApiKeysPage() {
  const [keys, setKeys] = useState<ApiKeyVO[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [keyName, setKeyName] = useState('');
  const [createdKey, setCreatedKey] = useState<ApiKeyVO | null>(null);

  const fetchKeys = useCallback(async () => {
    setLoading(true);
    try {
      const res = await listKeys();
      setKeys(res.data);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetchKeys(); }, [fetchKeys]);

  const handleCreate = async () => {
    if (!keyName.trim()) {
      message.warning('请输入 Key 名称');
      return;
    }
    try {
      const res = await createKey(keyName.trim());
      setCreatedKey(res.data);
      setModalOpen(false);
      setKeyName('');
      fetchKeys();
    } catch { /* handled */ }
  };

  const handleToggle = async (id: number, enable: boolean) => {
    try {
      await toggleKey(id, enable);
      message.success(enable ? '已启用' : '已禁用');
      fetchKeys();
    } catch { /* handled */ }
  };

  const columns = [
    { title: '名称', dataIndex: 'name', key: 'name' },
    {
      title: 'Access Key',
      dataIndex: 'accessKey',
      key: 'accessKey',
      ellipsis: true,
      render: (val: string) => (
        <Space>
          <code style={{ fontSize: 12 }}>{val}</code>
          <Button
            type="link"
            size="small"
            icon={<CopyOutlined />}
            onClick={() => { navigator.clipboard.writeText(val); message.success('已复制'); }}
          />
        </Space>
      ),
    },
    {
      title: 'Secret Key',
      dataIndex: 'secretKey',
      key: 'secretKey',
      ellipsis: true,
      render: (val: string) => (
        <span style={{ fontFamily: 'monospace', fontSize: 12 }}>
          {val}（仅创建时可见）
        </span>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (_: any, record: ApiKeyVO) => (
        record.status === 1
          ? <Tag color="green">启用</Tag>
          : <Tag color="red">禁用</Tag>
      ),
    },
    {
      title: '每日配额',
      dataIndex: 'quotaDaily',
      key: 'quotaDaily',
      width: 100,
      render: (val: number) => val?.toLocaleString() ?? '-',
    },
    {
      title: '过期时间',
      dataIndex: 'expiredAt',
      key: 'expiredAt',
      render: (val: string) => val ? dayjs(val).format('YYYY-MM-DD HH:mm') : '永久',
    },
    {
      title: '操作',
      key: 'action',
      width: 100,
      render: (_: any, record: ApiKeyVO) => (
        record.status === 1 ? (
          <Button size="small" danger onClick={() => handleToggle(record.id, false)}>禁用</Button>
        ) : (
          <Button size="small" type="primary" onClick={() => handleToggle(record.id, true)}>启用</Button>
        )
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>🔑 API Key 管理</h2>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={fetchKeys}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>创建 Key</Button>
        </Space>
      </div>

      <Table
        columns={columns}
        dataSource={keys}
        rowKey="id"
        loading={loading}
        pagination={false}
      />

      <Modal
        title="创建 API Key"
        open={modalOpen}
        onOk={handleCreate}
        onCancel={() => { setModalOpen(false); setKeyName(''); }}
        okText="创建"
        cancelText="取消"
      >
        <Input
          placeholder="Key 名称（如：生产环境、开发测试）"
          value={keyName}
          onChange={(e) => setKeyName(e.target.value)}
          style={{ marginTop: 16 }}
        />
      </Modal>

      {/* 创建成功 — 展示完整密钥 */}
      <Modal
        title="API Key 创建成功"
        open={!!createdKey}
        onCancel={() => setCreatedKey(null)}
        footer={[
          <Button key="done" type="primary" onClick={() => setCreatedKey(null)}>
            我已复制，关闭
          </Button>,
        ]}
      >
        <Alert
          type="warning"
          showIcon
          message="Secret Key 仅在此处完整展示一次，关闭后将无法再次查看，请立即复制保存！"
          style={{ marginBottom: 16 }}
        />
        <div style={{ marginBottom: 12 }}>
          <Text strong>Access Key</Text>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 4 }}>
            <code style={{ flex: 1, padding: '6px 10px', background: '#f5f5f5', borderRadius: 4, wordBreak: 'break-all' }}>
              {createdKey?.accessKey}
            </code>
            <Button
              size="small"
              icon={<CopyOutlined />}
              onClick={() => { navigator.clipboard.writeText(createdKey?.accessKey ?? ''); message.success('已复制 Access Key'); }}
            />
          </div>
        </div>
        <div>
          <Text strong>Secret Key</Text>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 4 }}>
            <code style={{ flex: 1, padding: '6px 10px', background: '#fff2f0', borderRadius: 4, wordBreak: 'break-all' }}>
              {createdKey?.secretKey}
            </code>
            <Button
              size="small"
              icon={<CopyOutlined />}
              onClick={() => { navigator.clipboard.writeText(createdKey?.secretKey ?? ''); message.success('已复制 Secret Key'); }}
            />
          </div>
        </div>
      </Modal>
    </div>
  );
}