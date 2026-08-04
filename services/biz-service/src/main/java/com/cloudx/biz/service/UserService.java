package com.cloudx.biz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cloudx.biz.dto.LoginDTO;
import com.cloudx.biz.dto.RegisterDTO;
import com.cloudx.biz.entity.SysUser;

import java.util.Map;

public interface UserService extends IService<SysUser> {

    /** 注册，返回用户信息 */
    SysUser register(RegisterDTO dto);

    /** 登录，返回 token + 用户信息 */
    Map<String, Object> login(LoginDTO dto);

    /** 根据 Token 获取当前用户 */
    SysUser currentUser(String token);
}
