# Spring 异步分发在 SSE 场景的整体流程（文字版）

本文用于说明：在 `SseEmitter` 流式接口中，什么是“异步分发阶段（ASYNC dispatch）”，以及它在一次请求中的位置。

## 1. 一句话定义

异步分发阶段是同一个 HTTP 请求的“第二段生命周期”：

- 第一段：普通 `REQUEST` 分发（进 Controller 前后）
- 第二段：异步 `ASYNC` 分发（请求已切到异步后，由容器再次调度）

在流式输出（SSE）里，这个第二段非常常见。

## 2. 对应到本项目（`/api/agent/chat/stream`）的时序

1. 客户端发起请求到 `POST /api/agent/chat/stream`
2. 请求先进入 Spring Security 过滤链（JWT、授权、限流等）  
   这是第一段：`DispatcherType.REQUEST`
3. 进入 `AgentController.chatStream(...)`
4. Controller 创建 `SseEmitter`
5. Controller 把实际生成回答的工作提交到后台线程池（`agentTaskExecutor`）
6. Controller 立即返回 `SseEmitter`，HTTP 连接保持打开（长连接）
7. 后台线程开始持续 `emitter.send(...)` 推送 `delta/tool_start/done` 等事件
8. 容器在后续处理里会触发同一请求的异步调度  
   这是第二段：`DispatcherType.ASYNC`
9. 如果异步阶段再次走到安全/异常处理逻辑，且这时响应已经写出，可能出现：  
   `response is already committed`

## 3. 为什么会“前端有回答，但后端报 AccessDenied”

因为回答可能已经在步骤 7 写给客户端了。  
随后在步骤 8 的异步或错误分发中，又触发了授权失败/异常翻译，框架尝试再写 401/403 或错误页，就会出现“响应已提交”的异常日志。

换句话说：

- 业务输出成功（用户看到了回答）
- 安全异常发生在后续分发阶段（日志报错）

两者可以同时成立。

## 4. 工程实践建议（SSE + Security）

- 在安全配置中显式考虑 `DispatcherType.ASYNC` 和 `DispatcherType.ERROR`
- 自定义 `AuthenticationEntryPoint` / `AccessDeniedHandler` 时，先判断 `response.isCommitted()`
- 对 `OncePerRequestFilter` 场景，必要时评估是否需要跳过异步分发
- 排查日志时看到 `asyncDispatch` + `response is already committed`，优先怀疑异步阶段重复进入过滤/异常链路

## 5. 快速排障清单

- 是否为 `SseEmitter` / `DeferredResult` / `Callable` 等异步接口
- 是否只在流式接口复现
- 安全链是否对 `ASYNC`/`ERROR` 分发有明确策略
- 自定义异常处理器是否在 committed 响应上继续写出
- 日志里是否出现 `AsyncContextImpl.doInternalDispatch` / `asyncDispatch`

---

如果后续要做 Agent 流式能力扩展（多工具并发、长会话、重试），优先把“请求两阶段（REQUEST + ASYNC）”当作默认模型来设计和排查。
