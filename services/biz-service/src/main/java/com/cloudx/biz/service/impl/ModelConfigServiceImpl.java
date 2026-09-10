package com.cloudx.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudx.biz.dto.ModelConfigVO;
import com.cloudx.biz.entity.ModelConfig;
import com.cloudx.biz.mapper.ModelConfigMapper;
import com.cloudx.biz.service.ModelConfigService;
import com.cloudx.biz.util.AesUtil;
import com.cloudx.common.exception.BizException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 模型配置服务 — 模型 CRUD + Key 加解密 + 定价缓存
 */
@Slf4j
@Service
public class ModelConfigServiceImpl extends ServiceImpl<ModelConfigMapper, ModelConfig> implements ModelConfigService {

    private static final BigDecimal[] DEFAULT_PRICES =
            new BigDecimal[]{new BigDecimal("0.001"), new BigDecimal("0.002")};

    /** 模型定价缓存：name → [输入单价, 输出单价]，volatile 保证发布可见性 */
    private volatile Map<String, BigDecimal[]> priceCache = Map.of();

    @PostConstruct
    public void init() {
        try {
            refreshPrices();
        } catch (Exception e) {
            // 表可能尚未迁移，启动不因定价缓存加载失败而中断（首次 CRUD 或 reload 会重载）
            log.warn("Failed to load model pricing cache at startup: {}", e.getMessage());
        }
    }

    // ==================== 定价 ====================

    @Override
    public BigDecimal[] getPrices(String model) {
        Map<String, BigDecimal[]> cache = priceCache;
        if (model != null) {
            BigDecimal[] prices = cache.get(model);
            if (prices != null) return prices;
        }
        return DEFAULT_PRICES;
    }

    @Override
    public void refreshPrices() {
        Map<String, BigDecimal[]> map = new HashMap<>();
        for (ModelConfig m : list()) {
            if (m.getName() != null && !m.getName().isBlank()) {
                map.put(m.getName(), new BigDecimal[]{m.getInputPrice(), m.getOutputPrice()});
            }
        }
        this.priceCache = map; // 原子发布
        log.info("Model pricing cache refreshed: {} entries", map.size());
    }

    // ==================== CRUD ====================

    @Override
    public List<ModelConfigVO> listVO() {
        return list(new LambdaQueryWrapper<ModelConfig>()
                .orderByAsc(ModelConfig::getPriority)
                .orderByAsc(ModelConfig::getId))
                .stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public ModelConfigVO create(ModelConfig model) {
        if (model.getName() == null || model.getName().isBlank()) {
            throw new BizException("模型标识 name 不能为空");
        }
        if (count(new LambdaQueryWrapper<ModelConfig>().eq(ModelConfig::getName, model.getName())) > 0) {
            throw new BizException("模型已存在: " + model.getName());
        }
        applyDefaults(model);
        model.setApiKey(encryptIfPresent(model.getApiKey()));
        save(model);
        refreshPrices();
        return toVO(model);
    }

    @Override
    public ModelConfigVO update(Long id, ModelConfig model) {
        ModelConfig existing = getById(id);
        if (existing == null) {
            throw new BizException("模型不存在");
        }

        // name 唯一性校验（排除自身）
        if (model.getName() != null && !model.getName().isBlank() && !model.getName().equals(existing.getName())) {
            long dup = count(new LambdaQueryWrapper<ModelConfig>()
                    .eq(ModelConfig::getName, model.getName())
                    .ne(ModelConfig::getId, id));
            if (dup > 0) {
                throw new BizException("模型名已存在: " + model.getName());
            }
        }

        // apiKey：空则保留旧密文，否则重新加密
        if (model.getApiKey() != null && !model.getApiKey().isBlank()) {
            existing.setApiKey(AesUtil.encrypt(model.getApiKey()));
        }

        // 覆盖其余字段；NOT NULL 列对 null/空 做保护
        if (model.getName() != null && !model.getName().isBlank()) existing.setName(model.getName());
        if (model.getBaseUrl() != null && !model.getBaseUrl().isBlank()) existing.setBaseUrl(model.getBaseUrl());
        if (model.getModelName() != null && !model.getModelName().isBlank()) existing.setModelName(model.getModelName());
        existing.setProvider(model.getProvider());
        existing.setTags(model.getTags());
        existing.setPriority(model.getPriority());
        existing.setMaxFailures(model.getMaxFailures());
        existing.setFallback(model.getFallback());
        existing.setTemperature(model.getTemperature());
        existing.setMaxTokens(model.getMaxTokens());
        existing.setTimeoutSeconds(model.getTimeoutSeconds());
        existing.setFrequencyPenalty(model.getFrequencyPenalty());
        existing.setPresencePenalty(model.getPresencePenalty());
        existing.setInputPrice(model.getInputPrice());
        existing.setOutputPrice(model.getOutputPrice());
        existing.setStatus(model.getStatus());

        applyDefaults(existing);
        updateById(existing);
        refreshPrices();
        return toVO(existing);
    }

    // ==================== 内部接口（ai-agent 拉取） ====================

    @Override
    public List<Map<String, Object>> listForAgent() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ModelConfig m : list()) {
            String plain = decryptQuietly(m.getApiKey());
            boolean hasKey = plain != null && !plain.isBlank();

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", m.getName());
            item.put("provider", m.getProvider());
            item.put("baseUrl", m.getBaseUrl());
            item.put("modelName", m.getModelName());
            item.put("keys", hasKey ? List.of(plain) : List.of());
            item.put("tags", splitTags(m.getTags()));
            item.put("priority", m.getPriority());
            item.put("maxFailures", m.getMaxFailures());
            item.put("fallback", m.getFallback());
            item.put("temperature", m.getTemperature());
            item.put("maxTokens", m.getMaxTokens());
            item.put("timeoutSeconds", m.getTimeoutSeconds());
            item.put("frequencyPenalty", m.getFrequencyPenalty());
            item.put("presencePenalty", m.getPresencePenalty());
            item.put("status", m.getStatus());
            result.add(item);
        }
        return result;
    }

    // ==================== 私有工具 ====================

    private ModelConfigVO toVO(ModelConfig m) {
        String plain = decryptQuietly(m.getApiKey());
        boolean hasKey = plain != null && !plain.isBlank();
        return ModelConfigVO.builder()
                .id(m.getId())
                .name(m.getName())
                .provider(m.getProvider())
                .baseUrl(m.getBaseUrl())
                .modelName(m.getModelName())
                .hasApiKey(hasKey)
                .apiKeyMasked(hasKey ? maskKey(plain) : "")
                .tags(m.getTags())
                .priority(m.getPriority())
                .maxFailures(m.getMaxFailures())
                .fallback(m.getFallback())
                .temperature(m.getTemperature())
                .maxTokens(m.getMaxTokens())
                .timeoutSeconds(m.getTimeoutSeconds())
                .frequencyPenalty(m.getFrequencyPenalty())
                .presencePenalty(m.getPresencePenalty())
                .inputPrice(m.getInputPrice())
                .outputPrice(m.getOutputPrice())
                .status(m.getStatus())
                .createdAt(m.getCreatedAt())
                .updatedAt(m.getUpdatedAt())
                .build();
    }

    /** 补全未传字段的默认值，避免 NOT NULL 约束报错 */
    private void applyDefaults(ModelConfig m) {
        if (m.getProvider() == null || m.getProvider().isBlank()) m.setProvider("openai-compatible");
        if (m.getPriority() == null) m.setPriority(10);
        if (m.getMaxFailures() == null) m.setMaxFailures(3);
        if (m.getTemperature() == null) m.setTemperature(0.7);
        if (m.getMaxTokens() == null) m.setMaxTokens(2048);
        if (m.getTimeoutSeconds() == null) m.setTimeoutSeconds(60L);
        if (m.getFrequencyPenalty() == null) m.setFrequencyPenalty(0.3);
        if (m.getPresencePenalty() == null) m.setPresencePenalty(0.3);
        if (m.getInputPrice() == null) m.setInputPrice(new BigDecimal("0.001"));
        if (m.getOutputPrice() == null) m.setOutputPrice(new BigDecimal("0.002"));
        if (m.getStatus() == null) m.setStatus(1);
    }

    private String encryptIfPresent(String plain) {
        return (plain != null && !plain.isBlank()) ? AesUtil.encrypt(plain) : null;
    }

    private String decryptQuietly(String cipher) {
        if (cipher == null || cipher.isBlank()) return null;
        try {
            return AesUtil.decrypt(cipher);
        } catch (Exception e) {
            log.warn("Decrypt api_key failed for model: {}", e.getMessage());
            return null;
        }
    }

    /** 脱敏 Key：sk-a...1234（首 4 位 + ... + 尾 4 位） */
    private String maskKey(String plain) {
        if (plain == null) return "";
        if (plain.length() <= 8) return "****";
        return plain.substring(0, 4) + "..." + plain.substring(plain.length() - 4);
    }

    private List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) return new ArrayList<>();
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
