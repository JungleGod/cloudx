package com.cloudx.biz.controller;

import com.cloudx.biz.dto.ConversationVO;
import com.cloudx.biz.entity.ConversationMessage;
import com.cloudx.biz.service.ConversationService;
import com.cloudx.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 会话管理接口
 * Gateway AuthFilter 已校验 JWT 并透传 X-User-Id 请求头
 */
@Slf4j
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    /** 获取当前用户的会话列表 */
    @GetMapping
    public R<List<ConversationVO>> list(@RequestHeader("X-User-Id") Long userId) {
        return R.ok(conversationService.listByUser(userId));
    }

    /** 创建新会话 */
    @PostMapping
    public R<ConversationVO> create(@RequestHeader("X-User-Id") Long userId,
                                    @RequestBody Map<String, Object> body) {
        String title = body.getOrDefault("title", "新对话").toString();
        String model = body.get("model") != null ? body.get("model").toString() : null;
        Long agentId = body.get("agentId") != null ? Long.valueOf(body.get("agentId").toString()) : null;
        return R.ok(conversationService.create(userId, title, model, agentId));
    }

    /** 更新会话（标题） */
    @PutMapping("/{id}")
    public R<Void> update(@RequestHeader("X-User-Id") Long userId,
                          @PathVariable Long id,
                          @RequestBody Map<String, String> body) {
        String title = body.get("title");
        if (title != null && !title.isBlank()) {
            conversationService.updateTitle(userId, id, title);
        }
        return R.ok();
    }

    /** 删除会话 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@RequestHeader("X-User-Id") Long userId,
                          @PathVariable Long id) {
        conversationService.delete(userId, id);
        return R.ok();
    }

    /** 获取会话的所有消息 */
    @GetMapping("/{id}/messages")
    public R<List<ConversationMessage>> getMessages(@RequestHeader("X-User-Id") Long userId,
                                                     @PathVariable Long id) {
        return R.ok(conversationService.getMessages(userId, id));
    }

    /** 追加一条消息 */
    @PostMapping("/{id}/messages")
    public R<Void> appendMessage(@RequestHeader("X-User-Id") Long userId,
                                  @PathVariable Long id,
                                  @RequestBody ConversationMessage msg) {
        conversationService.appendMessage(userId, id, msg);
        return R.ok();
    }

    /** 清空会话消息 */
    @DeleteMapping("/{id}/messages")
    public R<Void> clearMessages(@RequestHeader("X-User-Id") Long userId,
                                  @PathVariable Long id) {
        conversationService.clearMessages(userId, id);
        return R.ok();
    }
}
