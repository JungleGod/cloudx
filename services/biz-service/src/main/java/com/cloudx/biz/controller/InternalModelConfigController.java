package com.cloudx.biz.controller;

import com.cloudx.biz.service.ModelConfigService;
import com.cloudx.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 模型配置内部接口 — 供 ai-agent 拉取（含解密后的上游 Key）
 * /api/internal/** 在网关白名单中，无 JWT，仅服务间直连
 */
@RestController
@RequiredArgsConstructor
public class InternalModelConfigController {

    private final ModelConfigService modelConfigService;

    /** 返回 ai-agent 需要的 ModelInfo 形状（name/provider/baseUrl/modelName/keys/tags/.../status） */
    @GetMapping("/api/internal/models")
    public R<List<Map<String, Object>>> listModels() {
        return R.ok(modelConfigService.listForAgent());
    }
}
