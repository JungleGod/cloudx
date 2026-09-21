package com.cloudx.aiagent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages API 请求/响应 DTO
 */
public final class AnthropicDTOs {

    private AnthropicDTOs() {}

    // ==================== 请求 ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class MessagesRequest {
        private String model;
        private List<Message> messages;
        @JsonProperty("max_tokens")
        private int maxTokens = 4096;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Object system;          // 顶层 system prompt，可以是 String 或 [{type,text}]
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Boolean stream;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Double temperature;
        @JsonProperty("top_p")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Double topP;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private List<String> stopSequences;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Map<String, Object> metadata;
        /** 客户端声明可自行执行的工具（如 Claude Code 的 Bash/Read/Edit）；非空走「客户端执行」协议 */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private List<ToolDef> tools;
        @JsonProperty("tool_choice")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Object toolChoice;
    }

    /** 客户端工具定义：{name, description, input_schema(JSON Schema)} */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolDef {
        private String name;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String description;
        @JsonProperty("input_schema")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Map<String, Object> inputSchema;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String role;    // "user" or "assistant"
        private Object content; // String or List<ContentBlock>
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContentBlock {
        private String type;    // "text" | "image" | "tool_use"
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String text;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private ImageSource source;
        // ---- tool_use 块专用 ----
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String id;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String name;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Map<String, Object> input;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageSource {
        private String type = "base64";
        @JsonProperty("media_type")
        private String mediaType;
        private String data;
    }

    // ==================== 非流式响应 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessagesResponse {
        private String id;
        private String type = "message";
        private String role = "assistant";
        private String model;
        private List<ContentBlock> content;
        @JsonProperty("stop_reason")
        private String stopReason;
        @JsonProperty("stop_sequence")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String stopSequence;
        private Usage usage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Usage {
        @JsonProperty("input_tokens")
        private int inputTokens;
        @JsonProperty("output_tokens")
        private int outputTokens;
    }

    // ==================== 流式 SSE 事件 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StreamEvent {
        private String type;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private MessageStartMessage message;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Integer index;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private ContentBlock contentBlock;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private DeltaData delta;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Usage usage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageStartMessage {
        private String id;
        private String type = "message";
        private String role = "assistant";
        private String model;
        private List<ContentBlock> content;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeltaData {
        @JsonProperty("stop_reason")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String stopReason;
        @JsonProperty("stop_sequence")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String stopSequence;
    }

    // ==================== 错误响应 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorResponse {
        private String type = "error";
        private ErrorDetail error;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorDetail {
        private String type;
        private String message;
    }
}
