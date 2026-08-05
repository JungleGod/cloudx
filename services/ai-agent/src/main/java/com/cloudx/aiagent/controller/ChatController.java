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
    public R<Map<String, Object>> chat(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        if (message.isBlank()) {
            return R.fail("消息不能为空");
        }
        String taskType = body.getOrDefault("taskType", null);

        RouteResult result = chatService.chat(message, taskType);

        // 返回 AI 回复 + 路由元信息，能看出背后发生了什么
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reply", result.reply());
        data.put("model", result.model());          // 哪个模型处理的
        data.put("strategy", result.strategy());    // 路由策略：keyword / taskType / default / failover
        data.put("failover", result.failover());    // 是否触发了故障转移
        return R.ok(data);
    }

    @GetMapping("/models")
    public R<Map<String, String>> models() {
        return R.ok(chatService.modelStatus());
    }
}
