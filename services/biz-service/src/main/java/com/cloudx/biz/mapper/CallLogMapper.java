package com.cloudx.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudx.biz.entity.CallLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface CallLogMapper extends BaseMapper<CallLog> {

    /** 统计每个用户本月成功调用的累计费用（元） */
    @Select("SELECT user_id, COALESCE(SUM(cost), 0) AS used " +
            "FROM call_log " +
            "WHERE status = 'success' AND created_at >= #{start} " +
            "GROUP BY user_id")
    List<Map<String, Object>> sumCostByUserSince(@Param("start") LocalDateTime start);

    // ==================== 管理员账单聚合（GROUP BY 下推 DB，月度数据不拉全表进内存） ====================

    /** 月度概览：总调用/成功调用/token/费用（失败调用计次数，费用只来自成功调用） */
    @Select("SELECT COUNT(*) AS calls, COALESCE(SUM(status = 'success'), 0) AS success_calls, " +
            "COALESCE(SUM(tokens_input), 0) AS tokens_input, COALESCE(SUM(tokens_output), 0) AS tokens_output, " +
            "COALESCE(SUM(cost), 0) AS cost " +
            "FROM call_log WHERE created_at >= #{start} AND created_at < #{end}")
    Map<String, Object> sumSummaryBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** 按模型汇总（仅成功调用，失败不产生费用） */
    @Select("SELECT model, COUNT(*) AS calls, SUM(tokens_total) AS tokens_total, COALESCE(SUM(cost), 0) AS cost " +
            "FROM call_log WHERE status = 'success' AND created_at >= #{start} AND created_at < #{end} " +
            "GROUP BY model ORDER BY cost DESC")
    List<Map<String, Object>> sumByModelBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** 按用户汇总，费用倒序取 Top N */
    @Select("SELECT user_id, COUNT(*) AS calls, SUM(tokens_total) AS tokens_total, COALESCE(SUM(cost), 0) AS cost " +
            "FROM call_log WHERE status = 'success' AND created_at >= #{start} AND created_at < #{end} " +
            "GROUP BY user_id ORDER BY cost DESC LIMIT #{limit}")
    List<Map<String, Object>> sumByUserBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end,
                                               @Param("limit") int limit);

    /** 按天汇总（缺数据的日期由调用方补零，保证趋势线连续） */
    @Select("SELECT DATE(created_at) AS date, COUNT(*) AS calls, SUM(tokens_total) AS tokens_total, " +
            "COALESCE(SUM(cost), 0) AS cost " +
            "FROM call_log WHERE status = 'success' AND created_at >= #{start} AND created_at < #{end} " +
            "GROUP BY DATE(created_at) ORDER BY date")
    List<Map<String, Object>> sumByDayBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
