-- 2026-09-21 调用日志区分「请求模型」与「实际路由模型」
-- 背景：此前流式路径把请求参数 auto 原样写入 model 列，导致：
--   1. 统计按 auto 分组，看不到实际模型的花费分布
--   2. getPrices('auto') 查不到定价 → cost=0 且不计入用户月度额度（计费漏洞）
-- 修复：ai-agent 统一记录实际路由模型到 model 列；客户端请求名（auto/指定名）记 requested_model
ALTER TABLE call_log ADD COLUMN requested_model VARCHAR(64) DEFAULT NULL
    COMMENT '客户端请求的模型名（auto=自动路由），实际模型存 model 列' AFTER model;

-- 历史数据：auto 行的实际模型已无从还原，保留原样（会随统计窗口自然滑出）
-- 如需标注可执行：UPDATE call_log SET requested_model='auto' WHERE model='auto' AND requested_model IS NULL;