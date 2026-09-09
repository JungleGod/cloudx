# 003 — toolSteps 未持久化导致 Agent 历史表格变乱

**日期**：2026-08-12  
**标签**：Agent、React、SSE、持久化、Rendering  
**影响范围**：AgentPage 历史对话加载

## 现象

Agent 回答"有多少模型在线"时，实时显示表格非常清晰。刷新页面重新打开同一个对话，表格变乱/丢失。

## 根因

Agent 回复的表格来自**两个渲染路径**，但只有一个被持久化：

| 路径 | 数据源 | 持久化 |
|------|--------|--------|
| `renderToolResult()` → HTML 表格 | 工具返回的 JSON（工具结果） | ❌ 存在 `msg.toolSteps`，不存 DB |
| `ReactMarkdown` → Markdown 表格 | LLM 回复的文本（content） | ✅ 存在 `conversation_message.content` |

**实时对话**：`toolSteps` 有数据 → `hasTools = true` → `stripRedundantMarkdown()` 剥离 LLM 的 markdown 表格 → 只展示 HTML 表格。用户看到的是 `renderToolResult()` 的美化表格。

**加载历史**：`toolSteps` 为 `undefined`（没存）→ `hasTools = false` → 只剩 content 里的 markdown 表格 → `ReactMarkdown` 渲染。虽然 CSS 有表格样式，但不如 HTML 表格清晰，且可能和 LLM 描述文字混在一起。

```typescript
// AgentPage.tsx:200 — 历史加载时只映射了 role + content
getMessages(activeConv.id)
  .then((msgs) => setMessages(msgs.map((m) => ({
    role: m.role as 'user' | 'assistant',
    content: m.content  // 没有 toolSteps！
  }))))
```

## 修复

1. DB：`ALTER TABLE conversation_message ADD COLUMN metadata TEXT`
2. 后端：`ConversationMessage` entity 加 `metadata` 字段
3. 前端 `onDone`：`JSON.stringify({ toolSteps })` 存入 metadata
4. 前端历史加载：`JSON.parse(m.metadata)` 恢复 toolSteps

```typescript
// 保存
const metadata = steps.length > 0 ? JSON.stringify({ toolSteps: steps }) : null;
appendMessage(conv.id, { role: 'assistant', content, metadata, ... });

// 恢复
if (m.metadata) {
  const meta = JSON.parse(m.metadata);
  if (meta.toolSteps) msg.toolSteps = meta.toolSteps;
}
```

## 教训

1. 实时渲染和历史回显要走同一套数据结构，否则两个路径表现不一致
2. 消息表加一个 `metadata TEXT` 列非常实用 — 不破坏 schema，JSON 灵活扩展
3. 流式场景下"看到的"和"存下的"要一致，否则用户信任度会下降
