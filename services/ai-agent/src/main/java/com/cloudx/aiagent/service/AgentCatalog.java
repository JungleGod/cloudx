package com.cloudx.aiagent.service;

import com.cloudx.aiagent.tool.ToolDefinition;
import com.cloudx.common.result.R;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 子 Agent 名册 — 从 biz-service 加载 Agent 定义，供 AgentOrchestrator 编排使用
 *
 * <p>两个职责：
 * <ul>
 *   <li>给 dispatch_agent 工具动态注入可用子 Agent 名册（LLM 据此决策调度谁）</li>
 *   <li>按名称查找子 Agent 定义，供 AgentDispatchHandler 构建子执行上下文</li>
 * </ul>
 *
 * <p>与 ToolRegistry 同模式：启动加载 + 热重载（/api/admin/tools/reload 一并刷新）
 */
@Slf4j
@Component
public class AgentCatalog {

    private final RestTemplate restTemplate;

    private volatile Map<String, Map<String, Object>> agentsByName = new ConcurrentHashMap<>();
    private volatile Map<Long, Map<String, Object>> agentsById = new ConcurrentHashMap<>();
    /** 第三方 Agent 调度目标（agentId → target），明文 Key 仅存在内存 */
    private volatile Map<Long, ExternalAgentClient.ExternalAgentTarget> externalTargets = new ConcurrentHashMap<>();
    private volatile long lastFailedReloadAt = 0;

    private static final String bizServiceUrl = "http://localhost:8081";
    private static final long RELOAD_RETRY_INTERVAL_MS = 30_000;

    /** dispatch_agent 工具的基础描述（名册动态拼接在后面） */
    private static final String DISPATCH_TOOL_BASE_DESC =
            "调度平台中的专业子Agent完成子任务。参数 agent_name=子Agent名称（必须用下方列表中的名称）；"
            + "task=交给子Agent的完整任务描述（自包含，含回答所需的全部上下文）。"
            + "每次调用只完成一件事，需要多件事时多次调用。";

    public AgentCatalog(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @PostConstruct
    public void init() {
        reload();
    }

    /** 从 biz-service 重新加载所有 Agent 定义（external Agent 额外拉取调度信息） */
    public synchronized void reload() {
        try {
            ResponseEntity<R<List<Map<String, Object>>>> resp = restTemplate.exchange(
                    bizServiceUrl + "/api/internal/agents", HttpMethod.GET, null,
                    new ParameterizedTypeReference<R<List<Map<String, Object>>>>() {});
            R<List<Map<String, Object>>> r = resp.getBody();
            List<Map<String, Object>> list = r != null ? r.getData() : null;
            if (list != null) {
                Map<String, Map<String, Object>> byName = new ConcurrentHashMap<>();
                Map<Long, Map<String, Object>> byId = new ConcurrentHashMap<>();
                Map<Long, ExternalAgentClient.ExternalAgentTarget> targets = new ConcurrentHashMap<>();
                for (Map<String, Object> a : list) {
                    Object name = a.get("name");
                    Long id = toLong(a.get("id"));
                    if (name == null || id == null) continue;
                    byName.put(name.toString(), a);
                    byId.put(id, a);
                    // 第三方 Agent：拉取含解密 Key 的调度信息
                    if ("external".equals(a.get("executionType"))) {
                        ExternalAgentClient.ExternalAgentTarget target = fetchExternalTarget(id, name.toString());
                        if (target != null) {
                            targets.put(id, target);
                        }
                    }
                }
                this.agentsByName = byName;
                this.agentsById = byId;
                this.externalTargets = targets;
                lastFailedReloadAt = 0;
                log.info("AgentCatalog loaded: {} agents ({} external)", byName.size(), targets.size());
            }
        } catch (Exception e) {
            lastFailedReloadAt = System.currentTimeMillis();
            log.warn("AgentCatalog reload failed: {}", e.getMessage());
        }
    }

    /** 拉取第三方 Agent 调度信息（含解密后的 endpointKey） */
    private ExternalAgentClient.ExternalAgentTarget fetchExternalTarget(Long id, String name) {
        try {
            ResponseEntity<R<Map<String, Object>>> resp = restTemplate.exchange(
                    bizServiceUrl + "/api/internal/agents/" + id + "/dispatch-info",
                    HttpMethod.GET, null,
                    new ParameterizedTypeReference<R<Map<String, Object>>>() {});
            R<Map<String, Object>> r = resp.getBody();
            Map<String, Object> info = r != null ? r.getData() : null;
            if (info == null) {
                log.warn("External agent [{}] dispatch-info unavailable", name);
                return null;
            }
            String url = (String) info.get("endpointUrl");
            if (url == null || url.isBlank()) {
                log.warn("External agent [{}] has empty endpointUrl", name);
                return null;
            }
            return new ExternalAgentClient.ExternalAgentTarget(
                    name, url,
                    (String) info.get("endpointKey"),
                    toInt(info.get("dispatchTimeoutMs"), 60_000));
        } catch (Exception e) {
            log.warn("Fetch dispatch-info for external agent [{}] failed: {}", name, e.getMessage());
            return null;
        }
    }

    /** 按名称查找子 Agent 定义（含未启用的，由调用方判断状态） */
    public Map<String, Object> getByName(String name) {
        ensureLoaded();
        return agentsByName.get(name);
    }

    /** 按 ID 查找 Agent 定义 */
    public Map<String, Object> getById(Long id) {
        ensureLoaded();
        return id != null ? agentsById.get(id) : null;
    }

    /** 获取第三方 Agent 调度目标（未注册/配置不完整返回 null） */
    public ExternalAgentClient.ExternalAgentTarget getExternalTarget(Long agentId) {
        return agentId != null ? externalTargets.get(agentId) : null;
    }

    /** 所有启用状态的子 Agent */
    public List<Map<String, Object>> listEnabled() {
        ensureLoaded();
        return agentsByName.values().stream()
                .filter(a -> toInt(a.get("status"), 0) == 1)
                .collect(Collectors.toList());
    }

    /** 生成给 LLM 看的子 Agent 名册（名称 + 描述，每行一个） */
    public String buildRoster() {
        List<Map<String, Object>> enabled = listEnabled();
        if (enabled.isEmpty()) {
            return "（当前没有可用的子Agent）";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> a : enabled) {
            sb.append("- ").append(a.get("name"));
            Object desc = a.get("description");
            if (desc != null && !desc.toString().isBlank()) {
                sb.append("：").append(desc);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 把子 Agent 名册注入 dispatch_agent 工具描述 — Agent-as-Tool 编排的核心 */
    public void enrichDispatchTool(List<ToolDefinition> tools) {
        if (tools == null) return;
        for (ToolDefinition t : tools) {
            if ("dispatch_agent".equals(t.getName())) {
                t.setDescription(DISPATCH_TOOL_BASE_DESC + "\n\n当前可用子Agent：\n" + buildRoster());
            }
        }
    }

    /** 缓存为空时懒加载（biz-service 比 ai-agent 后启动的场景），失败后 30s 内不重试 */
    private void ensureLoaded() {
        if (agentsByName.isEmpty()
                && System.currentTimeMillis() - lastFailedReloadAt > RELOAD_RETRY_INTERVAL_MS) {
            reload();
        }
    }

    private Long toLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(obj.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private int toInt(Object obj, int defaultVal) {
        if (obj == null) return defaultVal;
        if (obj instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(obj.toString());
        } catch (Exception e) {
            return defaultVal;
        }
    }
}