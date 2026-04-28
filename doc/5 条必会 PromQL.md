## 5 条“必会 PromQL”

1. 服务是否存活

up{job="chatagent-backend"}

1. 每秒请求数（QPS）

sum(rate(http_server_requests_seconds_count{job="chatagent-backend"}[1m]))

1. 接口 P95 延迟（示例：agent stream）

histogram_quantile(

  0.95,

  sum by (le) (

​    rate(http_server_requests_seconds_bucket{job="chatagent-backend",uri="/api/agent/chat/stream"}[5m])

  )

)

1. 4xx/5xx 错误速率

sum(rate(http_server_requests_seconds_count{job="chatagent-backend",status=~"4..|5.."}[5m]))

1. 线程池活跃线程

executor_active_threads{name="agentTaskExecutor"}