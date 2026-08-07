package com.cloudx.aiagent.controller;

import com.cloudx.aiagent.routing.ModelRouter.RouteResult;
import com.cloudx.aiagent.service.ChatService;
import com.cloudx.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/chat")
    public R<Map<String, Object>> chat(@RequestBody Map<String, String> body,
                                        @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        String message = body.getOrDefault("message", "");
        if (message.isBlank()) {
            return R.fail("消息不能为空");
        }
        String taskType = body.getOrDefault("taskType", null);

        RouteResult result = chatService.chat(message, taskType, userId);

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
