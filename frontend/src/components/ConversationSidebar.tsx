import { useState, useEffect, useCallback } from 'react';
import { Button, List, Typography, Popconfirm, message, Input } from 'antd';
import { PlusOutlined, DeleteOutlined, MessageOutlined } from '@ant-design/icons';
import {
  listConversations,
  deleteConversation,
  updateConversation,
  type Conversation,
} from '../api/conversation';
import dayjs from 'dayjs';

const { Text } = Typography;

interface Props {
  activeId: number | null;
  onSelect: (conv: Conversation) => void;
  onCreate: () => void;
  conversations: Conversation[];
  setConversations: React.Dispatch<React.SetStateAction<Conversation[]>>;
}

export default function ConversationSidebar({ activeId, onSelect, onCreate, conversations, setConversations }: Props) {
  const [loading, setLoading] = useState(true);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editTitle, setEditTitle] = useState('');

  useEffect(() => {
    loadList();
  }, []);

  const loadList = async () => {
    try {
      const list = await listConversations();
      setConversations(list);
    } catch {
      // ignore
    } finally {
      setLoading(false);
    }
  };

  const handleDelete = useCallback(async (id: number) => {
    try {
      await deleteConversation(id);
      setConversations((prev) => prev.filter((c) => c.id !== id));
      if (activeId === id) {
        onSelect(null as any); // 清除选中
      }
    } catch {
      message.error('删除失败');
    }
  }, [activeId, onSelect, setConversations]);

  const handleStartRename = useCallback((conv: Conversation) => {
    setEditingId(conv.id);
    setEditTitle(conv.title);
  }, []);

  const handleRename = useCallback(async (id: number) => {
    if (editTitle.trim() && editTitle !== '') {
      try {
        await updateConversation(id, editTitle.trim());
        setConversations((prev) => prev.map((c) => c.id === id ? { ...c, title: editTitle.trim() } : c));
      } catch {
        message.error('重命名失败');
      }
    }
    setEditingId(null);
  }, [editTitle, setConversations]);

  const formatTime = (dateStr: string) => {
    const d = dayjs(dateStr);
    const now = dayjs();
    if (d.isSame(now, 'day')) return d.format('HH:mm');
    if (d.isSame(now, 'year')) return d.format('MM-DD');
    return d.format('YY-MM-DD');
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <Button
        type="primary"
        icon={<PlusOutlined />}
        onClick={onCreate}
        block
        style={{ marginBottom: 12 }}
      >
        新建对话
      </Button>
      <List
        loading={loading}
        dataSource={conversations}
        style={{ flex: 1, overflow: 'auto' }}
        renderItem={(item) => (
          <List.Item
            key={item.id}
            onClick={() => onSelect(item)}
            onDoubleClick={() => handleStartRename(item)}
            style={{
              cursor: 'pointer',
              padding: '8px 12px',
              borderRadius: 8,
              background: item.id === activeId ? '#e6f4ff' : undefined,
              border: 'none',
              marginBottom: 4,
            }}
            actions={
              item.id === activeId
                ? [
                    <Popconfirm
                      key="delete"
                      title="确定删除此会话？"
                      onConfirm={(e) => { e?.stopPropagation(); handleDelete(item.id); }}
                      onCancel={(e) => e?.stopPropagation()}
                    >
                      <Button
                        type="text"
                        size="small"
                        danger
                        icon={<DeleteOutlined />}
                        onClick={(e) => e.stopPropagation()}
                      />
                    </Popconfirm>,
                  ]
                : undefined
            }
          >
            <List.Item.Meta
              avatar={<MessageOutlined style={{ color: '#1677ff' }} />}
              title={
                editingId === item.id ? (
                  <Input
                    size="small"
                    value={editTitle}
                    onChange={(e) => setEditTitle(e.target.value)}
                    onBlur={() => handleRename(item.id)}
                    onPressEnter={() => handleRename(item.id)}
                    onClick={(e) => e.stopPropagation()}
                    autoFocus
                  />
                ) : (
                  <Text ellipsis style={{ maxWidth: 140 }}>{item.title}</Text>
                )
              }
              description={
                <Text type="secondary" style={{ fontSize: 11 }}>
                  {item.messageCount ?? 0} 条消息 · {formatTime(item.updatedAt)}
                </Text>
              }
            />
          </List.Item>
        )}
        locale={{ emptyText: '暂无对话' }}
      />
    </div>
  );
}
