package com.cloudx.biz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cloudx.biz.entity.CallLog;

public interface CallLogService extends IService<CallLog> {

    /** 记录一次调用（model=实际路由模型，requestedModel=客户端请求的模型名，auto=自动路由） */
    void record(Long userId, Long apiKeyId, Long interfaceId, String model, String requestedModel,
                String requestBody, String responseBody,
                int tokensInput, int tokensOutput,
                long latencyMs, boolean success, String errorMsg);

    /** 今日统计 */
    java.util.Map<String, Object> statsToday(Long userId);

    /** 按模型统计（近 N 天，userId 为 null 表示全平台） */
    java.util.List<java.util.Map<String, Object>> statsByModel(int days, Long userId);

    /** 每日统计（近 N 天，userId 为 null 表示全平台） */
    java.util.List<java.util.Map<String, Object>> statsDaily(int days, Long userId);
}
