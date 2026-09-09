package com.cloudx.biz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cloudx.biz.dto.ConversationVO;
import com.cloudx.biz.entity.Conversation;
import com.cloudx.biz.entity.ConversationMessage;

import java.util.List;

public interface ConversationService extends IService<Conversation> {

    /** 获取用户的会话列表（按更新时间倒序，不含已删除） */
    List<ConversationVO> listByUser(Long userId);

    /** 创建新会话 */
    ConversationVO create(Long userId, String title, String model);

    /** 创建新会话（带 agentId） */
    ConversationVO create(Long userId, String title, String model, Long agentId);

    /** 更新会话标题 */
    void updateTitle(Long userId, Long conversationId, String title);

    /** 软删除会话 */
    void delete(Long userId, Long conversationId);

    /** 获取会话的所有消息（按时间正序） */
    List<ConversationMessage> getMessages(Long userId, Long conversationId);

    /** 追加一条消息到会话，同时更新会话的 updated_at */
    void appendMessage(Long userId, Long conversationId, ConversationMessage message);

    /** 清空会话消息 */
    void clearMessages(Long userId, Long conversationId);
}
