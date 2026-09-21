import { useEffect, useState, useCallback } from 'react';
import {
  Table, Tag, Button, Input, InputNumber, Select, DatePicker, Modal, Alert, Space, Typography,
} from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { listCallLogs, getCallLog, type CallLogItem } from '../api/stats';
import type { Dayjs } from 'dayjs';

const { RangePicker } = DatePicker;
const { Text, Paragraph } = Typography;

/** 列表/详情共用的字段过滤：脱掉 preview 大字段 */
function renderJson(text?: string) {
  if (!text) return '(空)';
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

export default function AdminCallLogsPage() {
  const [records, setRecords] = useState<CallLogItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // 服务端分页 + 筛选
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [userId, setUserId] = useState<number | undefined>();
  const [model, setModel] = useState<string>('');
  const [status, setStatus] = useState<string>('');
  const [range, setRange] = useState<[Dayjs, Dayjs] | null>(null);

  // 详情弹窗
  const [detail, setDetail] = useState<CallLogItem | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const buildQuery = useCallback((p: number, s: number) => ({
    page: p,
    size: s,
    userId,
    model: model || undefined,
    status: status || undefined,
    start: range ? range[0].format('YYYY-MM-DD 00:00:00') : undefined,
    end: range ? range[1].format('YYYY-MM-DD 23:59:59') : undefined,
  }), [userId, model, status, range]);

  const fetchLogs = useCallback(async (p = page, s = size) => {
    setLoading(true);
    try {
      const res = await listCallLogs(buildQuery(p, s));
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
    setModel('');
    setStatus('');
    setRange(null);
    setPage(1);
  };

  const showDetail = async (id: number) => {
    setDetailLoading(true);
    try {
      const res = await getCallLog(id);
      setDetail(res.data);
    } catch { /* handled */ } finally {
      setDetailLoading(false);
    }
  };

  if (error && records.length === 0) {
    return <Alert type="warning" message="加载失败" description={error} showIcon />;
  }

  const columns = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 80 },
    { title: '用户', dataIndex: 'username', key: 'username', width: 120 },
    {
      title: '模型', key: 'model', width: 220, ellipsis: true,
      render: (_: any, r: CallLogItem) => {
        // 请求与实际不一致（auto 路由 / failover）时标注，如 auto(deepseek-v4-flash)
        const req = r.requestedModel?.trim();
        if (req && req !== r.model) return `${req}(${r.model})`;
        return r.model;
      },
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (val: string) => val === 'success'
        ? <Tag color="green">成功</Tag>
        : <Tag color="red">失败</Tag>,
    },
    {
      title: 'Token (入/出)', key: 'tokens', width: 140,
      render: (_: any, r: CallLogItem) => `${r.tokensInput ?? 0} / ${r.tokensOutput ?? 0}`,
    },
    {
      title: '费用(元)', dataIndex: 'cost', key: 'cost', width: 110,
      render: (val: number | null) => val == null ? '-' : Number(val).toFixed(6),
    },
    {
      title: '耗时', dataIndex: 'latencyMs', key: 'latencyMs', width: 90,
      render: (val: number) => val != null ? `${val} ms` : '-',
    },
    {
      title: '时间', dataIndex: 'createdAt', key: 'createdAt', width: 170,
      render: (val: string) => val?.substring(0, 19)?.replace('T', ' '),
    },
    {
      title: '操作', key: 'action', width: 80,
      render: (_: any, r: CallLogItem) => (
        <Button size="small" type="link" loading={detailLoading} onClick={() => showDetail(r.id)}>
          详情
        </Button>
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>📜 调用记录</h2>
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
        <Input
          placeholder="模型（如 deepseek-chat）"
          style={{ width: 200 }}
          value={model}
          onChange={(e) => setModel(e.target.value)}
          onPressEnter={handleSearch}
          allowClear
        />
        <Select
          placeholder="状态"
          style={{ width: 100 }}
          value={status || undefined}
          onChange={(v) => setStatus(v ?? '')}
          allowClear
          options={[
            { value: 'success', label: '成功' },
            { value: 'fail', label: '失败' },
          ]}
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
        rowKey="id"
        loading={loading}
        pagination={{
          current: page,
          pageSize: size,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, s) => { setPage(p); setSize(s); },
        }}
      />

      <Modal
        title={`调用详情 #${detail?.id ?? ''}`}
        open={detail != null}
        onCancel={() => setDetail(null)}
        footer={null}
        width={820}
      >
        {detail && (
          <>
            <Space wrap size={24} style={{ marginBottom: 12 }}>
              <Text>用户：<Text strong>{detail.username}</Text></Text>
              <Text>模型：<Text strong>{detail.model}</Text></Text>
              <Text>
                状态：
                {detail.status === 'success'
                  ? <Tag color="green">成功</Tag>
                  : <Tag color="red">失败</Tag>}
              </Text>
              <Text>Token：{detail.tokensInput ?? 0} / {detail.tokensOutput ?? 0}</Text>
              <Text>费用：{detail.cost == null ? '-' : Number(detail.cost).toFixed(6)} 元</Text>
              <Text>耗时：{detail.latencyMs ?? '-'} ms</Text>
              <Text type="secondary">{detail.createdAt?.substring(0, 19)?.replace('T', ' ')}</Text>
            </Space>
            {detail.errorMsg && (
              <Paragraph>
                <Text type="danger">错误：{detail.errorMsg}</Text>
              </Paragraph>
            )}
            <Paragraph>
              <Text strong>请求体</Text>
              <pre style={{
                maxHeight: 240, overflow: 'auto', background: '#f6f6f6',
                padding: 12, borderRadius: 6, fontSize: 12, whiteSpace: 'pre-wrap',
              }}>
                {renderJson(detail.requestBody)}
              </pre>
            </Paragraph>
            <Paragraph>
              <Text strong>响应体</Text>
              <pre style={{
                maxHeight: 240, overflow: 'auto', background: '#f6f6f6',
                padding: 12, borderRadius: 6, fontSize: 12, whiteSpace: 'pre-wrap',
              }}>
                {renderJson(detail.responseBody)}
              </pre>
            </Paragraph>
          </>
        )}
      </Modal>
    </div>
  );
}