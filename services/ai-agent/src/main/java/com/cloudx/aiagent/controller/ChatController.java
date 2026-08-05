package com.cloudx.aiagent.controller;

import com.cloudx.aiagent.service.ChatService;
import com.cloudx.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/chat")
    public R<Map<String, String>> chat(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        if (message.isBlank()) {
            return R.fail("消息不能为空");
        }
        String reply = chatService.chat(message);
        return R.ok(Map.of("reply", reply));
    }
}
