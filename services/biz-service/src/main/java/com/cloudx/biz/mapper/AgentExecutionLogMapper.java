package com.cloudx.biz.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudx.biz.entity.AgentExecutionLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

@Mapper
public interface AgentExecutionLogMapper extends BaseMapper<AgentExecutionLog> {

    /**
     * 按执行会话聚合分页：一次 Agent 执行（含多轮 LLM/工具调用）聚成一行。
     * GROUP BY + 聚合列只能手写 SQL；条件/分组/排序经 QueryWrapper 拼接，
     * 分页交给 MyBatis-Plus 拦截器（含 GROUP BY 时自动包一层子查询计数）。
     */
    @Select("SELECT session_id, MAX(agent_id) AS agent_id, MAX(user_id) AS user_id, " +
            "COUNT(*) AS steps, SUM(tokens_input) AS tokens_input, SUM(tokens_output) AS tokens_output, " +
            "SUM(status = 'error') AS error_steps, " +
            "MIN(created_at) AS started_at, MAX(created_at) AS ended_at " +
            "FROM agent_execution_log ${ew.customSqlSegment}")
    IPage<Map<String, Object>> selectSessionPage(Page<AgentExecutionLog> page,
                                                 @Param(Constants.WRAPPER) Wrapper<AgentExecutionLog> ew);
}
