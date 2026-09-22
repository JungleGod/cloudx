package com.cloudx.biz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cloudx.biz.dto.ApiKeyVO;
import com.cloudx.biz.entity.ApiKey;

import java.util.List;

public interface ApiKeyService extends IService<ApiKey> {

    /** 为用户生成一对 AK/SK */
    ApiKeyVO create(Long userId, String name);

    /** 查询用户的所有 Key */
    List<ApiKeyVO> listByUser(Long userId);

    /** 禁用/启用 */
    void toggleStatus(Long userId, Long keyId, boolean enable);

    /** 校验 AK/SK，返回对应的 ApiKey */
    ApiKey validate(String accessKey, String secretKey);

    /** 仅通过 SecretKey 验证（用于 OpenAI 兼容 API 的 Bearer Token 认证） */
    ApiKey verifyBySecretKey(String secretKey);

    /** 仅通过 AccessKey 查找（AK/SK 签名验签用：gateway 取回明文 SK 计算 HMAC） */
    ApiKey findByAccessKey(String accessKey);
}
