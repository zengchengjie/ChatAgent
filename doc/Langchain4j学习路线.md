# LEARNING_PATH_LANGCHAIN4J

## 2 小时路径（快速建立全局认知）

1. 先读引擎切换入口：`backend/src/main/java/com/chatagent/agent/engine/AgentEngineRouter.java`
2. 再读 LangChain4j 主实现：`backend/src/main/java/com/chatagent/agent/engine/Langchain4jAgentEngine.java`
3. 对比自研引擎：`backend/src/main/java/com/chatagent/agent/AgentService.java`
4. 看工具契约：`backend/src/main/java/com/chatagent/tools/ToolExecutor.java` + `ToolRegistry.java`
5. 看 RAG 入口：`SearchKnowledgeTool.java` + `KnowledgeService.java`

目标：理解“同一套 Controller/API，如何在 self/langchain4j 两套引擎间切换”。

## 1 天路径（可动手验证）

1. 本地跑通后端+前端，切换 `APP_AGENT_ENGINE`
2. 执行脚本：
   - `backend/scripts/verify_planning_stream.sh`
   - `backend/scripts/verify_rag_nohit.sh`
   - `backend/scripts/verify_guardrail.sh`
3. 在日志里检索：
   - `event=tool_call`
   - `event=guardrail_hit`
   - `event=agent_summary`
4. 比较 self 与 langchain4j 模式下的事件差异与调用轨迹

目标：建立“观测 -> 推理 -> 调参”的闭环。

## 3 天路径（新增工具并完成验证）

### Day 1: 新增工具

1. 新建一个 `ToolExecutor`（例如 `time_tool`）
2. `@Component` 注入后自动进入 `ToolRegistry`
3. 在 `Langchain4jAgentEngine` 的 `Lc4jTools` 中加对应 @Tool 适配方法

### Day 2: 安全与护栏

1. 给工具加参数校验（长度/白名单）
2. 验证超时与 guardrail 事件是否触发
3. 检查错误是否“降级而不崩主链路”

### Day 3: 回归与文档

1. 跑三条 verify 脚本
2. 增加至少一个“无命中/异常”测试样例
3. 更新 README + 本文件的“工具学习模板”

目标：独立完成“工具开发 -> 运行验证 -> 文档沉淀”。

