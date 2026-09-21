package com.cloudx.aiagent.controller;

import com.cloudx.aiagent.adapter.AnthropicAdapter;
import com.cloudx.aiagent.adapter.AnthropicAdapter.InternalRequest;
import com.cloudx.aiagent.adapter.OpenAiAdapter;
import com.cloudx.aiagent.agent.AgentExecutionContext;
import com.cloudx.aiagent.agent.AgentMessage;
import com.cloudx.aiagent.agent.AgentResult;
import com.cloudx.aiagent.agent.AgentStreamCallback;
import com.cloudx.aiagent.dto.AnthropicDTOs;
import com.cloudx.aiagent.dto.OpenAiDTOs;
import com.cloudx.aiagent.dto.AnthropicDTOs.MessagesRequest;
import com.cloudx.aiagent.dto.OpenAiDTOs.ChatCompletionRequest;
import com.cloudx.aiagent.dto.OpenAiDTOs.ChatCompletionResponse;
import com.cloudx.aiagent.dto.OpenAiDTOs.ChatCompletionChunk;
import com.cloudx.aiagent.dto.OpenAiDTOs.ModelInfo;
import com.cloudx.aiagent.dto.OpenAiDTOs.ModelListResponse;
import com.cloudx.aiagent.dto.OpenAiDTOs.ErrorResponse;
import com.cloudx.aiagent.dto.OpenAiDTOs.Message;
import com.cloudx.aiagent.dto.OpenAiDTOs.ToolDef;
import com.cloudx.aiagent.dto.OpenAiDTOs.ToolCallDelta;
import com.cloudx.aiagent.dto.OpenAiDTOs.ToolCallFunctionDelta;
import com.cloudx.aiagent.provider.StreamCallback;
import com.cloudx.aiagent.routing.ModelRouter.RouteResult;
import com.cloudx.aiagent.service.AgentService;
import com.cloudx.aiagent.service.ApiKeyAuthClient;
import com.cloudx.aiagent.service.ApiKeyAuthClient.AuthResult;
import com.cloudx.aiagent.service.ChatService;
import com.cloudx.aiagent.service.QuotaClient;
import com.cloudx.common.exception.QuotaExceededException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 兼容 API 入口：OpenAI + Anthropic
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class OpenAiCompatibleController {

    private final ApiKeyAuthClient apiKeyAuthClient;
    private final OpenAiAdapter openAiAdapter;
    private final AnthropicAdapter anthropicAdapter;
    private final ChatService chatService;
    private final AgentService agentService;
    private final QuotaClient quotaClient;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        log.info("Compatible API ready: /v1/chat/completions, /v1/messages, /v1/models");
    }

    // ==================== 认证（兼容多种 Header） ====================

    /**
     * 从 x-api-key 或 Authorization 头提取 token 并验证
     */
    private AuthResult authenticate(
            @RequestHeader(value = "x-api-key", required = false) String apiKey,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        // 优先 x-api-key（Anthropic 标准），其次 Authorization（OpenAI 标准）
        String token = (apiKey != null && !apiKey.isBlank()) ? apiKey : authHeader;
        return apiKeyAuthClient.verify(token);
    }

    // ==================== /v1/chat/completions (OpenAI) ====================

    @PostMapping("/v1/chat/completions")
    public Object chatCompletions(
            @RequestHeader(value = "x-api-key", required = false) String apiKey,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody ChatCompletionRequest request) {

        AuthResult auth = authenticate(apiKey, authHeader);
        if (auth == null) {
            return openAiError(401, "invalid_request_error", "Invalid API Key");
        }

        // 月度额度校验（API Key 调用同样受限于用户额度）
        try {
            quotaClient.checkQuota(auth.userId());
        } catch (QuotaExceededException e) {
            return openAiError(429, "insufficient_quota", e.getMessage());
        }

        // 检测是否带 tools → Agent 模式
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            return handleAgentRequest(request, auth);
        }

        OpenAiAdapter.InternalRequest internal;
        try {
            internal = openAiAdapter.toInternal(request);
        } catch (IllegalArgumentException e) {
            return openAiError(400, "invalid_request_error", e.getMessage());
        }

        // 确定展示给客户端的模型名：传 "auto"/null 就显示 "auto"，防止客户端钉死实际模型
        String displayModel = (request.getModel() == null || "auto".equalsIgnoreCase(request.getModel()))
                ? "auto" : request.getModel();

        if (internal.stream()) {
            return openAiStream(internal, auth, displayModel);
        }
        return openAiSync(internal, auth);
    }

    // ==================== Agent 模式处理 ====================

    /** 带 tools 的请求走 Agent 循环 */
    private Object handleAgentRequest(ChatCompletionRequest request, AuthResult auth) {
        // 构建 Agent 执行上下文
        AgentExecutionContext ctx = openAiAdapter.toAgentContext(request, auth);

        boolean stream = request.getStream() != null && request.getStream();
        String displayModel = (request.getModel() == null || "auto".equalsIgnoreCase(request.getModel()))
                ? "auto" : request.getModel();

        if (stream) {
            return agentStream(ctx, auth, displayModel, request.getMessages());
        }
        return agentSync(ctx, auth, displayModel);
    }

    /** Agent 同步执行 → OpenAI 格式响应 */
    private ResponseEntity<?> agentSync(AgentExecutionContext ctx, AuthResult auth, String displayModel) {
        String requestId = "chatcmpl-" + UUID.randomUUID().toString().substring(0, 24);
        AgentResult result = agentService.execute(ctx);

        return ResponseEntity.ok()
                .header("X-Routed-Model", displayModel)
                .body(openAiAdapter.toAgentResponse(result, requestId, displayModel));
    }

    /** Agent 流式执行 → SSE */
    private SseEmitter agentStream(AgentExecutionContext ctx, AuthResult auth,
                                    String displayModel, List<Message> requestMessages) {
        String requestId = "chatcmpl-" + UUID.randomUUID().toString().substring(0, 24);
        SseEmitter emitter = new SseEmitter(300_000L);

        AgentStreamCallback callback = new AgentStreamCallback() {
            private final long created = System.currentTimeMillis() / 1000;
            private boolean firstToken = true;
            private final StringBuilder fullContent = new StringBuilder();
            // 累积 tool_call delta 状态
            private final Map<Integer, String> toolCallIds = new LinkedHashMap<>();
            private final Map<Integer, String> toolCallNames = new LinkedHashMap<>();
            private final Map<Integer, StringBuilder> toolCallArgs = new LinkedHashMap<>();

            @Override
            public void onThinking() { /* SSE 客户端不需要额外事件 */ }

            @Override
            public void onToolCallStart(String toolName, String callId) {
                // 记录 tool_call 信息，待 delta 时发送
            }

            @Override
            public void onToolCallArgs(String callId, String delta) {
                // 找到对应的 tool_call index
                int idx = -1;
                for (Map.Entry<Integer, String> e : toolCallIds.entrySet()) {
                    if (callId.equals(e.getValue())) { idx = e.getKey(); break; }
                }
                if (idx < 0) {
                    idx = toolCallIds.size();
                    toolCallIds.put(idx, callId);
                }
                toolCallArgs.computeIfAbsent(idx, k -> new StringBuilder()).append(delta);
            }

            @Override
            public void onToolCallExecuting(String toolName, String arguments) {
                // 同步所有已知 tool_call 信息
                for (int idx : toolCallIds.keySet()) {
                    toolCallNames.putIfAbsent(idx, toolName);
                }
            }

            @Override
            public void onToolResult(String toolName, String result, boolean success, long elapsedMs) {
                // 工具结果不在 SSE chunk 中发送（客户端从 toolSteps 获取）
            }

            @Override
            public void onToken(String token) {
                fullContent.append(token);
                try {
                    Map<String, Object> chunk = buildAgentChunk(requestId, displayModel, token,
                            false, null, null);
                    emitter.send(SseEmitter.event().data(
                            objectMapper.writeValueAsString(chunk), MediaType.APPLICATION_JSON));
                    firstToken = false;
                } catch (IOException e) { log.debug("SSE send failed"); }
            }

            @Override
            public void onDone(String fullReply) {
                try {
                    if (!toolCallIds.isEmpty()) {
                        // 客户端工具模式：tool_calls 是本轮回调的最终产出，finish_reason 必须是
                        // tool_calls（客户端执行后发起下一轮），不能追加 stop chunk 覆盖语义
                        List<Map<String, Object>> toolCalls = new ArrayList<>();
                        for (int idx : toolCallIds.keySet()) {
                            Map<String, Object> fn = new LinkedHashMap<>();
                            fn.put("name", toolCallNames.getOrDefault(idx, ""));
                            fn.put("arguments", toolCallArgs.containsKey(idx)
                                    ? toolCallArgs.get(idx).toString() : "");
                            Map<String, Object> tc = new LinkedHashMap<>();
                            tc.put("id", toolCallIds.get(idx));
                            tc.put("type", "function");
                            tc.put("function", fn);
                            toolCalls.add(tc);
                        }
                        Map<String, Object> chunk = buildAgentChunk(requestId, displayModel, null,
                                true, "tool_calls", toolCalls);
                        emitter.send(SseEmitter.event().data(
                                objectMapper.writeValueAsString(chunk), MediaType.APPLICATION_JSON));
                    } else {
                        // 发送最终 stop chunk
                        Map<String, Object> finalChunk = buildAgentChunk(requestId, displayModel, null,
                                true, "stop", null);
                        emitter.send(SseEmitter.event().data(
                                objectMapper.writeValueAsString(finalChunk), MediaType.APPLICATION_JSON));
                    }
                    emitter.send(SseEmitter.event().data("[DONE]"));
                    emitter.complete();
                } catch (IOException e) { emitter.completeWithError(e); }
            }

            @Override
            public void onError(Throwable error) {
                log.error("Agent stream error: {}", error.getMessage());
                emitter.completeWithError(error);
            }
        };

        new Thread(() -> agentService.executeStream(ctx, callback)).start();
        return emitter;
    }

    /** 构建 Agent 模式的流式 chunk */
    private Map<String, Object> buildAgentChunk(String requestId, String model, String content,
                                                  boolean isLast, String finishReason,
                                                  List<Map<String, Object>> toolCalls) {
        long created = System.currentTimeMillis() / 1000;
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", requestId);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", created);
        chunk.put("model", model);

        Map<String, Object> delta = new LinkedHashMap<>();
        if (content != null) delta.put("content", content);
        if (toolCalls != null) delta.put("tool_calls", toolCalls);

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        if (finishReason != null) choice.put("finish_reason", finishReason);

        chunk.put("choices", List.of(choice));
        return chunk;
    }

    private ResponseEntity<?> openAiSync(OpenAiAdapter.InternalRequest internal, AuthResult auth) {
        String requestId = "chatcmpl-" + UUID.randomUUID().toString().substring(0, 24);
        String fullPrompt = buildFullPrompt(internal.systemPrompt(), internal.userMessage());

        RouteResult result = chatService.chat(
                fullPrompt, internal.history(), internal.model(), null, auth.userId());

        log.info("OpenAI request routed to [{}] via {}", result.model(), result.strategy());
        return ResponseEntity.ok()
                .header("X-Routed-Model", result.model())
                .body(openAiAdapter.toResponseWithPrompt(result, requestId, fullPrompt, result.model()));
    }

    private SseEmitter openAiStream(OpenAiAdapter.InternalRequest internal, AuthResult auth, String displayModel) {
        String requestId = "chatcmpl-" + UUID.randomUUID().toString().substring(0, 24);
        SseEmitter emitter = new SseEmitter(300_000L);
        String fullPrompt = buildFullPrompt(internal.systemPrompt(), internal.userMessage());

        StreamCallback callback = new StreamCallback() {
            private boolean firstToken = true;
            @Override
            public void onToken(String token) {
                try {
                    emitter.send(SseEmitter.event().data(
                            openAiAdapter.toChunkJson(token, requestId, displayModel, firstToken, false),
                            MediaType.APPLICATION_JSON));
                    firstToken = false;
                } catch (IOException e) { log.debug("SSE send failed"); }
            }
            @Override
            public void onComplete(int inputTokens, int outputTokens) {
                try {
                    emitter.send(SseEmitter.event().data(
                            openAiAdapter.toChunkJson(null, requestId, displayModel, false, true),
                            MediaType.APPLICATION_JSON));
                    emitter.send(SseEmitter.event().data("[DONE]"));
                    emitter.complete();
                } catch (IOException e) { emitter.completeWithError(e); }
            }
            @Override
            public void onError(Throwable error) {
                log.error("Stream error: {}", error.getMessage());
                emitter.completeWithError(error);
            }
        };

        new Thread(() -> chatService.chatStream(
                fullPrompt, internal.history(), internal.model(), null, auth.userId(), callback)).start();

        return emitter;
    }

    // ==================== /v1/messages (Anthropic) ====================

    @PostMapping("/v1/messages")
    public Object messages(
            @RequestHeader(value = "x-api-key", required = false) String apiKey,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody MessagesRequest request) {

        AuthResult auth = authenticate(apiKey, authHeader);
        if (auth == null) {
            return anthropicError(401, "authentication_error", "Invalid API Key");
        }

        // 月度额度校验
        try {
            quotaClient.checkQuota(auth.userId());
        } catch (QuotaExceededException e) {
            return anthropicError(429, "rate_limit_error", e.getMessage());
        }

        InternalRequest internal;
        try {
            // 带 tools → Agent「客户端执行」协议（Claude Code 等 Anthropic 客户端自己执行工具）
            if (request.getTools() != null && !request.getTools().isEmpty()) {
                return handleAnthropicAgentRequest(request, auth);
            }
            internal = anthropicAdapter.toInternal(request);
        } catch (IllegalArgumentException e) {
            return anthropicError(400, "invalid_request_error", e.getMessage());
        }

        // 确定展示给客户端的模型名：传 "auto"/null 就显示 "auto"，防止客户端钉死实际模型
        String displayModel = (request.getModel() == null || "auto".equalsIgnoreCase(request.getModel()))
                ? "auto" : request.getModel();

        if (internal.stream()) {
            return anthropicStream(internal, auth, displayModel);
        }
        return anthropicSync(internal, auth);
    }

    private ResponseEntity<?> anthropicSync(InternalRequest internal, AuthResult auth) {
        String requestId = "msg_" + UUID.randomUUID().toString().substring(0, 24);
        String fullPrompt = buildFullPrompt(internal.systemPrompt(), internal.userMessage());

        RouteResult result = chatService.chat(
                fullPrompt, internal.history(), internal.model(), null, auth.userId());

        log.info("Anthropic request routed to [{}] via {}", result.model(), result.strategy());
        return ResponseEntity.ok()
                .header("X-Routed-Model", result.model())
                .body(anthropicAdapter.toResponse(result, requestId, fullPrompt, result.model()));
    }

    private SseEmitter anthropicStream(InternalRequest internal, AuthResult auth, String displayModel) {
        String requestId = "msg_" + UUID.randomUUID().toString().substring(0, 24);
        SseEmitter emitter = new SseEmitter(300_000L);
        String fullPrompt = buildFullPrompt(internal.systemPrompt(), internal.userMessage());

        // 流式序列：message_start → content_block_start → delta × N
        //          → content_block_stop → message_delta → message_stop
        StreamCallback callback = new StreamCallback() {
            private boolean started = false;
            @Override
            public void onToken(String token) {
                try {
                    if (!started) {
                        emitter.send(SseEmitter.event()
                                .data(anthropicAdapter.sseMessageStart(requestId, displayModel), MediaType.APPLICATION_JSON));
                        emitter.send(SseEmitter.event()
                                .data(anthropicAdapter.sseContentBlockStart(0), MediaType.APPLICATION_JSON));
                        started = true;
                    }
                    emitter.send(SseEmitter.event()
                            .data(anthropicAdapter.sseContentBlockDelta(0, token), MediaType.APPLICATION_JSON));
                } catch (IOException e) { log.debug("SSE send failed"); }
            }
            @Override
            public void onComplete(int inputTokens, int outputTokens) {
                try {
                    emitter.send(SseEmitter.event()
                            .data(anthropicAdapter.sseContentBlockStop(0), MediaType.APPLICATION_JSON));
                    emitter.send(SseEmitter.event()
                            .data(anthropicAdapter.sseMessageDelta(1), MediaType.APPLICATION_JSON));
                    emitter.send(SseEmitter.event()
                            .data(anthropicAdapter.sseMessageStop(), MediaType.APPLICATION_JSON));
                    emitter.complete();
                } catch (IOException e) { emitter.completeWithError(e); }
            }
            @Override
            public void onError(Throwable error) {
                log.error("Anthropic stream error: {}", error.getMessage());
                try {
                    emitter.send(SseEmitter.event().data(
                            anthropicAdapter.toJson(anthropicAdapter.toError("server_error", error.getMessage())),
                            MediaType.APPLICATION_JSON));
                } catch (IOException ignored) {}
                emitter.completeWithError(error);
            }
        };

        new Thread(() -> chatService.chatStream(
                fullPrompt, internal.history(), internal.model(), null, auth.userId(), callback)).start();

        return emitter;
    }

    // ==================== /v1/messages Agent 模式（客户端执行协议） ====================

    /** 带 tools 的 Anthropic 请求走 Agent 循环（clientTools 透传，平台不代执行） */
    private Object handleAnthropicAgentRequest(MessagesRequest request, AuthResult auth) {
        AgentExecutionContext ctx;
        try {
            ctx = anthropicAdapter.toAgentContext(request, auth);
        } catch (IllegalArgumentException e) {
            return anthropicError(400, "invalid_request_error", e.getMessage());
        }

        boolean stream = request.getStream() != null && request.getStream();
        String displayModel = (request.getModel() == null || "auto".equalsIgnoreCase(request.getModel()))
                ? "auto" : request.getModel();

        if (stream) {
            return agentAnthropicStream(ctx, auth, displayModel);
        }
        return agentAnthropicSync(ctx, auth, displayModel);
    }

    /** Agent 同步执行 → Anthropic MessagesResponse（tool_use 块） */
    private ResponseEntity<?> agentAnthropicSync(AgentExecutionContext ctx, AuthResult auth, String displayModel) {
        String requestId = "msg_" + UUID.randomUUID().toString().substring(0, 24);
        AgentResult result = agentService.execute(ctx);

        return ResponseEntity.ok()
                .header("X-Routed-Model", displayModel)
                .body(anthropicAdapter.toAgentResponse(result, requestId, displayModel));
    }

    /**
     * Agent 流式执行 → Anthropic SSE
     * <p>
     * 事件序列：message_start → [content_block_start(text) → text_delta × N → content_block_stop]
     *          → [content_block_start(tool_use) → input_json_delta × N → content_block_stop] × M
     *          → message_delta(stop_reason=tool_use|end_turn) → message_stop
     */
    private SseEmitter agentAnthropicStream(AgentExecutionContext ctx, AuthResult auth, String displayModel) {
        String requestId = "msg_" + UUID.randomUUID().toString().substring(0, 24);
        SseEmitter emitter = new SseEmitter(300_000L);

        AgentStreamCallback callback = new AgentStreamCallback() {
            private boolean messageStarted = false;
            private int nextBlockIndex = 0;       // 下一个 content block 的 index
            private int openBlockIndex = -1;      // 当前未关闭的 block
            private boolean textBlockOpen = false;
            private final StringBuilder fullContent = new StringBuilder();
            private int outputTokens = 0;
            private boolean hasToolUse = false;

            private void send(String json) {
                try {
                    emitter.send(SseEmitter.event().data(json, MediaType.APPLICATION_JSON));
                } catch (IOException e) { log.debug("SSE send failed"); }
            }

            private void ensureStarted() {
                if (!messageStarted) {
                    send(anthropicAdapter.sseMessageStart(requestId, displayModel));
                    messageStarted = true;
                }
            }

            private void closeOpenBlock() {
                if (openBlockIndex >= 0) {
                    send(anthropicAdapter.sseContentBlockStop(openBlockIndex));
                    openBlockIndex = -1;
                }
            }

            @Override
            public void onToken(String token) {
                fullContent.append(token);
                ensureStarted();
                if (!textBlockOpen) {
                    closeOpenBlock();
                    openBlockIndex = nextBlockIndex++;
                    send(anthropicAdapter.sseContentBlockStart(openBlockIndex));
                    textBlockOpen = true;
                }
                send(anthropicAdapter.sseContentBlockDelta(openBlockIndex, token));
            }

            @Override
            public void onToolCallStart(String toolName, String callId) {
                hasToolUse = true;
                ensureStarted();
                closeOpenBlock();   // 文本块先关闭，再开 tool_use 块
                textBlockOpen = false;
                openBlockIndex = nextBlockIndex++;
                send(anthropicAdapter.sseToolUseBlockStart(openBlockIndex, callId, toolName));
            }

            @Override
            public void onToolCallArgs(String callId, String delta) {
                send(anthropicAdapter.sseInputJsonDelta(openBlockIndex, delta));
            }

            @Override
            public void onToolCallExecuting(String toolName, String arguments) {
                // 客户端执行协议：参数已随 onToolCallArgs 流式发出，平台不执行工具，无额外事件
            }

            @Override
            public void onUsage(int inputTokens, int output) {
                outputTokens = output;
            }

            @Override
            public void onDone(String fullReply) {
                ensureStarted();
                closeOpenBlock();
                send(anthropicAdapter.sseMessageDelta(
                        hasToolUse ? "tool_use" : "end_turn",
                        Math.max(1, outputTokens > 0 ? outputTokens : fullContent.length() / 2)));
                send(anthropicAdapter.sseMessageStop());
                emitter.complete();
            }

            @Override
            public void onError(Throwable error) {
                log.error("Anthropic agent stream error: {}", error.getMessage());
                emitter.completeWithError(error);
            }
        };

        new Thread(() -> agentService.executeStream(ctx, callback)).start();
        return emitter;
    }

    // ==================== /v1/models ====================

    @GetMapping("/v1/models")
    public ResponseEntity<?> listModels(
            @RequestHeader(value = "x-api-key", required = false) String apiKey,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        AuthResult auth = authenticate(apiKey, authHeader);
        if (auth == null) {
            return openAiError(401, "invalid_request_error", "Invalid API Key");
        }

        List<Map<String, Object>> statusList = chatService.modelStatus();
        long now = System.currentTimeMillis() / 1000;

        List<ModelInfo> models = statusList.stream()
                .filter(m -> "UP".equals(m.get("status")))
                .map(m -> ModelInfo.builder().id((String) m.get("name")).created(now).build())
                .collect(Collectors.toList());

        models.add(0, ModelInfo.builder().id("auto").created(now).build());

        return ResponseEntity.ok(ModelListResponse.builder().data(models).build());
    }

    // ==================== 异常处理 ====================

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleException(Exception e) {
        log.error("API error: {}", e.getMessage());
        return openAiError(500, "server_error", e.getMessage());
    }

    // ==================== 私有工具方法 ====================

    /** 标记：用于路由时从完整 prompt 中提取纯用户消息 */
    static final String USER_MSG_MARKER = "\n【用户消息】\n";

    private String buildFullPrompt(String systemPrompt, String userMessage) {
        StringBuilder sb = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            sb.append("系统指令：").append(systemPrompt).append("\n");
        }
        sb.append(USER_MSG_MARKER).append(userMessage);
        return sb.toString();
    }

    private ResponseEntity<ErrorResponse> openAiError(int status, String type, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(openAiAdapter.toError(message, type, status));
    }

    private ResponseEntity<AnthropicDTOs.ErrorResponse> anthropicError(int status, String type, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(anthropicAdapter.toError(type, message));
    }
}
