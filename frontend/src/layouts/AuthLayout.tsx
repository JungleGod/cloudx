import { Outlet, Navigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { Spin } from 'antd';

/** 未登录可以访问（登录/注册页），已登录跳转仪表盘 */
export default function AuthLayout() {
  const { token, loading } = useAuth();

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh', background: '#0f1729' }}>
        <Spin size="large" />
      </div>
    );
  }

  if (token) {
    return <Navigate to="/dashboard" replace />;
  }

  return (
    <div style={{ display: 'flex', minHeight: '100vh', background: 'linear-gradient(160deg, #0f1729 0%, #151d33 40%, #111b2e 100%)' }}>
      {/* 全局网格背景 */}
      <div
        style={{
          position: 'fixed',
          inset: 0,
          backgroundImage: `
            linear-gradient(rgba(255,255,255,0.025) 1px, transparent 1px),
            linear-gradient(90deg, rgba(255,255,255,0.025) 1px, transparent 1px)
          `,
          backgroundSize: '40px 40px',
          pointerEvents: 'none',
        }}
      />
      {/* 光晕 */}
      <div style={{ position: 'fixed', top: -80, right: -80, width: 320, height: 320, background: 'radial-gradient(circle, rgba(99,102,241,0.1) 0%, transparent 70%)', borderRadius: '50%', pointerEvents: 'none' }} />
      <div style={{ position: 'fixed', bottom: -40, left: -60, width: 280, height: 280, background: 'radial-gradient(circle, rgba(59,130,246,0.08) 0%, transparent 70%)', borderRadius: '50%', pointerEvents: 'none' }} />

      {/* ======== 左侧品牌展示区 ======== */}
      <div
        style={{
          flex: '0 0 42%',
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'center',
          alignItems: 'center',
          padding: '60px 40px',
          position: 'relative',
          zIndex: 1,
        }}
      >
        {/* ====== 产品插画：模拟控制台面板 ====== */}
        <div style={{ position: 'relative', marginBottom: 48 }}>
          <div
            style={{
              width: 280,
              height: 200,
              borderRadius: 12,
              background: 'linear-gradient(135deg, rgba(30,41,72,0.9), rgba(22,30,58,0.95))',
              border: '1px solid rgba(99,102,241,0.15)',
              padding: 16,
              boxShadow: '0 20px 60px rgba(0,0,0,0.3), 0 0 0 1px rgba(99,102,241,0.1) inset',
              position: 'relative',
            }}
          >
            {/* 顶部导航条 */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 14 }}>
              <div style={{ width: 8, height: 8, borderRadius: '50%', background: '#ef4444', opacity: 0.6 }} />
              <div style={{ width: 8, height: 8, borderRadius: '50%', background: '#f59e0b', opacity: 0.6 }} />
              <div style={{ width: 8, height: 8, borderRadius: '50%', background: '#22c55e', opacity: 0.6 }} />
              <div style={{ flex: 1, height: 4, borderRadius: 2, background: 'rgba(255,255,255,0.06)', marginLeft: 8 }} />
            </div>

            {/* 数据卡片行 */}
            <div style={{ display: 'flex', gap: 8, marginBottom: 10 }}>
              <div style={{ flex: 1, height: 40, borderRadius: 6, background: 'linear-gradient(135deg, rgba(99,102,241,0.12), rgba(99,102,241,0.04))', border: '1px solid rgba(99,102,241,0.08)', padding: '6px 10px' }}>
                <div style={{ width: '60%', height: 3, borderRadius: 2, background: 'rgba(99,102,241,0.3)', marginBottom: 6 }} />
                <div style={{ width: '40%', height: 10, borderRadius: 2, background: 'rgba(255,255,255,0.12)' }} />
              </div>
              <div style={{ flex: 1, height: 40, borderRadius: 6, background: 'linear-gradient(135deg, rgba(59,130,246,0.12), rgba(59,130,246,0.04))', border: '1px solid rgba(59,130,246,0.08)', padding: '6px 10px' }}>
                <div style={{ width: '50%', height: 3, borderRadius: 2, background: 'rgba(59,130,246,0.3)', marginBottom: 6 }} />
                <div style={{ width: '35%', height: 10, borderRadius: 2, background: 'rgba(255,255,255,0.12)' }} />
              </div>
            </div>

            {/* 图表区域 */}
            <div style={{ height: 60, borderRadius: 6, background: 'rgba(255,255,255,0.02)', border: '1px solid rgba(255,255,255,0.04)', padding: '8px 10px', display: 'flex', alignItems: 'flex-end', gap: 4, marginBottom: 10 }}>
              {[45, 72, 38, 90, 55, 68, 82, 48, 75, 60, 85, 50].map((h, i) => (
                <div
                  key={i}
                  style={{
                    flex: 1,
                    height: `${h * 0.45}%`,
                    borderRadius: '2px 2px 0 0',
                    background: `linear-gradient(180deg, ${i % 3 === 0 ? 'rgba(99,102,241,0.6)' : 'rgba(59,130,246,0.35)'} 0%, transparent 100%)`,
                  }}
                />
              ))}
            </div>

            {/* 底部列表 */}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
              {[1, 2].map((i) => (
                <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <div style={{ width: 6, height: 6, borderRadius: '50%', background: i === 1 ? '#22c55e' : '#3b82f6', opacity: 0.7 }} />
                  <div style={{ flex: 1, height: 3, borderRadius: 2, background: 'rgba(255,255,255,0.06)' }} />
                </div>
              ))}
            </div>

            {/* 装饰光点 */}
            <div style={{ position: 'absolute', top: 12, right: 14, width: 4, height: 4, borderRadius: '50%', background: '#6366f1', opacity: 0.6, boxShadow: '0 0 8px #6366f1' }} />
          </div>

          {/* 浮动小卡片 */}
          <div
            style={{
              position: 'absolute',
              top: -12,
              right: -24,
              width: 80,
              height: 44,
              borderRadius: 8,
              background: 'linear-gradient(135deg, rgba(59,130,246,0.2), rgba(99,102,241,0.15))',
              border: '1px solid rgba(99,102,241,0.2)',
              padding: '6px 10px',
              boxShadow: '0 8px 32px rgba(0,0,0,0.3)',
              zIndex: 2,
            }}
          >
            <div style={{ width: '70%', height: 3, borderRadius: 2, background: 'rgba(255,255,255,0.2)', marginBottom: 5 }} />
            <div style={{ width: '50%', height: 3, borderRadius: 2, background: 'rgba(255,255,255,0.1)', marginBottom: 5 }} />
            <div style={{ width: '60%', height: 3, borderRadius: 2, background: 'rgba(255,255,255,0.15)' }} />
          </div>
        </div>

        {/* ====== 文字内容 ====== */}
        <div style={{ textAlign: 'center', maxWidth: 360 }}>
          <h1 style={{ fontSize: 26, fontWeight: 700, color: '#fff', margin: '0 0 8px', letterSpacing: -0.5 }}>
            CloudX AI 网关平台
          </h1>
          <p style={{ fontSize: 14, color: 'rgba(255,255,255,0.45)', margin: '0 0 32px', lineHeight: 1.7 }}>
            统一接入和管理多个大模型 API
            <br />
            智能路由 · 负载均衡 · 故障转移 · 成本可控
          </p>

          <div style={{ display: 'flex', justifyContent: 'center', gap: 24 }}>
            {[
              { label: '多模型', sub: 'DeepSeek / 通义千问' },
              { label: '高可用', sub: '自动故障转移' },
              { label: '低成本', sub: '智能选择最优模型' },
            ].map((item) => (
              <div key={item.label}>
                <div style={{ fontSize: 18, fontWeight: 700, color: '#818cf8', marginBottom: 2 }}>{item.label}</div>
                <div style={{ fontSize: 11, color: 'rgba(255,255,255,0.3)' }}>{item.sub}</div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* ======== 右侧表单区 ======== */}
      <div
        style={{
          flex: 1,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          padding: 40,
          position: 'relative',
          zIndex: 1,
        }}
      >
        <div
          style={{
            width: 420,
            maxWidth: '100%',
            background: '#fff',
            borderRadius: 12,
            padding: '40px 36px',
            boxShadow: '0 4px 24px rgba(0,0,0,0.25)',
          }}
        >
          <Outlet />
        </div>
      </div>
    </div>
  );
}
