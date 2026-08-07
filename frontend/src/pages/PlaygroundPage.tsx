import { useEffect, useState, useRef, useCallback } from 'react';
import { Card, Input, Button, Tag, Select, Space, Spin, Typography } from 'antd';
import { SendOutlined, ClearOutlined } from '@ant-design/icons';
import { sendMessage, getModelStatus, type ModelStatus, type HistoryMessage } from '../api/chat';

interface Message {
  role: 'user' | 'assistant';
  content: string;
  model?: string;
  strategy?: string;
  failover?: boolean;
}

const { TextArea } = Input;
const { Text } = Typography;

const STORAGE_KEY = 'cloudx-chat-messages';
const MAX_HISTORY = 10; // 发送最近 N 条消息作为上下文

/** 从 sessionStorage 恢复消息 */
function loadMessages(): Message[] {
  try {
    const saved = sessionStorage.getItem(STORAGE_KEY);
    return saved ? JSON.parse(saved) : [];
  } catch {
    return [];
  }
}

export default function PlaygroundPage() {
  const [messages, setMessages] = useState<Message[]>(loadMessages);
  const [input, setInput] = useState('');
  const [taskType, setTaskType] = useState<string | undefined>(undefined);
  const [sending, setSending] = useState(false);
  const [models, setModels] = useState<ModelStatus>({});
  const bottomRef = useRef<HTMLDivElement>(null);

  // 每次消息变化，持久化到 sessionStorage
  useEffect(() => {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(messages));
  }, [messages]);

  useEffect(() => {
    getModelStatus()
      .then((res) => setModels(res.data))
      .catch(() => {});
  }, []);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const handleClear = useCallback(() => {
    setMessages([]);
    sessionStorage.removeItem(STORAGE_KEY);
  }, []);

  const handleSend = async () => {
    const msg = input.trim();
    if (!msg) return;

    const userMsg: Message = { role: 'user', content: msg };
    setMessages((prev) => [...prev, userMsg]);
    setInput('');
    setSending(true);

    // 取最近 MAX_HISTORY 条作为上下文（不含刚发的这条）
    const recent = [...messages.slice(-MAX_HISTORY)].map(m => ({
      role: m.role,
      content: m.content,
    })) as HistoryMessage[];

    try {
      const res = await sendMessage(msg, taskType, recent);
      const r = res.data;
      setMessages((prev) => [
        ...prev,
        { role: 'assistant', content: r.reply, model: r.model, strategy: r.strategy, failover: r.failover },
      ]);
    } catch {
      setMessages((prev) => [
        ...prev,
        { role: 'assistant', content: '请求失败，请稍后重试' },
      ]);
    } finally {
      setSending(false);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 200px)' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>🧪 在线调试</h2>
        <Space>
          <Select
            allowClear
            placeholder="任务类型（可选）"
            style={{ width: 160 }}
            value={taskType}
            onChange={(val) => setTaskType(val)}
            options={[
              { value: 'code', label: '代码' },
              { value: 'translate', label: '翻译' },
              { value: 'chat', label: '闲聊' },
              { value: 'math', label: '数学' },
            ]}
          />
          <Button
            icon={<ClearOutlined />}
            onClick={handleClear}
            disabled={messages.length === 0}
          >
            清空
          </Button>
        </Space>
      </div>

      {/* 模型状态 */}
      <div style={{ marginBottom: 12, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        {Object.entries(models).map(([name, status]) => (
          <Tag key={name} color={status === 'UP' ? 'green' : 'red'}>
            {name} · {status === 'UP' ? '在线' : '离线'}
          </Tag>
        ))}
        {Object.keys(models).length === 0 && (
          <Text type="secondary">模型状态加载中...</Text>
        )}
      </div>

      {/* 聊天区 */}
      <Card style={{ flex: 1, overflow: 'auto', marginBottom: 16, background: '#fafafa' }}>
        {messages.length === 0 ? (
          <div style={{ textAlign: 'center', color: '#999', padding: 48 }}>
            发送一条消息开始调试 AI 对话
          </div>
        ) : (
          messages.map((msg, i) => (
            <div key={i} style={{
              marginBottom: 16,
              display: 'flex',
              justifyContent: msg.role === 'user' ? 'flex-end' : 'flex-start',
            }}>
              <div style={{
                maxWidth: '80%',
                padding: '10px 16px',
                borderRadius: 12,
                background: msg.role === 'user' ? '#1677ff' : '#fff',
                color: msg.role === 'user' ? '#fff' : '#333',
                boxShadow: msg.role === 'assistant' ? '0 1px 3px rgba(0,0,0,0.1)' : undefined,
              }}>
                <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{msg.content}</div>
                {msg.model && (
                  <div style={{ marginTop: 6, fontSize: 12, opacity: 0.7 }}>
                    <Tag>{msg.model}</Tag>
                    {msg.failover && <Tag color="orange">故障转移</Tag>}
                    {msg.strategy && <span> — {msg.strategy}</span>}
                  </div>
                )}
              </div>
            </div>
          ))
        )}
        {sending && (
          <div style={{ textAlign: 'center' }}>
            <Spin size="small" />
            <Text type="secondary" style={{ marginLeft: 8 }}>AI 正在思考...</Text>
          </div>
        )}
        <div ref={bottomRef} />
      </Card>

      {/* 输入区 */}
      <div style={{ display: 'flex', gap: 8 }}>
        <TextArea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="输入消息... (Enter 发送，Shift+Enter 换行)"
          rows={3}
          disabled={sending}
          style={{ flex: 1 }}
        />
        <Button
          type="primary"
          icon={<SendOutlined />}
          onClick={handleSend}
          loading={sending}
          disabled={!input.trim()}
          style={{ height: 'auto' }}
        >
          发送
        </Button>
      </div>
    </div>
  );
}