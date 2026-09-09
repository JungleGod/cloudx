# 001 — React State 突变导致流式输出文本重复

**日期**：2026-08-11  
**标签**：React、StrictMode、SSE、流式输出  
**影响范围**：ChatPage、PlaygroundPage

## 现象

在线调试 / 对话页面流式输出时，模型回复出现文字重复（如 "很高兴很高兴见到你你😊😊"）。  
切换对话后再切回来，历史记录显示正常 → **数据正确，渲染错误**。

## 根因

```tsx
// ❌ 错误写法 — 直接修改 React state 对象
onToken: (token) => {
  setMessages((prev) => {
    const updated = [...prev];
    const last = updated[updated.length - 1];
    if (last && last.streaming) {
      last.content += token;  // 修改了 prev 中的同一个对象！
    }
    return updated;
  });
},
```

`updated = [...prev]` 只是浅拷贝数组，`updated[i]` 和 `prev[i]` **指向同一个对象**。  
`last.content += token` 修改了这个共享对象。

React 18 StrictMode（开发环境）会双重渲染组件来检测副作用。在流式输出高频 `setState` 场景下，状态突变导致同一个 token 被 React 重复应用到 UI。

ChatPage 额外维护了 `fullContent` 局部变量（正确累加），`onDone` 时将其持久化到数据库。所以从 DB 重新加载后显示正确 — 但内存中的 React state 是错的。

## 修复

```tsx
// ✅ 正确写法 — 创建新对象，不修改原对象
onToken: (token) => {
  setMessages((prev) => {
    const updated = [...prev];
    const lastIdx = updated.length - 1;
    const last = updated[lastIdx];
    if (last && last.streaming) {
      updated[lastIdx] = { ...last, content: last.content + token };
    }
    return updated;
  });
},
```

## 教训

1. **React state 永远不要直接修改** — 数组项也是 state 的一部分
2. `[...arr]` 只浅拷贝数组，内部对象仍是原引用
3. 高频 `setState`（如 SSE 流式）叠加 StrictMode 会让突变问题放大
4. 数据正确但渲染错误 → 先怀疑 state 是否被正确更新