package com.cloudx.aiagent.adapter;

import com.cloudx.aiagent.dto.OpenAiDTOs.*;
import com.cloudx.aiagent.routing.ModelRouter.RouteResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * OpenAI 协议格式 ↔ 内部格式 互转
 */
@Component
public class OpenAiAdapter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * OpenAI messages[] → 内部格式
     */
    public InternalRequest toInternal(ChatCompletionRequest req) {
        List<Message> messages = req.getMessages();
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages is required");
        }

        // 提取 system prompts（拼接所有 system 消息）
        String systemPrompt = messages.stream()
                .filter(m -> "system".equals(m.getRole()))
                .map(Message::getContent)
                .collect(Collectors.joining("\n"));
        if (systemPrompt.isEmpty()) {
            systemPrompt = null;
        }

        // 找到最后一条 user 消息的索引
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

        String userMessage = messages.get(lastUserIdx).getContent();

        // 最后一条 user 之前的 user/assistant 对 → history
        List<Map<String, String>> history = null;
        if (lastUserIdx > 0) {
            history = new ArrayList<>();
            for (int i = 0; i < lastUserIdx; i++) {
                Message m = messages.get(i);
                if ("user".equals(m.getRole()) || "assistant".equals(m.getRole())) {
                    Map<String, String> entry = new HashMap<>();
                    entry.put("role", m.getRole());
                    entry.put("content", m.getContent());
                    history.add(entry);
                }
            }
            if (history.isEmpty()) {
                history = null;
            }
        }

        // model 处理："auto" 或空 → null（触发自动路由）
        String model = req.getModel();
        if (model == null || "auto".equalsIgnoreCase(model)) {
            model = null;
        }

        return new InternalRequest(systemPrompt, userMessage, history, model, req.getStream() != null && req.getStream());
    }

    /**
     * RouteResult → OpenAI ChatCompletionResponse
     */
    public ChatCompletionResponse toResponse(RouteResult result, String requestId) {
        long now = System.currentTimeMillis() / 1000;

        return ChatCompletionResponse.builder()
                .id(requestId)
                .created(now)
                .model(result.model())
                .choices(List.of(
                        Choice.builder()
                                .index(0)
                                .message(ResponseMessage.builder()
                                        .role("assistant")
                                        .content(result.reply())
                                        .build())
                                .finishReason("stop")
                                .build()
                ))
                .usage(Usage.builder()
                        .promptTokens(0)   // 估算值，后续从 Provider 获取精确值
                        .completionTokens(result.reply().length() / 2)
                        .totalTokens(result.reply().length() / 2)
                        .build())
                .build();
    }

    /**
     * 构建流式 Chunk，作为 SSE data 行的 JSON 字符串
     */
    public String toChunkJson(String token, String requestId, String model, boolean isFirst, boolean isLast) {
        long now = System.currentTimeMillis() / 1000;

        Delta.DeltaBuilder deltaBuilder = Delta.builder();
        if (isFirst) {
            deltaBuilder.role("assistant");
        }
        if (token != null && !token.isEmpty()) {
            deltaBuilder.content(token);
        }

        ChatCompletionChunk chunk = ChatCompletionChunk.builder()
                .id(requestId)
                .created(now)
                .model(model)
                .choices(List.of(
                        ChoiceDelta.builder()
                                .index(0)
                                .delta(deltaBuilder.build())
                                .finishReason(isLast ? "stop" : null)
                                .build()
                ))
                .build();

        try {
            return objectMapper.writeValueAsString(chunk);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /**
     * 构建错误响应
     */
    public ErrorResponse toError(String message, String type, int statusCode) {
        return ErrorResponse.builder()
                .error(ErrorDetail.builder()
                        .message(message)
                        .type(type)
                        .code(String.valueOf(statusCode))
                        .build())
                .build();
    }

    /**
     * RouteResult（带内置 prompt）→ ChatCompletionResponse
     */
    public ChatCompletionResponse toResponseWithPrompt(RouteResult result, String requestId, String prompt, String displayModel) {
        long now = System.currentTimeMillis() / 1000;
        int promptTokens = Math.max(1, prompt.length() / 2);
        int completionTokens = Math.max(1, result.reply().length() / 2);

        return ChatCompletionResponse.builder()
                .id(requestId)
                .created(now)
                .model(displayModel != null ? displayModel : result.model())
                .choices(List.of(
                        Choice.builder()
                                .index(0)
                                .message(ResponseMessage.builder()
                                        .role("assistant")
                                        .content(result.reply())
                                        .build())
                                .finishReason("stop")
                                .build()
                ))
                .usage(Usage.builder()
                        .promptTokens(promptTokens)
                        .completionTokens(completionTokens)
                        .totalTokens(promptTokens + completionTokens)
                        .build())
                .build();
    }

    /**
     * 内部请求结构
     */
    public record InternalRequest(
            String systemPrompt,
            String userMessage,
            List<Map<String, String>> history,
            String model,
            boolean stream
    ) {}

    // ==================== Agent 模式转换 ====================

    /**
     * 将带 tools 的 OpenAI 请求转换为 Agent 执行上下文
     */
    public com.cloudx.aiagent.agent.AgentExecutionContext toAgentContext(
            ChatCompletionRequest req,
            com.cloudx.aiagent.service.ApiKeyAuthClient.AuthResult auth) {

        List<Message> messages = req.getMessages();

        // 提取 system prompt
        String systemPrompt = messages.stream()
                .filter(m -> "system".equals(m.getRole()))
                .map(Message::getContent)
                .collect(Collectors.joining("\n"));
        if (systemPrompt.isEmpty()) systemPrompt = null;

        // 最后一条 user 消息
        String userMessage = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                userMessage = messages.get(i).getContent();
                break;
            }
        }

        // 构建历史（所有非最后一条 user 的消息，包括 assistant 的 tool_calls）
        List<com.cloudx.aiagent.agent.AgentMessage> history = new ArrayList<>();
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                lastUserIdx = i;
                break;
            }
        }
        if (lastUserIdx > 0) {
            for (int i = 0; i < lastUserIdx; i++) {
                Message m = messages.get(i);
                com.cloudx.aiagent.agent.AgentMessage am = switch (m.getRole()) {
                    case "user" -> com.cloudx.aiagent.agent.AgentMessage.user(m.getContent());
                    case "assistant" -> buildAgentAssistantMessage(m);
                    case "tool" -> com.cloudx.aiagent.agent.AgentMessage.tool(
                            m.getToolCallId(), "unknown", m.getContent());
                    default -> null;
                };
                if (am != null) history.add(am);
            }
        }

        // model 处理
        String model = req.getModel();
        if (model == null || "auto".equalsIgnoreCase(model)) model = null;

        return com.cloudx.aiagent.agent.AgentExecutionContext.builder()
                .userId(auth != null ? auth.userId() : null)
                .callerRole("user")
                .systemPrompt(systemPrompt)
                .userMessage(userMessage)
                .history(history)
                .modelName(model)
                .temperature(req.getTemperature())
                .stream(req.getStream() != null && req.getStream())
                .build();
    }

    /** 构建带 tool_calls 的 assistant AgentMessage */
    private com.cloudx.aiagent.agent.AgentMessage buildAgentAssistantMessage(Message m) {
        if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
            List<com.cloudx.aiagent.agent.AgentMessage.ToolCall> toolCalls = m.getToolCalls().stream()
                    .map(tc -> com.cloudx.aiagent.agent.AgentMessage.ToolCall.builder()
                            .id(tc.getId())
                            .type(tc.getType())
                            .function(com.cloudx.aiagent.agent.AgentMessage.ToolCall.FunctionCall.builder()
                                    .name(tc.getFunction().getName())
                                    .arguments(tc.getFunction().getArguments())
                                    .build())
                            .build())
                    .toList();
            return com.cloudx.aiagent.agent.AgentMessage.assistantWithToolCalls(toolCalls);
        }
        return com.cloudx.aiagent.agent.AgentMessage.assistant(m.getContent());
    }

    /**
     * AgentResult → OpenAI ChatCompletionResponse（含 tool_calls）
     */
    public ChatCompletionResponse toAgentResponse(
            com.cloudx.aiagent.agent.AgentResult result,
            String requestId, String displayModel) {
        long now = System.currentTimeMillis() / 1000;

        // 如果有工具调用步骤，转换为 tool_calls 格式
        List<ToolCall> openAiToolCalls = null;
        if (!result.getToolSteps().isEmpty()) {
            openAiToolCalls = result.getToolSteps().stream()
                    .map(s -> ToolCall.builder()
                            .id("call_" + UUID.randomUUID().toString().substring(0, 8))
                            .type("function")
                            .function(ToolCallFunction.builder()
                                    .name(s.getToolName())
                                    .arguments(s.getArguments())
                                    .build())
                            .build())
                    .toList();
        }

        // 构建 choice message
        var msgBuilder = ResponseMessage.builder()
                .role("assistant")
                .content(result.getAnswer() != null ? result.getAnswer() : "")
                .toolCalls(openAiToolCalls);

        // 构建 choice
        var choiceBuilder = Choice.builder()
                .index(0)
                .message(msgBuilder.build())
                .finishReason(result.isInterrupted() ? "length" : "stop");

        return ChatCompletionResponse.builder()
                .id(requestId)
                .created(now)
                .model(displayModel)
                .choices(List.of(choiceBuilder.build()))
                .usage(Usage.builder()
                        .promptTokens(0)
                        .completionTokens(result.getTotalTokens())
                        .totalTokens(result.getTotalTokens())
                        .build())
                .build();
    }
}
