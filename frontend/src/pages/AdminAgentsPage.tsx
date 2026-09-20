import { useEffect, useState, useCallback } from 'react';
import {
  Table, Tag, Button, Modal, Input, InputNumber, Switch, Radio, Select, message, Alert, Popconfirm, Space,
} from 'antd';
import { ReloadOutlined, PlusOutlined, RobotOutlined, ApiOutlined } from '@ant-design/icons';
import {
  listAgents, createAgent, updateAgent, deleteAgent, bindAgentTools,
  getAllTools, getAgentTools, reloadTools,
  type AgentDefinition, type ToolDefinition,
} from '../api/agent';

interface FormState {
  executionType: 'internal' | 'external';
  name: string;
  description: string;
  systemPrompt: string;
  temperature: number;
  maxIterations: number;
  toolIds: number[];
  endpointUrl: string;
  endpointKey: string;
  dispatchTimeoutMs: number;
  status: number;
}

const emptyForm: FormState = {
  executionType: 'internal',
  name: '',
  description: '',
  systemPrompt: '',
  temperature: 0.7,
  maxIterations: 5,
  toolIds: [],
  endpointUrl: '',
  endpointKey: '',
  dispatchTimeoutMs: 60000,
  status: 1,
};

/** 第三方 Agent 落库的占位提示词（提示词由对方平台管理，DB 列 NOT NULL） */
const EXTERNAL_PROMPT_PLACEHOLDER = '（第三方Agent提示词由对方平台管理，此字段仅占位）';

export default function AdminAgentsPage() {
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [tools, setTools] = useState<ToolDefinition[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [saving, setSaving] = useState(false);
  const [endpointUrlChanged, setEndpointUrlChanged] = useState(false); // 调度目标变化时提示刷新

  const fetchAgents = useCallback(async () => {
    setLoading(true);
    try {
      const res = await listAgents();
      setAgents(res.data);
    } catch (err: any) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, []);

  const fetchTools = useCallback(async () => {
    try {
      const res = await getAllTools('admin');
      setTools(res.data.tools);
    } catch { /* 工具列表加载失败不阻塞页面 */ }
  }, []);

  useEffect(() => { fetchAgents(); fetchTools(); }, [fetchAgents, fetchTools]);

  const update = (key: keyof FormState, value: any) => setForm((f) => ({ ...f, [key]: value }));

  const openCreate = () => {
    setEditingId(null);
    setForm(emptyForm);
    setModalOpen(true);
  };

  const openEdit = async (record: AgentDefinition) => {
    setEditingId(record.id);
    setForm({
      executionType: record.executionType || 'internal',
      name: record.name,
      description: record.description || '',
      systemPrompt: record.systemPrompt || '',
      temperature: record.temperature ?? 0.7,
      maxIterations: record.maxIterations ?? 5,
      toolIds: [],
      endpointUrl: record.endpointUrl || '',
      endpointKey: '', // 留空 = 保留原 Key
      dispatchTimeoutMs: record.dispatchTimeoutMs ?? 60000,
      status: record.status,
    });
    setModalOpen(true);
    // 回填已绑定的工具
    if ((record.executionType || 'internal') === 'internal') {
      try {
        const res = await getAgentTools(record.id, 'admin');
        setForm((f) => ({ ...f, toolIds: res.data.tools.map((t) => t.id) }));
      } catch { /* 绑定回填失败不阻塞编辑 */ }
    }
  };

  const handleSubmit = async () => {
    if (!form.name.trim()) { message.warning('请填写 Agent 名称'); return; }
    if (form.executionType === 'internal' && !form.systemPrompt.trim()) {
      message.warning('内置 Agent 需要填写系统提示词'); return;
    }
    if (form.executionType === 'external') {
      if (!/^https?:\/\//.test(form.endpointUrl.trim())) {
        message.warning('第三方地址必须以 http(s):// 开头'); return;
      }
    }
    setSaving(true);
    try {
      const payload: Record<string, any> = {
        name: form.name.trim(),
        description: form.description.trim(),
        status: form.status,
        executionType: form.executionType,
      };
      if (form.executionType === 'internal') {
        payload.systemPrompt = form.systemPrompt;
        payload.temperature = form.temperature;
        payload.maxIterations = form.maxIterations;
      } else {
        payload.systemPrompt = EXTERNAL_PROMPT_PLACEHOLDER;
        payload.endpointUrl = form.endpointUrl.trim();
        payload.dispatchTimeoutMs = form.dispatchTimeoutMs;
        if (form.endpointKey.trim()) payload.endpointKey = form.endpointKey.trim();
      }

      let agentId = editingId;
      if (editingId == null) {
        const res = await createAgent(payload);
        agentId = res.data?.id;
        message.success('Agent 已创建');
      } else {
        await updateAgent(editingId, payload);
        message.success('Agent 已更新');
      }

      // 内置 Agent 同步工具绑定（第三方 Agent 无工具绑定）
      if (agentId != null && form.executionType === 'internal') {
        await bindAgentTools(agentId, form.toolIds);
      }

      // 热刷新工具注册表 + 子 Agent 名册，编排立即生效
      await reloadTools();
      setModalOpen(false);
      fetchAgents();
    } catch { /* handled by interceptor */ } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (record: AgentDefinition) => {
    try {
      await deleteAgent(record.id);
      message.success('Agent 已删除');
      await reloadTools();
      fetchAgents();
    } catch { /* handled by interceptor */ }
  };

  const columns = [
    {
      title: 'ID', dataIndex: 'id', width: 60,
    },
    {
      title: '名称', dataIndex: 'name', width: 160,
      render: (v: string, r: AgentDefinition) => (
        <Space>
          <span style={{ fontWeight: 600 }}>{v}</span>
          {(r.executionType || 'internal') === 'external'
            ? <Tag color="purple" icon={<ApiOutlined />}>第三方</Tag>
            : <Tag color="blue" icon={<RobotOutlined />}>内置</Tag>}
        </Space>
      ),
    },
    {
      title: '描述', dataIndex: 'description', ellipsis: true,
    },
    {
      title: '调度目标 / 提示词', key: 'target', width: 260, ellipsis: true,
      render: (_: any, r: AgentDefinition) => (r.executionType || 'internal') === 'external'
        ? <code style={{ fontSize: 11 }}>{r.endpointUrl || '-'}</code>
        : <span style={{ color: '#888', fontSize: 12 }}>{r.systemPrompt?.slice(0, 60)}…</span>,
    },
    {
      title: '状态', dataIndex: 'status', width: 80,
      render: (v: number) => v === 1 ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>,
    },
    {
      title: '更新时间', dataIndex: 'updatedAt', width: 160,
      render: (v: string) => v ? new Date(v).toLocaleString() : '-',
    },
    {
      title: '操作', key: 'action', width: 140,
      render: (_: any, r: AgentDefinition) => (
        <Space>
          <Button type="link" size="small" onClick={() => openEdit(r)}>编辑</Button>
          {r.id !== 1 && (
            <Popconfirm
              title="确认删除该 Agent？"
              description="正在使用它的对话和编排将回退到内置行为"
              onConfirm={() => handleDelete(r)}
            >
              <Button type="link" size="small" danger>删除</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div style={{ padding: 24 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>Agent 管理</h2>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => { fetchAgents(); fetchTools(); }}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建 Agent</Button>
        </Space>
      </div>

      {error && <Alert type="error" message={error} style={{ marginBottom: 16 }} showIcon closable />}

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="内置 Agent 由平台本地执行（可绑定工具）；第三方 Agent 通过 OpenAI 兼容接口委托执行（提示词与工具在对方平台）。两者都可被「系统助手」通过 dispatch_agent 自动调度。"
      />

      <Table
        rowKey="id"
        columns={columns}
        dataSource={agents}
        loading={loading}
        pagination={false}
        size="middle"
      />

      <Modal
        title={editingId == null ? '新建 Agent' : `编辑 Agent · ${form.name}`}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={handleSubmit}
        confirmLoading={saving}
        width={640}
        okText="保存"
        cancelText="取消"
        destroyOnClose
      >
        <div style={{ display: 'grid', gap: 14, paddingTop: 8 }}>
          <div>
            <div style={{ marginBottom: 4, fontSize: 13 }}>Agent 类型</div>
            <Radio.Group
              value={form.executionType}
              onChange={(e) => update('executionType', e.target.value)}
              disabled={editingId != null}
            >
              <Radio.Button value="internal"><RobotOutlined /> 平台内置</Radio.Button>
              <Radio.Button value="external"><ApiOutlined /> 第三方 Agent</Radio.Button>
            </Radio.Group>
            {editingId != null && (
              <div style={{ color: '#999', fontSize: 12, marginTop: 4 }}>类型创建后不可切换</div>
            )}
          </div>

          <div>
            <div style={{ marginBottom: 4, fontSize: 13 }}>名称 <span style={{ color: '#ff4d4f' }}>*</span></div>
            <Input value={form.name} onChange={(e) => update('name', e.target.value)} placeholder="如：数据分析Agent" />
          </div>

          <div>
            <div style={{ marginBottom: 4, fontSize: 13 }}>描述（展示给 LLM 的调度依据，写清擅长什么）</div>
            <Input value={form.description} onChange={(e) => update('description', e.target.value)}
              placeholder="如：查询系统用户数、今日调用统计、个人使用量" />
          </div>

          {form.executionType === 'internal' ? (
            <>
              <div>
                <div style={{ marginBottom: 4, fontSize: 13 }}>系统提示词 <span style={{ color: '#ff4d4f' }}>*</span></div>
                <Input.TextArea
                  value={form.systemPrompt}
                  onChange={(e) => update('systemPrompt', e.target.value)}
                  rows={5}
                  placeholder="定义这个 Agent 的角色和行为规则"
                />
              </div>
              <div style={{ display: 'flex', gap: 24 }}>
                <div>
                  <div style={{ marginBottom: 4, fontSize: 13 }}>温度</div>
                  <InputNumber min={0} max={2} step={0.1} value={form.temperature}
                    onChange={(v) => update('temperature', v ?? 0.7)} />
                </div>
                <div>
                  <div style={{ marginBottom: 4, fontSize: 13 }}>最大迭代轮数</div>
                  <InputNumber min={1} max={10} value={form.maxIterations}
                    onChange={(v) => update('maxIterations', v ?? 5)} />
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <span style={{ fontSize: 13 }}>启用</span>
                  <Switch checked={form.status === 1} onChange={(v) => update('status', v ? 1 : 0)} />
                </div>
              </div>
              <div>
                <div style={{ marginBottom: 4, fontSize: 13 }}>绑定工具</div>
                <Select
                  mode="multiple"
                  style={{ width: '100%' }}
                  placeholder="选择该 Agent 可以调用的工具"
                  value={form.toolIds}
                  onChange={(v) => update('toolIds', v)}
                  options={tools.map((t) => ({
                    value: t.id,
                    label: `${t.name}（${t.category} · ${t.requiredRole}）`,
                  }))}
                  optionFilterProp="label"
                />
              </div>
            </>
          ) : (
            <>
              <div>
                <div style={{ marginBottom: 4, fontSize: 13 }}>调度地址 <span style={{ color: '#ff4d4f' }}>*</span>（OpenAI 兼容 chat/completions）</div>
                <Input value={form.endpointUrl} onChange={(e) => {
                  update('endpointUrl', e.target.value);
                  setEndpointUrlChanged(true);
                }} placeholder="如：https://dify.example.com/v1/chat/completions" />
                {endpointUrlChanged && editingId != null && (
                  <div style={{ color: '#faad14', fontSize: 12, marginTop: 4 }}>
                    地址已修改，保存后将自动热刷新调度目标
                  </div>
                )}
              </div>
              <div>
                <div style={{ marginBottom: 4, fontSize: 13 }}>鉴权 Key（AES 加密存储）</div>
                <Input.Password
                  value={form.endpointKey}
                  onChange={(e) => update('endpointKey', e.target.value)}
                  placeholder={editingId != null ? '留空则保留原 Key' : '无鉴权可留空'}
                  autoComplete="new-password"
                />
              </div>
              <div style={{ display: 'flex', gap: 24, alignItems: 'center' }}>
                <div>
                  <div style={{ marginBottom: 4, fontSize: 13 }}>调用超时（秒）</div>
                  <InputNumber min={5} max={300} value={form.dispatchTimeoutMs / 1000}
                    onChange={(v) => update('dispatchTimeoutMs', (v ?? 60) * 1000)} />
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <span style={{ fontSize: 13 }}>启用</span>
                  <Switch checked={form.status === 1} onChange={(v) => update('status', v ? 1 : 0)} />
                </div>
              </div>
              <Alert
                type="warning"
                showIcon
                message="数据出域提醒：发给该 Agent 的任务内容将离开本平台，请勿委派涉及敏感数据的任务"
              />
            </>
          )}
        </div>
      </Modal>
    </div>
  );
}