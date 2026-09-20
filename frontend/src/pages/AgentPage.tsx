import { useEffect, useState, useRef, useCallback } from 'react';
import {
  Card, Input, Button, Tag, Space, Typography, Select, Collapse, Spin, Empty, Tooltip, message,
} from 'antd';
import {
  SendOutlined, ToolOutlined, CheckCircleOutlined,
  CloseCircleOutlined, LoadingOutlined, RobotOutlined, BulbOutlined,
} from '@ant-design/icons';
import ReactMarkdown from 'react-markdown';
import {
  executeAgentStream,
  listAgents, getAgentTools,
  type AgentDefinition, type ToolDefinition, type ToolStep,
} from '../api/agent';
import {
  createConversation, getMessages, appendMessage,
  type Conversation,
} from '../api/conversation';

interface Message {
  role: 'user' | 'assistant' | 'tool';
  content: string;
  toolSteps?: ToolStep[];       // 工具调用步骤
  streaming?: boolean;
  thinking?: boolean;
  currentToolCall?: { name: string; args: string };
}

const { TextArea } = Input;
const { Text } = Typography;

// 格式化 JSON
function formatJson(jsonStr: string, maxLen = 200): string {
  try {
    return JSON.stringify(JSON.parse(jsonStr), null, 2);
  } catch {
    return jsonStr.length > maxLen ? jsonStr.substring(0, maxLen) + '...' : jsonStr;
  }
}

/** 过滤 LLM 反复生成的 markdown 表格和已知冗余行 */
function stripRedundantMarkdown(content: string): string {
  const lines = content.split('\n');
  const result: string[] = [];
  let inTable = false;
  for (const line of lines) {
    const t = line.trim();
    if (/^\|.+\|$/.test(t)) { inTable = true; continue; }
    if (inTable) { inTable = false; continue; }  // 表格结束也吞掉紧跟的空行
    result.push(line);
  }
  return result.join('\n').replace(/\n{3,}/g, '\n\n').trim();
}

/** 判断值是否为简单类型（字符串/数字/布尔/null） */
function isScalar(v: any) {
  return v === null || v === undefined || typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean';
}

/**
 * 通用工具结果渲染器
 * - 对象数组（如 models）→ HTML 表格
 * - 简单键值对（如 {userCount:2, message:"..."}) → 统计卡片
 * - 其他 → 格式化 JSON
 */
function renderToolResult(resultJson: string): { element: React.ReactNode; title: string } | null {
  let obj: any;
  try { obj = JSON.parse(resultJson); } catch { return null; }
  if (!obj || typeof obj !== 'object') return null;

  // 尝试找到数据数组（models / items / list / data 等键）
  let listKey: string | null = null;
  let list: any[] | null = null;
  for (const key of Object.keys(obj)) {
    if (Array.isArray(obj[key]) && obj[key].length > 0 && typeof obj[key][0] === 'object') {
      listKey = key;
      list = obj[key];
      break;
    }
  }

  if (list && list.length > 0) {
    // 提取列表中所有对象的键作为列
    const columns = Array.from(new Set(list.flatMap((item: any) => Object.keys(item))));
    const th: React.CSSProperties = { padding: '4px 8px', fontWeight: 600, fontSize: 11, borderBottom: '1px solid #e8e8e8', whiteSpace: 'nowrap' };
    const td: React.CSSProperties = { padding: '4px 8px', fontSize: 11, borderBottom: '1px solid #f5f5f5' };

    return {
      title: listKey === 'models' ? '模型状态' : (listKey!),
      element: (
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 11 }}>
          <thead><tr style={{ background: '#fafafa' }}>
            {columns.map((col) => <th key={col} style={th}>{col}</th>)}
          </tr></thead>
          <tbody>
            {list.map((item: any, i: number) => (
              <tr key={i}>
                {columns.map((col) => (
                  <td key={col} style={td}>
                    {col === 'status' ? (
                      <Tag color={item[col] === 'UP' ? 'green' : 'red'} style={{ margin: 0, fontSize: 10 }}>
                        {item[col]}
                      </Tag>
                    ) : Array.isArray(item[col]) ? (
                      item[col].map((v: any) => <Tag key={v} style={{ fontSize: 9, margin: '0 2px 2px 0' }}>{v}</Tag>)
                    ) : (
                      <span style={{ fontSize: 10 }}>{item[col] ?? '-'}</span>
                    )}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      ),
    };
  }

  // 简单键值对（过滤掉 message 等描述字段，只展示数据字段）
  const dataFields = Object.entries(obj).filter(([k, v]) => k !== 'message' && k !== 'error' && isScalar(v));
  if (dataFields.length > 0) {
    return {
      title: '查询结果',
      element: (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {dataFields.map(([key, value]) => (
            <div key={key} style={{
              padding: '8px 14px', background: '#f6ffed', borderRadius: 6,
              border: '1px solid #b7eb8f', minWidth: 120,
            }}>
              <div style={{ fontSize: 10, color: '#888', marginBottom: 2 }}>{key}</div>
              <div style={{ fontSize: 18, fontWeight: 700, color: '#1677ff' }}>{String(value)}</div>
            </div>
          ))}
        </div>
      ),
    };
  }

  return null; // 不适合结构化展示，回退到 JSON
}

/**
 * dispatch_agent 结果渲染器 — 展示子 Agent 的名字和 Markdown 回答
 */
function renderDispatchResult(resultJson: string): { element: React.ReactNode; title: string } | null {
  let obj: any;
  try { obj = JSON.parse(resultJson); } catch { return null; }
  if (!obj || typeof obj !== 'object' || !obj.agent) return null;
  const answer: string = obj.answer || '';
  return {
    title: `${obj.agent} · ${obj.toolCalls ?? 0} 次工具调用`,
    element: (
      <div className="markdown-body" style={{ wordBreak: 'break-word' }}>
        <ReactMarkdown>{answer}</ReactMarkdown>
      </div>
    ),
  };
}

/** 解析 dispatch_agent 的参数，取出子 Agent 名 */
function parseDispatchAgentName(argumentsJson: string): string | null {
  try {
    const obj = JSON.parse(argumentsJson);
    return obj?.agent_name ?? null;
  } catch { return null; }
}

interface AgentPageProps {
  activeConv: Conversation | null;
  onConvMutated: () => void;
  onAgentChange: (agentId: number | null) => void;
}

export default function AgentPage({
  activeConv,
  onConvMutated,
  onAgentChange,
}: AgentPageProps) {
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [selectedAgent, setSelectedAgent] = useState<AgentDefinition | null>(null);
  const [agentTools, setAgentTools] = useState<ToolDefinition[]>([]);
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [loadingAgents, setLoadingAgents] = useState(true);
  const bottomRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<(() => void) | null>(null);
  const pendingToolStepsRef = useRef<ToolStep[]>([]);

  // 加载 Agent 列表，默认选中第一个
  useEffect(() => {
    listAgents()
      .then((res) => {
        setAgents(res.data);
        // 自动选中第一个启用的 Agent（系统助手），确保新建对话时有 agentId
        if (!selectedAgent && res.data.length > 0) {
          const first = res.data.find((a: AgentDefinition) => a.status === 1) || res.data[0];
          setSelectedAgent(first);
        }
      })
      .catch(() => message.error('加载 Agent 列表失败'))
      .finally(() => setLoadingAgents(false));
  }, []);

  // 通知父组件 Agent 选择变化
  useEffect(() => {
    onAgentChange(selectedAgent?.id ?? null);
  }, [selectedAgent?.id, onAgentChange]);

  // 加载 Agent 工具
  useEffect(() => {
    if (!selectedAgent) { setAgentTools([]); return; }
    getAgentTools(selectedAgent.id)
      .then((res) => setAgentTools(res.data.tools || []))
      .catch(() => setAgentTools([]));
  }, [selectedAgent]);

  // 当 activeConv 变化时（用户在左侧 Sider 选择对话），加载历史消息和对应 Agent
  useEffect(() => {
    if (!activeConv) { setMessages([]); return; }
    // 自动选择对应的 Agent
    if (activeConv.agentId) {
      const agent = agents.find((a) => a.id === activeConv.agentId);
      if (agent) setSelectedAgent(agent);
    }
    getMessages(activeConv.id)
      .then((msgs) => setMessages(msgs.map((m) => {
        const msg: Message = { role: m.role as 'user' | 'assistant', content: m.content };
        if (m.metadata) {
          try {
            const meta = JSON.parse(m.metadata);
            if (meta.toolSteps) msg.toolSteps = meta.toolSteps;
          } catch { /* ignore */ }
        }
        return msg;
      })))
      .catch(() => setMessages([]));
  }, [activeConv?.id, agents]);

  // 自动滚动
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  // ==================== 发送消息 ====================
  const handleSend = async () => {
    const msg = input.trim();
    if (!msg || sending) return;
    let conv = activeConv;

    // 首次发送：创建对话
    if (!conv) {
      try {
        conv = await createConversation(
          msg.length > 20 ? msg.substring(0, 20) + '...' : msg,
          selectedAgent?.model || undefined,
          selectedAgent?.id,
        );
        onConvMutated(); // 通知父组件刷新对话列表
      } catch {
        // 持久化失败不影响对话，fallback 到本地模式
      }
    }

    setMessages((prev) => [...prev, { role: 'user', content: msg }]);
    setInput('');
    setSending(true);

    // 持久化用户消息
    if (conv) {
      appendMessage(conv.id, { role: 'user', content: msg }).catch(() => {});
    }

    const assistantMsg: Message = { role: 'assistant', content: '', streaming: true, toolSteps: [] };
    setMessages((prev) => [...prev, assistantMsg]);
    pendingToolStepsRef.current = [];

    const callbacks = {
      onThinking: () => {
        setMessages((prev) => {
          const updated = [...prev];
          const last = updated[updated.length - 1];
          if (last?.streaming) updated[updated.length - 1] = { ...last, thinking: true };
          return updated;
        });
      },
      onToolCallStart: (toolName: string, callId: string) => {
        setMessages((prev) => {
          const updated = [...prev];
          const last = updated[updated.length - 1];
          if (last?.streaming) {
            updated[updated.length - 1] = {
              ...last, thinking: false,
              currentToolCall: { name: toolName, args: '' },
            };
          }
          return updated;
        });
      },
      onToolCallArgs: (callId: string, delta: string) => {
        setMessages((prev) => {
          const updated = [...prev];
          const last = updated[updated.length - 1];
          if (last?.streaming && last.currentToolCall) {
            updated[updated.length - 1] = {
              ...last,
              currentToolCall: { ...last.currentToolCall, args: last.currentToolCall.args + delta },
            };
          }
          return updated;
        });
      },
      onToolCallExecuting: (toolName: string, args: string) => {
        setMessages((prev) => {
          const updated = [...prev];
          const last = updated[updated.length - 1];
          if (last?.streaming) {
            updated[updated.length - 1] = {
              ...last,
              currentToolCall: { name: toolName, args },
            };
          }
          return updated;
        });
      },
      onToolResult: (toolName: string, result: string, success: boolean, elapsedMs: number) => {
        const step: ToolStep = {
          iteration: 0, toolName, arguments: '', result, success, elapsedMs,
        };
        pendingToolStepsRef.current = [...pendingToolStepsRef.current, step];
        setMessages((prev) => {
          const updated = [...prev];
          const last = updated[updated.length - 1];
          if (last?.streaming) {
            const existingSteps = last.toolSteps || [];
            if (last.currentToolCall?.name === toolName) {
              step.arguments = last.currentToolCall.args;
            }
            updated[updated.length - 1] = {
              ...last,
              toolSteps: [...existingSteps, step],
              currentToolCall: undefined,
            };
          }
          return updated;
        });
      },
      onToken: (token: string) => {
        setMessages((prev) => {
          const updated = [...prev];
          const last = updated[updated.length - 1];
          if (last && last.streaming) {
            updated[updated.length - 1] = { ...last, content: last.content + token, thinking: false };
          }
          return updated;
        });
      },
      onDone: (content: string) => {
        setMessages((prev) => {
          const updated = [...prev];
          const last = updated[updated.length - 1];
          if (last && last.streaming) {
            updated[updated.length - 1] = { ...last, streaming: false, thinking: false, content: last.content || content };
          }
          return updated;
        });
        // 持久化助手回复（含 toolSteps metadata）
        if (conv) {
          const steps = pendingToolStepsRef.current;
          const metadata = steps.length > 0 ? JSON.stringify({ toolSteps: steps }) : null;
          const finalContent = content || '';
          appendMessage(conv.id, {
            role: 'assistant',
            content: finalContent,
            metadata,
            model: selectedAgent?.model || null,
          }).catch(() => {});
          pendingToolStepsRef.current = [];
        }
        setSending(false);
        abortRef.current = null;
      },
      onError: (error: string) => {
        setMessages((prev) => {
          const updated = [...prev];
          const last = updated[updated.length - 1];
          if (last && last.streaming) {
            updated[updated.length - 1] = { ...last, streaming: false, thinking: false, content: `执行失败: ${error}` };
          }
          return updated;
        });
        setSending(false);
        abortRef.current = null;
      },
    };

    const abort = executeAgentStream(
      {
        message: msg,
        agentId: selectedAgent?.id,
        agentName: selectedAgent?.name,
        systemPrompt: selectedAgent?.systemPrompt,
        model: selectedAgent?.model || undefined,
        history: messages
          .filter((m) => !m.streaming && m.role !== 'tool')
          .map((m) => ({ role: m.role, content: m.content })),
      },
      callbacks,
    );

    abortRef.current = abort;
  };

  const handleStop = useCallback(() => {
    abortRef.current?.();
    abortRef.current = null;
    setSending(false);
    setMessages((prev) => {
      const updated = [...prev];
      const last = updated[updated.length - 1];
      if (last?.streaming) {
        updated[updated.length - 1] = { ...last, streaming: false, thinking: false };
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

  // ==================== 渲染 ====================

  /** 渲染单条消息 */
  const renderMessage = (msg: Message, i: number) => {
    const isUser = msg.role === 'user';

    return (
      <div key={i} style={{
        marginBottom: 16,
        display: 'flex',
        justifyContent: isUser ? 'flex-end' : 'flex-start',
      }}>
        <div style={{ maxWidth: '85%', minWidth: 200 }}>
          {/* 用户消息 */}
          {isUser && (
            <div style={{
              padding: '10px 16px', borderRadius: 12, background: '#1677ff', color: '#fff',
              display: 'inline-block', float: 'right',
            }}>
              <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{msg.content}</div>
            </div>
          )}

          {/* 助手消息 */}
          {!isUser && (
            <div style={{
              padding: '12px 16px', borderRadius: 12, background: '#fff',
              boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
            }}>
              {/* 思考中 */}
              {msg.thinking && (
                <div style={{ color: '#888', marginBottom: 8, display: 'flex', alignItems: 'center', gap: 8 }}>
                  <LoadingOutlined /> Agent 正在思考...
                </div>
              )}

              {/* 工具调用结果：自动渲染为表格/卡片，或折叠 JSON */}
              {msg.toolSteps && msg.toolSteps.length > 0 && (
                <div style={{ marginBottom: 12 }}>
                  {msg.toolSteps.map((step, si) => {
                    const isDispatch = step.toolName === 'dispatch_agent';
                    const dispatchName = isDispatch ? parseDispatchAgentName(step.arguments || '') : null;
                    const rendered = step.success
                      ? (isDispatch ? renderDispatchResult(step.result || '') : renderToolResult(step.result || ''))
                      : null;
                    return (
                      <div key={si} style={{ marginBottom: 8 }}>
                        {/* 标题栏 */}
                        <div style={{
                          padding: '4px 8px', borderRadius: '6px 6px 0 0',
                          background: step.success ? '#f6ffed' : '#fff2f0',
                          border: '1px solid #e8e8e8', borderBottom: rendered ? 'none' : '1px solid #e8e8e8',
                          display: 'flex', alignItems: 'center', gap: 6,
                        }}>
                          {step.success
                            ? <CheckCircleOutlined style={{ color: '#52c41a', fontSize: 12 }} />
                            : <CloseCircleOutlined style={{ color: '#ff4d4f', fontSize: 12 }} />
                          }
                          <Tag color={isDispatch ? 'blue' : (step.success ? 'green' : 'red')} style={{ margin: 0 }}>
                            {isDispatch
                              ? <>🤖 调度子Agent{dispatchName ? ` · ${dispatchName}` : ''}</>
                              : <><ToolOutlined /> {step.toolName}</>}
                          </Tag>
                          <Text type="secondary" style={{ fontSize: 11 }}>{step.elapsedMs}ms</Text>
                          {rendered && <Text type="secondary" style={{ fontSize: 11, marginLeft: 'auto' }}>{rendered.title}</Text>}
                        </div>
                        {/* 内容区 */}
                        {rendered ? (
                          <div style={{
                            padding: 8, border: '1px solid #e8e8e8', borderTop: 'none',
                            borderRadius: '0 0 6px 6px', background: '#fff',
                            maxHeight: 260, overflow: 'auto',
                          }}>
                            {rendered.element}
                          </div>
                        ) : (
                          <Collapse
                            size="small" ghost
                            style={{ border: '1px solid #e8e8e8', borderTop: 'none', borderRadius: '0 0 6px 6px' }}
                            items={[{
                              key: 'detail',
                              label: <Text type="secondary" style={{ fontSize: 11 }}>查看原始数据</Text>,
                              children: (
                                <div style={{ fontSize: 12 }}>
                                  <div style={{ marginBottom: 4 }}>
                                    <Text type="secondary">参数：</Text>
                                    <pre style={{ margin: 0, padding: '4px 8px', background: '#f5f5f5', borderRadius: 4, fontSize: 12, maxHeight: 80, overflow: 'auto' }}>
                                      {formatJson(step.arguments || '{}')}
                                    </pre>
                                  </div>
                                  <div>
                                    <Text type="secondary">结果：</Text>
                                    <pre style={{ margin: '4px 0 0', padding: '4px 8px', background: step.success ? '#f6ffed' : '#fff2f0', borderRadius: 4, fontSize: 12, maxHeight: 200, overflow: 'auto' }}>
                                      {formatJson(step.result || '', 500)}
                                    </pre>
                                  </div>
                                </div>
                              ),
                            }]}
                          />
                        )}
                      </div>
                    );
                  })}
                </div>
              )}

              {/* 当前工具调用（流式中） */}
              {msg.currentToolCall && (
                <div style={{
                  marginBottom: 8, padding: '8px 12px', background: '#fffbe6',
                  borderRadius: 8, border: '1px solid #ffe58f',
                }}>
                  <Space>
                    <LoadingOutlined style={{ color: '#faad14' }} />
                    <Tag color="orange"><ToolOutlined /> {msg.currentToolCall.name}</Tag>
                    <Text type="secondary" style={{ fontSize: 12 }}>执行中...</Text>
                  </Space>
                  {msg.currentToolCall.args && (
                    <pre style={{
                      margin: '4px 0 0', padding: '4px 8px', background: '#f5f5f5',
                      borderRadius: 4, fontSize: 11, maxHeight: 80, overflow: 'auto',
                    }}>
                      {formatJson(msg.currentToolCall.args)}
                    </pre>
                  )}
                </div>
              )}

              {/* 回答内容 */}
              {(msg.content || msg.streaming) && (
                <div className="markdown-body" style={{ wordBreak: 'break-word' }}>
                  <ReactMarkdown>
                    {(() => {
                      const hasTools = (msg.toolSteps?.length ?? 0) > 0;
                      return hasTools ? stripRedundantMarkdown(msg.content || '') : (msg.content || '');
                    })()}
                  </ReactMarkdown>
                  {msg.streaming && !msg.currentToolCall && !msg.thinking && (
                    <span className="cursor-blink">▌</span>
                  )}
                </div>
              )}

              {/* 空状态（刚开始等第一个 token） */}
              {!msg.content && msg.streaming && !msg.currentToolCall && !msg.thinking && (
                <div style={{ color: '#888' }}>
                  <Spin size="small" /> 等待响应...
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    );
  };

  return (
    <>
      <style>{`
        .markdown-body h1, .markdown-body h2, .markdown-body h3 { margin-top: 12px; margin-bottom: 8px; font-weight: 600; }
        .markdown-body h2 { font-size: 16px; border-bottom: 1px solid #eee; padding-bottom: 4px; }
        .markdown-body h3 { font-size: 14px; }
        .markdown-body p { margin: 4px 0; line-height: 1.6; }
        .markdown-body table { border-collapse: collapse; width: 100%; margin: 8px 0; font-size: 13px; }
        .markdown-body th, .markdown-body td { border: 1px solid #e8e8e8; padding: 6px 12px; text-align: left; }
        .markdown-body th { background: #fafafa; font-weight: 600; }
        .markdown-body tr:nth-child(even) { background: #fafafa; }
        .markdown-body ul, .markdown-body ol { padding-left: 20px; margin: 4px 0; }
        .markdown-body code { background: #f5f5f5; padding: 2px 6px; border-radius: 3px; font-size: 12px; }
        .markdown-body pre { background: #f5f5f5; padding: 12px; border-radius: 6px; overflow-x: auto; font-size: 12px; }
        .markdown-body blockquote { border-left: 3px solid #1677ff; padding-left: 12px; color: #666; margin: 8px 0; }
        .markdown-body strong { color: #1677ff; }
      `}</style>
      <div style={{ display: 'flex', flexDirection: 'column', height: '100%', gap: 12 }}>
        {/* Agent 选择器 + 已绑定工具 */}
        <Card size="small" style={{ background: '#fafafa' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8 }}>
            <Space>
              <RobotOutlined />
              <Text strong>Agent：</Text>
              <Select
                allowClear
                placeholder="选择 Agent（或直接输入自由指令）"
                style={{ minWidth: 240 }}
                value={selectedAgent?.id}
                onChange={(id) => {
                  const agent = agents.find((a) => a.id === id) || null;
                  setSelectedAgent(agent);
                  if (!id) setAgentTools([]);
                }}
                loading={loadingAgents}
                options={agents.map((a) => ({
                  value: a.id,
                  label: (
                    <Space>
                      <span>{a.name}</span>
                      <Tag style={{ fontSize: 10 }} color={a.status === 1 ? 'green' : 'default'}>
                        {a.status === 1 ? '启用' : '禁用'}
                      </Tag>
                    </Space>
                  ),
                }))}
                notFoundContent={loadingAgents ? <Spin size="small" /> : <Empty description="暂无 Agent" />}
              />
              {selectedAgent && (
                <Tooltip title={selectedAgent.description || selectedAgent.systemPrompt}>
                  <Tag color="blue"><BulbOutlined /> {selectedAgent.name}</Tag>
                </Tooltip>
              )}
            </Space>

            {/* 已绑定工具 */}
            {agentTools.length > 0 && (
              <Space size={4} wrap>
                <Text type="secondary" style={{ fontSize: 12 }}>可用工具：</Text>
                {agentTools.map((t) => (
                  <Tooltip key={t.id} title={t.description}>
                    <Tag style={{ fontSize: 11, margin: 0 }} color={
                      t.requiredRole === 'admin' ? 'red' : t.requiredRole === 'user' ? 'blue' : 'default'
                    }>
                      <ToolOutlined /> {t.name}
                    </Tag>
                  </Tooltip>
                ))}
              </Space>
            )}
          </div>
        </Card>

        {/* 聊天区 */}
        <Card style={{ flex: 1, overflow: 'auto', background: '#fafafa' }}>
          {messages.length === 0 ? (
            <div style={{ textAlign: 'center', color: '#999', padding: 64 }}>
              <RobotOutlined style={{ fontSize: 48, marginBottom: 16 }} />
              <div style={{ fontSize: 16, marginBottom: 8 }}>Agent 工作区</div>
              <div style={{ fontSize: 13 }}>
                {selectedAgent
                  ? `已选择「${selectedAgent.name}」，在下方输入自然语言指令`
                  : '在左侧选择 Agent 对话或在下方直接输入指令'}
              </div>
              <div style={{ fontSize: 12, color: '#bbb', marginTop: 8 }}>
                示例：系统有多少注册用户？ / 今天调用量是多少？ / 哪些模型在线？
              </div>
            </div>
          ) : (
            messages.map(renderMessage)
          )}
          <div ref={bottomRef} />
        </Card>

        {/* 输入区 */}
        <div style={{ display: 'flex', gap: 8 }}>
          <TextArea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={
              selectedAgent
                ? `给「${selectedAgent.name}」发送指令... (Enter 发送)`
                : '输入指令... (Enter 发送，Shift+Enter 换行)'
            }
            rows={3}
            disabled={sending}
            style={{ flex: 1 }}
          />
          {sending ? (
            <Button danger onClick={handleStop} style={{ height: 'auto' }}>
              停止
            </Button>
          ) : (
            <Button
              type="primary"
              icon={<SendOutlined />}
              onClick={handleSend}
              disabled={!input.trim()}
              style={{ height: 'auto' }}
            >
              发送
            </Button>
          )}
        </div>
      </div>
    </>
  );
}
