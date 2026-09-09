package com.cloudx.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudx.biz.dto.ConversationVO;
import com.cloudx.biz.entity.Conversation;
import com.cloudx.biz.entity.ConversationMessage;
import com.cloudx.biz.mapper.ConversationMapper;
import com.cloudx.biz.mapper.ConversationMessageMapper;
import com.cloudx.biz.service.ConversationService;
import com.cloudx.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationServiceImpl extends ServiceImpl<ConversationMapper, Conversation>
        implements ConversationService {

    private final ConversationMessageMapper messageMapper;

    @Override
    public List<ConversationVO> listByUser(Long userId) {
        List<Conversation> conversations = list(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .orderByDesc(Conversation::getUpdatedAt));

        return conversations.stream().map(conv -> {
            long msgCount = messageMapper.selectCount(new LambdaQueryWrapper<ConversationMessage>()
                    .eq(ConversationMessage::getConversationId, conv.getId()));
            return ConversationVO.builder()
                    .id(conv.getId())
                    .title(conv.getTitle())
                    .model(conv.getModel())
                    .agentId(conv.getAgentId())
                    .messageCount((int) msgCount)
                    .createdAt(conv.getCreatedAt())
                    .updatedAt(conv.getUpdatedAt())
                    .build();
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ConversationVO create(Long userId, String title, String model) {
        return create(userId, title, model, null);
    }

    @Override
    @Transactional
    public ConversationVO create(Long userId, String title, String model, Long agentId) {
        Conversation conv = new Conversation();
        conv.setUserId(userId);
        conv.setTitle(title != null && !title.isBlank() ? title : "新对话");
        conv.setModel(model);
        conv.setAgentId(agentId);
        save(conv);
        return ConversationVO.builder()
                .id(conv.getId())
                .title(conv.getTitle())
                .model(conv.getModel())
                .agentId(conv.getAgentId())
                .messageCount(0)
                .createdAt(conv.getCreatedAt())
                .updatedAt(conv.getUpdatedAt())
                .build();
    }

    @Override
    public void updateTitle(Long userId, Long conversationId, String title) {
        Conversation conv = getAndCheckOwner(userId, conversationId);
        conv.setTitle(title);
        updateById(conv);
    }

    @Override
    public void delete(Long userId, Long conversationId) {
        getAndCheckOwner(userId, conversationId);
        removeById(conversationId); // MyBatis-Plus 逻辑删除
        // 物理删除关联消息（消息表没有逻辑删除字段）
        messageMapper.delete(new LambdaQueryWrapper<ConversationMessage>()
                .eq(ConversationMessage::getConversationId, conversationId));
    }

    @Override
    public List<ConversationMessage> getMessages(Long userId, Long conversationId) {
        getAndCheckOwner(userId, conversationId);
        return messageMapper.selectList(new LambdaQueryWrapper<ConversationMessage>()
                .eq(ConversationMessage::getConversationId, conversationId)
                .orderByAsc(ConversationMessage::getCreatedAt));
    }

    @Override
    public void appendMessage(Long userId, Long conversationId, ConversationMessage message) {
        getAndCheckOwner(userId, conversationId);
        message.setConversationId(conversationId);
        messageMapper.insert(message);
        // 更新会话的 updated_at
        update(new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .set(Conversation::getUpdatedAt, LocalDateTime.now()));
    }

    @Override
    public void clearMessages(Long userId, Long conversationId) {
        getAndCheckOwner(userId, conversationId);
        messageMapper.delete(new LambdaQueryWrapper<ConversationMessage>()
                .eq(ConversationMessage::getConversationId, conversationId));
    }

    /** 获取会话并校验归属 */
    private Conversation getAndCheckOwner(Long userId, Long conversationId) {
        Conversation conv = getById(conversationId);
        if (conv == null) {
            throw new BizException("会话不存在");
        }
        if (!conv.getUserId().equals(userId)) {
            throw new BizException(403, "无权操作该会话");
        }
        return conv;
    }
}
