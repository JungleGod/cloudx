package com.cloudx.aiagent.controller;

import com.cloudx.aiagent.provider.StreamCallback;
import com.cloudx.aiagent.routing.ModelRouter.RouteResult;
import com.cloudx.aiagent.service.ChatService;
import com.cloudx.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @SuppressWarnings("unchecked")
    @PostMapping("/chat")
    public R<Map<String, Object>> chat(@RequestBody Map<String, Object> body,
                                        @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        String message = (String) body.getOrDefault("message", "");
        if (message.isBlank()) {
            return R.fail("消息不能为空");
        }
        String model = (String) body.getOrDefault("model", null);
        String taskType = (String) body.getOrDefault("taskType", null);

        // 解析历史消息列表
        List<Map<String, String>> history = parseHistory(body);

        RouteResult result = chatService.chat(message, history, model, taskType, userId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reply", result.reply());
        data.put("model", result.model());
        data.put("strategy", result.strategy());
        data.put("failover", result.failover());
        return R.ok(data);
    }

    /** 流式对话 — SSE 打字机效果 */
    @PostMapping("/chat/stream")
    public SseEmitter chatStream(@RequestBody Map<String, Object> body,
                                  @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        String message = (String) body.getOrDefault("message", "");
        if (message == null || message.isBlank()) {
            SseEmitter errorEmitter = new SseEmitter();
            errorEmitter.completeWithError(new IllegalArgumentException("消息不能为空"));
            return errorEmitter;
        }
        String model = (String) body.getOrDefault("model", null);
        String taskType = (String) body.getOrDefault("taskType", null);
        List<Map<String, String>> history = parseHistory(body);

        // 超时 5 分钟
        SseEmitter emitter = new SseEmitter(300_000L);
        StringBuilder fullReply = new StringBuilder();

        StreamCallback callback = new StreamCallback() {
            @Override
            public void onToken(String token) {
                try {
                    fullReply.append(token);
                    emitter.send(SseEmitter.event().name("token").data(token));
                } catch (IOException e) {
                    log.warn("SSE send failed, client may have disconnected: {}", e.getMessage());
                }
            }

            @Override
            public void onComplete() {
                try {
                    emitter.send(SseEmitter.event().name("done")
                            .data(Map.of("reply", fullReply.toString())));
                    emitter.complete();
                } catch (IOException e) {
                    log.warn("Failed to send done event: {}", e.getMessage());
                    emitter.completeWithError(e);
                }
            }

            @Override
            public void onError(Throwable error) {
                log.error("Stream chat error: {}", error.getMessage());
                try {
                    emitter.send(SseEmitter.event().name("error").data(error.getMessage()));
                } catch (IOException e) {
                    // ignore
                }
                emitter.completeWithError(error);
            }
        };

        // 异步执行，不阻塞 Tomcat 线程
        // 注意：chatStream 内部 generate() 是非阻塞的，真正的完成/错误在 callback 里处理
        new Thread(() -> chatService.chatStream(message, history, model, taskType, userId, callback)).start();

        return emitter;
    }

    /** 多模态对话 — 支持图片上传 */
    @SuppressWarnings("unchecked")
    @PostMapping("/chat/multimodal")
    public R<Map<String, Object>> chatMultimodal(@RequestBody Map<String, Object> body,
                                                  @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        String message = (String) body.getOrDefault("message", "");
        if (message.isBlank()) {
            return R.fail("消息不能为空");
        }
        String model = (String) body.getOrDefault("model", null);
        String taskType = (String) body.getOrDefault("taskType", null);

        // 解析图片列表（base64 data URLs）
        List<String> images = new ArrayList<>();
        Object imagesObj = body.get("images");
        if (imagesObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) {
                    images.add(s);
                }
            }
        }
        if (images.isEmpty()) {
            return R.fail("请上传至少一张图片");
        }

        RouteResult result = chatService.chatMultimodal(message, images, model, taskType, userId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reply", result.reply());
        data.put("model", result.model());
        data.put("strategy", result.strategy());
        data.put("failover", result.failover());
        return R.ok(data);
    }

    @GetMapping("/models")
    public R<List<Map<String, Object>>> models() {
        return R.ok(chatService.modelStatus());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseHistory(Map<String, Object> body) {
        Object historyObj = body.get("history");
        if (historyObj instanceof List<?> list && !list.isEmpty()) {
            List<Map<String, String>> history = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, String> msg = new LinkedHashMap<>();
                    Object r = m.get("role");
                    Object c = m.get("content");
                    Object md = m.get("model");
                    msg.put("role", r != null ? r.toString() : "");
                    msg.put("content", c != null ? c.toString() : "");
                    if (md != null && !md.toString().isBlank()) {
                        msg.put("model", md.toString());
                    }
                    history.add(msg);
                }
            }
            return history;
        }
        return null;
    }
}