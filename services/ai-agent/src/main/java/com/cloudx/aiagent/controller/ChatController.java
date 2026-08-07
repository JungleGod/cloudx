package com.cloudx.aiagent.controller;

import com.cloudx.aiagent.routing.ModelRouter.RouteResult;
import com.cloudx.aiagent.service.ChatService;
import com.cloudx.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

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
        String taskType = (String) body.getOrDefault("taskType", null);

        // 解析历史消息列表
        List<Map<String, String>> history = null;
        Object historyObj = body.get("history");
        if (historyObj instanceof List<?> list && !list.isEmpty()) {
            history = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, String> msg = new LinkedHashMap<>();
                    Object r = m.get("role");
                    Object c = m.get("content");
                    msg.put("role", r != null ? r.toString() : "");
                    msg.put("content", c != null ? c.toString() : "");
                    history.add(msg);
                }
            }
        }

        RouteResult result = chatService.chat(message, history, taskType, userId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reply", result.reply());
        data.put("model", result.model());
        data.put("strategy", result.strategy());
        data.put("failover", result.failover());
        return R.ok(data);
    }

    @GetMapping("/models")
    public R<Map<String, String>> models() {
        return R.ok(chatService.modelStatus());
    }
}
