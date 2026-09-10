package com.cloudx.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudx.biz.dto.LoginDTO;
import com.cloudx.biz.dto.RegisterDTO;
import com.cloudx.biz.entity.SysUser;
import com.cloudx.biz.mapper.SysUserMapper;
import com.cloudx.biz.service.UserService;
import com.cloudx.biz.util.JwtUtil;
import com.cloudx.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements UserService {

    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Override
    public SysUser register(RegisterDTO dto) {
        // 检查用户名是否已存在
        long count = count(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername()));
        if (count > 0) {
            throw new BizException("用户名已存在");
        }

        SysUser user = new SysUser();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setEmail(dto.getEmail());
        user.setPhone(dto.getPhone());
        user.setStatus(1);
        // 新用户默认每月 200 元基础额度
        user.setMonthlyQuota(new java.math.BigDecimal("200.00"));
        save(user);
        return user;
    }

    @Override
    public Map<String, Object> login(LoginDTO dto) {
        SysUser user = getOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername()));
        if (user == null) {
            throw new BizException("用户名或密码错误");
        }
        if (user.getStatus() == 0) {
            throw new BizException("账号已被禁用");
        }
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BizException("用户名或密码错误");
        }

        String role = user.getRole() != null ? user.getRole() : "user";
        String token = jwtUtil.generate(user.getId(), user.getUsername(), role);
        return Map.of("token", token, "userId", user.getId(), "username", user.getUsername(), "role", role);
    }

    @Override
    public SysUser currentUser(String token) {
        Long userId = jwtUtil.getUserId(token);
        SysUser user = getById(userId);
        if (user == null || user.getStatus() == 0) {
            throw new BizException("用户不存在或已禁用");
        }
        return user;
    }
}
