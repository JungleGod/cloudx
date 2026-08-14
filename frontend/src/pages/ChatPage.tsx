import { useEffect, useState, useRef, useCallback } from 'react';
import { Card, Input, Button, Tag, Select, Space, Typography, Switch, Layout, Tabs } from 'antd';
import { SendOutlined, ThunderboltOutlined, PictureOutlined, DeleteOutlined, FileTextOutlined, RobotOutlined, MessageOutlined } from '@ant-design/icons';
import * as pdfjsLib from 'pdfjs-dist';
pdfjsLib.GlobalWorkerOptions.workerSrc = `https://cdnjs.cloudflare.com/ajax/libs/pdf.js/${pdfjsLib.version}/pdf.worker.min.mjs`;
import { sendMessage, sendMessageStream, sendMessageMultimodal, getModelStatus, type ModelDetail, type HistoryMessage } from '../api/chat';
import {
  listConversations,
  createConversation,
  getMessages,
  appendMessage,
  updateConversation,
  type Conversation,
  type ConversationMessage,
} from '../api/conversation';
import ConversationSidebar from '../components/ConversationSidebar';
import AgentPage from './AgentPage';

interface Message {
  role: 'user' | 'assistant';
  content: string;
  model?: string;
  strategy?: string;
  failover?: boolean;
  streaming?: boolean;
}

interface AttachedFile {
  name: string;
  content: string;
  size: number;
}

const { TextArea } = Input;
const { Text } = Typography;
const { Sider, Content } = Layout;

const MAX_HISTORY = 10;
const SIDEBAR_WIDTH = 280;

// 支持提取文本的文件扩展名
const TEXT_EXTENSIONS = [
  '.txt', '.md', '.json', '.csv', '.xml', '.yaml', '.yml',
  '.java', '.py', '.js', '.ts', '.tsx', '.jsx', '.html', '.css',
  '.sql', '.log', '.properties', '.env', '.sh', '.bat',
  '.c', '.cpp', '.h', '.hpp', '.rs', '.go', '.rb', '.php',
  '.vue', '.svelte', '.kt', '.swift', '.scala', '.gradle',
  '.gitignore', '.dockerfile', '.toml', '.ini', '.cfg',
];
const MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

export default function ChatPage() {
  // 会话状态
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeConv, setActiveConv] = useState<Conversation | null>(null);

  // Agent 对话状态
  const [agentConversations, setAgentConversations] = useState<Conversation[]>([]);
  const [agentActiveConv, setAgentActiveConv] = useState<Conversation | null>(null);

  // Tab 切换：对话 | Agent
  const [activeTab, setActiveTab] = useState<'chat' | 'agent'>('chat');

  // 聊天状态
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [taskType, setTaskType] = useState<string | undefined>(undefined);
  const [selectedModel, setSelectedModel] = useState<string | undefined>(undefined);
  const [sending, setSending] = useState(false);
  const [streamMode, setStreamMode] = useState(true);
  const [models, setModels] = useState<ModelDetail[]>([]);
  const [images, setImages] = useState<string[]>([]);
  const [attachedFiles, setAttachedFiles] = useState<AttachedFile[]>([]);
  const bottomRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<(() => void) | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const docInputRef = useRef<HTMLInputElement>(null);
  const inputRef = useRef<any>(null);

  // 加载模型状态
  useEffect(() => {
    getModelStatus()
      .then((res) => setModels(res.data))
      .catch(() => {});
  }, []);

  // 自动滚动到底部
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  // 加载 Agent 对话列表
  const loadAgentConversations = () => {
    listConversations()
      .then((list) => setAgentConversations(list.filter((c) => c.agentId != null)))
      .catch(() => {});
  };

  useEffect(() => { loadAgentConversations(); }, []);

  /** 新建会话 */
  const handleCreate = useCallback(() => {
    setActiveConv(null);
    setMessages([]);
    setInput('');
    inputRef.current?.focus();
  }, []);

  /** 新建 Agent 会话 */
  const handleAgentCreate = useCallback(() => {
    setAgentActiveConv(null);
  }, []);

  /** 切换 Agent 会话 */
  const handleAgentSelect = useCallback(async (conv: Conversation) => {
    if (!conv) { handleAgentCreate(); return; }
    setAgentActiveConv(conv);
  }, [handleAgentCreate]);

  /** 切换会话 */
  const handleSelect = useCallback(async (conv: Conversation) => {
    if (!conv) { handleCreate(); return; }
    if (activeConv?.id === conv.id) return;

    setActiveConv(conv);
    setSelectedModel(conv.model || undefined);

    // 加载该会话的历史消息
    try {
      const msgs = await getMessages(conv.id);
      setMessages(msgs.map((m: ConversationMessage) => ({
        role: m.role as 'user' | 'assistant',
        content: m.content,
        model: m.model || undefined,
      })));
    } catch {
      setMessages([]);
    }
  }, [activeConv, handleCreate]);

  /** 发送消息 */
  const handleSend = async () => {
    const msg = input.trim();
    if (!msg || sending) return;

    // 构建完整消息（含附件内容）
    let fullMessage = msg;
    const currentFiles = attachedFiles;
    if (currentFiles.length > 0) {
      const fileBlocks = currentFiles.map((f) =>
        `[文件: ${f.name}]\n\n文件内容:\n${f.content}`
      ).join('\n\n---\n\n');
      fullMessage = fileBlocks + '\n\n---\n' + msg;
    }

    let conv = activeConv;

    // 如果没有活跃会话，先创建一个
    if (!conv) {
      const title = msg.length > 20 ? msg.substring(0, 20) + '...' : msg;
      try {
        conv = await createConversation(title, selectedModel);
        setConversations((prev) => [conv!, ...prev]);
        setActiveConv(conv);
      } catch {
        setMessages((prev) => [...prev, { role: 'assistant', content: '创建会话失败，请稍后重试' }]);
        return;
      }
    }

    // 用户看到的是原始消息，持久化的是完整消息（含附件）
    const displayMsg: Message = { role: 'user', content: msg };
    setMessages((prev) => [...prev, displayMsg]);
    setInput('');
    setAttachedFiles([]);
    setSending(true);

    // 持久化用户消息（保存完整内容，含附件文本）
    appendMessage(conv.id, { role: 'user', content: fullMessage }).catch(() => {});

    // 准备历史上下文
    const recent = [...messages.slice(-MAX_HISTORY)].map((m) => ({
      role: m.role,
      content: m.content,
      model: m.model, // 标注模型，防止上下文串身份
    })) as HistoryMessage[];

    if (images.length > 0) {
      // ========== 多模态模式 ==========
      try {
        const res = await sendMessageMultimodal(fullMessage, images, taskType, selectedModel);
        const r = res.data;
        const assistantMsg: Message = { role: 'assistant', content: r.reply, model: r.model, strategy: r.strategy, failover: r.failover };
        setMessages((prev) => [...prev, assistantMsg]);
        appendMessage(conv!.id, { role: 'assistant', content: r.reply, model: r.model }).catch(() => {});
        setImages([]);
      } catch {
        setMessages((prev) => [...prev, { role: 'assistant', content: '请求失败，请稍后重试' }]);
      } finally {
        setSending(false);
        refreshList();
      }
    } else if (streamMode) {
      // ========== 流式模式 ==========
      const assistantMsg: Message = { role: 'assistant', content: '', streaming: true };
      setMessages((prev) => [...prev, assistantMsg]);

      let fullContent = '';

      const abort = sendMessageStream(
        fullMessage,
        {
          onToken: (token) => {
            fullContent += token;
            setMessages((prev) => {
              const updated = [...prev];
              const lastIdx = updated.length - 1;
              const last = updated[lastIdx];
              if (last && last.streaming) {
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
            appendMessage(conv!.id, { role: 'assistant', content: fullContent, model: selectedModel || null }).catch(() => {});
            setSending(false);
            abortRef.current = null;
            refreshList();
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
            if (fullContent) {
              appendMessage(conv!.id, { role: 'assistant', content: fullContent, model: selectedModel || null }).catch(() => {});
            }
            setSending(false);
            abortRef.current = null;
            refreshList();
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
        const res = await sendMessage(fullMessage, taskType, recent, selectedModel);
        const r = res.data;
        const assistantMsg: Message = { role: 'assistant', content: r.reply, model: r.model, strategy: r.strategy, failover: r.failover };
        setMessages((prev) => [...prev, assistantMsg]);
        appendMessage(conv!.id, { role: 'assistant', content: r.reply, model: r.model }).catch(() => {});
      } catch {
        setMessages((prev) => [...prev, { role: 'assistant', content: '请求失败，请稍后重试' }]);
      } finally {
        setSending(false);
        refreshList();
      }
    }

    // 首次对话后更新标题
    if (conv && messages.length === 0) {
      const title = msg.length > 20 ? msg.substring(0, 20) + '...' : msg;
      updateConversation(conv.id, title).then(() => {
        setConversations((prev) => prev.map((c) => c.id === conv!.id ? { ...c, title } : c));
      }).catch(() => {});
    }
  };

  /** 刷新会话列表（异步，不阻塞） */
  const refreshList = () => {
    listConversations()
      .then((list) => setConversations(list.filter((c) => c.agentId == null)))
      .catch(() => {});
  };

  /** 停止流式输出 */
  const handleStop = useCallback(() => {
    abortRef.current?.();
    abortRef.current = null;
    setSending(false);
    setMessages((prev) => {
      const updated = [...prev];
      const lastIdx = updated.length - 1;
      const last = updated[lastIdx];
      if (last && last.streaming) {
        updated[lastIdx] = {
          ...last,
          streaming: false,
          content: last.content || '(已中止)',
        };
      }
      return updated;
    });
  }, []);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

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
    e.target.value = '';
  }, []);

  const handleRemoveImage = useCallback((index: number) => {
    setImages((prev) => prev.filter((_, i) => i !== index));
  }, []);

  /** 提取 PDF 文本 */
  const extractPdfText = async (file: File): Promise<string> => {
    const arrayBuf = await file.arrayBuffer();
    const pdf = await pdfjsLib.getDocument({ data: arrayBuf }).promise;
    const lines: string[] = [];
    for (let i = 1; i <= pdf.numPages; i++) {
      const page = await pdf.getPage(i);
      const textContent = await page.getTextContent();
      const pageText = textContent.items.map((item: any) => item.str).join(' ');
      lines.push(pageText);
    }
    return lines.join('\n');
  };

  /** 选择附件并提取文本 */
  const handleFileUpload = useCallback(async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (!files) return;

    for (let i = 0; i < files.length; i++) {
      const file = files[i];
      if (file.size > MAX_FILE_SIZE) {
        continue; // 跳过超大文件
      }

      const ext = '.' + file.name.split('.').pop()?.toLowerCase();
      try {
        if (ext === '.pdf') {
          const text = await extractPdfText(file);
          setAttachedFiles((prev) => [...prev, { name: file.name, content: text, size: file.size }]);
        } else if (TEXT_EXTENSIONS.includes(ext)) {
          const text = await new Promise<string>((resolve) => {
            const reader = new FileReader();
            reader.onload = () => resolve(reader.result as string);
            reader.readAsText(file);
          });
          setAttachedFiles((prev) => [...prev, { name: file.name, content: text, size: file.size }]);
        }
      } catch {
        // 解析失败，跳过
      }
    }
    e.target.value = '';
  }, []);

  const handleRemoveFile = useCallback((index: number) => {
    setAttachedFiles((prev) => prev.filter((_, i) => i !== index));
  }, []);

  /** 格式化文件大小 */
  const formatSize = (bytes: number) => {
    if (bytes < 1024) return bytes + 'B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + 'KB';
    return (bytes / (1024 * 1024)).toFixed(1) + 'MB';
  };

  return (
    <Layout style={{ height: 'calc(100vh - 200px)', background: 'transparent' }}>
      {/* 左侧：会话列表 + Tab 切换 */}
      <Sider width={SIDEBAR_WIDTH} style={{ background: '#fff', borderRadius: 8, padding: 0, marginRight: 16, overflow: 'auto', display: 'flex', flexDirection: 'column' }}>
        <Tabs
          activeKey={activeTab}
          onChange={(key) => setActiveTab(key as 'chat' | 'agent')}
          size="small"
          centered
          style={{ padding: '0 12px' }}
          items={[
            {
              key: 'chat',
              label: <span><MessageOutlined /> 对话</span>,
            },
            {
              key: 'agent',
              label: <span><RobotOutlined /> Agent</span>,
            },
          ]}
        />
        {activeTab === 'chat' && (
          <div style={{ padding: '0 12px 12px', flex: 1, overflow: 'auto' }}>
            <ConversationSidebar
              activeId={activeConv?.id ?? null}
              onSelect={handleSelect}
              onCreate={handleCreate}
              conversations={conversations}
              setConversations={setConversations}
              filter={(list) => list.filter((c) => c.agentId == null)}
            />
          </div>
        )}
        {activeTab === 'agent' && (
          <div style={{ padding: '0 12px 12px', flex: 1, overflow: 'auto' }}>
            <ConversationSidebar
              activeId={agentActiveConv?.id ?? null}
              onSelect={handleAgentSelect}
              onCreate={handleAgentCreate}
              conversations={agentConversations}
              setConversations={setAgentConversations}
              filter={(list) => list.filter((c) => c.agentId != null)}
            />
          </div>
        )}
      </Sider>

      {/* 右侧内容区 */}
      <Content style={{ display: 'flex', flexDirection: 'column' }}>
        {activeTab === 'agent' ? (
          <AgentPage
            activeConv={agentActiveConv}
            onConvMutated={loadAgentConversations}
            onAgentChange={() => {}}
          />
        ) : (
          <>
            {/* 顶部控制栏 */}
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
              <h2 style={{ margin: 0 }}>
                {activeConv ? activeConv.title : '新对话'}
              </h2>
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
                  placeholder="指定模型"
                  style={{ width: 140 }}
                  value={selectedModel}
                  onChange={(val) => setSelectedModel(val)}
                  options={models.map((m) => ({ value: m.name, label: m.name }))}
                />
                <Select
                  allowClear
                  placeholder="任务类型"
                  style={{ width: 120 }}
                  value={taskType}
                  onChange={(val) => setTaskType(val)}
                  options={[
                    { value: 'code', label: '代码' },
                    { value: 'translate', label: '翻译' },
                    { value: 'chat', label: '闲聊' },
                    { value: 'math', label: '数学' },
                  ]}
                />
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
                  发送一条消息开始对话
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

            {/* 附件预览区 */}
            {attachedFiles.length > 0 && (
              <div style={{ display: 'flex', gap: 8, marginBottom: 12, flexWrap: 'wrap' }}>
                {attachedFiles.map((file, i) => (
                  <Tag
                    key={i}
                    closable
                    onClose={() => handleRemoveFile(i)}
                    color="blue"
                    style={{ margin: 0, padding: '2px 8px', display: 'flex', alignItems: 'center', gap: 4 }}
                  >
                    <FileTextOutlined />
                    <span>{file.name}</span>
                    <span style={{ opacity: 0.6, fontSize: 11 }}>({formatSize(file.size)})</span>
                  </Tag>
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
              <input
                ref={docInputRef}
                type="file"
                accept=".txt,.md,.json,.csv,.xml,.yaml,.yml,.java,.py,.js,.ts,.tsx,.jsx,.html,.css,.sql,.log,.properties,.env,.sh,.bat,.c,.cpp,.h,.hpp,.rs,.go,.rb,.php,.vue,.svelte,.kt,.swift,.scala,.gradle,.toml,.ini,.cfg,.pdf"
                multiple
                style={{ display: 'none' }}
                onChange={handleFileUpload}
              />
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8, alignSelf: 'stretch' }}>
                <Button
                  icon={<PictureOutlined />}
                  onClick={() => fileInputRef.current?.click()}
                  disabled={sending}
                  style={{ flex: 1 }}
                >
                  图片
                </Button>
                <Button
                  icon={<FileTextOutlined />}
                  onClick={() => docInputRef.current?.click()}
                  disabled={sending}
                  style={{ flex: 1 }}
                >
                  附件
                </Button>
              </div>
              <TextArea
                ref={inputRef}
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder="输入消息... (Enter 发送，Shift+Enter 换行)"
                rows={3}
                disabled={sending && !streamMode}
                style={{ flex: 1 }}
              />
              {sending && streamMode ? (
                <Button danger onClick={handleStop} style={{ height: 'auto' }}>
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
          </>
        )}
      </Content>
    </Layout>
  );
}
