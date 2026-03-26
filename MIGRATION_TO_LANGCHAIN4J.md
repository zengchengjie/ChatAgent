# MIGRATION_TO_LANGCHAIN4J

本文记录本仓库从「自研 Agent 编排为主」迁移到「LangChain4j 主导」的落地结果、映射关系与回滚方案。

## 1) 架构映射（旧 -> 新）

- **旧主路径**：`AgentController -> AgentService(self loop) -> DashScopeClient + ToolRegistry`
- **新主路径**：`AgentController -> AgentEngineRouter -> Langchain4jAgentEngine`
- **保留路径**：`AgentService` 作为 `self` 引擎保留，可通过配置切换回去

## 2) 关键类对照

| 旧类 | 新类 | 职责变化 |
|---|---|---|
| `AgentService` | `Langchain4jAgentEngine` | 主导引擎由自研循环切换为 LangChain4j 工具编排 |
| 无 | `AgentEngine` | 引擎抽象接口 |
| 无 | `AgentEngineRouter` | 按配置选择 `self` / `langchain4j` |
| `AgentController` 直接依赖 `AgentService` | `AgentController` 依赖 `AgentEngineRouter` | HTTP 路径不变，内部转发方式变化 |
| `ToolExecutor`/`ToolRegistry` | LangChain4j Tool 适配（内部仍复用 `ToolRegistry.execute`） | 兼容已有工具安全约束 |

## 3) 配置项变更与环境变量

### 引擎切换

- 新增：`app.agent.engine`
- 环境变量：`APP_AGENT_ENGINE=self|langchain4j`
- 默认：`langchain4j`（学习优先）

### 现有配置继续有效

- `DASHSCOPE_API_KEY`
- `DASHSCOPE_BASE_URL`
- `DASHSCOPE_MODEL`
- `AGENT_MAX_STEPS`
- `AGENT_MAX_TOOL_CALLS_PER_TURN`
- `AGENT_MAX_TOOL_CALLS_TOTAL`
- `AGENT_TOOL_TIMEOUT_MS`

## 4) 事件兼容与映射

新增/保留 SSE 事件：

- 文本：`delta`
- 工具：`tool_start` / `tool_end`
- 规划：`plan_start` / `plan_step` / `plan_done`
- 护栏：`guardrail`
- 结束：`done` / `error`

旧事件未移除，前端最小改造可直接兼容。

## 5) 数据结构与脚本兼容

- API 路径保持：
  - `/api/auth/login`
  - `/api/sessions`
  - `/api/agent/chat`
  - `/api/agent/chat/stream`
- 现有 SQLite/Flyway 表结构保持兼容（无需额外 schema 迁移）
- 验证脚本：
  - `verify_planning_stream.sh`
  - `verify_rag_nohit.sh`
  - `verify_guardrail.sh`

## 6) 回滚方案（切回 self）

只需设置：

```bash
export APP_AGENT_ENGINE=self
```

然后重启后端。`AgentController` 路由会自动切换到 `AgentService` 原有实现。

