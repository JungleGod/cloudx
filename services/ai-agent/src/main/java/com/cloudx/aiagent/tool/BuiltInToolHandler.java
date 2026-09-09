package com.cloudx.aiagent.tool;

import com.cloudx.aiagent.routing.ModelRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 内置工具处理器 — 系统级工具（查询用户数、统计、模型状态等）
 * <p>
 * 每个 handler 方法对应 tool_definition.builtin_handler 字段值
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuiltInToolHandler {

    private final ModelRouter modelRouter;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String bizServiceUrl = "http://localhost:8081";

    /**
     * 根据 handler 名称执行内置工具
     */
    public String execute(String handlerName, String argumentsJson, String callerRole, Long userId) {
        return switch (handlerName) {
            case "getUserCountHandler"   -> getUserCount();
            case "getDailyStatsHandler"  -> getDailyStats();
            case "getApiKeyCountHandler" -> getApiKeyCount();
            case "getModelStatusHandler" -> getModelStatus();
            case "getMyUsageHandler"     -> getMyUsage(userId);
            default -> throw new RuntimeException("Unknown built-in handler: " + handlerName);
        };
    }

    /** 查询系统注册用户总数 */
    private String getUserCount() {
        try {
            String url = bizServiceUrl + "/api/internal/stats";
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.getForObject(url, Map.class);
            if (resp != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) resp.get("data");
                if (data != null) {
                    return objectMapper.writeValueAsString(Map.of(
                            "userCount", data.get("userCount"),
                            "message", "系统当前共有 " + data.get("userCount") + " 个注册用户"
                    ));
                }
            }
        } catch (Exception e) {
            log.error("getUserCount failed: {}", e.getMessage());
            return "{\"error\": \"查询用户数失败: " + e.getMessage() + "\"}";
        }
        return "{\"error\": \"查询用户数失败\"}";
    }

    /** 获取今日 API 调用统计 */
    private String getDailyStats() {
        try {
            String url = bizServiceUrl + "/api/stats/today";
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.getForObject(url, Map.class);
            if (resp != null) {
                return objectMapper.writeValueAsString(Map.of(
                        "stats", resp.get("data"),
                        "message", "已返回今日统计数据"
                ));
            }
        } catch (Exception e) {
            log.error("getDailyStats failed: {}", e.getMessage());
            return "{\"error\": \"查询统计失败\"}";
        }
        return "{\"error\": \"查询统计失败\"}";
    }

    /** 获取 API Key 总数 */
    private String getApiKeyCount() {
        try {
            String url = bizServiceUrl + "/api/internal/stats";
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.getForObject(url, Map.class);
            if (resp != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) resp.get("data");
                if (data != null) {
                    return objectMapper.writeValueAsString(Map.of(
                            "stats", data,
                            "message", "系统当前统计信息"
                    ));
                }
            }
        } catch (Exception e) {
            log.error("getApiKeyCount failed: {}", e.getMessage());
            return "{\"error\": \"查询 Key 数失败: " + e.getMessage() + "\"}";
        }
        return "{\"error\": \"查询失败\"}";
    }

    /** 查看当前在线模型 — 返回详细信息（名称、状态、供应商、标签、熔断、回退等） */
    private String getModelStatus() {
        try {
            java.util.List<Map<String, Object>> models = modelRouter.getProviderStatus();
            long onlineCount = models.stream()
                    .filter(m -> "UP".equals(m.get("status")))
                    .count();
            return objectMapper.writeValueAsString(Map.of(
                    "models", models,
                    "total", models.size(),
                    "onlineCount", onlineCount,
                    "message", "共 " + models.size() + " 个模型，" + onlineCount + " 个在线"
            ));
        } catch (Exception e) {
            log.error("getModelStatus failed: {}", e.getMessage());
            return "{\"error\": \"查询模型状态失败\"}";
        }
    }

    /** 获取当前用户今日使用量 */
    private String getMyUsage(Long userId) {
        if (userId == null) {
            return "{\"error\": \"无法获取当前用户ID，请确认已登录\"}";
        }
        try {
            String url = bizServiceUrl + "/api/stats/today?userId=" + userId;
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.getForObject(url, Map.class);
            if (resp != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) resp.get("data");
                if (data != null) {
                    return objectMapper.writeValueAsString(Map.of(
                            "userId", userId,
                            "stats", data,
                            "message", "用户 " + userId + " 的今日使用情况"
                    ));
                }
            }
        } catch (Exception e) {
            log.error("getMyUsage failed for userId={}: {}", userId, e.getMessage());
            return "{\"error\": \"查询个人使用量失败: " + e.getMessage() + "\"}";
        }
        return "{\"error\": \"查询个人使用量失败\"}";
    }
}
