import { useEffect, useState, useCallback } from 'react';
import {
  Table, Tag, Button, Modal, Input, InputNumber, Switch, message, Alert, Popconfirm, Row, Col, Tooltip, Space,
} from 'antd';
import { ReloadOutlined, PlusOutlined } from '@ant-design/icons';
import { listModels, createModel, updateModel, deleteModel, type ModelConfig } from '../api/models';

interface FormState {
  name: string;
  provider: string;
  baseUrl: string;
  modelName: string;
  apiKey: string;
  tags: string;
  priority: number;
  maxFailures: number;
  fallback: string;
  temperature: number;
  maxTokens: number;
  timeoutSeconds: number;
  frequencyPenalty: number;
  presencePenalty: number;
  inputPrice: number;
  outputPrice: number;
  status: number;
}

const emptyForm: FormState = {
  name: '',
  provider: 'openai-compatible',
  baseUrl: '',
  modelName: '',
  apiKey: '',
  tags: '',
  priority: 10,
  maxFailures: 3,
  fallback: '',
  temperature: 0.7,
  maxTokens: 2048,
  timeoutSeconds: 60,
  frequencyPenalty: 0.3,
  presencePenalty: 0.3,
  inputPrice: 0.001,
  outputPrice: 0.002,
  status: 1,
};

export default function AdminModelsPage() {
  const [models, setModels] = useState<ModelConfig[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [saving, setSaving] = useState(false);

  const fetchModels = useCallback(async () => {
    setLoading(true);
    try {
      const res = await listModels();
      setModels(res.data);
    } catch (err: any) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetchModels(); }, [fetchModels]);

  const update = (key: keyof FormState, value: any) => setForm((f) => ({ ...f, [key]: value }));

  const openCreate = () => {
    setEditingId(null);
    setForm(emptyForm);
    setModalOpen(true);
  };

  const openEdit = (record: ModelConfig) => {
    setEditingId(record.id);
    setForm({
      name: record.name,
      provider: record.provider,
      baseUrl: record.baseUrl,
      modelName: record.modelName,
      apiKey: '', // 编辑时留空 = 保持不变
      tags: record.tags || '',
      priority: record.priority,
      maxFailures: record.maxFailures,
      fallback: record.fallback || '',
      temperature: record.temperature,
      maxTokens: record.maxTokens,
      timeoutSeconds: record.timeoutSeconds,
      frequencyPenalty: record.frequencyPenalty,
      presencePenalty: record.presencePenalty,
      inputPrice: record.inputPrice,
      outputPrice: record.outputPrice,
      status: record.status,
    });
    setModalOpen(true);
  };

  const handleSubmit = async () => {
    if (!form.name.trim()) { message.warning('请填写模型标识'); return; }
    if (!form.baseUrl.trim()) { message.warning('请填写 API 地址'); return; }
    if (!form.modelName.trim()) { message.warning('请填写上游模型名'); return; }
    setSaving(true);
    try {
      const payload: Record<string, any> = { ...form, apiKey: form.apiKey.trim() || undefined };
      if (editingId == null) {
        await createModel(payload);
        message.success('模型已创建');
      } else {
        await updateModel(editingId, payload);
        message.success('模型已更新');
      }
      setModalOpen(false);
      fetchModels();
    } catch { /* handled by interceptor */ } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteModel(id);
      message.success('已删除');
      fetchModels();
    } catch { /* handled */ }
  };

  if (error) {
    return <Alert type="warning" message="加载失败" description={error} showIcon />;
  }

  const columns = [
    { title: '模型标识', dataIndex: 'name', key: 'name', width: 160 },
    { title: '上游模型名', dataIndex: 'modelName', key: 'modelName', width: 150 },
    { title: '供应商', dataIndex: 'provider', key: 'provider', width: 140 },
    {
      title: 'API 地址', dataIndex: 'baseUrl', key: 'baseUrl',
      ellipsis: true,
      render: (val: string) => <Tooltip title={val}><span style={{ color: '#999' }}>{val}</span></Tooltip>,
    },
    {
      title: '标签', dataIndex: 'tags', key: 'tags', width: 180,
      render: (val: string) => (val ? val.split(',').filter(Boolean).map((t) => <Tag key={t}>{t}</Tag>) : '-'),
    },
    { title: '优先级', dataIndex: 'priority', key: 'priority', width: 80 },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (val: number) => (val === 1 ? <Tag color="green">启用</Tag> : <Tag color="red">停用</Tag>),
    },
    {
      title: '定价(入/出) 元/千token', key: 'price', width: 160,
      render: (_: any, r: ModelConfig) => (
        <span>{r.inputPrice} / {r.outputPrice}</span>
      ),
    },
    {
      title: 'Key', key: 'apiKey', width: 150,
      render: (_: any, r: ModelConfig) => (
        r.hasApiKey
          ? <code>{r.apiKeyMasked}</code>
          : <Tag color="orange">未配置</Tag>
      ),
    },
    {
      title: '操作', key: 'action', width: 140,
      render: (_: any, r: ModelConfig) => (
        <>
          <Button size="small" type="link" onClick={() => openEdit(r)}>编辑</Button>
          <Popconfirm title="确定删除该模型？" onConfirm={() => handleDelete(r.id)}>
            <Button size="small" type="link" danger>删除</Button>
          </Popconfirm>
        </>
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>🤖 模型管理</h2>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={fetchModels}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增模型</Button>
        </Space>
      </div>

      <Table columns={columns} dataSource={models} rowKey="id" loading={loading} pagination={false} scroll={{ x: 1200 }} />

      <Modal
        title={editingId == null ? '新增模型' : '编辑模型'}
        open={modalOpen}
        onOk={handleSubmit}
        onCancel={() => setModalOpen(false)}
        confirmLoading={saving}
        width={720}
        destroyOnClose
      >
        <Row gutter={16}>
          <Col span={12}>
            <div style={{ marginBottom: 12 }}>
              <div style={{ marginBottom: 4 }}>模型标识 *</div>
              <Input value={form.name} onChange={(e) => update('name', e.target.value)} placeholder="如 deepseek-v4-flash" />
            </div>
          </Col>
          <Col span={12}>
            <div style={{ marginBottom: 12 }}>
              <div style={{ marginBottom: 4 }}>上游模型名 *</div>
              <Input value={form.modelName} onChange={(e) => update('modelName', e.target.value)} placeholder="provider 侧模型名" />
            </div>
          </Col>
          <Col span={12}>
            <div style={{ marginBottom: 12 }}>
              <div style={{ marginBottom: 4 }}>API 地址 *</div>
              <Input value={form.baseUrl} onChange={(e) => update('baseUrl', e.target.value)} placeholder="https://api.deepseek.com" />
            </div>
          </Col>
          <Col span={12}>
            <div style={{ marginBottom: 12 }}>
              <div style={{ marginBottom: 4 }}>供应商</div>
              <Input value={form.provider} onChange={(e) => update('provider', e.target.value)} />
            </div>
          </Col>
          <Col span={24}>
            <div style={{ marginBottom: 12 }}>
              <div style={{ marginBottom: 4 }}>上游 API Key {editingId != null && '(留空表示保持不变)'}</div>
              <Input.Password value={form.apiKey} onChange={(e) => update('apiKey', e.target.value)} placeholder="sk-..." autoComplete="new-password" />
            </div>
          </Col>
          <Col span={24}>
            <div style={{ marginBottom: 12 }}>
              <div style={{ marginBottom: 4 }}>路由标签（逗号分隔）</div>
              <Input value={form.tags} onChange={(e) => update('tags', e.target.value)} placeholder="fast,coding,general" />
            </div>
          </Col>
          <Col span={12}>
            <div style={{ marginBottom: 12 }}>
              <div style={{ marginBottom: 4 }}>优先级（越小越优先）</div>
              <InputNumber min={1} style={{ width: '100%' }} value={form.priority} onChange={(v) => update('priority', v ?? 10)} />
            </div>
          </Col>
          <Col span={12}>
            <div style={{ marginBottom: 12 }}>
              <div style={{ marginBottom: 4 }}>熔断阈值（连续失败次数）</div>
              <InputNumber min={1} style={{ width: '100%' }} value={form.maxFailures} onChange={(v) => update('maxFailures', v ?? 3)} />
            </div>
          </Col>
          <Col span={12}>
            <div style={{ marginBottom: 12 }}>
              <div style={{ marginBottom: 4 }}>回退模型</div>
              <Input value={form.fallback} onChange={(e) => update('fallback', e.target.value)} placeholder="熔断后回退的模型名" />
            </div>
          </Col>
          <Col span={12}>
            <div style={{ marginBottom: 12 }}>
              <div style={{ marginBottom: 4 }}>最大 token</div>
              <InputNumber min={1} style={{ width: '100%' }} value={form.maxTokens} onChange={(v) => update('maxTokens', v ?? 2048)} />
            </div>
          </Col>
          <Col span={12}>
            <div style={{ marginBottom: 12 }}>
              <div style={{ marginBottom: 4 }}>超时（秒）</div>
              <InputNumber min={1} style={{ width: '100%' }} value={form.timeoutSeconds} onChange={(v) => update('timeoutSeconds', v ?? 60)} />
            </div>
          </Col>
          <Col span={12}>
            <div style={{ marginBottom: 12 }}>
              <div style={{ marginBottom: 4 }}>温度</div>
              <InputNumber min={0} max={2} step={0.1} style={{ width: '100%' }} value={form.temperature} onChange={(v) => update('temperature', v ?? 0.7)} />
            </div>
          </Col>
          <Col span={12}>
            <div style={{ marginBottom: 12 }}>
              <div style={{ marginBottom: 4 }}>频率惩罚</div>
              <InputNumber min={-2} max={2} step={0.1} style={{ width: '100%' }} value={form.frequencyPenalty} onChange={(v) => update('frequencyPenalty', v ?? 0.3)} />
            </div>
          </Col>
          <Col span={12}>
            <div style={{ marginBottom: 12 }}>
              <div style={{ marginBottom: 4 }}>存在惩罚</div>
              <InputNumber min={-2} max={2} step={0.1} style={{ width: '100%' }} value={form.presencePenalty} onChange={(v) => update('presencePenalty', v ?? 0.3)} />
            </div>
          </Col>
          <Col span={12}>
            <div style={{ marginBottom: 12 }}>
              <div style={{ marginBottom: 4 }}>输入单价（元/千token）</div>
              <InputNumber min={0} step={0.001} style={{ width: '100%' }} value={form.inputPrice} onChange={(v) => update('inputPrice', v ?? 0.001)} />
            </div>
          </Col>
          <Col span={12}>
            <div style={{ marginBottom: 12 }}>
              <div style={{ marginBottom: 4 }}>输出单价（元/千token）</div>
              <InputNumber min={0} step={0.001} style={{ width: '100%' }} value={form.outputPrice} onChange={(v) => update('outputPrice', v ?? 0.002)} />
            </div>
          </Col>
          <Col span={12}>
            <div style={{ marginBottom: 12 }}>
              <div style={{ marginBottom: 4 }}>启用</div>
              <Switch checked={form.status === 1} onChange={(checked) => update('status', checked ? 1 : 0)} checkedChildren="启用" unCheckedChildren="停用" />
            </div>
          </Col>
        </Row>
      </Modal>
    </div>
  );
}
