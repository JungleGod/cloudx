---
name: cloudx-ai-gateway
description: cloudx AI 网关平台——统一管理多模型调用，转源生 + 转 AI 的双维度项目
metadata:
  type: project
---

# cloudx AI 网关平台

## 背景

- 当前：金蝶云苍穹开发，日常写 Java 和企业业务逻辑
- 目标一：转源生，去互联网公司做标准 Java 后端
- 目标二：转 AI，AI 是项目核心而非点缀
- 约束：
  - 运行流畅，7 个容器在 4C8G 内跑稳
  - 先单机 Docker Compose，后期可平滑拆分到多台服务器
  - 不跑本地模型，全部调用公开大模型 API（通义千问 / DeepSeek / OpenAI 等）
  - AI 选 LangChain4j，Java 生态最活跃，与 Spring 集成最好
  - 项目要同时证明"后端工程能力"和"AI 落地能力"

## 产品定位

AI 网关平台 = 统一管理多个大模型调用的中间层。

互联网公司普遍接入多个大模型（通义千问、DeepSeek、GLM、OpenAI），每个模型 API 格式不同、计费方式不同、限流策略不同。需要一个统一层来管理。这个项目就是做这个"统一层"。

## 核心功能

| 模块 | 功能 | 体现的能力 |
|------|------|-----------|
| 模型路由 | 根据请求内容、预算、延迟要求，自动选择最合适的模型 | 策略模式、规则引擎 |
| 负载均衡 | 一个模型多个 API Key，轮询/加权分发，优先用便宜的 | 分布式调度 |
| 故障转移 | 模型挂了自动切换备用模型，熔断降级 | Resilience4j、熔断器 |
| 统一限流 | 按用户/API Key/模型三维度限流 | 滑动窗口、令牌桶、Redis |
| 成本统计 | 每次调用消耗多少 tokens、多少钱，实时统计 | 数据库设计、定时任务 |
| 提示词管理 | 提示词模板库，版本管理，A/B 测试 | Nacos 配置中心实战 |
| 响应缓存 | 相同问题返回缓存结果，降低成本 | Redis 缓存策略 |
| 开发者中心 | 注册/认证（AK/SK），申请调用权限，在线调试 | 网关鉴权、签名校验 |
| 管理控制台 | API Key 管理、用量统计图表、计费账单 | React 前端 |

## 技术栈

| 组件 | 选型 | 在项目中的角色 |
|------|------|---------------|
| JDK | 17 | LTS |
| Spring Boot | 3.3.x | 所有后端服务 |
| Spring Cloud | 2023.x | 微服务框架 |
| Spring Cloud Gateway | 2023.x | 鉴权、限流、路由、日志拦截（核心！） |
| Nacos | 2.3.x | 注册中心 + 配置中心（路由规则/提示词热更新） |
| MySQL | 8.0 | 用户、API Key、接口定义、调用日志、计费 |
| Redis | 7.x | 限流计数、响应缓存、Token 缓存、会话缓存 |
| LangChain4j | 最新稳定版 | 统一调用多模型、故障转移、提示词管理 |
| 大模型 API | 通义千问 / DeepSeek / OpenAI | 平台管理的底层模型 |
| 前端 | React 18 + Ant Design 5 + Vite | 管理控制台 |
| 部署 | Docker Compose | 单机一键部署，后期拆机器改地址即可 |

## 最终服务器

- 腾讯云 4 核 8G 5M 轻量服务器 + Ubuntu 22.04
- 初期所有服务 Docker Compose 单机部署
- 后期可平滑迁移 MySQL/Redis 到腾讯云 RDS

## 部署节奏

- 第一阶段：本地 Docker Compose（已完成）
- 第二阶段：biz-service 写业务 + ai-agent 接入大模型
- 第三阶段：Gateway 加入 + 前端控制台
- 第四阶段：上腾讯云，外网可访问

## 服务与容器规划（7 个容器）

```
cloudx/
├── docker-compose.yml
├── nginx/                    # 反向代理容器
├── gateway/                  # Spring Cloud Gateway 容器（鉴权+限流+路由）
├── services/
│   ├── biz-service/          # 业务服务：用户、API Key、调用日志、计费
│   └── ai-agent/             # AI 代理服务：模型路由、负载均衡、故障转移
├── common/                   # 公共模块：R 返回体、异常处理、工具类
├── frontend/                 # React 管理控制台
└── docs/
    └── sql/                  # 建表脚本
```

### 容器清单

| 容器 | 镜像 | 端口 | 说明 |
|------|------|------|------|
| nginx | nginx:alpine | 80 | 统一入口、SSL、静态资源 |
| mysql | mysql:8.0 | 3306 | 数据持久化 |
| redis | redis:7-alpine | 6379 | 缓存+限流计数 |
| nacos | nacos/nacos-server:v2.3.2 | 8848 | 注册+配置中心 |
| gateway | 自构建 | 8080 | API 网关 |
| biz-service | 自构建 | 8081 | 业务逻辑 |
| ai-agent | 自构建 | 9090 | AI 调度核心 |

## 开发顺序

```
1. ✅ docker-compose 搭建 MySQL + Redis + Nacos 基础设施（已完成 2026-08-04）
2. ✅ biz-service：用户管理 + API Key 管理 + 调用日志存储（已完成 2026-08-04）
3. ✅ ai-agent：LangChain4j 接入 DeepSeek，跑通对话（已完成 2026-08-05）
4. ✅ ai-agent：模型路由 + 故障转移 + 负载均衡（已完成 2026-08-05）
5. ✅ gateway：鉴权 + 限流 + 路由转发（已完成 2026-08-05）
6. ✅ biz-service：成本统计 + 调用量分析（已验证通过 2026-08-07）
7. ✅ frontend：React 管理控制台（已验证通过 2026-08-07）
8. ✅ Nacos 配置中心热更新（模型配置实时生效，已完成 2026-08-07）
9. ✅ 多会话系统 + 文件上传 + 上下文修复 + 流式日志（已完成 2026-08-07）
10. 🔲 全链路压测，调优，确保 4C8G 下流畅
10. 🔲 部署到腾讯云
```

## 当前进度

- **2026-08-07（下午）**：多会话 + 文件上传 + 关键 Bug 修复
  - ✅ 多会话系统：conversation / conversation_message 表，biz-service CRUD API，ChatPage + ConversationSidebar 前端
  - ✅ 文件上传：支持 TXT/代码/JSON/CSV/PDF 等 30+ 格式，前端提取文本拼入 prompt，零后端改动
  - ✅ 修复切模型上下文混乱：history 携带 model，buildPrompt 标注 AI(模型名)，切换时插入身份提示
  - ✅ 修复流式调用不记日志：ChatService.chatStream 包装 callback 自动记录 callLog，概览有数据
  - ✅ UI 优化：附件按钮上下排列，高度对齐输入框
  - 🔲 突出 Agent 核心能力
  - 🔲 支持自定义 Agent 集成

- **2026-08-07（上午）**：联调验证 + Nacos 热更新 + Bug 修复
  - ✅ 全链路联调通过：前端 → gateway → biz-service/ai-agent → MySQL/Redis/Nacos
  - ✅ Nacos 配置中心热更新：`cloudx.models` + `cloudx.pricing` 迁移到 Nacos，`ModelRouter` 监听 `RefreshScopeRefreshedEvent` 自动重建
  - ✅ DeepSeek + 通义千问 双模型接入，互相 fallback
  - ✅ 联调中修复的 Bug：
    - DeepSeek 前端显示"离线"（前后端状态值不匹配：`UP` vs `online`）
    - 仪表盘无调用数据（`api_key_id` NOT NULL 但传了 null）
    - 在线调试切页签消息丢失（sessionStorage 持久化）
    - 多轮对话无上下文（前端发送历史 + 后端拼入 prompt）
    - API Key 创建后 Secret Key 从未完整展示（新增结果展示弹窗）
    - API Key 列表接口泄露完整 Secret Key（后端脱敏：`SK-xxxx...xxxx`）
  - 🔲 在线调试：模型选择器（用户手动选模型）
  - 🔲 在线调试：文件/图片上传（qwen-turbo 支持多模态）
  - 🔲 在线调试：流式输出（SSE 打字机效果）
  - 🔲 Secret Key 加密存储（当前明文存 DB）
  - 🔲 用户角色权限（管理员 vs 普通用户）

### 当前模块总览

| 模块 | 端口 | 功能 | 状态 |
|------|------|------|------|
| MySQL | 3306 | 数据持久化 | 🟢 |
| Redis | 6379 | 缓存+限流 | 🟢 |
| Nacos | 8848 | 注册中心+配置中心 | 🟢 |
| gateway | 8080 | 统一入口、JWT鉴权、路由转发、Redis限流 | 🟢 |
| biz-service | 8081 | 用户注册/登录、API Key管理、调用日志、成本统计 | 🟢 |
| ai-agent | 9090 | DeepSeek对话、模型路由、负载均衡、故障转移 | 🟢 |

### 调用链路

```
客户端 → gateway:8080 (JWT鉴权→限流)
           ├─→ /api/chat → ai-agent:9090 (ModelRouter→LoadBalancer→DeepSeek)
           ├─→ /api/user/** → biz-service:8081
           ├─→ /api/keys/** → biz-service:8081
           ├─→ /api/stats/** → biz-service:8081
           └─→ /api/internal/** → biz-service:8081
```

### API Key 注意事项

- `application.yml` 中 DeepSeek Key 用 `${DEEPSEEK_API_KEY:默认值}` 占位
- 部署到生产应移除默认值，改为环境变量或 Nacos 配置中心
- `.gitignore` 中已忽略 `application-local.yml`

## 面试能聊的关键点

- 网关鉴权：AK/SK 签名机制，防重放攻击
- 限流算法：滑动窗口 + Redis ZSET，按用户/模型多维度
- 故障转移：熔断器模式，连续失败 N 次自动切备用模型，半开状态恢复
- 多模型统一调用：适配器模式 + LangChain4j，新模型只加配置不改代码
- 微服务设计：为什么拆成 biz-service 和 ai-agent，各自职责边界
- AI 工程化：AI 不只调 API，而是完整的调度、容错、监控体系

---

## 硬件容量预估

目前已启动 MySQL + Redis + Nacos 三个容器。全部 7 个容器启动后：

- 每个 Java 进程设 -Xmx 256m（biz-service、ai-agent、gateway 共 3 个 = 768m）
- MySQL ≈ 400m、Redis ≈ 50m、Nacos ≈ 256m、Nginx ≈ 20m
- 合计约 1.5G，日常 4C8G 绰绰有余
