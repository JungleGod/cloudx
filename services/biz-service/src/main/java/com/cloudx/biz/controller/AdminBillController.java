package com.cloudx.biz.controller;

import com.cloudx.biz.entity.SysUser;
import com.cloudx.biz.mapper.CallLogMapper;
import com.cloudx.biz.mapper.SysUserMapper;
import com.cloudx.common.exception.BizException;
import com.cloudx.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 管理员接口 — 月度账单汇总（全平台视角）
 * 与普通用户的 /api/stats/**（仅本人数据、内存聚合）互补：
 * 管理员账单的聚合全部下推 DB（GROUP BY），月度数据不拉全表进内存。
 * Gateway AuthFilter 已校验 JWT 并透传 X-User-Role 请求头
 */
@RestController
@RequestMapping("/api/admin/bills")
@RequiredArgsConstructor
public class AdminBillController {

    private final CallLogMapper callLogMapper;
    private final SysUserMapper sysUserMapper;

    /** 校验是否管理员，不是则直接拒绝 */
    private void requireAdmin(String role) {
        if (!"admin".equals(role)) {
            throw new BizException(403, "无权限，仅管理员可操作");
        }
    }

    /** 月度账单：?month=2026-09（缺省当月）。概览含失败次数，费用/token 只算成功调用 */
    @GetMapping
    public R<Map<String, Object>> month(@RequestHeader("X-User-Role") String role,
                                        @RequestParam(required = false) String month) {
        requireAdmin(role);
        YearMonth ym;
        try {
            ym = (month == null || month.isBlank()) ? YearMonth.now() : YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            return R.fail("月份格式错误，应为 yyyy-MM");
        }
        LocalDateTime start = ym.atDay(1).atStartOfDay();
        LocalDateTime end = ym.plusMonths(1).atDay(1).atStartOfDay();

        // 概览
        Map<String, Object> sum = callLogMapper.sumSummaryBetween(start, end);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("calls", toLong(sum.get("calls")));
        summary.put("successCalls", toLong(sum.get("success_calls")));
        summary.put("tokensInput", toLong(sum.get("tokens_input")));
        summary.put("tokensOutput", toLong(sum.get("tokens_output")));
        summary.put("cost", sum.get("cost") == null ? BigDecimal.ZERO : sum.get("cost"));

        // 按用户 Top 10（费用倒序），批量补 username
        List<Map<String, Object>> byUserRows = callLogMapper.sumByUserBetween(start, end, 10);
        Set<Long> userIds = byUserRows.stream()
                .map(r -> toLong(r.get("user_id"))).collect(Collectors.toSet());
        Map<Long, String> nameMap = userIds.isEmpty() ? Map.of()
                : sysUserMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(SysUser::getId, SysUser::getUsername));
        List<Map<String, Object>> byUser = byUserRows.stream().map(r -> {
            long uid = toLong(r.get("user_id"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", uid);
            m.put("username", nameMap.getOrDefault(uid, "用户#" + uid + "(已删除)"));
            m.put("calls", toLong(r.get("calls")));
            m.put("tokensTotal", toLong(r.get("tokens_total")));
            m.put("cost", r.get("cost"));
            return m;
        }).collect(Collectors.toList());

        // 按模型
        List<Map<String, Object>> byModel = callLogMapper.sumByModelBetween(start, end).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("model", r.get("model"));
            m.put("calls", toLong(r.get("calls")));
            m.put("tokens", toLong(r.get("tokens_total")));
            m.put("cost", r.get("cost"));
            return m;
        }).collect(Collectors.toList());

        // 按天（缺数据的日期补零，保证趋势线连续）
        Map<LocalDate, Map<String, Object>> dayMap = new HashMap<>();
        for (Map<String, Object> r : callLogMapper.sumByDayBetween(start, end)) {
            Object dv = r.get("date");
            LocalDate d = dv instanceof java.sql.Date
                    ? ((java.sql.Date) dv).toLocalDate()
                    : LocalDate.parse(String.valueOf(dv));
            dayMap.put(d, r);
        }
        List<Map<String, Object>> daily = new ArrayList<>();
        for (LocalDate d = ym.atDay(1); !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) {
            Map<String, Object> row = dayMap.get(d);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", d.toString());
            m.put("calls", row == null ? 0L : toLong(row.get("calls")));
            m.put("tokens", row == null ? 0L : toLong(row.get("tokens_total")));
            m.put("cost", row == null ? BigDecimal.ZERO : row.get("cost"));
            daily.add(m);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("month", ym.toString());
        data.put("summary", summary);
        data.put("byUser", byUser);
        data.put("byModel", byModel);
        data.put("daily", daily);
        return R.ok(data);
    }

    private long toLong(Object v) {
        return v == null ? 0 : ((Number) v).longValue();
    }
}