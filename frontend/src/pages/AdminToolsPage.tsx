import { useEffect, useState, useCallback } from 'react';
import {
  Table, Tag, Button, Modal, Input, InputNumber, Switch, Radio, Select, message, Alert, Popconfirm, Space,
} from 'antd';
import { ReloadOutlined, PlusOutlined } from '@ant-design/icons';
import {
  listAllToolDefs, createToolDef, updateToolDef, deleteToolDef, reloadTools,
  type ToolDefinition,
} from '../api/agent';

const DEFAULT_SCHEMA = '{\n  "type": "object",\n  "properties": {},\n  "required": []\n}';

interface FormState {
  category: 'http' | 'internal-api';
  name: string;
  description: string;
  requiredRole: 'public' | 'user' | 'admin';
  timeoutSec: number;
  retryCount: number;
  status: number;
  parametersSchema: string;
  httpMethod: string;
  httpUrl: string;
  httpHeaders: string;
  internalPath: string;
  builtinHandler: string;
}

const emptyForm: FormState = {
  category: 'http',
  name: '',
  description: '',
  requiredRole: 'user',
  timeoutSec: 10,
  retryCount: 0,
  status: 1,
  parametersSchema: DEFAULT_SCHEMA,
  httpMethod: 'GET',
  httpUrl: '',
  httpHeaders: '',
  internalPath: '',
  builtinHandler: '',
};

const CATEGORY_META: Record<string, { label: string; color: string }> = {
  'built-in': { label: '内置', color: 'geekblue' },
  'http': { label: 'HTTP', color: 'orange' },
  'internal-api': { label: '内部API', color: 'cyan' },
  'agent': { label: '子Agent', color: 'blue' },
};

/** 美化 JSON（失败返回原文） */
function prettyJson(s: string): string {
  try { return JSON.stringify(JSON.parse(s), null, 2); } catch { return s; }
}

export default function AdminToolsPage() {
  const [tools, setTools] = useState<ToolDefinition[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editingCategory, setEditingCategory] = useState<string>('http');
  const [form, setForm] = useState<FormState>(emptyForm);
  const [saving, setSaving] = useState(false);

  const fetchTools = useCallback(async () => {
    setLoading(true);
    try {
      const res = await listAllToolDefs();
      setTools(res.data);
    } catch (err: any) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetchTools(); }, [fetchTools]);

  const update = (key: keyof FormState, value: any) => setForm((f) => ({ ...f, [key]: value }));

  const openCreate = () => {
    setEditingId(null);
    setEditingCategory('http');
    setForm(emptyForm);
    setModalOpen(true);
  };

  const openEdit = (record: ToolDefinition) => {
    setEditingId(record.id);
    setEditingCategory(record.category);
    setForm({
      category: (['http', 'internal-api'].includes(record.category) ? record.category : 'http') as FormState['category'],
      name: record.name,
      description: record.description || '',
      requiredRole: record.requiredRole || 'user',
      timeoutSec: Math.round((record.timeoutMs ?? 10000) / 1000),
      retryCount: record.retryCount ?? 0,
      status: record.status ?? (record.enabled ? 1 : 0),
      parametersSchema: prettyJson(typeof record.parametersSchema === 'string'
        ? record.parametersSchema : JSON.stringify(record.parametersSchema ?? {}, null, 2)),
      httpMethod: record.httpMethod || 'GET',
      httpUrl: record.httpUrl || '',
      httpHeaders: record.httpHeaders || '',
      internalPath: record.internalPath || '',
      builtinHandler: record.builtinHandler || '',
    });
    setModalOpen(true);
  };

  const handleSubmit = async () => {
    if (!form.name.trim()) { message.warning('请填写工具名（函数名，如 get_weather）'); return; }
    if (!form.description.trim()) { message.warning('请填写描述（给 LLM 看的，写清什么场景该调用它）'); return; }

    // Schema JSON 校验
    let schemaObj: any;
    try {
      schemaObj = JSON.parse(form.parametersSchema);
    } catch {
      message.error('参数 Schema 不是合法 JSON'); return;
    }
    if (schemaObj?.type !== 'object') {
      message.error('参数 Schema 顶层 type 必须是 "object"'); return;
    }

    // httpHeaders JSON 校验
    let headers: string | null = null;
    if (form.category === 'http' && form.httpHeaders.trim()) {
      try {
        const parsed = JSON.parse(form.httpHeaders);
        if (typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error();
        headers = JSON.stringify(parsed);
      } catch {
        message.error('静态请求头必须是 JSON 对象，如 {"Authorization": "Bearer xxx"}'); return;
      }
    }

    // 类别必填项
    if (form.category === 'http' && !form.httpUrl.trim()) {
      message.warning('HTTP 工具需要填写请求地址'); return;
    }
    if (form.category === 'internal-api' && !form.internalPath.trim()) {
      message.warning('内部 API 工具需要填写 biz-service 接口路径'); return;
    }

    setSaving(true);
    try {
      const payload: Record<string, any> = {
        name: form.name.trim(),
        description: form.description.trim(),
        category: form.category,
        requiredRole: form.requiredRole,
        timeoutMs: form.timeoutSec * 1000,
        retryCount: form.retryCount,
        status: form.status,
        parametersSchema: JSON.stringify(schemaObj),
      };
      if (form.category === 'http') {
        payload.httpMethod = form.httpMethod;
        payload.httpUrl = form.httpUrl.trim();
        payload.httpHeaders = headers;
      } else if (form.category === 'internal-api') {
        payload.internalPath = form.internalPath.trim();
      } else if (form.category === 'built-in') {
        payload.builtinHandler = form.builtinHandler;
      }

      if (editingId == null) {
        await createToolDef(payload);
        message.success('工具已创建');
      } else {
        await updateToolDef(editingId, payload);
        message.success('工具已更新');
      }
      await reloadTools(); // 热刷新注册表，所有 Agent 立即可用
      setModalOpen(false);
      fetchTools();
    } catch { /* handled by interceptor */ } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (record: ToolDefinition) => {
    try {
      await deleteToolDef(record.id);
      message.success('工具已删除（Agent 绑定已级联解除）');
      await reloadTools();
      fetchTools();
    } catch { /* handled by interceptor */ }
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    {
      title: '工具名', dataIndex: 'name', width: 200,
      render: (v: string) => <code style={{ fontSize: 12, fontWeight: 600 }}>{v}</code>,
    },
    { title: '描述', dataIndex: 'description', ellipsis: true },
    {
      title: '类别', dataIndex: 'category', width: 100,
      render: (v: string) => {
        const meta = CATEGORY_META[v] || { label: v, color: 'default' };
        return <Tag color={meta.color}>{meta.label}</Tag>;
      },
    },
    {
      title: '最低权限', dataIndex: 'requiredRole', width: 100,
      render: (v: string) => {
        const map: Record<string, [string, string]> = {
          public: ['公开', 'green'], user: ['用户', 'blue'], admin: ['管理员', 'red'],
        };
        const [label, color] = map[v] || [v, 'default'];
        return <Tag color={color}>{label}</Tag>;
      },
    },
    {
      title: '配置', key: 'config', ellipsis: true,
      render: (_: any, r: ToolDefinition) => {
        if (r.category === 'http') return <code style={{ fontSize: 11 }}>{r.httpMethod} {r.httpUrl}</code>;
        if (r.category === 'internal-api') return <code style={{ fontSize: 11 }}>{r.internalPath}</code>;
        if (r.category === 'built-in') return <code style={{ fontSize: 11 }}>{r.builtinHandler}</code>;
        return '-';
      },
    },
    {
      title: '状态', key: 'status', width: 80,
      render: (_: any, r: ToolDefinition) => {
        const enabled = r.status != null ? r.status === 1 : r.enabled;
        return enabled ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>;
      },
    },
    {
      title: '操作', key: 'action', width: 140,
      render: (_: any, r: ToolDefinition) => (
        <Space>
          <Button type="link" size="small" onClick={() => openEdit(r)}>编辑</Button>
          {r.category !== 'agent' && r.category !== 'built-in' && (
            <Popconfirm
              title="确认删除该工具？"
              description="将级联解除所有 Agent 的绑定，并从调度中移除"
              onConfirm={() => handleDelete(r)}
            >
              <Button type="link" size="small" danger>删除</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  const isBuiltinEdit = editingId != null && editingCategory === 'built-in';

  return (
    <div style={{ padding: 24 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>工具管理</h2>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={fetchTools}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建工具</Button>
        </Space>
      </div>

      {error && <Alert type="error" message={error} style={{ marginBottom: 16 }} showIcon closable />}

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="工具 = 定义（本页维护）× 执行器（平台代码）。HTTP 工具可把任意 REST API 注册为所有 Agent 可用的工具；内置工具由平台代码提供，仅可编辑定义；保存后自动热刷新，所有 Agent 立即生效。"
      />

      <Table
        rowKey="id"
        columns={columns}
        dataSource={tools}
        loading={loading}
        pagination={false}
        size="middle"
      />

      <Modal
        title={editingId == null ? '新建工具' : `编辑工具 · ${form.name}`}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={handleSubmit}
        confirmLoading={saving}
        width={680}
        okText="保存"
        cancelText="取消"
        destroyOnClose
      >
        <div style={{ display: 'grid', gap: 14, paddingTop: 8 }}>
          {/* 类别：新建只能选 http / internal-api；编辑锁定 */}
          {editingId == null ? (
            <div>
              <div style={{ marginBottom: 4, fontSize: 13 }}>工具类别</div>
              <Radio.Group value={form.category} onChange={(e) => update('category', e.target.value)}>
                <Radio.Button value="http">HTTP 工具（调外部 REST API）</Radio.Button>
                <Radio.Button value="internal-api">内部 API（调 biz-service）</Radio.Button>
              </Radio.Group>
              <div style={{ color: '#999', fontSize: 12, marginTop: 4 }}>
                built-in / 子Agent 类别由平台产生，不支持在线新建
              </div>
            </div>
          ) : (
            <div>
              <div style={{ marginBottom: 4, fontSize: 13 }}>工具类别</div>
              <Tag color={(CATEGORY_META[editingCategory] || {}).color}>{(CATEGORY_META[editingCategory] || {}).label}</Tag>
              <span style={{ color: '#999', fontSize: 12 }}>类别创建后不可更改</span>
            </div>
          )}

          <div>
            <div style={{ marginBottom: 4, fontSize: 13 }}>工具名 <span style={{ color: '#ff4d4f' }}>*</span>（LLM 调用的函数名）</div>
            <Input value={form.name} onChange={(e) => update('name', e.target.value)} placeholder="如：get_weather" />
          </div>

          <div>
            <div style={{ marginBottom: 4, fontSize: 13 }}>描述 <span style={{ color: '#ff4d4f' }}>*</span>（给 LLM 看的：什么场景该调用它、返回什么）</div>
            <Input.TextArea value={form.description} onChange={(e) => update('description', e.target.value)}
              rows={2} placeholder="如：查询指定城市的实时天气，返回温度、湿度、天气状况" />
          </div>

          {/* 类别专属配置 */}
          {editingCategory === 'http' && (
            <>
              <div style={{ display: 'flex', gap: 16 }}>
                <div>
                  <div style={{ marginBottom: 4, fontSize: 13 }}>HTTP 方法</div>
                  <Select value={form.httpMethod} onChange={(v) => update('httpMethod', v)} style={{ width: 100 }}
                    options={['GET', 'POST', 'PUT', 'DELETE'].map((m) => ({ value: m, label: m }))} />
                </div>
                <div style={{ flex: 1 }}>
                  <div style={{ marginBottom: 4, fontSize: 13 }}>请求地址 <span style={{ color: '#ff4d4f' }}>*</span>（支持 {'{var}'} 模板，从参数取值）</div>
                  <Input value={form.httpUrl} onChange={(e) => update('httpUrl', e.target.value)}
                    placeholder="如：https://api.example.com/weather/{city}" />
                </div>
              </div>
              <div>
                <div style={{ marginBottom: 4, fontSize: 13 }}>静态请求头（JSON 对象，可选）</div>
                <Input.TextArea value={form.httpHeaders} onChange={(e) => update('httpHeaders', e.target.value)}
                  rows={2} placeholder='{"Authorization": "Bearer xxx"}' style={{ fontFamily: 'monospace' }} />
              </div>
            </>
          )}

          {editingCategory === 'internal-api' && (
            <div>
              <div style={{ marginBottom: 4, fontSize: 13 }}>biz-service 接口路径 <span style={{ color: '#ff4d4f' }}>*</span></div>
              <Input value={form.internalPath} onChange={(e) => update('internalPath', e.target.value)}
                placeholder="如：/api/internal/stats" />
            </div>
          )}

          {isBuiltinEdit && (
            <div>
              <div style={{ marginBottom: 4, fontSize: 13 }}>内置处理器（ai-agent 已实现，只读）</div>
              <Input value={form.builtinHandler} disabled />
            </div>
          )}

          <div>
            <div style={{ marginBottom: 4, fontSize: 13 }}>
              参数 Schema（JSON Schema，LLM 据此生成调用参数）<span style={{ color: '#ff4d4f' }}>*</span>
            </div>
            <Input.TextArea
              value={form.parametersSchema}
              onChange={(e) => update('parametersSchema', e.target.value)}
              rows={7}
              style={{ fontFamily: 'monospace', fontSize: 12 }}
              placeholder={DEFAULT_SCHEMA}
            />
            <div style={{ color: '#999', fontSize: 12, marginTop: 4 }}>
              示例：{'{"type":"object","properties":{"city":{"type":"string","description":"城市名"}},"required":["city"]}'}
            </div>
          </div>

          <div style={{ display: 'flex', gap: 24, alignItems: 'center' }}>
            <div>
              <div style={{ marginBottom: 4, fontSize: 13 }}>最低权限</div>
              <Select value={form.requiredRole} onChange={(v) => update('requiredRole', v)} style={{ width: 120 }}
                options={[
                  { value: 'public', label: '公开（所有人）' },
                  { value: 'user', label: '用户（登录）' },
                  { value: 'admin', label: '管理员' },
                ]} />
            </div>
            <div>
              <div style={{ marginBottom: 4, fontSize: 13 }}>超时（秒）</div>
              <InputNumber min={1} max={120} value={form.timeoutSec}
                onChange={(v) => update('timeoutSec', v ?? 10)} />
            </div>
            <div>
              <div style={{ marginBottom: 4, fontSize: 13 }}>失败重试</div>
              <InputNumber min={0} max={5} value={form.retryCount}
                onChange={(v) => update('retryCount', v ?? 0)} />
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span style={{ fontSize: 13 }}>启用</span>
              <Switch checked={form.status === 1} onChange={(v) => update('status', v ? 1 : 0)} />
            </div>
          </div>

          {form.requiredRole === 'admin' && (
            <Alert type="warning" showIcon
              message="管理员权限工具不会出现在普通用户的 Agent 调度中，防止越权查询全平台数据" />
          )}
        </div>
      </Modal>
    </div>
  );
}