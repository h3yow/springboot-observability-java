# Spring Boot 请求级可观测性采集

为每个进入服务的请求建立一致、可追溯的执行上下文，并在并发、异步与降级场景下给出稳定且可解释的采集结论。

## 架构总览

```
请求 → RequestContextFilter（建上下文/记指标/提交事件/清理）
         ├─ RequestContextHolder（ThreadLocal 绑定）
         ├─ ContextPropagator（异步分支传播）
         ├─ MetricsRegistry（接口维度计数 + 耗时桶）
         └─ BoundedEventExporter（有界队列 + 采样 + 降级导出）
GET /observability/metrics → MetricsController（本地读取）
```

## 上下文传播规则

- **创建**：`RequestContextFilter`（最高优先级）为每个请求创建 `RequestContext`。请求头 `X-Request-Id` 合法（非空且 ≤128 字符）则采纳，否则生成 UUID；响应头回写 `X-Request-Id` 供客户端核对归属。
- **绑定**：`RequestContextHolder` 基于 ThreadLocal。同一线程绑定**不同**请求标识时抛出 `IllegalStateException`，拒绝重复注入造成的错误归属；入口过滤器发现线程残留上下文时先清理再绑定。
- **传播**：异步/并发分支必须通过 `ContextPropagator.wrap(...)` 或注入 `observabilityExecutor`（已装配 `TaskDecorator`）执行。提交时捕获快照，执行线程上绑定、执行后恢复原状态——分支归属同一请求，且线程池复用不串号。
- **清理**：过滤器 `finally` 中强制 `clear()`，杜绝跨请求复用。
- **自指排除**：`/observability/**` 不参与采集。

## 指标语义

按接口维度（key 为 `METHOD /path`，如 `GET /demo/ok`，命名一经创建保持稳定）：

| 字段 | 语义 |
|---|---|
| `total` | 调用总次数（= success + failure） |
| `success` | HTTP 状态 < 500 且无异常 |
| `failure` | HTTP 状态 ≥ 500 或抛出异常 |
| `latencyBuckets` | 耗时分布：`lt10ms / lt50ms / lt100ms / lt500ms / lt1000ms / gte1000ms`，桶合计 = total |

并发一致性由 `ConcurrentHashMap.computeIfAbsent` + `LongAdder` 保证：计数不丢失、不重复。

> 注：异步接口（`/demo/async`）的指标在初始派发时记录。

## 采样与降级策略

配置项（`application.properties`，前缀 `observability`）：

| 配置 | 默认 | 说明 |
|---|---|---|
| `sample-rate` | 1.0 | 采样率 0.0~1.0，未命中记 `SAMPLED_OUT` |
| `queue-capacity` | 1024 | 导出缓冲队列容量，满则拒绝记 `QUEUE_FULL` |
| `export-timeout-millis` | 500 | 单次导出超时，超时记 `EXPORT_TIMEOUT` |
| `exporter-unavailable` | false | 降级开关，开启时直接拒绝记 `EXPORTER_UNAVAILABLE` |

保证：

- **不阻塞业务**：`submit` 只做采样判断与非阻塞 `offer`，O(1) 返回；
- **不无界增长**：`ArrayBlockingQueue` 定容，溢出即拒绝；
- **不静默丢弃**：每种拒绝/丢弃都有独立计数，经 `/observability/metrics` 的 `exporter.drops` 可读。

丢弃原因（`DropReason`）：`SAMPLED_OUT`（采样未命中）、`QUEUE_FULL`（队列满）、`EXPORTER_UNAVAILABLE`（降级开关）、`EXPORT_TIMEOUT`（导出超时）、`EXPORT_FAILURE`（导出异常）。

## 本地验证

```bash
mvn spring-boot:run

# 产生流量（可带自有请求标识）
curl -H 'X-Request-Id: my-req-1' localhost:8080/demo/ok
curl localhost:8080/demo/async
curl localhost:8080/demo/fail

# 本地读取指标与降级计数
curl localhost:8080/observability/metrics
```

## 测试

```bash
mvn test
```

| 测试类 | 覆盖场景 |
|---|---|
| `ContextAttributionTest` | 32 路并发请求归属校验（响应头 + 两个异步分支的标识一致）、重复注入拒绝、传播后线程无残留 |
| `MetricsConsistencyTest` | 200 并发 × 16 线程计数精确性（total/success+failure/桶合计三者一致）、失败请求单独计数 |
| `DegradationTest` | 队列满拒绝且不阻塞、降级开关、采样丢弃、导出超时、导出异常、正常路径精确导出 |
| `LocalMetricsEndpointTest` | 本地接口读取指标与降级计数 |

所有测试日志均打印请求标识与判定依据（`pass=true/false`），可复现验证。
