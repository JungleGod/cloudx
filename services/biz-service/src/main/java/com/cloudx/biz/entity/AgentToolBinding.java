package com.cloudx.biz.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("agent_tool_binding")
public class AgentToolBinding {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long agentId;
    private Long toolId;
}
