package com.cloudx.aiagent.agent;

import com.cloudx.aiagent.config.ModelConfig;
import com.cloudx.aiagent.service.AgentCatalog;
import com.cloudx.aiagent.service.ModelConfigClient;
import com.cloudx.aiagent.tool.ToolDefinition;
import com.cloudx.aiagent.tool.ToolExecutor;
import com.cloudx.aiagent.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Agent 核心循环 — ReAct (Reasoning + Acting) 模式实现
 *
 * <pre>
 * 循环流程：
 *   1. 构建 messages（system + 工具定义 + 历史 + 用户消息）
 *   2. 调用 LLM（LangChain4j，带 tools）
 *   3. LLM 返回 tool_calls → 执行工具 → 注入结果 → 回到步骤 2
 *   4. LLM 返回文本 → 流式输出最终答案
 *   5. 达到最大迭代次数 → 强制 LLM 基于已有信息总结
 * </pre>
 */
@Slf4j
@Component
public class AgentLoop {

    private final ModelConfigClient modelConfigClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final AgentExecutionLogger executionLogger;
    private final AgentCatalog agentCatalog;

    /** 单次 LLM 调用最长等待时间 */
    private static final long LLM_TIMEOUT_SECONDS = 60;

    public AgentLoop(ModelConfigClient modelConfigClient, ToolRegistry toolRegistry,
                     ToolExecutor toolExecutor, AgentExecutionLogger executionLogger,
                     AgentCatalog agentCatalog) {
        this.modelConfigClient = modelConfigClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.executionLogger = executionLogger;
        this.agentCatalog = agentCatalog;
    }

    // ==================== 同步执行 ====================

    /**
     * 同步执行 Agent（非流式），通过 LangChain4j OpenAiChatModel
     */
    public AgentResult execute(AgentExecutionContext ctx, AgentStreamCallback callback) {
        long startTime = System.currentTimeMillis();
        AgentResult result = AgentResult.builder()
                .iterations(0)
                .totalTokens(0)
                .interrupted(false)
                .build();

        ModelConfig.ModelInfo modelInfo = resolveModelForStream(ctx);

        // 获取 Agent 绑定的工具
        List<ToolDefinition> tools = getTools(ctx);

        // 构建 messages
        List<AgentMessage> messages = buildMessages(ctx, tools);

        int maxIter = ctx.getEffectiveMaxIterations();

        for (int iter = 1; iter <= maxIter; iter++) {
            result.setIterations(iter);
            log.info("Agent [{}] iteration {}/{}", ctx.getAgentName(), iter, maxIter);

            Response<AiMessage> response = callLLM(modelInfo, messages, tools);

            if (response == null || response.content() == null) {
                result.setAnswer("错误：LLM 无响应");
                break;
            }

            AiMessage aiMessage = response.content();
            TokenUsage usage = response.tokenUsage();
            if (usage != null) {
                result.addUsage(usage.inputTokenCount(), usage.outputTokenCount());
            }

            // 情况 1：LLM 直接返回文本（没有 tool_calls）
            if (!aiMessage.hasToolExecutionRequests() && aiMessage.text() != null && !aiMessage.text().isBlank()) {
                result.setAnswer(aiMessage.text());
                finish(callback, result, aiMessage.text());
                break;
            }

            // 情况 2：LLM 返回了 tool_calls
            if (aiMessage.hasToolExecutionRequests()) {
                // 客户端工具模式（OpenAI 兼容 /v1 透传）：工具由调用方执行，原样返回并终止服务端循环
                if (isClientToolMode(ctx)) {
                    result.setClientToolCalls(toAgentToolCalls(aiMessage.toolExecutionRequests()));
                    break;
                }
                if (callback != null) callback.onThinking();

                List<AgentMessage.ToolCall> toolCalls = toAgentToolCalls(aiMessage.toolExecutionRequests());
                messages.add(AgentMessage.assistantWithToolCalls(toolCalls));

                for (AgentMessage.ToolCall tc : toolCalls) {
                    String toolName = tc.getFunction().getName();
                    String arguments = tc.getFunction().getArguments();

                    if (callback != null) {
                        callback.onToolCallStart(toolName, tc.getId());
                        callback.onToolCallExecuting(toolName, arguments);
                    }

                    ToolDefinition toolDef = tools.stream()
                            .filter(t -> t.getName().equals(toolName))
                            .findFirst().orElse(null);

                    long toolStart = System.currentTimeMillis();
                    String toolResult;
                    boolean success;
                    try {
                        toolResult = toolDef != null
                                ? toolExecutor.execute(toolDef, arguments, ctx)
                                : "{\"error\": \"Unknown tool: " + toolName + "\"}";
                        success = true;
                    } catch (Exception e) {
                        toolResult = "{\"error\": \"" + e.getMessage() + "\"}";
                        success = false;
                    }
                    long toolElapsed = System.currentTimeMillis() - toolStart;

                    result.addToolStep(AgentResult.ToolStep.builder()
                            .iteration(iter).toolName(toolName).arguments(arguments)
                            .result(toolResult).success(success).elapsedMs(toolElapsed)
                            .build());

                    if (callback != null) {
                        callback.onToolResult(toolName, toolResult, success, toolElapsed);
                    }

                    messages.add(AgentMessage.tool(tc.getId(), toolName, toolResult));
                }
                mergeChildTokens(ctx, result);
                continue;
            }

            // 情况 3：既无内容也无 tool_calls（异常情况）
            log.warn("Agent [{}] iteration {} returned empty response, forcing summary", ctx.getAgentName(), iter);
            result.setAnswer("（模型未返回有效响应）");
            break;
        }

        // 达到最大迭代次数但未结束 → 强制 LLM 总结
        if (result.getAnswer() == null && result.getClientToolCalls() == null && result.getIterations() >= maxIter) {
            result.setInterrupted(true);
            log.info("Agent [{}] max iterations reached, forcing summary", ctx.getAgentName());
            messages.add(AgentMessage.user("你已收集了足够的信息，请基于以上工具调用结果，给出最终答案。不要再次调用工具。"));
            Response<AiMessage> finalResp = callLLM(modelInfo, messages, List.of());
            if (finalResp != null && finalResp.content() != null && finalResp.content().text() != null) {
                result.setAnswer(finalResp.content().text());
                if (finalResp.tokenUsage() != null) {
                    result.addUsage(finalResp.tokenUsage().inputTokenCount(), finalResp.tokenUsage().outputTokenCount());
                }
                finish(callback, result, finalResp.content().text());
            } else {
                result.setAnswer("（达到最大迭代次数，无法获取最终答案）");
            }
        }

        result.setElapsedMs(System.currentTimeMillis() - startTime);
        mergeChildTokens(ctx, result);
        executionLogger.log(ctx, result);
        return result;
    }

    // ==================== 流式执行 ====================

    /**
     * 流式执行 Agent — LangChain4j StreamingChatLanguageModel + SSE 推送
     */
    public void executeStream(AgentExecutionContext ctx, AgentStreamCallback callback) {
        long startTime = System.currentTimeMillis();
        AgentResult result = AgentResult.builder()
                .iterations(0)
                .interrupted(false)
                .build();

        try {
            ModelConfig.ModelInfo modelInfo = resolveModelForStream(ctx);
            List<ToolDefinition> tools = getTools(ctx);
            List<AgentMessage> messages = buildMessages(ctx, tools);
            int maxIter = ctx.getEffectiveMaxIterations();

            for (int iter = 1; iter <= maxIter; iter++) {
                result.setIterations(iter);
                log.info("Agent [{}] stream iteration {}/{}", ctx.getAgentName(), iter, maxIter);

                if (callback != null) callback.onThinking();

                // 流式调用 LLM（LangChain4j）
                StreamResult streamResult = callLLMStream(modelInfo, messages, tools, callback);

                // 流式失败 → 降级为非流式
                if (streamResult.error != null) {
                    log.warn("Agent [{}] stream failed, falling back to non-streaming", ctx.getAgentName());
                    Response<AiMessage> fallback = callLLM(modelInfo, messages, tools);
                    if (fallback != null && fallback.content() != null
                            && fallback.content().text() != null && !fallback.content().text().isBlank()) {
                        result.setAnswer(fallback.content().text());
                        if (fallback.tokenUsage() != null) {
                            result.addUsage(fallback.tokenUsage().inputTokenCount(), fallback.tokenUsage().outputTokenCount());
                        }
                        finish(callback, result, fallback.content().text());
                        break;
                    }
                    if (callback != null) callback.onError(streamResult.error);
                    return;
                }

                result.addUsage(streamResult.inputTokens, streamResult.outputTokens);

                // 有 tool_calls → 执行工具并循环
                if (!streamResult.toolCalls.isEmpty()) {
                    // 客户端工具模式（OpenAI 兼容 /v1 透传）：回调 tool_call 事件让上层组装
                    // tool_calls chunk（finish_reason=tool_calls），终止服务端循环
                    if (isClientToolMode(ctx)) {
                        result.setClientToolCalls(streamResult.toolCalls);
                        if (callback != null) {
                            for (AgentMessage.ToolCall tc : streamResult.toolCalls) {
                                String name = tc.getFunction().getName();
                                String args = tc.getFunction().getArguments() != null
                                        ? tc.getFunction().getArguments() : "{}";
                                callback.onToolCallStart(name, tc.getId());
                                callback.onToolCallArgs(tc.getId(), args);
                                callback.onToolCallExecuting(name, args);
                            }
                        }
                        finish(callback, result, streamResult.content != null ? streamResult.content : "");
                        break;
                    }
                    messages.add(AgentMessage.assistantWithToolCalls(streamResult.toolCalls));

                    for (AgentMessage.ToolCall tc : streamResult.toolCalls) {
                        String toolName = tc.getFunction().getName();
                        String arguments = tc.getFunction().getArguments();

                        if (callback != null) {
                            callback.onToolCallStart(toolName, tc.getId());
                            callback.onToolCallExecuting(toolName, arguments);
                        }

                        ToolDefinition toolDef = tools.stream()
                                .filter(t -> t.getName().equals(toolName))
                                .findFirst().orElse(null);

                        long toolStart = System.currentTimeMillis();
                        String toolResult;
                        boolean success;
                        try {
                            toolResult = toolDef != null
                                    ? toolExecutor.execute(toolDef, arguments, ctx)
                                    : "{\"error\": \"Unknown tool: " + toolName + "\"}";
                            success = true;
                        } catch (Exception e) {
                            toolResult = "{\"error\": \"" + e.getMessage() + "\"}";
                            success = false;
                        }
                        long toolElapsed = System.currentTimeMillis() - toolStart;

                        result.addToolStep(AgentResult.ToolStep.builder()
                                .iteration(iter).toolName(toolName).arguments(arguments)
                                .result(toolResult).success(success).elapsedMs(toolElapsed)
                                .build());

                        if (callback != null) {
                            callback.onToolResult(toolName, toolResult, success, toolElapsed);
                        }

                        messages.add(AgentMessage.tool(tc.getId(), toolName, toolResult));
                    }
                    mergeChildTokens(ctx, result);
                    continue;
                }

                // 有文本内容 → 完成
                result.setAnswer(streamResult.content);
                finish(callback, result, streamResult.content);
                break;
            }

            if (result.getAnswer() == null && result.getClientToolCalls() == null && result.getIterations() >= maxIter) {
                result.setInterrupted(true);
                messages.add(AgentMessage.user("请基于以上信息给出最终答案。不要调用工具。"));
                StreamResult finalResp = callLLMStream(modelInfo, messages, List.of(), callback);
                if (finalResp.error == null && finalResp.content != null) {
                    result.setAnswer(finalResp.content);
                    result.addUsage(finalResp.inputTokens, finalResp.outputTokens);
                } else {
                    log.warn("Agent [{}] final stream failed, falling back to non-streaming", ctx.getAgentName());
                    Response<AiMessage> fallback = callLLM(modelInfo, messages, List.of());
                    if (fallback != null && fallback.content() != null && fallback.content().text() != null) {
                        result.setAnswer(fallback.content().text());
                        if (fallback.tokenUsage() != null) {
                            result.addUsage(fallback.tokenUsage().inputTokenCount(), fallback.tokenUsage().outputTokenCount());
                        }
                    }
                }
                finish(callback, result, result.getAnswer());
            }

        } catch (Exception e) {
            log.error("Agent stream execution error: {}", e.getMessage(), e);
            if (callback != null) callback.onError(e);
        } finally {
            result.setElapsedMs(System.currentTimeMillis() - startTime);
            mergeChildTokens(ctx, result);
            executionLogger.log(ctx, result);
        }
    }

    // ==================== LLM 调用（LangChain4j） ====================

    /**
     * 同步调用 LLM（带 tools）— 通过 LangChain4j OpenAiChatModel
     */
    private Response<AiMessage> callLLM(ModelConfig.ModelInfo modelInfo, List<AgentMessage> messages,
                                         List<ToolDefinition> tools) {
        try {
            var builder = OpenAiChatModel.builder()
                    .apiKey(modelInfo.getKeys().get(0))
                    .baseUrl(modelInfo.getBaseUrl())
                    .modelName(modelInfo.getModelName() != null ? modelInfo.getModelName() : modelInfo.getName())
                    .temperature(modelInfo.getTemperature())
                    .maxTokens(modelInfo.getMaxTokens())
                    .timeout(Duration.ofSeconds(LLM_TIMEOUT_SECONDS));

            if (modelInfo.getFrequencyPenalty() != 0) {
                builder.frequencyPenalty(modelInfo.getFrequencyPenalty());
            }
            if (modelInfo.getPresencePenalty() != 0) {
                builder.presencePenalty(modelInfo.getPresencePenalty());
            }

            OpenAiChatModel model = builder.build();
            return model.generate(toLangChain4jMessages(messages), toToolSpecifications(tools));

        } catch (Exception e) {
            log.error("LLM sync call failed: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 流式调用 LLM（带 tools）— 通过 LangChain4j OpenAiStreamingChatModel
     * <p>
     * 超时保护：用 CompletableFuture 包装，SSE 读取最长等 LLM_TIMEOUT_SECONDS 秒
     */
    private StreamResult callLLMStream(ModelConfig.ModelInfo modelInfo, List<AgentMessage> messages,
                                        List<ToolDefinition> tools, AgentStreamCallback callback) {
        try {
            var builder = OpenAiStreamingChatModel.builder()
                    .apiKey(modelInfo.getKeys().get(0))
                    .baseUrl(modelInfo.getBaseUrl())
                    .modelName(modelInfo.getModelName() != null ? modelInfo.getModelName() : modelInfo.getName())
                    .temperature(modelInfo.getTemperature())
                    .maxTokens(modelInfo.getMaxTokens())
                    .timeout(Duration.ofSeconds(LLM_TIMEOUT_SECONDS));

            if (modelInfo.getFrequencyPenalty() != 0) {
                builder.frequencyPenalty(modelInfo.getFrequencyPenalty());
            }
            if (modelInfo.getPresencePenalty() != 0) {
                builder.presencePenalty(modelInfo.getPresencePenalty());
            }

            OpenAiStreamingChatModel model = builder.build();

            List<ChatMessage> lcMessages = toLangChain4jMessages(messages);
            List<ToolSpecification> lcTools = toToolSpecifications(tools);

            // 用 CompletableFuture 包装，防止 LangChain4j 内部阻塞无超时
            CompletableFuture<StreamResult> future = new CompletableFuture<>();
            model.generate(lcMessages, lcTools, new dev.langchain4j.model.StreamingResponseHandler<AiMessage>() {
                private final StringBuilder content = new StringBuilder();
                private final List<AgentMessage.ToolCall> toolCalls = new ArrayList<>();

                @Override
                public void onNext(String token) {
                    content.append(token);
                    if (callback != null) callback.onToken(token);
                }

                @Override
                public void onComplete(Response<AiMessage> response) {
                    AiMessage aiMsg = response.content();
                    if (aiMsg.hasToolExecutionRequests()) {
                        for (ToolExecutionRequest tr : aiMsg.toolExecutionRequests()) {
                            toolCalls.add(AgentMessage.ToolCall.builder()
                                    .id(tr.id())
                                    .type("function")
                                    .function(AgentMessage.ToolCall.FunctionCall.builder()
                                            .name(tr.name())
                                            .arguments(tr.arguments())
                                            .build())
                                    .build());
                        }
                    }
                    TokenUsage streamUsage = response.tokenUsage();
                    future.complete(new StreamResult(
                            content.toString(),
                            null,
                            toolCalls,
                            streamUsage != null ? streamUsage.inputTokenCount() : 0,
                            streamUsage != null ? streamUsage.outputTokenCount() : 0
                    ));
                }

                @Override
                public void onError(Throwable error) {
                    log.error("LLM stream error: {}", error.getMessage());
                    if (!future.isDone()) {
                        future.complete(new StreamResult(
                                content.toString(),
                                error,
                                toolCalls,
                                0,
                                0
                        ));
                    }
                }
            });

            return future.get(LLM_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        } catch (TimeoutException e) {
            log.warn("LLM stream timed out after {}s", LLM_TIMEOUT_SECONDS);
            return new StreamResult(null,
                    new RuntimeException("LLM 响应超时（" + LLM_TIMEOUT_SECONDS + "s），请重试"),
                    List.of(), 0, 0);
        } catch (Exception e) {
            log.error("LLM stream call failed: {}", e.getMessage(), e);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new StreamResult(null, cause, List.of(), 0, 0);
        }
    }

    // ==================== 消息 / 工具转换 ====================

    /** AgentMessage → LangChain4j ChatMessage */
    private static List<ChatMessage> toLangChain4jMessages(List<AgentMessage> messages) {
        List<ChatMessage> result = new ArrayList<>();
        for (AgentMessage msg : messages) {
            result.add(switch (msg.getRole()) {
                case "system" -> SystemMessage.from(msg.getContent());
                case "user" -> UserMessage.from(msg.getContent());
                case "assistant" -> {
                    if (msg.hasToolCalls()) {
                        List<ToolExecutionRequest> requests = msg.getToolCalls().stream()
                                .map(tc -> ToolExecutionRequest.builder()
                                        .id(tc.getId())
                                        .name(tc.getFunction().getName())
                                        .arguments(tc.getFunction().getArguments())
                                        .build())
                                .toList();
                        yield AiMessage.from(requests);
                    } else {
                        yield AiMessage.from(msg.getContent() != null ? msg.getContent() : "");
                    }
                }
                case "tool" -> ToolExecutionResultMessage.from(
                        msg.getToolCallId(),
                        msg.getToolName() != null ? msg.getToolName() : "unknown",
                        msg.getContent() != null ? msg.getContent() : "");
                default -> throw new IllegalArgumentException("Unknown role: " + msg.getRole());
            });
        }
        return result;
    }

    /** ToolDefinition → LangChain4j ToolSpecification。
     *  必须带参数 Schema：缺失时上游模型无法走原生 function calling，会把工具调用当文本输出 */
    private static List<ToolSpecification> toToolSpecifications(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) return List.of();
        return tools.stream()
                .map(td -> {
                    ToolSpecification.Builder builder = ToolSpecification.builder()
                            .name(td.getName())
                            .description(td.getDescription());
                    Map<String, Object> schema = td.getParametersSchema();
                    if (schema != null && "object".equals(schema.get("type"))) {
                        builder.parameters((JsonObjectSchema) toJsonSchemaElement(schema));
                    }
                    return builder.build();
                })
                .collect(Collectors.toList());
    }

    /**
     * JSON Schema（Map 形式）→ LangChain4j JsonSchemaElement，递归转换。
     * 覆盖 object/string/integer/number/boolean/enum/array；未知类型降级为 string
     */
    private static JsonSchemaElement toJsonSchemaElement(Map<String, Object> schema) {
        String type = schema.get("type") != null ? String.valueOf(schema.get("type")) : "string";
        String description = schema.get("description") != null ? String.valueOf(schema.get("description")) : null;
        return switch (type) {
            case "object" -> {
                JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
                if (description != null) builder.description(description);
                if (schema.get("properties") instanceof Map<?, ?> properties) {
                    properties.forEach((name, prop) ->
                            builder.addProperty(String.valueOf(name), toJsonSchemaElement(asSchemaMap(prop))));
                }
                if (schema.get("required") instanceof List<?> required) {
                    builder.required(required.stream().map(String::valueOf).toList());
                }
                if (schema.get("additionalProperties") instanceof Boolean ap) {
                    builder.additionalProperties(ap);
                }
                yield builder.build();
            }
            case "array" -> {
                JsonArraySchema.Builder builder = JsonArraySchema.builder();
                if (description != null) builder.description(description);
                if (schema.get("items") instanceof Map<?, ?> items) {
                    builder.items(toJsonSchemaElement(asSchemaMap(items)));
                }
                yield builder.build();
            }
            case "integer" -> {
                JsonIntegerSchema.Builder builder = JsonIntegerSchema.builder();
                if (description != null) builder.description(description);
                yield builder.build();
            }
            case "number" -> {
                JsonNumberSchema.Builder builder = JsonNumberSchema.builder();
                if (description != null) builder.description(description);
                yield builder.build();
            }
            case "boolean" -> {
                JsonBooleanSchema.Builder builder = JsonBooleanSchema.builder();
                if (description != null) builder.description(description);
                yield builder.build();
            }
            case "string" -> {
                if (schema.get("enum") instanceof List<?> enums && !enums.isEmpty()) {
                    JsonEnumSchema.Builder builder = JsonEnumSchema.builder()
                            .enumValues(enums.stream().map(String::valueOf).toList());
                    if (description != null) builder.description(description);
                    yield builder.build();
                }
                JsonStringSchema.Builder builder = JsonStringSchema.builder();
                if (description != null) builder.description(description);
                yield builder.build();
            }
            default -> {
                log.warn("Unsupported JSON Schema type '{}', falling back to string", type);
                JsonStringSchema.Builder builder = JsonStringSchema.builder();
                if (description != null) builder.description(description);
                yield builder.build();
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asSchemaMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Collections.emptyMap();
    }

    /** OpenAI 兼容 /v1 透传模式：调用方自带工具集，工具由客户端执行 */
    private static boolean isClientToolMode(AgentExecutionContext ctx) {
        return ctx.getClientTools() != null && !ctx.getClientTools().isEmpty();
    }

    /** LangChain4j ToolExecutionRequest → AgentMessage.ToolCall */
    private static List<AgentMessage.ToolCall> toAgentToolCalls(List<ToolExecutionRequest> requests) {
        if (requests == null || requests.isEmpty()) return List.of();
        return requests.stream()
                .map(tr -> AgentMessage.ToolCall.builder()
                        .id(tr.id())
                        .type("function")
                        .function(AgentMessage.ToolCall.FunctionCall.builder()
                                .name(tr.name())
                                .arguments(tr.arguments())
                                .build())
                        .build())
                .toList();
    }

    // ==================== 辅助方法 ====================

    /** 解析目标模型，返回 ModelInfo */
    private ModelConfig.ModelInfo resolveModelForStream(AgentExecutionContext ctx) {
        // 已经通过 ApiKeyAuthClient 预设了模型信息
        if (ctx.getBaseUrl() != null && ctx.getApiKey() != null) {
            ModelConfig.ModelInfo info = new ModelConfig.ModelInfo();
            info.setBaseUrl(ctx.getBaseUrl());
            info.setModelName(ctx.getActualModelName());
            info.setKeys(List.of(ctx.getApiKey()));
            info.setName(ctx.getActualModelName() != null ? ctx.getActualModelName() : "custom");
            info.setTemperature(ctx.getEffectiveTemperature());
            info.setMaxTokens(4096);
            return info;
        }
        // 从 DB（biz-service）拉取启用且带 key 的模型，按优先级取第一个
        ModelConfig.ModelInfo info = modelConfigClient.fetch().stream()
                .filter(m -> m.getStatus() == 1)
                .filter(ModelConfig.ModelInfo::hasKeys)
                .min(Comparator.comparingInt(ModelConfig.ModelInfo::getPriority))
                .orElseThrow(() -> new RuntimeException("没有可用的 AI 模型"));
        ctx.setBaseUrl(info.getBaseUrl());
        ctx.setApiKey(info.getKeys().get(0));
        ctx.setActualModelName(info.getModelName() != null ? info.getModelName() : info.getName());
        return info;
    }

    /** 获取 Agent 绑定的工具（dispatch_agent 的描述动态注入子 Agent 名册） */
    private List<ToolDefinition> getTools(AgentExecutionContext ctx) {
        // OpenAI 兼容 /v1 透传模式：调用方（如 Claude Code）自带工具集，平台不注入内部工具
        if (isClientToolMode(ctx)) {
            return ctx.getClientTools();
        }

        List<ToolDefinition> tools;
        if (ctx.getAgentId() != null) {
            tools = toolRegistry.getToolsForAgent(ctx.getAgentId(), ctx.getCallerRole());
        } else {
            tools = toolRegistry.getToolsByNames(
                    toolRegistry.getAll().stream().map(ToolDefinition::getName).collect(Collectors.toList()),
                    ctx.getCallerRole());
        }
        agentCatalog.enrichDispatchTool(tools);
        return tools;
    }

    /**
     * 把子 Agent 消耗的 token 归集进父结果（取走即清零，不会重复累计）
     * <p>计费口径：call_log 只记父调用一条（含子 Agent 全部消耗），审计日志各自独立
     */
    private void mergeChildTokens(AgentExecutionContext ctx, AgentResult result) {
        long[] child = ctx.takeChildTokens();
        if (child[0] > 0 || child[1] > 0) {
            result.addUsage((int) child[0], (int) child[1]);
        }
    }

    /** 构建 messages 列表 */
    private List<AgentMessage> buildMessages(AgentExecutionContext ctx, List<ToolDefinition> tools) {
        List<AgentMessage> messages = new ArrayList<>();

        String systemPrompt = buildSystemPrompt(ctx, tools);
        messages.add(AgentMessage.system(systemPrompt));

        if (ctx.getHistory() != null && !ctx.getHistory().isEmpty()) {
            messages.addAll(ctx.getHistory());
        }

        if (ctx.getUserMessage() != null) {
            messages.add(AgentMessage.user(ctx.getUserMessage()));
        }
        return messages;
    }

    /** 构建系统提示词 */
    private String buildSystemPrompt(AgentExecutionContext ctx, List<ToolDefinition> tools) {
        StringBuilder sb = new StringBuilder();
        if (ctx.getSystemPrompt() != null && !ctx.getSystemPrompt().isBlank()) {
            sb.append(ctx.getSystemPrompt()).append("\n\n");
        }
        sb.append("当前日期: ").append(java.time.LocalDate.now()).append("\n");
        sb.append("用户角色: ").append(ctx.getCallerRole() != null ? ctx.getCallerRole() : "user").append("\n");

        if (!tools.isEmpty() && !isClientToolMode(ctx)) {
            sb.append("\n你可以使用以下工具来完成任务。\n");
            sb.append("重要规则：\n");
            sb.append("1. 当需要查询数据时，调用相应工具获取信息\n");
            sb.append("2. 每次工具调用后，仔细分析结果再决定下一步\n");
            sb.append("3. 基于工具返回的数据给出准确回答，不要编造数据\n");
            sb.append("4. 如果工具返回错误，如实告知用户并尝试其他方式\n");
        }
        return sb.toString();
    }

    // ==================== 流式结果记录 ====================

    private record StreamResult(String content, Throwable error,
                                 List<AgentMessage.ToolCall> toolCalls,
                                 int inputTokens, int outputTokens) {
        StreamResult {
            if (toolCalls == null) toolCalls = List.of();
        }
    }

    /** 结束执行：先报告累计 token 用量，再回调最终答案 */
    private void finish(AgentStreamCallback callback, AgentResult result, String text) {
        if (callback != null) {
            callback.onUsage(result.getInputTokens(), result.getOutputTokens());
            callback.onDone(text);
        }
    }
}