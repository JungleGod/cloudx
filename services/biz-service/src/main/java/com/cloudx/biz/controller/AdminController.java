package com.cloudx.biz.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudx.biz.entity.SysUser;
import com.cloudx.biz.service.UserService;
import com.cloudx.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 管理员接口 — 仅 admin 角色可调用
 * Gateway AuthFilter 已校验 JWT 并透传 X-User-Role 请求头
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserService userService;

    /** 校验是否管理员，不是则直接拒绝 */
    private void requireAdmin(String role) {
        if (!"admin".equals(role)) {
            throw new com.cloudx.common.exception.BizException(403, "无权限，仅管理员可操作");
        }
    }

    /** 获取所有用户列表 */
    @GetMapping("/users")
    public R<List<Map<String, Object>>> listUsers(@RequestHeader("X-User-Role") String role) {
        requireAdmin(role);
        List<SysUser> users = userService.list(new LambdaQueryWrapper<SysUser>()
                .orderByDesc(SysUser::getCreatedAt));
        List<Map<String, Object>> result = new ArrayList<>();
        for (SysUser u : users) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", u.getId());
            item.put("username", u.getUsername());
            item.put("email", u.getEmail());
            item.put("role", u.getRole() != null ? u.getRole() : "user");
            item.put("status", u.getStatus());
            item.put("createdAt", u.getCreatedAt());
            result.add(item);
        }
        return R.ok(result);
    }

    /** 切换用户角色 */
    @PutMapping("/users/{id}/role")
    public R<Void> setRole(@PathVariable Long id,
                           @RequestBody Map<String, String> body,
                           @RequestHeader("X-User-Role") String role) {
        requireAdmin(role);
        SysUser user = userService.getById(id);
        if (user == null) {
            return R.fail("用户不存在");
        }
        user.setRole(body.get("role"));
        userService.updateById(user);
        return R.ok();
    }

    /** 切换用户状态 */
    @PutMapping("/users/{id}/status")
    public R<Void> toggleStatus(@PathVariable Long id,
                                @RequestBody Map<String, Object> body,
                                @RequestHeader("X-User-Role") String role) {
        requireAdmin(role);
        SysUser user = userService.getById(id);
        if (user == null) {
            return R.fail("用户不存在");
        }
        user.setStatus((Integer) body.getOrDefault("status", 1));
        userService.updateById(user);
        return R.ok();
    }
}