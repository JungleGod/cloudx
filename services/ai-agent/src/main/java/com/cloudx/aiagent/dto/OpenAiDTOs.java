package com.cloudx.aiagent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * OpenAI 兼容 API 的请求/响应 DTO
 */
public final class OpenAiDTOs {

    private OpenAiDTOs() {}

    // ==================== 请求 ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatCompletionRequest {
        /** 模型名："auto"=自动路由，也可指定 "deepseek"/"qwen" */
        private String model;

        /** 对话消息列表 */
        private List<Message> messages;

        /** 是否流式返回，默认 false */
        @JsonProperty("stream")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Boolean stream;

        private Double temperature;

        @JsonProperty("max_tokens")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Integer maxTokens;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String role;
        private String content;

        // 多模态支持（预留）
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Object contentParts;
    }

    // ==================== 非流式响应 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatCompletionResponse {
        private String id;
        @Builder.Default
        private String object = "chat.completion";
        private Long created;
        private String model;
        private List<Choice> choices;
        private Usage usage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Choice {
        private int index;
        private ResponseMessage message;
        @JsonProperty("finish_reason")
        private String finishReason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResponseMessage {
        private String role;
        private String content;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Usage {
        @JsonProperty("prompt_tokens")
        private int promptTokens;
        @JsonProperty("completion_tokens")
        private int completionTokens;
        @JsonProperty("total_tokens")
        private int totalTokens;
    }

    // ==================== 流式响应 Chunk ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatCompletionChunk {
        private String id;
        @Builder.Default
        private String object = "chat.completion.chunk";
        private Long created;
        private String model;
        private List<ChoiceDelta> choices;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChoiceDelta {
        private int index;
        private Delta delta;
        @JsonProperty("finish_reason")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String finishReason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Delta {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String role;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String content;
    }

    // ==================== 模型列表 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelListResponse {
        @Builder.Default
        private String object = "list";
        private List<ModelInfo> data;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelInfo {
        private String id;
        @Builder.Default
        private String object = "model";
        private Long created;
        @Builder.Default
        @JsonProperty("owned_by")
        private String ownedBy = "cloudx";
    }

    // ==================== 错误响应 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorResponse {
        private ErrorDetail error;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorDetail {
        private String message;
        private String type;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String code;
    }
}
