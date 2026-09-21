package com.cloudx.biz.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudx.biz.entity.CallLog;
import com.cloudx.biz.entity.SysUser;
import com.cloudx.biz.mapper.SysUserMapper;
import com.cloudx.biz.service.CallLogService;
import com.cloudx.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 管理员接口 — 全平台调用记录查询
 * Gateway AuthFilter 已校验 JWT 并透传 X-User-Role 请求头
 */
@RestController
@RequestMapping("/api/admin/calllogs")
@RequiredArgsConstructor
public class AdminCallLogController {

    private final CallLogService callLogService;
    private final SysUserMapper sysUserMapper;

    /** 校验是否管理员，不是则直接拒绝 */
    private void requireAdmin(String role) {
        if (!"admin".equals(role)) {
            throw new com.cloudx.common.exception.BizException(403, "无权限，仅管理员可操作");
        }
    }

    /** 分页查询全平台调用记录（列表不含完整请求/响应体，防大字段拖垮页面） */
    @GetMapping
    public R<Map<String, Object>> list(@RequestHeader("X-User-Role") String role,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size,
                                       @RequestParam(required = false) Long userId,
                                       @RequestParam(required = false) String model,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String start,
                                       @RequestParam(required = false) String end) {
        requireAdmin(role);
        Page<CallLog> p = callLogService.page(new Page<>(page, Math.min(size, 100)),
                new LambdaQueryWrapper<CallLog>()
                        .eq(userId != null, CallLog::getUserId, userId)
                        .eq(model != null && !model.isBlank(), CallLog::getModel, model)
                        .eq(status != null && !status.isBlank(), CallLog::getStatus, status)
                        .ge(start != null && !start.isBlank(), CallLog::getCreatedAt, start)
                        .le(end != null && !end.isBlank(), CallLog::getCreatedAt, end)
                        .orderByDesc(CallLog::getId));

        // 批量补 username，避免 N+1
        Set<Long> userIds = p.getRecords().stream()
                .map(CallLog::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> nameMap = userIds.isEmpty() ? Map.of()
                : sysUserMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(SysUser::getId, SysUser::getUsername));

        List<Map<String, Object>> records = p.getRecords().stream()
                .map(l -> toListItem(l, resolveUsername(l, nameMap)))
                .collect(Collectors.toList());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", records);
        data.put("total", p.getTotal());
        return R.ok(data);
    }

    /** 单条详情（含完整请求/响应体） */
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@RequestHeader("X-User-Role") String role, @PathVariable Long id) {
        requireAdmin(role);
        CallLog l = callLogService.getById(id);
        if (l == null) return R.fail("记录不存在");
        SysUser user = l.getUserId() != null ? sysUserMapper.selectById(l.getUserId()) : null;
        Map<String, Object> data = toListItem(l, user != null ? user.getUsername() : resolveUsername(l, Map.of()));
        data.put("requestBody", l.getRequestBody());
        data.put("responseBody", l.getResponseBody());
        return R.ok(data);
    }

    /** 用户名解析：用户已删除时标注，不留含糊的「未知用户」 */
    private String resolveUsername(CallLog l, Map<Long, String> nameMap) {
        if (l.getUserId() == null) return "-";
        String name = nameMap.get(l.getUserId());
        return name != null ? name : "用户#" + l.getUserId() + "(已删除)";
    }

    /** 列表项组装：大字段截断为预览 */
    private Map<String, Object> toListItem(CallLog l, String username) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("userId", l.getUserId());
        m.put("username", username);
        m.put("model", l.getModel());
        m.put("requestedModel", l.getRequestedModel());
        m.put("tokensInput", l.getTokensInput());
        m.put("tokensOutput", l.getTokensOutput());
        m.put("tokensTotal", l.getTokensTotal());
        m.put("cost", l.getCost());
        m.put("latencyMs", l.getLatencyMs());
        m.put("status", l.getStatus());
        m.put("errorMsg", l.getErrorMsg());
        m.put("createdAt", l.getCreatedAt() == null ? null : l.getCreatedAt().toString());
        m.put("requestPreview", preview(l.getRequestBody()));
        m.put("responsePreview", preview(l.getResponseBody()));
        return m;
    }

    private String preview(String text) {
        if (text == null) return "";
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}