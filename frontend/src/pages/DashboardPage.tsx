import { useEffect, useState } from 'react';
import { Row, Col, Card, Statistic, Spin, Alert } from 'antd';
import {
  ThunderboltOutlined,
  FieldNumberOutlined,
  DollarOutlined,
} from '@ant-design/icons';
import { getTodayStats, getStatsByModel, getStatsDaily, type TodayStats, type ModelStats, type DailyStats } from '../api/stats';
import ModelPieChart from '../components/ModelPieChart';
import DailyTrendChart from '../components/DailyTrendChart';

export default function DashboardPage() {
  const [today, setToday] = useState<TodayStats | null>(null);
  const [byModel, setByModel] = useState<ModelStats[]>([]);
  const [daily, setDaily] = useState<DailyStats[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([
      getTodayStats(),
      getStatsByModel(7),
      getStatsDaily(30),
    ])
      .then(([todayRes, modelRes, dailyRes]) => {
        setToday(todayRes.data);
        setByModel(modelRes.data);
        setDaily(dailyRes.data);
      })
      .catch((err) => setError(err.message))
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return <div style={{ textAlign: 'center', padding: 64 }}><Spin size="large" /></div>;
  }

  if (error) {
    return <Alert type="warning" message="统计数据加载失败" description={error} showIcon />;
  }

  return (
    <div>
      <h2 style={{ marginBottom: 24 }}>📊 数据概览</h2>

      {/* 今日概览卡片 */}
      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic
              title="今日调用次数"
              value={today?.calls ?? 0}
              prefix={<ThunderboltOutlined />}
              suffix="次"
            />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic
              title="今日 Token 消耗"
              value={today?.tokens ?? 0}
              prefix={<FieldNumberOutlined />}
              formatter={(v) => (v as number).toLocaleString()}
            />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic
              title="今日费用"
              value={today?.cost ?? 0}
              prefix={<DollarOutlined />}
              precision={4}
              suffix="元"
            />
          </Card>
        </Col>
      </Row>

      {/* 图表区 */}
      <Row gutter={[16, 16]}>
        <Col xs={24} lg={10}>
          <Card title="近7天模型分布">
            <ModelPieChart data={byModel} />
          </Card>
        </Col>
        <Col xs={24} lg={14}>
          <Card title="近30天调用趋势">
            <DailyTrendChart data={daily} />
          </Card>
        </Col>
      </Row>
    </div>
  );
}