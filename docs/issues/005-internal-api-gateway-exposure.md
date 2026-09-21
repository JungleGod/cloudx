# 005 - /api/internal/** 经 gateway 白名单暴露公网

**日期**：2026-09-21
**严重级别**：P0（安全）
**状态**：已修复

## 问题

`gateway/application.yml` 中，鉴权白名单包含 `/api/internal/`，同时路由表里还有一条 `biz-internal`（Path=/api/internal/** → biz-service）。注释写着「不对外暴露」，实际效果是：**任何人不带 Token 就能从公网打穿全部内部接口**。

## 影响

- `GET /api/internal/models` → 返回**解密后的上游模型 API Key 明文**（2026-09-10 做 AES 加密就是为了保护它，结果被白名单绕过）
- `GET /api/internal/agents/{id}/dispatch-info` → 第三方 Agent 明文 endpoint_key
- `/api/internal/tools|agents` 的 POST/PUT/DELETE → 未认证直接改库

本地开发无感（服务都是 localhost），上云后是灾难级漏洞。

## 根因

1. 白名单是字符串 startsWith 匹配，`/api/internal/` 一条就把整个内部 API 族放行了
2. 「内部接口」只有注释约束，没有技术约束——路由存在 + 白名单放行 = 完全敞开
3. 典型的「配置漂移」：早期本地联调图方便加的白名单，忘了收

## 修复（2026-09-21）

三层防御：

1. **gateway**：白名单移除 `/api/internal/`，删除 `biz-internal` 路由 → 内部接口在公网入口层面不存在（服务间调用本来就是 ai-agent 直连 `localhost:8081`，不走 gateway）
2. **biz-service**：新增 `InternalTokenInterceptor`（WebMvcConfig 注册到 `/api/internal/**`），校验 `X-Internal-Token` 请求头，不匹配返回 403 → 防御内网直连 8081
3. **ai-agent**：新增 `InternalAuthInterceptor`（ClientHttpRequestInterceptor），10 处 biz-service 调用点（@Bean RestTemplate 5 处共用 + 5 处独立实例）统一携带 Token

Token 配置：`cloudx.internal-token`，默认 `cloudx-internal-dev-2026`（仅本地开发），生产用 `INTERNAL_TOKEN` 环境变量或 Nacos 覆盖，两服务必须一致。

## 启示

- 白名单条目要做「最小化 + 定期审计」，每一项都要能回答「为什么需要它」
- 「内部接口」必须有技术边界（网络层不可达 or 请求头密钥），注释不是安全机制
- 给「仅内网下发」性质的接口做设计时（如 dispatch-info 解密下发），要顺手验证它的实际暴露面，而不是只看自己的调用链