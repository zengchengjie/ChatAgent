# 从零开发 AI Agent 项目 — 开发指导文档

**用途**：指导 Code Agent / 人工开发，在新仓库中实现可部署、可演示、面向面试的 LLM Agent（工具调用闭环），默认适配 **阿里云通义千问（DashScope）**，基础设施按 **单机（如腾讯云轻量）** 优化。

**原则**：先砍掉 RabbitMQ、异步双链路、过重监控栈；保留 Redis（推荐）与 Nginx 反代；核心交付 Agent 编排 + 会话 + 可观测轨迹。

---

## 1. 项目目标与范围

### 1.1 必须达成（MVP）

- 用户登录（JWT）后进入对话页。
- 支持 **多轮对话**，会话维度有稳定 `sessionId`。
- 实现 Agent 主循环：模型可发起 **工具调用（function calling）** → 后端执行白名单内工具 → 将结果写回上下文 → 再次请求模型，直到模型给出最终回复或达到 **最大步数**。
- 支持 **流式输出（SSE）** 展示最终助手回复（工具过程可配置为流式或仅日志/结构化事件）。
- **限流**（按用户或 IP），防止演示/滥用刷爆 API。
- **结构化日志**：每次 LLM 调用、每次工具调用有 `traceId`、耗时、成功/失败。

### 1.2 明确不做（第一版）

- RabbitMQ / Kafka / RocketMQ（除非后续明确要做削峰队列）。
- 完整 Prometheus + Grafana 栈（可用 Actuator health + 日志即可）。
- 自建向量库集群；RAG 若做则 Phase 2 可选（见第 10 节）。

### 1.3 非功能需求

- **单机可运行**：后端 + Redis + Nginx（前端静态）内存占用可控。
- **配置外置**：密钥不进仓库，使用环境变量或 `application-prod.yml`（且不提交密钥）。

---

## 2. 推荐技术栈（可与原项目一致）

| 层级 | 选型 | 说明 |
|------|------|------|
| 后端 | Spring Boot 3.x + Java 17 | REST + SSE；Agent 编排放 Service 层 |
| 安全 | Spring Security + JWT | 访问 `/api/**` 需认证（除 login、health） |
| 缓存/会话 | Redis | 限流计数、会话元数据、可选对话摘要缓存 |
| 持久化 | SQLite 或 MySQL 8（二选一） | 用户、会话列表、消息落库（比「只写 Redis 不读」更利于面试叙事） |
| 前端 | Vue 3 + Vite + Pinia | 对话 UI、SSE 消费、可选「工具步骤」展示 |
| 部署 | Nginx + systemd 或 Docker Compose（精简版） | 静态资源 + 反代 API |

---

## 3. 系统架构（逻辑）

```
[Browser] → Nginx → [Spring Boot]
                        │
                        ├→ DashScope API（chat / stream，带 tools）
                        ├→ ToolRegistry（白名单工具执行）
                        ├→ Redis（rate limit / session cache）
                        └→ DB（users, sessions, messages）
```

**Agent 编排伪流程**：

1. 组装 `messages`（system + 可选历史 + user）。
2. 调用模型（若 API 支持 tools，传入工具 JSON Schema）。
3. 若响应为 `tool_calls`：依次执行工具（超时、参数校验）→ 追加 tool 消息 → 回到步骤 2。
4. 若为 **最终文本**：流式或一次性返回客户端；持久化 assistant 消息。
5. 若步数 > `maxSteps`：返回友好错误并记录日志。

---

## 4. 通义千问（DashScope）配置说明

### 4.1 账号与密钥

- 在阿里云 DashScope 控制台创建 API-Key。
- **禁止提交到 Git**：使用环境变量，例如 `DASHSCOPE_API_KEY`。

### 4.2 建议配置项（后端 `application.yml` 占位）

以下键名供实现时统一（值由环境变量注入）：

```yaml
dashscope:
  api-key: ${DASHSCOPE_API_KEY:}
  # 文本生成多轮对话（按实际选用的 API 路径调整，与官方文档一致）
  base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
  # 或旧版 generation 路径，以所选模型与文档为准
  model: qwen-plus   # 或 qwen-turbo / qwen-max 等，需支持 tools 的模型
  connect-timeout-ms: 10000
  read-timeout-ms: 120000
  max-tokens: 4096
  temperature: 0.3
```

**开发注意**：

- 确认所选模型与接口 **是否支持 OpenAI-compatible 的 tools/function calling**；若仅用旧版 HTTP API，需在 Client 层适配千问的 tools 字段格式（以官方文档为准）。
- **流式**：SSE 头与解析需与 DashScope 流式响应格式一致；实现单元测试或集成测试时用 mock。

### 4.3 环境变量示例（生产/轻量机）

```bash
export DASHSCOPE_API_KEY="sk-xxxxxxxx"
export SPRING_PROFILES_ACTIVE=prod
```

---

## 5. 必要中间件与配置

### 5.1 Redis（推荐保留）

**用途**：分布式友好限流、会话短期状态、可选缓存。

**Docker 示例（开发）**：

```yaml
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    command: redis-server --appendonly yes
```

**Spring 配置示例**：

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
```

单机无 Docker：可在轻量机 apt/yum 安装 Redis，绑定 `127.0.0.1`，设密码。

### 5.2 数据库（SQLite 或 MySQL）

- **SQLite**：零运维，适合演示；注意并发写入配置。
- **MySQL**：更接近「生产叙事」。

**至少表**：

- `users`（id, username, password_hash, role, created_at）
- `chat_sessions`（id, user_id, title, created_at, updated_at）
- `chat_messages`（id, session_id, role, content, tool_calls_json, created_at）

### 5.3 Nginx（部署）

- 托管前端 `dist`。
- `location /api/` → `proxy_pass` 到 `http://127.0.0.1:8080`（或实际端口）。
- **SSE**：`proxy_buffering off;`、`proxy_read_timeout` 加大、Connection 相关头按常规 SSE 配置。

---

## 6. API 设计（建议）

### 6.1 认证

- `POST /api/auth/login` → 返回 JWT
- `POST /api/auth/logout` → 可选黑名单（单机可用内存；文档注明多实例需 Redis）

### 6.2 会话与消息

- `POST /api/sessions` 创建会话
- `GET /api/sessions` 列表
- `GET /api/sessions/{id}/messages` 历史

### 6.3 Agent 对话（核心）

- `POST /api/agent/chat`  
  Body：`{ "sessionId", "content", "stream": true/false }`  
  非流式：200 + 完整回复 + 可选 `steps`（工具调用摘要）
- `POST /api/agent/chat/stream`  
  `text/event-stream`：事件类型建议：`delta`（文本增量）、`tool_start` / `tool_end`（可选）、`error`、`done`

### 6.4 健康检查

- `GET /api/health` 或 Actuator：`/actuator/health`（勿暴露敏感端点）

---

## 7. 工具（Tools）规范

### 7.1 第一版内置工具（示例）

| 工具名 | 作用 | 安全约束 |
|--------|------|----------|
| calculator | 安全表达式求值（仅数字与有限运算符，禁止 eval 任意代码） | 超时 500ms |
| get_mock_weather | 返回固定/mock 天气 | 仅允许城市白名单或字符串长度限制 |
| search_knowledge | Phase 2：检索本地文档片段 | 返回长度上限 |

### 7.2 工具注册

- `ToolRegistry`：`name -> ToolExecutor`
- **JSON Schema 与发给模型的 tools 定义单一来源**，避免手写两份不一致。

### 7.3 Agent 安全

- `maxSteps` 默认 5～8（可配置）
- 单用户 QPM 限流
- 工具入参校验（Bean Validation）
- 所有工具调用打审计日志（不含密钥）

---

## 8. 前端需求摘要

- 登录页、会话列表、对话页。
- **SSE**：拼接 `delta` 为当前 assistant 气泡；错误事件 toast。
- 可选：侧栏显示本轮 `tool_start`/`tool_end`（面试加分）。
- **勿在浏览器存 API Key**；仅 JWT。

---

## 9. 配置与安全清单（提交前自检）

- `DASHSCOPE_API_KEY` 仅环境变量/密钥管理
- CORS 仅允许前端域名
- 生产关闭 Actuator 敏感端点或使用独立 management 端口
- Nginx 限 body 大小
- HTTPS（有域名时 Let's Encrypt）

---

## 10. Phase 2（可选）

- **最小 RAG**：上传 md/pdf → 切块 → embedding（可调用 DashScope embedding API）→ 存 SQLite fts 或轻量向量（如 sqlite-vss）→ `search_knowledge` 走检索。
- **人工确认**：高风险工具前插入 `pending_approval` 状态（WebSocket 或轮询）。

---

## 11. Code Agent 分阶段任务列表（建议执行顺序）

1. 脚手架：Spring Boot + Security + JWT + 用户表 + 登录接口。
2. 会话与消息 CRUD + DB 迁移（Flyway/Liquibase 或手写 SQL）。
3. DashScopeClient：非流式 chat；再实现 stream。
4. ToolRegistry + 2 个工具 + AgentLoop（maxSteps、日志）。
5. REST + SSE 打通前端。
6. Redis 限流接入关键接口。
7. Nginx 部署文档 + 环境变量模板 `.env.example`。
8. README：架构图、Agent 流程、如何换模型、面试讲解要点。

---

## 12. 验收标准（演示用）

- 登录后可连续多轮对话，刷新后历史仍在（DB）。
- 提问触发至少一次工具调用的问题（如「帮我算 123*456」），日志可见 tool → model 第二轮。
- 流式回复无明显断连；限流在压测脚本下可返回 429。
- 仓库内无真实 API Key。

---

## 给 Code Agent 的提示（新窗口）

在新窗口对 Code Agent 说：

> **严格按 `AGENT_PROJECT_SPEC.md` 分阶段实现，先做 Phase 1，千问配置用环境变量。**
