package com.cloudx.biz.controller;

import com.cloudx.biz.dto.ApiKeyVO;
import com.cloudx.biz.service.ApiKeyService;
import com.cloudx.biz.util.JwtUtil;
import com.cloudx.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/keys")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final JwtUtil jwtUtil;

    @PostMapping
    public R<ApiKeyVO> create(@RequestHeader("Authorization") String authHeader,
                              @RequestBody Map<String, String> body) {
        Long userId = getUserId(authHeader);
        return R.ok(apiKeyService.create(userId, body.getOrDefault("name", "")));
    }

    @GetMapping
    public R<List<ApiKeyVO>> list(@RequestHeader("Authorization") String authHeader) {
        return R.ok(apiKeyService.listByUser(getUserId(authHeader)));
    }

    @PutMapping("/{id}/toggle")
    public R<Void> toggle(@RequestHeader("Authorization") String authHeader,
                          @PathVariable Long id,
                          @RequestBody Map<String, Boolean> body) {
        apiKeyService.toggleStatus(getUserId(authHeader), id, body.getOrDefault("enable", true));
        return R.ok();
    }

    private Long getUserId(String authHeader) {
        return jwtUtil.getUserId(authHeader.replace("Bearer ", ""));
    }
}
