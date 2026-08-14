import { useEffect, useState, useRef, useCallback } from 'react';
import { Card, Input, Button, Tag, Select, Space, Spin, Typography, Switch } from 'antd';
import { SendOutlined, ClearOutlined, ThunderboltOutlined, PictureOutlined, DeleteOutlined } from '@ant-design/icons';
import { sendMessage, sendMessageStream, sendMessageMultimodal, getModelStatus, type ModelDetail, type HistoryMessage } from '../api/chat';

interface Message {
  role: 'user' | 'assistant';
  content: string;
  model?: string;
  strategy?: string;
  failover?: boolean;
  streaming?: boolean; // 是否正在流式输出中
}

const { TextArea } = Input;
const { Text } = Typography;

const STORAGE_KEY = 'cloudx-chat-messages';
const MAX_HISTORY = 10;

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
  const [selectedModel, setSelectedModel] = useState<string | undefined>(undefined);
  const [sending, setSending] = useState(false);
  const [streamMode, setStreamMode] = useState(true); // 默认开启流式
  const [models, setModels] = useState<ModelDetail[]>([]);
  const [images, setImages] = useState<string[]>([]); // base64 data URLs
  const bottomRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<(() => void) | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

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

  /** 停止流式输出 */
  const handleStop = useCallback(() => {
    abortRef.current?.();
    abortRef.current = null;
    setSending(false);
    setMessages((prev) => {
      const updated = [...prev];
      const last = updated[updated.length - 1];
      if (last && last.streaming) {
        last.streaming = false;
        if (!last.content) last.content = '(已中止)';
      }
      return updated;
    });
  }, []);

  /** 选择图片并转为 base64 */
  const handleImageUpload = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (!files) return;
    const readers: Promise<string>[] = [];
    for (let i = 0; i < Math.min(files.length, 5); i++) {
      readers.push(new Promise((resolve) => {
        const reader = new FileReader();
        reader.onload = () => resolve(reader.result as string);
        reader.readAsDataURL(files[i]);
      }));
    }
    Promise.all(readers).then((dataUrls) => {
      setImages((prev) => [...prev, ...dataUrls].slice(0, 5));
    });
    // 重置 input 以便重新选择同一文件
    e.target.value = '';
  }, []);

  const handleRemoveImage = useCallback((index: number) => {
    setImages((prev) => prev.filter((_, i) => i !== index));
  }, []);

  const handleSend = async () => {
    const msg = input.trim();
    if (!msg) return;

    const userMsg: Message = { role: 'user', content: msg };
    setMessages((prev) => [...prev, userMsg]);
    setInput('');
    setSending(true);

    const recent = [...messages.slice(-MAX_HISTORY)].map((m) => ({
      role: m.role,
      content: m.content,
    })) as HistoryMessage[];

    if (images.length > 0) {
      // ========== 多模态模式（暂用普通请求，后续可加流式） ==========
      try {
        const res = await sendMessageMultimodal(msg, images, taskType, selectedModel);
        const r = res.data;
        setMessages((prev) => [
          ...prev,
          { role: 'assistant', content: r.reply, model: r.model, strategy: r.strategy, failover: r.failover },
        ]);
        setImages([]);
      } catch {
        setMessages((prev) => [...prev, { role: 'assistant', content: '请求失败，请稍后重试' }]);
      } finally {
        setSending(false);
      }
    } else if (streamMode) {
      // ========== 流式模式 ==========
      const assistantMsg: Message = { role: 'assistant', content: '', streaming: true };
      setMessages((prev) => [...prev, assistantMsg]);

      const abort = sendMessageStream(
        msg,
        {
          onToken: (token) => {
            setMessages((prev) => {
              const updated = [...prev];
              const lastIdx = updated.length - 1;
              const last = updated[lastIdx];
              if (last && last.streaming) {
                // 创建新对象而非修改原对象，防止 React 并发模式下的重复渲染
                updated[lastIdx] = { ...last, content: last.content + token };
              }
              return updated;
            });
          },
          onDone: () => {
            setMessages((prev) => {
              const updated = [...prev];
              const lastIdx = updated.length - 1;
              const last = updated[lastIdx];
              if (last && last.streaming) {
                updated[lastIdx] = { ...last, streaming: false };
              }
              return updated;
            });
            setSending(false);
            abortRef.current = null;
          },
          onError: (error) => {
            setMessages((prev) => {
              const updated = [...prev];
              const lastIdx = updated.length - 1;
              const last = updated[lastIdx];
              if (last && last.streaming) {
                updated[lastIdx] = {
                  ...last,
                  streaming: false,
                  content: last.content || `请求失败: ${error}`,
                };
              }
              return updated;
            });
            setSending(false);
            abortRef.current = null;
          },
        },
        taskType,
        recent,
        selectedModel,
      );

      abortRef.current = abort;
    } else {
      // ========== 普通模式 ==========
      try {
        const res = await sendMessage(msg, taskType, recent, selectedModel);
        const r = res.data;
        setMessages((prev) => [
          ...prev,
          { role: 'assistant', content: r.reply, model: r.model, strategy: r.strategy, failover: r.failover },
        ]);
      } catch {
        setMessages((prev) => [...prev, { role: 'assistant', content: '请求失败，请稍后重试' }]);
      } finally {
        setSending(false);
      }
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
          <span style={{ fontSize: 13, color: '#666' }}>
            <ThunderboltOutlined /> 流式输出
          </span>
          <Switch
            size="small"
            checked={streamMode}
            onChange={setStreamMode}
            disabled={sending}
          />
          <Select
            allowClear
            placeholder="指定模型（可选）"
            style={{ width: 150 }}
            value={selectedModel}
            onChange={(val) => setSelectedModel(val)}
            options={models.map((m) => ({ value: m.name, label: m.name }))}
          />
          <Select
            allowClear
            placeholder="任务类型（可选）"
            style={{ width: 140 }}
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
        {models.map((m) => (
          <Tag key={m.name} color={m.status === 'UP' ? 'green' : 'red'}>
            {m.name} · {m.status === 'UP' ? '在线' : '离线'}
          </Tag>
        ))}
        {models.length === 0 && (
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
                <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                  {msg.content}
                  {msg.streaming && <span className="cursor-blink">▌</span>}
                </div>
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
        {sending && !streamMode && (
          <div style={{ textAlign: 'center' }}>
            <Spin size="small" />
            <Text type="secondary" style={{ marginLeft: 8 }}>AI 正在思考...</Text>
          </div>
        )}
        <div ref={bottomRef} />
      </Card>

      {/* 图片预览区 */}
      {images.length > 0 && (
        <div style={{ display: 'flex', gap: 8, marginBottom: 12, flexWrap: 'wrap' }}>
          {images.map((dataUrl, i) => (
            <div key={i} style={{ position: 'relative', width: 64, height: 64, borderRadius: 6, overflow: 'hidden', border: '1px solid #d9d9d9' }}>
              <img src={dataUrl} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
              <Button
                type="text"
                danger
                size="small"
                icon={<DeleteOutlined />}
                onClick={() => handleRemoveImage(i)}
                style={{ position: 'absolute', top: 0, right: 0, background: 'rgba(255,255,255,0.8)' }}
              />
            </div>
          ))}
        </div>
      )}

      {/* 输入区 */}
      <div style={{ display: 'flex', gap: 8 }}>
        <input
          ref={fileInputRef}
          type="file"
          accept="image/*"
          multiple
          style={{ display: 'none' }}
          onChange={handleImageUpload}
        />
        <Button
          icon={<PictureOutlined />}
          onClick={() => fileInputRef.current?.click()}
          disabled={sending}
          title="上传图片（支持多模态模型）"
        >
          图片
        </Button>
        <TextArea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="输入消息... (Enter 发送，Shift+Enter 换行)"
          rows={3}
          disabled={sending && !streamMode}
          style={{ flex: 1 }}
        />
        {sending && streamMode ? (
          <Button
            danger
            onClick={handleStop}
            style={{ height: 'auto' }}
          >
            停止
          </Button>
        ) : (
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
        )}
      </div>
    </div>
  );
}