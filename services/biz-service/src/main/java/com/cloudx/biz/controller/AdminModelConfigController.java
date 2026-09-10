package com.cloudx.biz.controller;

import com.cloudx.biz.dto.ModelConfigVO;
import com.cloudx.biz.entity.ModelConfig;
import com.cloudx.biz.service.ModelConfigService;
import com.cloudx.common.exception.BizException;
import com.cloudx.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 模型管理接口 — 仅 admin 角色可调用
 * Gateway AuthFilter 已校验 JWT 并透传 X-User-Role 请求头
 */
@RestController
@RequestMapping("/api/admin/models")
@RequiredArgsConstructor
public class AdminModelConfigController {

    private final ModelConfigService modelConfigService;

    private void requireAdmin(String role) {
        if (!"admin".equals(role)) {
            throw new BizException(403, "无权限，仅管理员可操作");
        }
    }

    /** 列出所有模型（脱敏，不返回明文 key） */
    @GetMapping
    public R<List<ModelConfigVO>> list(@RequestHeader("X-User-Role") String role) {
        requireAdmin(role);
        return R.ok(modelConfigService.listVO());
    }

    /** 创建模型（body 含明文 apiKey，加密落库） */
    @PostMapping
    public R<ModelConfigVO> create(@RequestBody ModelConfig model,
                                   @RequestHeader("X-User-Role") String role) {
        requireAdmin(role);
        return R.ok(modelConfigService.create(model));
    }

    /** 更新模型（apiKey 为空则保留旧 Key） */
    @PutMapping("/{id}")
    public R<ModelConfigVO> update(@PathVariable Long id,
                                   @RequestBody ModelConfig model,
                                   @RequestHeader("X-User-Role") String role) {
        requireAdmin(role);
        return R.ok(modelConfigService.update(id, model));
    }

    /** 删除模型（逻辑删除） */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id,
                          @RequestHeader("X-User-Role") String role) {
        requireAdmin(role);
        modelConfigService.removeById(id);
        modelConfigService.refreshPrices();
        return R.ok();
    }

    /** 手动刷新定价缓存 */
    @PostMapping("/reload")
    public R<Void> reload(@RequestHeader("X-User-Role") String role) {
        requireAdmin(role);
        modelConfigService.refreshPrices();
        return R.ok();
    }
}
