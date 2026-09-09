# 004 — 统计接口未按用户隔离，新用户看到全平台调用数据

**日期**：2026-09-04  
**标签**：DataIsolation、统计、JWT、权限、BizService  
**影响范围**：DashboardPage 数据概览（今日概览 / 近7天模型分布 / 近30天调用趋势）

## 现象

新注册的用户登录后，数据概览「近30天调用趋势」显示 8-11 有 61 次调用，且多个日期都有调用记录——这些全是其他用户的调用数据，新用户自己还没调用过任何一次。

## 排查过程

1. 前端 `DashboardPage` 调 `getTodayStats()` / `getStatsByModel(7)` / `getStatsDaily(30)`
2. `getTodayStats` 有 `userId` 参数但前端没传；`by-model` / `daily` 后端**根本没有** userId 参数
3. 后端 `CallLogServiceImpl.statsDaily/statsByModel` 查询只按 `created_at + status=success` 聚合全表，无 `user_id` 过滤
4. 对比 `ApiKeyController` 已经通过 `jwtUtil.getUserId()` 按用户隔离，说明是统计接口遗漏了

## 根因

三个统计接口都没按用户分割：

| 接口 | 用途 | 后端接受 userId? | 前端传了吗 |
|------|------|-----------------|-----------|
| `/stats/today` | 今日概览 | ✅ 可选参数 | ❌ 没传 → 全局 |
| `/stats/by-model` | 近7天模型分布 | ❌ 没有参数 | — |
| `/stats/daily` | 近30天调用趋势 | ❌ 没有参数 | — |

## 修复

1. `CallLogService` / `CallLogServiceImpl`：`statsByModel`、`statsDaily` 增加 `Long userId` 参数，非空时 `q.eq(CallLog::getUserId, userId)`
2. `CallLogController`：注入 `JwtUtil`，三个统计接口读 `Authorization` 头，新增 `resolveStatsUserId` 统一解析：

```java
private Long resolveStatsUserId(String authHeader, Long userId) {
    if (authHeader != null && !authHeader.isBlank()) {
        String token = authHeader.replace("Bearer ", "");
        if ("admin".equals(jwtUtil.getRole(token))) return null; // admin 看全平台
        return jwtUtil.getUserId(token);                          // 普通用户看自己
    }
    return userId; // 内部服务直连（ai-agent → biz-service，无 JWT）沿用显式 userId 参数
}
```

语义：**普通用户看自己、admin 看全平台**。内部工具调用（`getMyUsage?userId=X`）不受影响。

## 教训

1. 统计/列表类接口默认先问「这数据是谁的」，数据隔离是安全第一课，不是后补项
2. 网关已经注入了 `X-User-Id` / `X-User-Role` 头，下游本应统一信任它，而不是各自重解析 JWT（这次为与 `ApiKeyController` 一致选择重解析，后续可统一）
3. 新功能上线前，用「新注册用户」视角过一遍，最容易暴露数据隔离问题