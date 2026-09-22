# 006 - /v1/messages 丢弃 tools 参数，模型把工具调用写成 DSML 文本泄漏

**日期**：2026-09-21
**严重级别**：P1（核心功能不可用）
**状态**：已修复，2026-09-22 Claude Code 实测通过

## 问题

Claude Code 接入本平台作为后端模型（Anthropic 协议，走 `/v1/messages`）后，所有工具操作全部异常：让 Claude Code 读文件，屏幕上流回来的不是文件内容，而是一堆 DeepSeek 内部的 DSML 标记文本（类似 `<|dsml|>function_call` 之类的乱码）。`/v1/chat/completions`（OpenAI 协议）却一切正常。

## 根因

`/v1/messages` 的请求解析把客户端传来的 `tools` 数组**直接丢弃**了：

- Claude Code 是 Anthropic 协议客户端，工具清单通过 `tools`（含 `input_schema`）传入；
- 平台解析请求时没处理这个字段 → AgentLoop 拿到的工具列表是空的 → 请求上游模型时也没带 tools；
- 模型没有原生 function-calling 通道，但 Claude Code 的系统提示词又强烈要求它「用工具完成任务」；
- 于是模型把「调用工具」的意图写成**训练语料里的内部 DSL 标记文本**（DSML）当普通文字输出——语法上是流式文本，语义上是工具调用，两边都不认识。

DSML 乱码不是编码问题、不是 SSE 问题，是**协议通道缺失后模型的降级行为**。

## 修复

让 `/v1/messages` 与 `/v1/chat/completions` 对称，共用 AgentLoop 的 client 模式（客户端执行协议）：

1. **AnthropicDTOs**：请求体加 `tools`（name/description/input_schema），ContentBlock 补 `id/name/input`（tool_use 块）与 `tool_use_id/content`（tool_result 块）
2. **AnthropicAdapter**：
   - `toAgentContext`：tool_use / tool_result 块双向还原进 AgentLoop 的 history（多轮工具循环有上下文）
   - `toAgentResponse`：AgentLoop 返回的 clientToolCalls 组装成 tool_use 块 + `stop_reason=tool_use`
   - SSE 三件套：`content_block_start(tool_use)` → `input_json_delta`（参数增量流）→ `message_delta(stop_reason=tool_use)`
3. **Controller**：按请求是否带 `tools` 分流——带工具走 AgentLoop client 模式（工具由 Claude Code 执行，结果经 tool_result 回传），不带走普通对话

协议对齐后的认知：**OpenAI（tool_calls + finish_reason）与 Anthropic（tool_use 块 + stop_reason=tool_use）是同一「客户端执行」协议的两种线格式**，一套 AgentLoop 支撑两协议。

## 启示

- **协议兼容层的「子集实现」比报错更危险**：不认识的字段静默丢弃，故障不会在接入时报错，而是以诡异症状（DSML 乱码）在运行期暴露。兼容层应做到「支持的字段全处理，不支持的字段显式拒绝」
- **诡异症状先问「这个字段我处理了吗」**：看到模型输出内部标记文本，第一反应是编码/流式问题，实际是上游根本没收到工具定义。从请求进入的第一站开始逐字段核对，比盯着响应猜快得多
- **双协议要对拍测试**：`/v1/chat/completions` 实现时验证过 tools 透传，但两条协议各自演进就会出现这种「一半有、一半没有」——协议对齐要用同一组用例两侧各跑一遍