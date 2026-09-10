package com.cloudx.biz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cloudx.biz.dto.ModelConfigVO;
import com.cloudx.biz.entity.ModelConfig;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface ModelConfigService extends IService<ModelConfig> {

    /** 列出所有模型（脱敏 VO，按优先级排序） */
    List<ModelConfigVO> listVO();

    /** 创建模型（apiKey 加密落库） */
    ModelConfigVO create(ModelConfig model);

    /** 更新模型（apiKey 为空则保留旧密文） */
    ModelConfigVO update(Long id, ModelConfig model);

    /** 获取模型定价 [输入单价, 输出单价]，精确匹配 name，找不到退回默认 */
    BigDecimal[] getPrices(String model);

    /** 刷新定价缓存 */
    void refreshPrices();

    /** 内部接口：返回 ai-agent 需要的 ModelInfo 形状（含解密 key） */
    List<Map<String, Object>> listForAgent();
}
