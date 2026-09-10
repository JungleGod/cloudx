package com.cloudx.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudx.biz.entity.CallLog;
import com.cloudx.biz.entity.SysUser;
import com.cloudx.biz.mapper.CallLogMapper;
import com.cloudx.biz.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 用户月度基础额度（金额，元）
 * <p>
 * 设计：
 * - 额度定义在 sys_user.monthly_quota（NULL = 不限，admin 角色一律豁免）
 * - 已用金额计数放 Redis，key 按月分片：cloudx:quota:{userId}:{yyyyMM}
 *   跨月自动换新 key，天然实现「每月 1 号刷新」，无需 cron
 * - 计数以「微元」（1 元 = 1e6）整数存储，用 INCRBY 精确累加，避免浮点误差
 * - 首次命中时从 call_log 当月 SUM(cost) 回填，保证 Redis 丢失/重启后仍与 DB 一致
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private static final String KEY_PREFIX = "cloudx:quota:";
    /** 1 元 = 1_000_000 微元，与 call_log.cost 的 DECIMAL(10,6) 精度对齐 */
    private static final long MICRO = 1_000_000L;
    /** 计数器 TTL：40 天，覆盖当月并留出月初过渡缓冲 */
    private static final long TTL_SECONDS = 40L * 24 * 3600;
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final StringRedisTemplate redis;
    private final SysUserMapper sysUserMapper;
    private final CallLogMapper callLogMapper;

    /**
     * 查询用户本月额度状态。
     * admin 角色、monthly_quota 为 NULL、用户不存在或 userId 为空，均视为「不限」。
     */
    public QuotaStatus check(Long userId) {
        if (userId == null || userId <= 0) {
            return QuotaStatus.unlimited(currentMonth());
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || "admin".equals(user.getRole()) || user.getMonthlyQuota() == null) {
            return QuotaStatus.unlimited(currentMonth());
        }

        BigDecimal quota = user.getMonthlyQuota();
        long usedMicro = getUsedMicro(userId);
        BigDecimal used = microToBigDecimal(usedMicro);
        long quotaMicro = toMicro(quota);
        boolean exceeded = usedMicro >= quotaMicro;
        BigDecimal remaining = quota.subtract(used).max(BigDecimal.ZERO);

        return new QuotaStatus(false, currentMonth(), quota, used, remaining, exceeded);
    }

    /**
     * 记录一次成功调用的费用，累加到本月计数器。
     * 只处理成功且 cost>0 的记录（由 CallLogServiceImpl.record 调用）。
     */
    public void addUsage(Long userId, BigDecimal cost) {
        if (userId == null || userId <= 0 || cost == null) {
            return;
        }
        long costMicro = toMicro(cost);
        if (costMicro <= 0) {
            return;
        }
        String key = key(userId);
        Long newVal = redis.opsForValue().increment(key, costMicro);
        // 首次累加时给 key 设 TTL，避免历史月份残留孤儿 key
        if (newVal != null && newVal == costMicro) {
            redis.expire(key, Duration.ofSeconds(TTL_SECONDS));
        }
    }

    /** 每个用户本月的累计费用（用于管理端列表展示），userId -> 已用（元） */
    public Map<Long, BigDecimal> monthUsedByUsers() {
        LocalDateTime start = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        Map<Long, BigDecimal> result = new HashMap<>();
        for (Map<String, Object> row : callLogMapper.sumCostByUserSince(start)) {
            Long userId = toLong(row.get("user_id"));
            BigDecimal used = row.get("used") instanceof Number n
                    ? new BigDecimal(n.toString()) : BigDecimal.ZERO;
            result.put(userId, used);
        }
        return result;
    }

    // ==================== 私有工具 ====================

    private long getUsedMicro(Long userId) {
        String key = key(userId);
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            try {
                return Long.parseLong(cached);
            } catch (NumberFormatException ignored) {
                // 落入 DB 回填
            }
        }
        // 懒加载回填：从 call_log 统计当月已用费用
        long usedMicro = sumMonthCostMicro(userId);
        redis.opsForValue().setIfAbsent(key, String.valueOf(usedMicro), Duration.ofSeconds(TTL_SECONDS));
        return usedMicro;
    }

    private long sumMonthCostMicro(Long userId) {
        LocalDateTime start = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        BigDecimal sum = BigDecimal.ZERO;
        for (CallLog log : callLogMapper.selectList(new LambdaQueryWrapper<CallLog>()
                .eq(CallLog::getUserId, userId)
                .eq(CallLog::getStatus, "success")
                .ge(CallLog::getCreatedAt, start))) {
            if (log.getCost() != null) {
                sum = sum.add(log.getCost());
            }
        }
        return toMicro(sum);
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId + ":" + currentMonth();
    }

    private String currentMonth() {
        return LocalDate.now().format(MONTH_FMT);
    }

    private long toMicro(BigDecimal yuan) {
        if (yuan == null) return 0L;
        return yuan.multiply(BigDecimal.valueOf(MICRO))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private BigDecimal microToBigDecimal(long micro) {
        return BigDecimal.valueOf(micro, 6);
    }

    private Long toLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(obj.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** 额度状态 */
    public record QuotaStatus(boolean unlimited, String month, BigDecimal quota,
                              BigDecimal used, BigDecimal remaining, boolean exceeded) {
        static QuotaStatus unlimited(String month) {
            return new QuotaStatus(true, month, null, null, null, false);
        }
    }
}