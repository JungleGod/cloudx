# 002 — DB 迁移未执行导致 Agent 对话数据隔离失效

**日期**：2026-08-12  
**标签**：MySQL、Migration、DataIsolation、Agent  
**影响范围**：Agent 对话、ChatPage 对话列表

## 现象

Agent tab 下创建的对话全部出现在 Chat tab 对话列表中，Agent tab 自己的列表为空。

## 排查过程

1. POST `/api/conversations` 请求体确认带了 `agentId: 1` ✅
2. GET `/api/conversations` 响应 JSON 中**没有 `agentId` 字段** ❌
3. Java 代码检查：`ConversationVO` 有 `agentId`，`ConversationServiceImpl` 正确映射，代码无问题
4. 查 DB：`DESCRIBE conversation` → **没有 `agent_id` 列**

## 根因

迁移 SQL `docs/sql/migration_20260812_agent_conversation.sql` 写了但从未执行。

前端 filter 逻辑 `c.agentId != null` 过滤 agent 对话，但 API 没返回这个字段 → `c.agentId` 为 `undefined` → `undefined != null` 是 `false` → agent 对话被过滤掉。而 `c.agentId == null` → `undefined == null` 是 `true` → 所有对话都进了 chat 列表。

MyBatis-Plus 默认 insert strategy 为 `NOT_NULL`，字段为 null 时不生成对应列，所以即使列不存在 INSERT 也不报错 — 掩盖了缺失。

## 修复

```sql
ALTER TABLE conversation ADD COLUMN agent_id BIGINT DEFAULT NULL AFTER model;
CREATE INDEX idx_agent_id ON conversation(agent_id);
```

## 教训

1. 迁移 SQL 写了 ≠ 执行了，写代码和运维要闭环
2. `undefined == null` 在 JS 中是 `true`，前端 filter 踩坑
3. MyBatis-Plus `NOT_NULL` 策略掩盖了列缺失，不报错比报错更危险
4. 排查顺序：前端 → 网络 → 后端代码 → **数据库**，很多 bug 源头在 DB
