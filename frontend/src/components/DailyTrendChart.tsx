import { ComposedChart, Bar, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import type { DailyStats } from '../api/stats';

export default function DailyTrendChart({ data }: { data: DailyStats[] }) {
  if (!data || data.length === 0) {
    return <div style={{ textAlign: 'center', color: '#999', padding: 48 }}>暂无数据</div>;
  }

  const chartData = data.map((item) => ({
    date: item.date?.slice(5) ?? item.date, // MM-DD
    calls: item.calls,
    tokens: item.tokens,
    cost: Number(item.cost),
  }));

  return (
    <ResponsiveContainer width="100%" height={300}>
      <ComposedChart data={chartData}>
        <CartesianGrid strokeDasharray="3 3" />
        <XAxis dataKey="date" fontSize={12} />
        <YAxis yAxisId="left" fontSize={12} />
        <YAxis yAxisId="right" orientation="right" fontSize={12} />
        <Tooltip />
        <Legend />
        <Bar yAxisId="left" dataKey="calls" fill="#1677ff" name="调用次数" barSize={10} />
        <Line yAxisId="right" type="monotone" dataKey="cost" stroke="#ff4d4f" name="费用(元)" strokeWidth={2} dot={false} />
      </ComposedChart>
    </ResponsiveContainer>
  );
}