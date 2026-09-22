# springboot-observability-java

请求级可观测性采集链路：每个进入服务的请求都获得一致、可追溯的执行上下文；
按接口维度采集调用次数、成功/失败与耗时分布；采集/导出链路在并发、异步与
降级场景下保持稳定且可解释。

## 架构总览

```
HTTP 请求
  └─ RequestContextFilter          创建 RequestContext（每请求恰好一次），绑定到请求线程
       ├─ RequestContextHolder     ThreadLocal 持有；attach 拒绝重复注入；finally 中 detach
       ├─ ContextPropagator        包装 Runnable/Callable/Executor，把上下文带到异步分支
       ├─ MetricsRegistry          接口维度计数（LongAdder，并发安全）
       └─ MetricsExportQueue       有界队列 + 分发线程 + 导出超时 + 丢弃原因统计
            └─ MetricsExporter     导出器（默认 LoggingMetricsExporter，可切换可用性）
```

## 上下文传播规则

1. **创建**：`RequestContextFilter` 在请求入口创建唯一的 `RequestContext`
   （`requestId` + `endpoint` + 起始时间）。入站携带 `X-Request-Id` 时沿用，
   否则生成 UUID；响应头始终回写该 id，便于调用方关联。
2. **绑定**：上下文通过 `RequestContextHolder`（ThreadLocal）绑定到当前线程。
   `attach` 时若线程上已存在上下文则抛出 `IllegalStateException`——
   重复注入或跨请求复用会被立即拒绝，而不是静默改归属。
3. **传播**：异步/并发分支必须使用 `ContextPropagator` 包装的
   `Runnable`/`Callable`/`Executor`（或直接注入名为 `observabilityExecutor`
   的 Bean）。提交时捕获当前上下文，在工作线程上执行前绑定、执行后解除：
   - 工作线程已绑定**同一**上下文（重入）→ 直接运行；
   - 工作线程绑定了**不同**请求的上下文 → 抛错，拒绝错误归属；
   - 提交线程无上下文 → 任务以无归属方式运行，不伪造归属。
4. **清理**：绑定方必须在 `finally` 中 `detach`，池化线程不会把上下文
   泄漏给无关任务（`pooledThreadsDoNotLeakContextBetweenTasks` 测试覆盖）。

## 指标语义

指标名稳定为 `METHOD /pattern`（如 `GET /demo/work`），不使用含路径参数的原始 URI。
每个接口的指标（`EndpointMetrics`）：

| 字段 | 语义 |
|---|---|
| `total` | 已完成的请求总数 |
| `success` | 成功完成数（HTTP 状态 < 500） |
| `failure` | 失败完成数 |
| `latencyBucketsMs` | 固定边界的耗时直方图（ms）：`le=5/10/25/50/100/250/500/1000/2500/5000` 与 `+Inf` |
| `latencyAvgMs` / `latencyMaxMs` | 平均 / 最大耗时 |

不变量：`total == success + failure`。计数基于 `LongAdder`，并发写入不丢失、
不重复（`MetricsRegistryConcurrencyTest` 以 16 线程 × 5000 次验证精确相等）。

## 采样与降级策略

导出链路的容量与超时均可配置（`application.properties`）：

| 配置 | 默认 | 含义 |
|---|---|---|
| `observability.export-queue-capacity` | 1024 | 导出队列有界容量 |
| `observability.offer-timeout-millis` | 0 | 业务线程入队最长等待；0 = 纯非阻塞 |
| `observability.export-timeout-millis` | 500 | 单次导出超时，超时事件按 `EXPORT_TIMEOUT` 丢弃 |
| `observability.request-id-header` | X-Request-Id | 请求标识头 |

保证：**不阻塞业务响应**（入队最多等待 `offer-timeout-millis`）、**不无界增长**
（容量固定）、**不静默丢弃**——每个被拒绝/丢弃的事件都计入且仅计入一个原因：

| `DropReason` | 触发条件 |
|---|---|
| `QUEUE_FULL` | 队列已满（背压） |
| `EXPORT_TIMEOUT` | 单次导出超过 `export-timeout-millis` |
| `EXPORTER_UNAVAILABLE` | 导出器自报不可用，或上一次导出卡死未恢复 |
| `EXPORT_FAILED` | 导出器抛异常 |
| `SHUTTING_DOWN` | 管线关闭中/关闭后提交，或关闭时队列残留 |

导出器卡死（忽略中断）时，后续事件以 `EXPORTER_UNAVAILABLE` 快速拒绝，
不会堆积在卡死任务之后；导出器恢复后自动继续。

## 本地读取与验证

启动服务：

```bash
mvn spring-boot:run
```

本地读取端点（服务自身能力，无需外部依赖）：

```bash
curl -s localhost:8080/demo/work                       # 产生流量（响应头含 X-Request-Id）
curl -s localhost:8080/demo/fanout                     # 异步扇出：分支与父请求同 requestId
curl -s localhost:8080/observability/metrics           # 接口维度指标
curl -s localhost:8080/observability/drops             # 丢弃原因计数
curl -s localhost:8080/observability/pipeline          # 队列容量/积压/已接受/已导出
curl -s -XPOST 'localhost:8080/observability/exporter/availability?available=false'  # 模拟导出器宕机
curl -s -XPOST 'localhost:8080/observability/exporter/availability?available=true'   # 恢复
```

降级演练：关闭导出器后再访问 `/demo/work`，业务响应仍 200，
`/observability/drops` 中 `EXPORTER_UNAVAILABLE` 递增，且 WARN 日志打印
`requestId` 与丢弃原因。

## 测试

```bash
mvn test
```

| 测试类 | 覆盖场景 |
|---|---|
| `RequestContextPropagationTest` | 16 请求 × 8 分支并发归属、重复注入拒绝、池化线程不串号、无上下文不伪造 |
| `MetricsRegistryConcurrencyTest` | 16 线程 × 5000 次并发计数精确一致、接口间隔离、直方图全覆盖 |
| `MetricsExportQueueTest` | 队列满非阻塞拒绝、导出超时、导出器不可用/卡死/异常、关闭语义、正常路径零丢弃 |
| `ObservabilityEndToEndTest` | 真实 HTTP 栈：请求标识回显、扇出归属、失败计数、导出器宕机时业务不受影响且丢弃可见 |

测试日志均打印请求标识与判定依据（`verdict:` 行），可复现验证。
