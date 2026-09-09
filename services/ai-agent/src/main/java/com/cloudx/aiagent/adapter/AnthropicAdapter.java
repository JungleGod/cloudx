package com.cloudx.aiagent.adapter;

import com.cloudx.aiagent.dto.AnthropicDTOs.*;
import com.cloudx.aiagent.routing.ModelRouter.RouteResult;
import com.fasterxml.jackson.core.JsonProcessingException;
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
        int inputTokens = Math.max(1, prompt.length() / 2);
        int outputTokens = Math.max(1, result.reply().length() / 2);
        return MessagesResponse.builder()
                .id(requestId)
                .model(displayModel != null ? displayModel : result.model())
                .content(List.of(ContentBlock.builder().type("text").text(result.reply()).build()))
                .stopReason("end_turn")
                .usage(Usage.builder().inputTokens(inputTokens).outputTokens(outputTokens).build())
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

    public record InternalRequest(
            String systemPrompt,
            String userMessage,
            List<Map<String, String>> history,
            String model,
            boolean stream,
            int maxTokens
    ) {}
}
