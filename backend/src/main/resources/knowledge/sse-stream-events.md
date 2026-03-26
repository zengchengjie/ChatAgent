# SSE 流式事件（Phase 2 Knowledge）

本项目的流式端点是 `POST /api/agent/chat/stream`，返回一个 `SseEmitter` 长连接：后端在生成过程中持续 `send` 事件给前端。

事件类型主要有：`delta`（分块正文）、`tool_start`（模型准备执行某个工具）、`tool_end`（工具执行结束，附带 ok/detail）、`done`（本轮完成）、`error`（发生异常）。

下面这段是用于测试“长段落二次切块”的知识：本项目的 `AgentService.chatStream` 会先把用户输入写入消息表，然后进入循环（最多 `agent.max-steps`）。每一轮都会从数据库重载会话历史，把历史转换为 LLM 的 `messages`，并且把 `ToolRegistry.toolsJson()` 作为 tools 传入。若模型返回了 `tool_calls`，后端会先把 `assistant`（包含原始 tool_calls_json）落库，再逐个工具执行：在调用工具之前发送 `tool_start`（包含工具名和 id），执行成功/失败后发送 `tool_end`（包含 ok=true 或失败信息 detail），同时把工具结果以 `tool` 角色落库。若模型本轮没有 `tool_calls`，后端会把最终文本按固定 chunkSize 以 `emitTextDeltas` 切分，并依次发送 `delta` 事件给客户端，之后把最终 assistant 文本落库，并发送 `done` 作为结束标记；无论成功或失败，都会在 `finally` 中 `emitter.complete()` 结束连接。理解“写出响应（先返回 emitter）”与“后台继续推送”的两阶段生命周期，有助于定位流式接口中与安全/错误处理相关的日志问题。

