package com.cloudx.aiagent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.util.Collections;
import java.util.Map;

/**
 * 工具定义模型 — 与 DB tool_definition 表对应
 */
@Data
public class ToolDefinition {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private Long id;
    private String name;
    private String description;
    private String category;          // built-in / http / internal-api
    private Map<String, Object> parametersSchema; // parsed JSON Schema
    private String httpMethod;
    private String httpUrl;
    private Map<String, String> httpHeaders;
    private String internalPath;
    private String builtinHandler;
    private String requiredRole;      // public / user / admin
    private int timeoutMs = 10000;
    private int retryCount = 0;
    private boolean enabled = true;

    /** 解析 JSON Schema 字段 */
    @SuppressWarnings("unchecked")
    public void setParametersSchemaFromJson(String json) {
        if (json == null || json.isBlank()) {
            this.parametersSchema = Collections.emptyMap();
            return;
        }
        try {
            this.parametersSchema = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            this.parametersSchema = Collections.emptyMap();
        }
    }

    /** 转为 OpenAI function 定义格式 */
    public Map<String, Object> toOpenAiFunction() {
        Map<String, Object> function = new java.util.LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", parametersSchema != null ? parametersSchema : Collections.emptyMap());

        Map<String, Object> tool = new java.util.LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }
}
