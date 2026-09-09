package com.cloudx.biz.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudx.biz.entity.AgentExecutionLog;
import com.cloudx.biz.mapper.AgentExecutionLogMapper;
import com.cloudx.biz.service.AgentExecutionLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AgentExecutionLogServiceImpl extends ServiceImpl<AgentExecutionLogMapper, AgentExecutionLog>
        implements AgentExecutionLogService {
}
