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
}
