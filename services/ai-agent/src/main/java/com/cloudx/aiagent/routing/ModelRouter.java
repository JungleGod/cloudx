package com.cloudx.aiagent.routing;

import com.cloudx.aiagent.config.ModelConfig;
import com.cloudx.aiagent.provider.ModelProvider;
import com.cloudx.aiagent.provider.OpenAiCompatibleProvider;
import com.cloudx.aiagent.provider.StreamCallback;
import com.cloudx.common.exception.BizException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 模型路由器 — 根据请求内容选择最合适的模型
 * <p>
 * 路由优先级：taskType 指定 > 关键词匹配 > 默认（priority 最小）
 */
@Slf4j
@Component
public class ModelRouter {

    private final ModelConfig modelConfig;
    private final Map<String, ModelProvider> providers = new LinkedHashMap<>();
    private final Map<String, List<String>> tagIndex = new HashMap<>();
    private List<ModelProvider> sortedByPriority = new ArrayList<>();
    private final AtomicInteger refreshCount = new AtomicInteger(0);

    public ModelRouter(ModelConfig modelConfig) {
        this.modelConfig = modelConfig;
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    /**
     * 重建所有 provider 和索引 — 配置变更时自动调用
     */
    public void refresh() {
        providers.clear();
        tagIndex.clear();

        for (ModelConfig.ModelInfo info : modelConfig.getModels()) {
            if (!info.hasKeys()) {
                log.warn("Model [{}] has no valid API keys, skipping", info.getName());
                continue;
            }
            ModelProvider provider = new OpenAiCompatibleProvider(info);
            providers.put(info.getName(), provider);

            for (String tag : info.getTags()) {
                tagIndex.computeIfAbsent(tag, k -> new ArrayList<>()).add(info.getName());
            }
        }

        sortedByPriority = providers.values().stream()
                .sorted(Comparator.comparing(p -> getConfig(p.getModelName()).getPriority()))
                .collect(Collectors.toList());

        int count = refreshCount.incrementAndGet();
        log.info("ModelRouter refreshed (#{}): {} providers, tags: {}", count, providers.size(), tagIndex.keySet());
    }

    /**
     * 监听 Nacos 配置刷新事件，自动重建路由表
     */
    @EventListener(RefreshScopeRefreshedEvent.class)
    public void onConfigRefresh(RefreshScopeRefreshedEvent event) {
        log.info("Detected RefreshScopeRefreshedEvent, rebuilding ModelRouter...");
        refresh();
    }

    /**
     * 流式路由 — 选择模型后流式回调，异常时自动 failover
     */
    public void routeStream(String message, String model, String taskType, StreamCallback callback) {
        ModelProvider provider = select(message, model, taskType);
        String strategy = resolveStrategy(model, taskType, message);

        log.info("Streaming to [{}] via {}, message preview: {}...",
                provider.getModelName(), strategy,
                message.length() > 50 ? message.substring(0, 50) : message);

        try {
            provider.streamChat(message, callback);
            provider.recordSuccess();
        } catch (Exception e) {
            log.error("Stream model [{}] failed: {}", provider.getModelName(), e.getMessage());
            provider.recordFailure();

            // 尝试回退到备用模型
            ModelConfig.ModelInfo config = getConfig(provider.getModelName());
            if (config.getFallback() != null) {
                ModelProvider fallback = providers.get(config.getFallback());
                if (fallback != null && fallback.isAvailable()) {
                    log.warn("Stream falling back to [{}]", fallback.getModelName());
                    try {
                        fallback.streamChat(message, callback);
                        fallback.recordSuccess();
                        return;
                    } catch (Exception fe) {
                        log.error("Stream fallback [{}] also failed", fallback.getModelName());
                        fallback.recordFailure();
                    }
                }
            }
            callback.onError(new BizException("所有可用模型流式调用失败，请稍后重试"));
        }
    }

    /**
     * 路由结果，包含元信息
     */
    public record RouteResult(String reply, String model, String strategy, boolean failover) {}

    /**
     * 路由选择模型并执行对话
     */
    public RouteResult route(String message, String model, String taskType) {
        ModelProvider provider = select(message, model, taskType);
        String strategy = resolveStrategy(model, taskType, message);
        boolean failover = false;

        log.info("Routing to [{}] via {}, message preview: {}...",
                provider.getModelName(), strategy,
                message.length() > 50 ? message.substring(0, 50) : message);

        // 调用主模型
        try {
            String reply = provider.chat(message);
            provider.recordSuccess();
            return new RouteResult(reply, provider.getModelName(), strategy, false);
        } catch (Exception e) {
            log.error("Model [{}] failed: {}", provider.getModelName(), e.getMessage());
            provider.recordFailure();

            // 尝试回退到备用模型
            ModelConfig.ModelInfo config = getConfig(provider.getModelName());
            if (config.getFallback() != null) {
                ModelProvider fallback = providers.get(config.getFallback());
                if (fallback != null && fallback.isAvailable()) {
                    log.warn("Falling back to [{}]", fallback.getModelName());
                    try {
                        String reply = fallback.chat(message);
                        fallback.recordSuccess();
                        return new RouteResult(reply, fallback.getModelName(), "failover", true);
                    } catch (Exception fe) {
                        log.error("Fallback model [{}] also failed: {}", fallback.getModelName(), fe.getMessage());
                        fallback.recordFailure();
                    }
                }
            }
            throw new BizException("所有可用模型调用失败，请稍后重试");
        }
    }

    /** 解析路由策略名 */
    private String resolveStrategy(String model, String taskType, String message) {
        if (model != null && !model.isBlank()) return "user=" + model;
        if (taskType != null && !taskType.isBlank()) return "taskType=" + taskType;
        if (matchByKeyword(message.toLowerCase()) != null) return "keyword";
        return "default";
    }

    /**
     * 选择合适的模型
     * @param model 用户手动指定的模型名，null 则自动选择
     */
    private ModelProvider select(String message, String model, String taskType) {
        // 0. 用户手动指定模型（最高优先级）
        if (model != null && !model.isBlank()) {
            ModelProvider provider = providers.get(model);
            if (provider != null && provider.isAvailable()) {
                log.info("Using user-specified model: {}", model);
                return provider;
            }
            log.warn("User-specified model [{}] not available, falling back to auto-select", model);
        }

        String targetModel = null;

        // 1. 如果请求指定了 taskType，按标签匹配
        if (taskType != null && !taskType.isBlank()) {
            targetModel = matchByTag(taskType.toLowerCase());
        }

        // 2. 关键词匹配
        if (targetModel == null) {
            targetModel = matchByKeyword(message.toLowerCase());
        }

        // 3. 默认：优先级最高的可用模型
        if (targetModel == null) {
            for (ModelProvider p : sortedByPriority) {
                if (p.isAvailable()) {
                    return p;
                }
            }
        }

        // 找到目标模型
        if (targetModel != null) {
            ModelProvider provider = providers.get(targetModel);
            if (provider != null && provider.isAvailable()) {
                return provider;
            }
            // 目标不可用，回退到默认
            log.warn("Target model [{}] is not available, falling back to default", targetModel);
            for (ModelProvider p : sortedByPriority) {
                if (p.isAvailable()) {
                    return p;
                }
            }
        }

        throw new BizException("没有可用的 AI 模型");
    }

    private String matchByTag(String taskType) {
        List<String> candidates = tagIndex.get(taskType);
        if (candidates != null) {
            for (String name : candidates) {
                ModelProvider p = providers.get(name);
                if (p != null && p.isAvailable()) return name;
            }
        }
        // 模糊匹配标签
        for (Map.Entry<String, List<String>> entry : tagIndex.entrySet()) {
            if (taskType.contains(entry.getKey()) || entry.getKey().contains(taskType)) {
                for (String name : entry.getValue()) {
                    ModelProvider p = providers.get(name);
                    if (p != null && p.isAvailable()) return name;
                }
            }
        }
        return null;
    }

    private String matchByKeyword(String message) {
        // 代码相关 → deepseek（代码能力强）
        if (containsAny(message, "写代码", "debug", "java", "python", "bug", "代码", "编程", "算法", "重构")) {
            return findAvailable("deepseek");
        }
        // 图片/多模态 → 找支持 multimodal 的模型
        if (containsAny(message, "图片", "生成图", "画", "图像", "照片")) {
            return findByTag("multimodal");
        }
        return null;
    }

    private String findAvailable(String modelName) {
        ModelProvider p = providers.get(modelName);
        return (p != null && p.isAvailable()) ? modelName : null;
    }

    private String findByTag(String tag) {
        List<String> names = tagIndex.get(tag);
        if (names != null) {
            for (String name : names) {
                ModelProvider p = providers.get(name);
                if (p != null && p.isAvailable()) return name;
            }
        }
        return null;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private ModelConfig.ModelInfo getConfig(String modelName) {
        return modelConfig.getModels().stream()
                .filter(m -> m.getName().equals(modelName))
                .findFirst()
                .orElseThrow(() -> new BizException("模型配置不存在: " + modelName));
    }

    /** 多模态路由 — 支持图片输入 */
    public RouteResult routeMultimodal(String text, List<String> base64Images, String model, String taskType) {
        ModelProvider provider = select(text, model, taskType != null ? taskType : "multimodal");
        String strategy = resolveStrategy(model, taskType, text);

        try {
            String reply = provider.chatMultimodal(text, base64Images);
            provider.recordSuccess();
            return new RouteResult(reply, provider.getModelName(), strategy, false);
        } catch (Exception e) {
            log.error("Multimodal model [{}] failed: {}", provider.getModelName(), e.getMessage());
            provider.recordFailure();
            ModelConfig.ModelInfo config = getConfig(provider.getModelName());
            if (config.getFallback() != null) {
                ModelProvider fallback = providers.get(config.getFallback());
                if (fallback != null && fallback.isAvailable()) {
                    try {
                        String reply = fallback.chatMultimodal(text, base64Images);
                        fallback.recordSuccess();
                        return new RouteResult(reply, fallback.getModelName(), "failover", true);
                    } catch (Exception fe) {
                        log.error("Multimodal fallback [{}] also failed", fallback.getModelName());
                        fallback.recordFailure();
                    }
                }
            }
            throw new BizException("多模态模型调用失败，请稍后重试");
        }
    }

    public Map<String, String> getProviderStatus() {
        Map<String, String> status = new LinkedHashMap<>();
        for (ModelProvider p : providers.values()) {
            status.put(p.getModelName(), p.isAvailable() ? "UP" : "DOWN");
        }
        return status;
    }
}
