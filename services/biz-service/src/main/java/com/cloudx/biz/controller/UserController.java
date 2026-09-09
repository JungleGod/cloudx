package com.cloudx.biz.controller;

import com.cloudx.biz.dto.LoginDTO;
import com.cloudx.biz.dto.RegisterDTO;
import com.cloudx.biz.entity.SysUser;
import com.cloudx.biz.service.UserService;
import com.cloudx.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/register")
    public R<Map<String, Object>> register(@Valid @RequestBody RegisterDTO dto) {
        SysUser user = userService.register(dto);
        return R.ok(Map.of("userId", user.getId(), "username", user.getUsername()));
    }

    @PostMapping("/login")
    public R<Map<String, Object>> login(@Valid @RequestBody LoginDTO dto) {
        return R.ok(userService.login(dto));
    }

    @GetMapping("/me")
    public R<Map<String, Object>> me(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.replace("Bearer ", "");
        SysUser user = userService.currentUser(token);
        return R.ok(Map.of("userId", user.getId(), "username", user.getUsername(),
                "role", user.getRole() != null ? user.getRole() : "user",
                "email", user.getEmail() != null ? user.getEmail() : ""));
    }
}
