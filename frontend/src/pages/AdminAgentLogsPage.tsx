import { useEffect, useState, useCallback } from 'react';
import {
  Table, Tag, Button, InputNumber, DatePicker, Modal, Alert, Space, Typography,
} from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { listAgentLogs, getAgentLogDetail, type AgentLogSession, type AgentLogStep } from '../api/stats';
import type { Dayjs } from 'dayjs';

const { RangePicker } = DatePicker;
const { Text, Paragraph } = Typography;

const STEP_TYPE_LABELS: Record<string, string> = {
  llm_call: 'LLM 调用',
  tool_call: '工具调用',
  tool_result: '工具结果',
  final_answer: '最终回答',
};

const STEP_TYPE_COLORS: Record<string, string> = {
  llm_call: 'blue',
  tool_call: 'purple',
  tool_result: 'cyan',
  final_answer: 'green',
};

function renderJson(text?: string) {
  if (!text) return '(空)';
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

function fmtTime(val?: string) {
  return val?.substring(0, 19)?.replace('T', ' ');
}

const preStyle = {
  maxHeight: 240, overflow: 'auto', background: '#f6f6f6',
  padding: 12, borderRadius: 6, fontSize: 12, whiteSpace: 'pre-wrap' as const,
};

export default function AdminAgentLogsPage() {
  const [records, setRecords] = useState<AgentLogSession[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // 服务端分页 + 筛选
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [userId, setUserId] = useState<number | undefined>();
  const [agentId, setAgentId] = useState<number | undefined>();
  const [range, setRange] = useState<[Dayjs, Dayjs] | null>(null);

  // 详情弹窗
  const [detail, setDetail] = useState<{
    sessionId: string; agentName: string; username: string; steps: AgentLogStep[];
  } | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const buildQuery = useCallback((p: number, s: number) => ({
    page: p,
    size: s,
    userId,
    agentId,
    start: range ? range[0].format('YYYY-MM-DD 00:00:00') : undefined,
    end: range ? range[1].format('YYYY-MM-DD 23:59:59') : undefined,
  }), [userId, agentId, range]);

  const fetchLogs = useCallback(async (p = page, s = size) => {
    setLoading(true);
    try {
      const res = await listAgentLogs(buildQuery(p, s));
      setRecords(res.data.records);
      setTotal(res.data.total);
      setError(null);
    } catch (err: any) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, [buildQuery, page, size]);

  useEffect(() => { fetchLogs(page, size); }, [page, size]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleSearch = () => {
    setPage(1);
    fetchLogs(1, size);
  };

  const handleReset = () => {
    setUserId(undefined);
    setAgentId(undefined);
    setRange(null);
    setPage(1);
  };

  const showDetail = async (sessionId: string) => {
    setDetailLoading(true);
    try {
      const res = await getAgentLogDetail(sessionId);
      setDetail(res.data);
    } catch { /* handled */ } finally {
      setDetailLoading(false);
    }
  };

  if (error && records.length === 0) {
    return <Alert type="warning" message="加载失败" description={error} showIcon />;
  }

  const columns = [
    { title: '开始时间', dataIndex: 'startedAt', key: 'startedAt', width: 170, render: fmtTime },
    { title: '用户', dataIndex: 'username', key: 'username', width: 120 },
    { title: 'Agent', dataIndex: 'agentName', key: 'agentName', width: 140, ellipsis: true },
    { title: '步骤数', dataIndex: 'steps', key: 'steps', width: 80 },
    {
      title: 'Token (入/出)', key: 'tokens', width: 140,
      render: (_: any, r: AgentLogSession) => `${r.tokensInput ?? 0} / ${r.tokensOutput ?? 0}`,
    },
    {
      title: '失败步骤', dataIndex: 'errorSteps', key: 'errorSteps', width: 90,
      render: (val: number) => val > 0 ? <Text type="danger">{val}</Text> : <Text type="secondary">0</Text>,
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (val: string) => val === 'success'
        ? <Tag color="green">成功</Tag>
        : <Tag color="red">有失败</Tag>,
    },
    {
      title: '操作', key: 'action', width: 80,
      render: (_: any, r: AgentLogSession) => (
        <Button size="small" type="link" loading={detailLoading} onClick={() => showDetail(r.sessionId)}>
          步骤链
        </Button>
      ),
    },
  ];

  const stepColumns = [
    { title: '轮次', dataIndex: 'iteration', key: 'iteration', width: 70 },
    {
      title: '类型', dataIndex: 'stepType', key: 'stepType', width: 110,
      render: (val: string) => (
        <Tag color={STEP_TYPE_COLORS[val] ?? 'default'}>
          {STEP_TYPE_LABELS[val] ?? val}
        </Tag>
      ),
    },
    {
      title: '工具 / 模型', key: 'target', width: 180, ellipsis: true,
      render: (_: any, r: AgentLogStep) => r.toolName ?? r.model ?? '-',
    },
    {
      title: 'Token (入/出)', key: 'tokens', width: 130,
      render: (_: any, r: AgentLogStep) => `${r.tokensInput ?? 0} / ${r.tokensOutput ?? 0}`,
    },
    {
      title: '耗时', dataIndex: 'latencyMs', key: 'latencyMs', width: 90,
      render: (val: number) => val != null ? `${val} ms` : '-',
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (val: string) => val === 'success'
        ? <Tag color="green">成功</Tag>
        : <Tag color="red">失败</Tag>,
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>🧾 Agent 执行审计</h2>
        <Button icon={<ReloadOutlined />} onClick={() => fetchLogs()}>刷新</Button>
      </div>

      {/* 筛选栏 */}
      <Space wrap style={{ marginBottom: 16 }}>
        <InputNumber
          placeholder="用户ID"
          min={1}
          style={{ width: 110 }}
          value={userId}
          onChange={(v) => setUserId(v ?? undefined)}
        />
        <InputNumber
          placeholder="Agent ID"
          min={1}
          style={{ width: 110 }}
          value={agentId}
          onChange={(v) => setAgentId(v ?? undefined)}
        />
        <RangePicker
          value={range}
          onChange={(v) => setRange(v as [Dayjs, Dayjs] | null)}
        />
        <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>查询</Button>
        <Button onClick={handleReset}>重置</Button>
      </Space>

      <Table
        columns={columns}
        dataSource={records}
        rowKey="sessionId"
        loading={loading}
        pagination={{
          current: page,
          pageSize: size,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 次执行`,
          onChange: (p, s) => { setPage(p); setSize(s); },
        }}
      />

      <Modal
        title={`执行步骤链 ${detail ? detail.agentName : ''}`}
        open={detail != null}
        onCancel={() => setDetail(null)}
        footer={null}
        width={900}
      >
        {detail && (
          <>
            <Space wrap size={24} style={{ marginBottom: 12 }}>
              <Text>用户：<Text strong>{detail.username}</Text></Text>
              <Text>Agent：<Text strong>{detail.agentName}</Text></Text>
              <Text>步骤数：<Text strong>{detail.steps.length}</Text></Text>
            </Space>
            <Table
              columns={stepColumns}
              dataSource={detail.steps}
              rowKey="id"
              size="small"
              pagination={false}
              expandable={{
                expandedRowRender: (r: AgentLogStep) => (
                  <>
                    {r.errorMsg && (
                      <Paragraph>
                        <Text type="danger">错误：{r.errorMsg}</Text>
                      </Paragraph>
                    )}
                    <Paragraph>
                      <Text strong>请求 / 输入</Text>
                      <pre style={preStyle}>{renderJson(r.requestPreview)}</pre>
                    </Paragraph>
                    <Paragraph>
                      <Text strong>响应 / 输出</Text>
                      <pre style={preStyle}>{renderJson(r.responsePreview)}</pre>
                    </Paragraph>
                  </>
                ),
              }}
            />
          </>
        )}
      </Modal>
    </div>
  );
}