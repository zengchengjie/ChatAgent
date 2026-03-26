# Phase 2 安全与登录要点（Knowledge Snippets）

鉴权使用 JWT：请求头 `Authorization: Bearer <token>` 会在后端 `JwtAuthenticationFilter` 中被解析，解析成功后把 `uid`、`username`、`role` 放入 `SecurityContextHolder`。

后端安全配置里：

1) `POST /api/auth/login` 允许匿名访问；
2) `GET /api/health` 和 `/actuator/health` 允许匿名访问；
3) 其它所有请求默认要求认证（`anyRequest().authenticated()`）。

流式接口是 `POST /api/agent/chat/stream`。该接口先完成会话归属校验，然后创建 `SseEmitter` 并把长耗时处理丢给 `agentTaskExecutor` 在后台执行。此设计能避免阻塞容器线程，但也意味着一次请求会经历“写出响应后”的后续处理阶段：如果后续阶段抛出鉴权/异常并尝试写响应，就可能触发 `response is already committed` 这类日志。实践上应确保安全链对异步分发（`ASYNC`/`ERROR`）有合理策略，并在异常处理器里在 `response.isCommitted()` 时直接返回。

