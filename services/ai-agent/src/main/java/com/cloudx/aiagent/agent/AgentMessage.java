package com.cloudx.aiagent.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 结构化消息模型 — 用于 Agent 循环内部和 OpenAI API 交互
 * <p>
 * 支持 role: system | user | assistant | tool
 * assistant 消息可携带 tool_calls（LLM 返回的工具调用请求）
 * tool 消息携带 tool_call_id（工具执行结果回传）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentMessage {

    private static final ObjectMapper mapper = new ObjectMapper();

    private String role;
    private String content;

    /** tool 消息专用：对应 assistant 消息中 tool_call 的 id */
    private String toolCallId;

    /** tool 消息专用：工具名称 */
    private String toolName;

    /** assistant 消息专用：LLM 请求的工具调用列表 */
    private List<ToolCall> toolCalls;

    // ---- role 工厂方法 ----

    public static AgentMessage system(String content) {
        return AgentMessage.builder().role("system").content(content).build();
    }

    public static AgentMessage user(String content) {
        return AgentMessage.builder().role("user").content(content).build();
    }

    public static AgentMessage assistant(String content) {
        return AgentMessage.builder().role("assistant").content(content).build();
    }

    public static AgentMessage assistantWithToolCalls(List<ToolCall> toolCalls) {
        return AgentMessage.builder().role("assistant").content(null).toolCalls(toolCalls).build();
    }

    public static AgentMessage tool(String toolCallId, String toolName, String result) {
        return AgentMessage.builder().role("tool").toolCallId(toolCallId).toolName(toolName).content(result).build();
    }

    // ---- 序列化为 OpenAI API 格式 ----

    @SuppressWarnings("unchecked")
    public Map<String, Object> toOpenAiMap() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("role", role);
        if (content != null) map.put("content", content);
        if (toolCallId != null) map.put("tool_call_id", toolCallId);
        if (toolCalls != null && !toolCalls.isEmpty()) {
            map.put("tool_calls", toolCalls.stream().map(ToolCall::toMap).toList());
        }
        return map;
    }

    // ---- inner class: ToolCall ----

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCall {
        /** 由 LLM 生成，格式如 "call_xxxx" */
        private String id;
        /** 工具类型，固定 "function" */
        private String type;
        /** 函数详情 */
        private FunctionCall function;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class FunctionCall {
            private String name;
            private String arguments; // JSON string
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("id", id);
            map.put("type", type != null ? type : "function");
            Map<String, String> fnMap = new java.util.LinkedHashMap<>();
            fnMap.put("name", function.name);
            fnMap.put("arguments", function.arguments);
            map.put("function", fnMap);
            return map;
        }

        public static ToolCall fromStreamDelta(String id, String name, String argumentsChunk) {
            return ToolCall.builder()
                    .id(id)
                    .type("function")
                    .function(FunctionCall.builder().name(name).arguments(argumentsChunk).build())
                    .build();
        }
    }

    // ---- 从 OpenAI response choice 解析 ----

    @SuppressWarnings("unchecked")
    public static List<ToolCall> parseToolCallsFromChoice(Map<String, Object> choice) {
        List<Map<String, Object>> rawCalls = (List<Map<String, Object>>) choice.get("tool_calls");
        if (rawCalls == null || rawCalls.isEmpty()) return List.of();
        return rawCalls.stream().map(tc -> {
            Map<String, Object> fn = (Map<String, Object>) tc.get("function");
            return ToolCall.builder()
                    .id((String) tc.get("id"))
                    .type((String) tc.get("type"))
                    .function(ToolCall.FunctionCall.builder()
                            .name((String) fn.get("name"))
                            .arguments((String) fn.get("arguments"))
                            .build())
                    .build();
        }).toList();
    }

    /** 获取 plain text 内容，方便快速判断 */
    public boolean hasContent() {
        return content != null && !content.isBlank();
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    @Override
    public String toString() {
        try {
            return mapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            return "AgentMessage{role=" + role + "}";
        }
    }
}
