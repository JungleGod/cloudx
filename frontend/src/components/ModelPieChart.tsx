import { PieChart, Pie, Cell, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import type { ModelStats } from '../api/stats';

const COLORS = ['#1677ff', '#52c41a', '#faad14', '#ff4d4f', '#722ed1', '#13c2c2', '#eb2f96'];

export default function ModelPieChart({ data }: { data: ModelStats[] }) {
  if (!data || data.length === 0) {
    return <div style={{ textAlign: 'center', color: '#999', padding: 48 }}>暂无数据</div>;
  }

  const chartData = data.map((item) => ({
    name: item.model,
    value: item.calls,
    cost: item.cost,
    tokens: item.tokens,
  }));

  return (
    <ResponsiveContainer width="100%" height={300}>
      <PieChart>
        <Pie
          data={chartData}
          cx="50%"
          cy="50%"
          innerRadius={50}
          outerRadius={100}
          paddingAngle={2}
          dataKey="value"
          label={({ name, percent }) => `${name} ${(percent * 100).toFixed(0)}%`}
        >
          {chartData.map((_, i) => (
            <Cell key={i} fill={COLORS[i % COLORS.length]} />
          ))}
        </Pie>
        <Tooltip
          formatter={(value: number, name: string, props: any) => {
            const { payload } = props;
            return [
              `${value} 次 / ${payload.tokens.toLocaleString()} tokens / ¥${Number(payload.cost).toFixed(4)}`,
              payload.name,
            ];
          }}
        />
        <Legend />
      </PieChart>
    </ResponsiveContainer>
  );
}