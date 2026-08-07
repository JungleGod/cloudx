package com.cloudx.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudx.biz.entity.ConversationMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMessageMapper extends BaseMapper<ConversationMessage> {
}
