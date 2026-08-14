package com.cloudx.aiagent.tool;

import com.cloudx.common.result.R;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具注册中心 — 从 biz-service 加载工具定义，支持热重载和角色过滤
 */
@Slf4j
@Component
public class ToolRegistry {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private volatile Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();
    private volatile Map<Long, List<ToolDefinition>> agentToolCache = new ConcurrentHashMap<>();

    private static final String bizServiceUrl = "http://localhost:8081";

    public ToolRegistry(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        reload();
    }

    /** 从 biz-service 重新加载所有工具定义 */
    public synchronized void reload() {
        try {
            String url = bizServiceUrl + "/api/internal/tools/batch";
            Map<String, List<String>> body = Map.of("names", List.of()); // 空列表=全部

            ResponseEntity<R<List<Map<String, Object>>>> resp = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body),
                    new ParameterizedTypeReference<R<List<Map<String, Object>>>>() {});

            R<List<Map<String, Object>>> r = resp.getBody();
            List<Map<String, Object>> list = r != null ? r.getData() : null;
            if (list != null) {
                Map<String, ToolDefinition> newTools = new ConcurrentHashMap<>();
                for (Map<String, Object> map : list) {
                    ToolDefinition t = mapToToolDef(map);
                    if (t != null) {
                        newTools.put(t.getName(), t);
                    }
                }
                this.tools = newTools;
                log.info("ToolRegistry reloaded: {} tools", newTools.size());
            }
            agentToolCache.clear();
        } catch (Exception e) {
            log.warn("ToolRegistry reload failed: {}", e.getMessage());
        }
    }

    /** 获取 Agent 绑定的工具，按角色过滤 */
    public List<ToolDefinition> getToolsForAgent(Long agentId, String callerRole) {
        List<ToolDefinition> cached = agentToolCache.get(agentId);
        if (cached != null) {
            return filterByRole(cached, callerRole);
        }

        try {
            String url = bizServiceUrl + "/api/internal/agents/" + agentId + "/tools";
            ResponseEntity<R<List<Map<String, Object>>>> resp = restTemplate.exchange(
                    url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<R<List<Map<String, Object>>>>() {});
            R<List<Map<String, Object>>> r = resp.getBody();
            List<Map<String, Object>> list = r != null ? r.getData() : null;
            if (list != null) {
                List<ToolDefinition> boundTools = list.stream()
                        .map(this::mapToToolDef)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                agentToolCache.put(agentId, boundTools);
                return filterByRole(boundTools, callerRole);
            }
        } catch (Exception e) {
            log.warn("Failed to load tools for agent {}: {}", agentId, e.getMessage());
        }
        return List.of();
    }

    /** 按名称批量获取工具，按角色过滤 */
    public List<ToolDefinition> getToolsByNames(List<String> names, String callerRole) {
        // 优先从本地缓存匹配
        List<ToolDefinition> result = new ArrayList<>();
        for (String name : names) {
            ToolDefinition t = tools.get(name);
            if (t != null) result.add(t);
        }

        // 缓存不全命中，从 biz-service 加载
        if (result.size() < names.size() && !names.isEmpty()) {
            try {
                String url = bizServiceUrl + "/api/internal/tools/batch";
                Map<String, List<String>> body = Map.of("names", names);
                ResponseEntity<R<List<Map<String, Object>>>> resp = restTemplate.exchange(
                        url, HttpMethod.POST,
                        new HttpEntity<>(body),
                        new ParameterizedTypeReference<R<List<Map<String, Object>>>>() {});
                R<List<Map<String, Object>>> r = resp.getBody();
                List<Map<String, Object>> list = r != null ? r.getData() : null;
                if (list != null) {
                    result = list.stream()
                            .map(this::mapToToolDef)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                    for (ToolDefinition t : result) {
                        tools.put(t.getName(), t);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to load tools by names {}: {}", names, e.getMessage());
            }
        }

        // 如果 names 为空或缓存为空，尝试从 biz-service 拉全部
        if (result.isEmpty() && (names.isEmpty() || tools.isEmpty())) {
            reload();
            for (String name : names) {
                ToolDefinition t = tools.get(name);
                if (t != null) result.add(t);
            }
        }

        return filterByRole(result, callerRole);
    }

    /** 转为 OpenAI function 列表 */
    public List<Map<String, Object>> toOpenAiFunctions(List<ToolDefinition> toolDefs) {
        return toolDefs.stream()
                .map(ToolDefinition::toOpenAiFunction)
                .collect(Collectors.toList());
    }

    /** 获取所有已注册工具 */
    public Collection<ToolDefinition> getAll() {
        return List.copyOf(tools.values());
    }

    /** 按角色过滤工具 */
    private List<ToolDefinition> filterByRole(List<ToolDefinition> toolDefs, String callerRole) {
        final String role = callerRole != null ? callerRole : "user";
        return toolDefs.stream()
                .filter(t -> isRoleAllowed(t.getRequiredRole(), role))
                .collect(Collectors.toList());
    }

    /** 检查角色是否允许使用工具 */
    private boolean isRoleAllowed(String requiredRole, String callerRole) {
        return switch (requiredRole) {
            case "public" -> true;
            case "user" -> "user".equals(callerRole) || "admin".equals(callerRole);
            case "admin" -> "admin".equals(callerRole);
            default -> "admin".equals(callerRole);
        };
    }

    /** Map → ToolDefinition */
    @SuppressWarnings("unchecked")
    private ToolDefinition mapToToolDef(Map<String, Object> map) {
        try {
            ToolDefinition t = new ToolDefinition();
            t.setId(toLong(map.get("id")));
            t.setName((String) map.get("name"));
            t.setDescription((String) map.get("description"));
            t.setCategory((String) map.get("category"));
            t.setBuiltinHandler((String) map.get("builtinHandler"));
            t.setInternalPath((String) map.get("internalPath"));
            t.setHttpMethod((String) map.get("httpMethod"));
            t.setHttpUrl((String) map.get("httpUrl"));
            t.setRequiredRole((String) map.getOrDefault("requiredRole", "user"));
            t.setTimeoutMs(toInt(map.get("timeoutMs"), 10000));
            t.setRetryCount(toInt(map.get("retryCount"), 0));
            Object status = map.get("status");
            t.setEnabled(status == null || toInt(status, 1) == 1);
            String schemaJson = (String) map.get("parametersSchema");
            t.setParametersSchemaFromJson(schemaJson);
            String headersJson = (String) map.get("httpHeaders");
            if (headersJson != null && !headersJson.isBlank()) {
                t.setHttpHeaders(objectMapper.readValue(headersJson,
                        new TypeReference<Map<String, String>>() {}));
            }
            return t;
        } catch (Exception e) {
            log.warn("Failed to map tool definition: {}", e.getMessage());
            return null;
        }
    }

    private Long toLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number n) return n.longValue();
        try { return Long.parseLong(obj.toString()); } catch (Exception e) { return null; }
    }

    private int toInt(Object obj, int defaultVal) {
        if (obj == null) return defaultVal;
        if (obj instanceof Number n) return n.intValue();
        try { return Integer.parseInt(obj.toString()); } catch (Exception e) { return defaultVal; }
    }
}
