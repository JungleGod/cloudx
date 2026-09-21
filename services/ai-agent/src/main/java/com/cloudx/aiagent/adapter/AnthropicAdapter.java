package com.cloudx.aiagent.adapter;

import com.cloudx.aiagent.agent.AgentExecutionContext;
import com.cloudx.aiagent.agent.AgentMessage;
import com.cloudx.aiagent.agent.AgentResult;
import com.cloudx.aiagent.dto.AnthropicDTOs.*;
import com.cloudx.aiagent.routing.ModelRouter.RouteResult;
import com.cloudx.aiagent.service.ApiKeyAuthClient.AuthResult;
import com.cloudx.aiagent.tool.ToolDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Anthropic Messages 格式 ↔ 内部格式 互转
 */
@Component
public class AnthropicAdapter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 请求转换 ====================

    /**
     * Anthropic MessagesRequest → 内部格式
     */
    public InternalRequest toInternal(MessagesRequest req) {
        List<Message> messages = req.getMessages();
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages is required");
        }

        String systemPrompt = extractText(req.getSystem());

        // 找到最后一条 user 消息
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                lastUserIdx = i;
                break;
            }
        }
        if (lastUserIdx == -1) {
            throw new IllegalArgumentException("At least one user message is required");
        }

        String userMessage = extractText(messages.get(lastUserIdx).getContent());

        // 前面的 user/assistant → history
        List<Map<String, String>> history = null;
        if (lastUserIdx > 0) {
            history = new ArrayList<>();
            for (int i = 0; i < lastUserIdx; i++) {
                Message m = messages.get(i);
                if ("user".equals(m.getRole()) || "assistant".equals(m.getRole())) {
                    Map<String, String> entry = new HashMap<>();
                    entry.put("role", m.getRole());
                    entry.put("content", extractText(m.getContent()));
                    history.add(entry);
                }
            }
            if (history.isEmpty()) history = null;
        }

        String model = req.getModel();
        if (model == null || "auto".equalsIgnoreCase(model)) model = null;

        return new InternalRequest(systemPrompt, userMessage, history, model,
                req.getStream() != null && req.getStream(), Math.max(req.getMaxTokens(), 256));
    }

    // ==================== 非流式响应 ====================

    public MessagesResponse toResponse(RouteResult result, String requestId, String prompt, String displayModel) {
        int inputTokens = result.inputTokens() > 0 ? result.inputTokens() : Math.max(1, prompt.length() / 2);
        int outputTokens = result.outputTokens() > 0 ? result.outputTokens() : Math.max(1, result.reply().length() / 2);
        return MessagesResponse.builder()
                .id(requestId)
                .model(displayModel != null ? displayModel : result.model())
                .content(List.of(ContentBlock.builder().type("text").text(result.reply()).build()))
                .stopReason("end_turn")
                .usage(Usage.builder().inputTokens(inputTokens).outputTokens(outputTokens).build())
                .build();
    }

    // ==================== Agent 模式（客户端工具透传） ====================

    /**
     * 带 tools 的 Anthropic 请求 → Agent 执行上下文（客户端执行协议）
     * <p>
     * Anthropic 协议本身就是「客户端执行」模型：tool_use 块由调用方执行后以 tool_result 回传，
     * 因此带 tools 的请求一律透传为 clientTools，完整消息序列（含 tool_use / tool_result 块）进 history
     */
    public AgentExecutionContext toAgentContext(MessagesRequest req, AuthResult auth) {
        List<Message> messages = req.getMessages();
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages is required");
        }

        String systemPrompt = extractText(req.getSystem());

        // tools → clientTools（input_schema 即 JSON Schema，直接透传给上游模型）
        List<ToolDefinition> clientTools = new ArrayList<>();
        if (req.getTools() != null) {
            for (ToolDef t : req.getTools()) {
                if (t.getName() == null || t.getName().isBlank()) continue;
                ToolDefinition td = new ToolDefinition();
                td.setName(t.getName());
                td.setDescription(t.getDescription() != null ? t.getDescription() : "");
                td.setParametersSchema(t.getInputSchema() != null ? t.getInputSchema() : Collections.emptyMap());
                clientTools.add(td);
            }
        }
        if (clientTools.isEmpty()) {
            throw new IllegalArgumentException("tools must contain at least one usable tool");
        }

        // 完整消息序列进 history：最后一条往往是 tool_result，不能按「最后一条 user」切分
        List<AgentMessage> history = new ArrayList<>();
        for (Message m : messages) {
            String role = m.getRole();
            if ("assistant".equals(role)) {
                List<AgentMessage.ToolCall> calls = extractToolUses(m.getContent());
                if (!calls.isEmpty()) {
                    history.add(AgentMessage.assistantWithToolCalls(calls));
                } else {
                    String text = extractText(m.getContent());
                    if (!text.isBlank()) history.add(AgentMessage.assistant(text));
                }
            } else if ("user".equals(role)) {
                // user 块里可能混有 text 与 tool_result
                for (Map<?, ?> block : rawBlocks(m.getContent())) {
                    String type = Objects.toString(block.get("type"), "");
                    if ("text".equals(type)) {
                        String text = Objects.toString(block.get("text"), "");
                        if (!text.isBlank()) history.add(AgentMessage.user(text));
                    } else if ("tool_result".equals(type)) {
                        history.add(AgentMessage.tool(
                                Objects.toString(block.get("tool_use_id"), null),
                                null, toolResultText(block.get("content"))));
                    }
                }
            }
        }

        String model = req.getModel();
        if (model == null || "auto".equalsIgnoreCase(model)) model = null;

        return AgentExecutionContext.builder()
                .userId(auth != null ? auth.userId() : null)
                .callerRole("user")
                .systemPrompt(systemPrompt)
                .userMessage(null) // 消息序列由 history 承载，避免空 user 消息
                .history(history)
                .clientTools(clientTools)
                .modelName(model)
                .temperature(req.getTemperature())
                .stream(req.getStream() != null && req.getStream())
                .build();
    }

    /**
     * AgentResult → MessagesResponse（含 tool_use 内容块）
     */
    public MessagesResponse toAgentResponse(AgentResult result, String requestId, String displayModel) {
        List<ContentBlock> blocks = new ArrayList<>();
        String stopReason = "end_turn";

        if (result.getClientToolCalls() != null && !result.getClientToolCalls().isEmpty()) {
            // 客户端工具模式：模型请求的 tool_calls → tool_use 块原样返回（保留原始 id，客户端按 id 回传结果）
            for (AgentMessage.ToolCall tc : result.getClientToolCalls()) {
                blocks.add(ContentBlock.builder()
                        .type("tool_use")
                        .id(tc.getId())
                        .name(tc.getFunction() != null ? tc.getFunction().getName() : "")
                        .input(parseJsonInput(tc.getFunction() != null ? tc.getFunction().getArguments() : null))
                        .build());
            }
            stopReason = "tool_use";
        } else {
            blocks.add(ContentBlock.builder()
                    .type("text").text(result.getAnswer() != null ? result.getAnswer() : "").build());
            if (result.isInterrupted()) stopReason = "max_tokens";
        }

        return MessagesResponse.builder()
                .id(requestId)
                .model(displayModel)
                .content(blocks)
                .stopReason(stopReason)
                .usage(Usage.builder()
                        .inputTokens(result.getInputTokens())
                        .outputTokens(Math.max(1, result.getOutputTokens()))
                        .build())
                .build();
    }

    // ==================== 流式 SSE 事件 ====================

    public String sseMessageStart(String requestId, String model) {
        return json(Map.of(
                "type", "message_start",
                "message", Map.of(
                        "id", requestId,
                        "type", "message",
                        "role", "assistant",
                        "model", model,
                        "content", List.of(Map.of("type", "text", "text", ""))
                )
        ));
    }

    public String sseContentBlockStart(int index) {
        return json(Map.of(
                "type", "content_block_start",
                "index", index,
                "content_block", Map.of("type", "text", "text", "")
        ));
    }

    public String sseContentBlockDelta(int index, String text) {
        return json(Map.of(
                "type", "content_block_delta",
                "index", index,
                "delta", Map.of("type", "text_delta", "text", text)
        ));
    }

    public String sseContentBlockStop(int index) {
        return json(Map.of(
                "type", "content_block_stop",
                "index", index
        ));
    }

    public String sseMessageDelta(int outputTokens) {
        return json(Map.of(
                "type", "message_delta",
                "delta", Map.of("stop_reason", "end_turn"),
                "usage", Map.of("output_tokens", outputTokens)
        ));
    }

    public String sseMessageStop() {
        return json(Map.of("type", "message_stop"));
    }

    // ---- tool_use 流式事件（客户端执行协议） ----

    public String sseToolUseBlockStart(int index, String id, String name) {
        return json(Map.of(
                "type", "content_block_start",
                "index", index,
                "content_block", Map.of(
                        "type", "tool_use",
                        "id", Objects.toString(id, ""),
                        "name", Objects.toString(name, ""),
                        "input", Map.of())
        ));
    }

    public String sseInputJsonDelta(int index, String partialJson) {
        return json(Map.of(
                "type", "content_block_delta",
                "index", index,
                "delta", Map.of(
                        "type", "input_json_delta",
                        "partial_json", partialJson != null ? partialJson : "")
        ));
    }

    /** message_delta 带自定义 stop_reason（tool_use / end_turn / max_tokens） */
    public String sseMessageDelta(String stopReason, int outputTokens) {
        return json(Map.of(
                "type", "message_delta",
                "delta", Map.of("stop_reason", stopReason != null ? stopReason : "end_turn"),
                "usage", Map.of("output_tokens", outputTokens)
        ));
    }

    // ==================== 错误 ====================

    public ErrorResponse toError(String type, String message) {
        return ErrorResponse.builder()
                .error(ErrorDetail.builder().type(type).message(message).build())
                .build();
    }

    public String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    // ==================== 内部工具方法 ====================

    private String json(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String extractText(Object content) {
        if (content instanceof String s) return s;
        if (content instanceof List<?> blocks) {
            return blocks.stream()
                    .filter(b -> b instanceof Map)
                    .map(b -> (Map<?, ?>) b)
                    .filter(m -> "text".equals(m.get("type")))
                    .map(m -> Objects.toString(m.get("text"), ""))
                    .collect(Collectors.joining());
        }
        return Objects.toString(content, "");
    }

    /** content 块列表（Jackson 反序列化为 List<Map>），非列表返回空 */
    private List<Map<?, ?>> rawBlocks(Object content) {
        if (content instanceof List<?> list) {
            List<Map<?, ?>> blocks = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) blocks.add(m);
            }
            return blocks;
        }
        return List.of();
    }

    /** assistant 内容块中的 tool_use → ToolCall（input 对象序列化为 JSON 字符串，与内部 tool_calls 同构） */
    private List<AgentMessage.ToolCall> extractToolUses(Object content) {
        List<AgentMessage.ToolCall> calls = new ArrayList<>();
        for (Map<?, ?> block : rawBlocks(content)) {
            if ("tool_use".equals(block.get("type"))) {
                calls.add(AgentMessage.ToolCall.builder()
                        .id(Objects.toString(block.get("id"), null))
                        .type("function")
                        .function(AgentMessage.ToolCall.FunctionCall.builder()
                                .name(Objects.toString(block.get("name"), ""))
                                .arguments(toJsonString(block.get("input")))
                                .build())
                        .build());
            }
        }
        return calls;
    }

    /** tool_result 的 content：字符串或 [{type:text,text:..}] 块 → 纯文本 */
    private String toolResultText(Object content) {
        if (content instanceof String s) return s;
        StringBuilder sb = new StringBuilder();
        for (Map<?, ?> block : rawBlocks(content)) {
            if ("text".equals(block.get("type"))) {
                sb.append(Objects.toString(block.get("text"), ""));
            }
        }
        return sb.toString();
    }

    /** 工具入参 JSON 字符串 → Map（tool_use 块的 input 必须是对象） */
    private Map<String, Object> parseJsonInput(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(argsJson, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String toJsonString(Object obj) {
        if (obj == null) return "{}";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    public record InternalRequest(
            String systemPrompt,
            String userMessage,
            List<Map<String, String>> history,
            String model,
            boolean stream,
            int maxTokens
    ) {}
}
