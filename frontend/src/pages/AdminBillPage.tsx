import { useEffect, useState, useCallback } from 'react';
import {
  Row, Col, Card, Statistic, Spin, Alert, Table, Progress, DatePicker, Button, Space, Typography,
} from 'antd';
import {
  ThunderboltOutlined, FieldNumberOutlined, DollarOutlined, ReloadOutlined,
} from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { getMonthBill, type MonthBill, type BillUserRow } from '../api/stats';
import ModelPieChart from '../components/ModelPieChart';
import DailyTrendChart from '../components/DailyTrendChart';

const { Text } = Typography;

/** 管理员月度账单：全平台视角的汇总，与用户侧 Dashboard（仅本人）区分 */
export default function AdminBillPage() {
  const [month, setMonth] = useState<Dayjs>(dayjs());
  const [bill, setBill] = useState<MonthBill | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchBill = useCallback(async (m: Dayjs) => {
    setLoading(true);
    try {
      const res = await getMonthBill(m.format('YYYY-MM'));
      setBill(res.data);
      setError(null);
    } catch (err: any) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetchBill(month); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const handleMonthChange = (v: Dayjs | null) => {
    if (v) {
      setMonth(v);
      fetchBill(v);
    }
  };

  if (error && !bill) {
    return <Alert type="warning" message="账单加载失败" description={error} showIcon />;
  }

  const summary = bill?.summary;
  const totalCost = Number(summary?.cost ?? 0);
  const failCalls = (summary?.calls ?? 0) - (summary?.successCalls ?? 0);

  const userColumns = [
    { title: '排名', key: 'rank', width: 70, render: (_: any, __: BillUserRow, idx: number) => idx + 1 },
    { title: '用户', dataIndex: 'username', key: 'username', width: 160 },
    { title: '调用次数', dataIndex: 'calls', key: 'calls', width: 100 },
    {
      title: 'Token', dataIndex: 'tokensTotal', key: 'tokensTotal', width: 130,
      render: (v: number) => (v ?? 0).toLocaleString(),
    },
    {
      title: '费用(元)', dataIndex: 'cost', key: 'cost', width: 130,
      render: (v: number) => Number(v ?? 0).toFixed(6),
    },
    {
      title: '费用占比', key: 'share',
      render: (_: any, r: BillUserRow) => (
        <Progress
          percent={totalCost > 0 ? Math.round((Number(r.cost) / totalCost) * 100) : 0}
          size="small"
          style={{ width: 150 }}
        />
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>💰 账单汇总（全平台）</h2>
        <Space>
          <DatePicker
            picker="month"
            value={month}
            onChange={handleMonthChange}
            allowClear={false}
          />
          <Button icon={<ReloadOutlined />} onClick={() => fetchBill(month)}>刷新</Button>
        </Space>
      </div>

      {/* 汇总卡片 */}
      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic
              title="本月调用"
              value={summary?.calls ?? 0}
              prefix={<ThunderboltOutlined />}
              suffix="次"
            />
            <Text type="secondary">
              成功 {summary?.successCalls ?? 0} / 失败 {failCalls}
            </Text>
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic
              title="本月 Token 消耗"
              value={(summary?.tokensInput ?? 0) + (summary?.tokensOutput ?? 0)}
              prefix={<FieldNumberOutlined />}
              formatter={(v) => (v as number).toLocaleString()}
            />
            <Text type="secondary">
              入 {(summary?.tokensInput ?? 0).toLocaleString()} / 出 {(summary?.tokensOutput ?? 0).toLocaleString()}
            </Text>
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic
              title="本月总费用"
              value={totalCost}
              prefix={<DollarOutlined />}
              precision={4}
              suffix="元"
            />
            <Text type="secondary">{bill?.month ?? month.format('YYYY-MM')} 月账单</Text>
          </Card>
        </Col>
      </Row>

      {/* 图表区 */}
      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        <Col xs={24} lg={10}>
          <Card title="模型成本分布">
            <ModelPieChart data={bill?.byModel ?? []} />
          </Card>
        </Col>
        <Col xs={24} lg={14}>
          <Card title="每日消费趋势">
            <DailyTrendChart data={bill?.daily ?? []} />
          </Card>
        </Col>
      </Row>

      {/* 用户费用 Top 10 */}
      <Card title="用户费用 Top 10" style={{ marginBottom: 24 }}>
        <Table
          columns={userColumns}
          dataSource={bill?.byUser ?? []}
          rowKey="userId"
          loading={loading}
          pagination={false}
          size="small"
        />
      </Card>

      {loading && <div style={{ textAlign: 'center', padding: 16 }}><Spin /></div>}
    </div>
  );
}