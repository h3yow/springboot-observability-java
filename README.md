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
       ├─ MetricsRegistry          接口维度计数（LongAdder，并发安全）——不受采样影响，始终全量
       ├─ Sampler                  按接口配置、可运行时调整的导出采样；判定结果随请求记忆
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

## 导出采样（按接口、可运行时调整）

采样只作用于**导出**这一步：每个请求的 `MetricsRegistry` 计数（次数、成败、耗时）
始终全量记录，不受采样影响。判定在请求入口由 `Sampler.decide(context)` 完成。

**配置**（`application.properties`，均可在启动时给定）：

| 配置 | 默认 | 含义 |
|---|---|---|
| `observability.sampling.default-ratio` | 1.0 | 未单独配置接口使用的导出比例，范围 `[0,1]` |
| `observability.sampling.endpoints.<接口名>` | 无 | 接口覆盖比例，键为稳定的 `METHOD /pattern` 名 |

比例语义与边界：

- `1.0` = 该接口/默认**一个都要**，事件一定导出，绝不被采样挡下；
- `0.0` = **一个都不要**，事件一定不导出；边界取值先于任何哈希判定，行为与配置严格一致；
- `(0,1)` 之间：对 `requestId` 做无随机种子的确定性哈希（FNV 风格），落入前
  `ratio × 10000` 个桶则导出，粒度 0.01%。同一 `requestId` 在任何机器、任意次数下
  结论相同。

判定的可复现性与归属安全：

- 判定结果（`SamplingDecision`：`sampled/endpoint/ratio/basis`）在请求首次判定后
  **记忆在该请求的 `RequestContext` 上**，同请求在任意分支、任意线程、任意次数重复
  判定都返回同一结论，且只计数一次——不会“一次导、一次不导”；判定缓存在请求结束时
  随上下文一起回收，**不会无界增长**。
- 运行时改比例只发布一份不可变配置快照（写时复制 + 原子替换）：**新请求立即**按新
  比例走；**在途请求**沿用记忆中的旧结论，归属不会被改掉，也不会串到别的请求。
- 上下文缺失时 `decide(null)` 直接抛 `IllegalArgumentException`，不伪造接口归属。
- 采样判定本身不做任何阻塞操作，业务响应依旧不被拖慢；被挡下的事件**根本不入队**，
  因此还能进一步降低有界队列压力。

**运行时调整（无需重启）**：

```bash
# 改默认比例，新请求立即生效
curl -s -XPOST 'localhost:8080/observability/sampling/default?ratio=0.1'
# 给单个接口设置覆盖比例
curl -s -XPOST 'localhost:8080/observability/sampling/endpoint?endpoint=GET%20%2Fdemo%2Fwork&ratio=0.25'
# 省略 ratio = 删除该接口覆盖，回落到默认比例
curl -s -XPOST 'localhost:8080/observability/sampling/endpoint?endpoint=GET%20%2Fdemo%2Fwork'
# 查看默认值、覆盖、各接口当前实际生效比例与 sampledIn/sampledOut 计数
curl -s localhost:8080/observability/sampling
```

`/observability/sampling` 返回示例：

```json
{
  "defaultRatio": 1.0,
  "endpointOverrides": {"GET /demo/work": 0.25},
  "effectiveRatios": {"GET /demo/work": 0.25, "GET /demo/fail": 1.0},
  "stats": {
    "GET /demo/work": {"effectiveRatio": 0.25, "decisions": 120, "sampledIn": 31, "sampledOut": 89}
  },
  "totalSampledOut": 89
}
```

**默认行为与兼容范围**：默认比例为 `1.0` 且无接口覆盖，即与引入采样前完全一致——
所有事件照常导出；既有配置、队列/超时参数与降级语义不受影响。`SAMPLED_OUT` 是新增
的丢弃原因，旧代码读取丢弃计数时它会作为一个额外键出现。

## 采样与降级策略

导出链路的容量与超时均可配置（`application.properties`）：

| 配置 | 默认 | 含义 |
|---|---|---|
| `observability.export-queue-capacity` | 1024 | 导出队列有界容量 |
| `observability.offer-timeout-millis` | 0 | 业务线程入队最长等待；0 = 纯非阻塞 |
| `observability.export-timeout-millis` | 500 | 单次导出超时，超时事件按 `EXPORT_TIMEOUT` 丢弃 |
| `observability.request-id-header` | X-Request-Id | 请求标识头 |

保证：**不阻塞业务响应**（入队最多等待 `offer-timeout-millis`；采样判定不阻塞、
被采样挡下的事件根本不入队）、**不无界增长**（容量固定；采样判定缓存随请求回收）、
**不静默丢弃**——每个未导出的事件都计入且仅计入一个原因：

| `DropReason` | 性质 | 触发条件 |
|---|---|---|
| `SAMPLED_OUT` | **有意的策略决策，非故障** | 请求被采样比例判定为不导出；事件从未进入队列 |
| `QUEUE_FULL` | 导出链路降级 | 队列已满（背压） |
| `EXPORT_TIMEOUT` | 导出链路降级 | 单次导出超过 `export-timeout-millis` |
| `EXPORTER_UNAVAILABLE` | 导出链路降级 | 导出器自报不可用，或上一次导出卡死未恢复 |
| `EXPORT_FAILED` | 导出链路降级 | 导出器抛异常 |
| `SHUTTING_DOWN` | 生命周期 | 管线关闭中/关闭后提交，或关闭时队列残留 |

`SAMPLED_OUT` 与队列/超时/失败类原因互斥且位置不同：采样挡下发生在入队之前，由
`Sampler` 计数（按接口 `sampledOut` 与全局 `totalSampledOut`，并汇总到
`/observability/drops`）；其余原因发生在事件已入队之后，由导出队列计数。因此本地
读取能明确区分“主动不导”与“想导但没导成”。

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
curl -s localhost:8080/observability/metrics           # 接口维度指标（采样不影响，始终全量）
curl -s localhost:8080/observability/drops             # 丢弃原因计数（含 SAMPLED_OUT）
curl -s localhost:8080/observability/pipeline          # 队列容量/积压/已接受/已导出
curl -s localhost:8080/observability/sampling          # 默认/覆盖比例、实际生效比例、采样计数
curl -s -XPOST 'localhost:8080/observability/exporter/availability?available=false'  # 模拟导出器宕机
curl -s -XPOST 'localhost:8080/observability/exporter/availability?available=true'   # 恢复
```

采样演练（验证不阻塞、指标不乱、丢弃原因可区分）：

```bash
# 让 /demo/work 一个都不导出
curl -s -XPOST 'localhost:8080/observability/sampling/endpoint?endpoint=GET%20%2Fdemo%2Fwork&ratio=0.0'
curl -s localhost:8080/demo/work                        # 业务照常 200，响应头仍含 X-Request-Id
curl -s localhost:8080/observability/metrics           # total/success 照常 +1（采样不影响指标）
curl -s localhost:8080/observability/drops             # SAMPLED_OUT +1，QUEUE_FULL/EXPORT_* 不变
curl -s localhost:8080/observability/sampling          # 该接口 sampledOut 计数、实际生效比例 0.0
# 运行时改回全采，随后的新请求立即导出，在途请求不受影响
curl -s -XPOST 'localhost:8080/observability/sampling/endpoint?endpoint=GET%20%2Fdemo%2Fwork&ratio=1.0'
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
| `SamplerTest` | 默认/按接口比例分布、`0.0`/`1.0` 边界（默认与覆盖均覆盖）、运行时调整仅对新请求立即生效、同请求重复判定稳定、16 线程并发判定+并发改比例下计数不丢不重、无上下文拒绝、非法比例拒绝 |
| `RequestContextFilterSamplingTest` | 采样挡下时成败/耗时指标仍记录且事件不入队；全采时失败请求计入 failure 且正常导出；上下文在 finally 解除 |
| `ObservabilityEndToEndTest` | 真实 HTTP 栈：请求标识回显、扇出归属、失败计数、导出器宕机时业务不受影响且丢弃可见；采样 0/1 边界、运行时翻转立即生效、`/observability/sampling` 生效比例与计数 |

测试日志均打印请求标识与判定依据（`verdict:` 行），可复现验证。
