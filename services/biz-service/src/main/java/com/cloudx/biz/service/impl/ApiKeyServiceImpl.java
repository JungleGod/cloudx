package com.cloudx.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudx.biz.dto.ApiKeyVO;
import com.cloudx.biz.entity.ApiKey;
import com.cloudx.biz.mapper.ApiKeyMapper;
import com.cloudx.biz.service.ApiKeyService;
import com.cloudx.biz.util.AesUtil;
import com.cloudx.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ApiKeyServiceImpl extends ServiceImpl<ApiKeyMapper, ApiKey> implements ApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public ApiKeyVO create(Long userId, String name) {
        String plainSecretKey = "SK-" + Base64.getUrlEncoder().withoutPadding().encodeToString(generateRandomBytes(32));

        ApiKey key = new ApiKey();
        key.setUserId(userId);
        key.setAccessKey("AK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        key.setSecretKey(AesUtil.encrypt(plainSecretKey)); // 加密存储
        key.setName(name);
        key.setStatus(1);
        key.setQuotaDaily(1000);
        key.setQuotaTotal(10000);
        save(key);
        // 创建时返回明文 Secret Key（客户端保存后不可再获取）
        ApiKeyVO vo = toVO(key);
        vo.setSecretKey(plainSecretKey);
        return vo;
    }

    @Override
    public List<ApiKeyVO> listByUser(Long userId) {
        return list(new LambdaQueryWrapper<ApiKey>().eq(ApiKey::getUserId, userId))
                .stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public void toggleStatus(Long userId, Long keyId, boolean enable) {
        ApiKey key = getById(keyId);
        if (key == null || !key.getUserId().equals(userId)) {
            throw new BizException("Key 不存在");
        }
        key.setStatus(enable ? 1 : 0);
        updateById(key);
    }

    @Override
    public ApiKey validate(String accessKey, String secretKey) {
        // 明文 SK 先加密再比对（DB 存的是密文）
        String encrypted = AesUtil.encrypt(secretKey);
        ApiKey key = getOne(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getAccessKey, accessKey)
                .eq(ApiKey::getSecretKey, encrypted)
                .eq(ApiKey::getStatus, 1));
        if (key == null) {
            throw new BizException(401, "API Key 无效或已禁用");
        }
        return key;
    }

    @Override
    public ApiKey findByAccessKey(String accessKey) {
        if (accessKey == null || accessKey.isBlank()) {
            return null;
        }
        return getOne(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getAccessKey, accessKey.trim())
                .eq(ApiKey::getStatus, 1));
    }

    @Override
    public ApiKey verifyBySecretKey(String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new BizException(401, "API Key 不能为空");
        }
        // 明文 SK 加密后匹配
        String encrypted = AesUtil.encrypt(secretKey.trim());
        ApiKey key = getOne(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getSecretKey, encrypted)
                .eq(ApiKey::getStatus, 1));
        if (key == null) {
            throw new BizException(401, "API Key 无效或已禁用");
        }
        // 检查过期
        if (key.getExpiredAt() != null && key.getExpiredAt().isBefore(java.time.LocalDateTime.now())) {
            throw new BizException(401, "API Key 已过期");
        }
        return key;
    }

    private ApiKeyVO toVO(ApiKey entity) {
        return ApiKeyVO.builder()
                .id(entity.getId())
                .accessKey(entity.getAccessKey())
                .secretKey(maskSecret(entity.getSecretKey()))
                .name(entity.getName())
                .status(entity.getStatus())
                .quotaDaily(entity.getQuotaDaily())
                .quotaTotal(entity.getQuotaTotal())
                .expiredAt(entity.getExpiredAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    /** 脱敏 Secret Key：SK-xxxx...xxxx 仅保留前后几位用于区分 */
    private String maskSecret(String secret) {
        if (secret == null || secret.length() <= 12) {
            return secret == null ? "" : secret;
        }
        return secret.substring(0, 8) + "..." + secret.substring(secret.length() - 5);
    }

    private byte[] generateRandomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
