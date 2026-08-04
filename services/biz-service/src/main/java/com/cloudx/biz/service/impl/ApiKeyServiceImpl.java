package com.cloudx.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudx.biz.dto.ApiKeyVO;
import com.cloudx.biz.entity.ApiKey;
import com.cloudx.biz.mapper.ApiKeyMapper;
import com.cloudx.biz.service.ApiKeyService;
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
        ApiKey key = new ApiKey();
        key.setUserId(userId);
        key.setAccessKey("AK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        key.setSecretKey("SK-" + Base64.getUrlEncoder().withoutPadding().encodeToString(generateRandomBytes(32)));
        key.setName(name);
        key.setStatus(1);
        key.setQuotaDaily(1000);
        key.setQuotaTotal(10000);
        save(key);
        return toVO(key);
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
        ApiKey key = getOne(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getAccessKey, accessKey)
                .eq(ApiKey::getSecretKey, secretKey)
                .eq(ApiKey::getStatus, 1));
        if (key == null) {
            throw new BizException(401, "API Key 无效或已禁用");
        }
        return key;
    }

    private ApiKeyVO toVO(ApiKey entity) {
        return ApiKeyVO.builder()
                .id(entity.getId())
                .accessKey(entity.getAccessKey())
                .secretKey(entity.getSecretKey())
                .name(entity.getName())
                .status(entity.getStatus())
                .quotaDaily(entity.getQuotaDaily())
                .quotaTotal(entity.getQuotaTotal())
                .expiredAt(entity.getExpiredAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private byte[] generateRandomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
