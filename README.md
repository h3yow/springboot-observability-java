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
       ├─ MetricsRegistry          接口维度计数（LongAdder，并发安全）——每个请求都记录，不受采样影响
       ├─ Sampler                  按接口采样（默认比例 + 按接口覆盖），结论按请求记忆、可运行时调整
       └─ MetricsExportQueue       有界队列 + 分发线程 + 导出超时 + 丢弃原因统计（仅放行被采样的事件）
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

## 采样策略

导出（不是指标）在进入有界队列之前先过一层 `Sampler`：

- **按接口配置 + 统一默认值**：接口名沿用稳定的 `METHOD /path`。每个接口命中自己的
  覆盖比例就用覆盖值，否则用 `observability.default-sample-rate`。
- **边界精确**：比例取值闭区间 `[0.0, 1.0]`。`0.0` = 一个都不导（阈值 0，
  任何哈希桶都不命中）；`1.0` = 一个都不漏（阈值 = 桶总数）。边界不经过浮点比较，
  不会出现“配了全采还被挡下 / 配了全弃还往外导”。内点比例映射到 `10000` 个桶，
  由 requestId 的稳定 FNV-1a 哈希取桶，`bucket < round(rate * 10000)` 即放行。
- **判定可复现**：判定是 `(requestId, 生效比例)` 的纯函数；结论（放/弃、比例、
  来源、桶、阈值）缓存在**请求上下文自身**上。同一请求在链路里（含扇出分支、并发、
  被反复调用）算多少次都得到同一个 `SamplingDecision`，不会一次导一次不导；
  缓存随请求生灭、每请求至多一条，天然有界，不会无界增长。
- **判定时点固定在请求入口**：`RequestContextFilter` 在**业务处理开始之前**
  （过滤器入口、`filterChain.doFilter` 之前）完成首次判定并把结论固定在该请求的
  上下文上；请求完成时（`finally`）只读取这个既有结论，绝不重新判定。因此：
  - 请求的采样结论在它**整个处理期间保持不变**，处理中途改比例不会翻盘；
  - 结论不会串到别的请求上——缓存在各请求自己的 `RequestContext` 实例上；
  - **只有调整之后才开始处理的请求**按新比例走；执行调整的那个 HTTP 请求本身，
    在进入接口时就已经按**旧**比例定案，不受自己发起的这次调整影响。
  - 采样统计（`considered/sampled/notSampled`）也在入口首次判定时计数；请求
    仍在处理中时，它的判定结果就已计入统计。
- **只影响导出，不影响统计**：成功/失败计数与耗时直方图对**每个**完成的请求照常记录，
  采样只决定“导不导出这一条事件”，不会把计数和耗时搞乱。
- **不伪造归属**：缺失请求上下文时采样直接拒绝（`NullPointerException`），
  不会给事件安一个假接口。
- **业务不被阻塞**：采样判定是内存计算（命中已缓存结论时只做一次引用读取，首次判定
  为读一次 volatile 配置 + 哈希 + 一次 CAS），不做 I/O；被挡下的事件根本不入队，
  队列压力随比例线性下降。
- **计数在并发下精确**：每个请求只有“赢下”缓存 CAS 的那一次判定更新计数，且一次只
  增加 `kept`/`dropped` 中的一个，`considered = kept + dropped` 对并发读者恒成立；
  `notSampledCount()` 顺序遍历累加，不会因并行遍历丢失更新。

### 配置（启动时）

| 配置 | 默认 | 含义 |
|---|---|---|
| `observability.default-sample-rate` | 1.0 | 未单独配置接口的统一采样比例，`[0.0, 1.0]` |
| `observability.sample-rates[<接口名>]` | 无 | 按接口覆盖，键为 `METHOD /path`，如 `observability.sample-rates[GET /demo/work]=0.1` |

默认 `1.0` 与历史行为完全一致：升级后不配任何采样项即全量导出（向后兼容）。
非法比例（负数、>1、NaN）在配置/调整时立即拒绝，配置保持不变。

### 运行时调整（无需重启）

比例存于一个 `volatile` 指向的**不可变配置快照**，调整通过 CAS 原子替换快照，
默认比例与单个接口比例并发修改互不覆盖：

- **新请求立即生效**：快照替换后，**之后才开始处理**的请求在入口首次判定时读到的
  就是新比例；
- **在途请求结论不变**：请求开始处理的那一刻，结论（放/弃）与其判定时所依据的比例
  就已经固定在该请求的上下文上，整个处理期间（含异步扇出分支）反复读取都是同一条
  结论。中途把比例从全采改成全弃，处理中的请求仍会导出；从全弃改成全采，处理中的
  请求仍不导出——不会翻盘，也不会串到别的请求；
- **执行调整的请求自身**：管理接口（如
  `POST /observability/sampling/default-rate?rate=0`）也是普通请求，它在进入接口时
  先按**调整前**的旧比例定案，然后才执行比例修改，因此它自己不会被本次调整影响
  （“先定案、后改配置”）；
- **并发计数精确**：只有“赢下”该请求缓存 CAS 的那一次判定更新计数（`LongAdder`），
  并发采样 + 并发调整（默认比例和单接口比例一起改）下不丢、不重；同一请求被多个
  线程/分支重复判定，拿到的是同一个结论实例，计数恰好一次。

HTTP 接口：

```bash
curl -s localhost:8080/observability/sampling/config                          # 当前默认比例与覆盖
curl -s localhost:8080/observability/sampling/stats                           # 各接口 considered/sampled/notSampled 与实际生效比例及来源
curl -s -XPOST 'localhost:8080/observability/sampling/default-rate?rate=0.1'  # 调整默认比例
curl -s -XPOST 'localhost:8080/observability/sampling/endpoint-rate?endpoint=GET%20%2Fdemo%2Fwork&rate=0.05'  # 调整单接口
curl -s -XPOST 'localhost:8080/observability/sampling/endpoint-rate?endpoint=GET%20%2Fdemo%2Fwork&remove=true' # 删除覆盖，回归默认
```

被挡下的事件以独立原因 `NOT_SAMPLED` 统计，可在 `/observability/drops` 读取，
并打 WARN 日志（含 `requestId`、接口、比例、比例来源、桶与阈值），便于复现。

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

| `DropReason` | 触发条件 | 阶段 |
|---|---|---|
| `NOT_SAMPLED` | 按采样策略主动不导出（事件根本没进入队列） | 采样（入队前，主动决策） |
| `QUEUE_FULL` | 队列已满（背压） | 导出（入队时，被动降级） |
| `EXPORT_TIMEOUT` | 单次导出超过 `export-timeout-millis` | 导出（导出时，被动降级） |
| `EXPORTER_UNAVAILABLE` | 导出器自报不可用，或上一次导出卡死未恢复 | 导出（被动降级） |
| `EXPORT_FAILED` | 导出器抛异常 | 导出（被动降级） |
| `SHUTTING_DOWN` | 管线关闭中/关闭后提交，或关闭时队列残留 | 关闭 |

`NOT_SAMPLED` 与其余原因的区别：前者是**入队之前、请求入口处的主动比例决策**
（系统健康、工作正常，只是按比例不放行；事件从未进入导出队列，也就不会计入队列的
`accepted`），后者都是**进入导出链路后的被动故障/背压**。排查“为什么没导出”时，
先看 `/observability/drops` 里递增的是 `NOT_SAMPLED` 还是 `QUEUE_FULL` 等队列原因：
前者去查采样配置（`/observability/sampling/config`）与该请求入口时的生效比例，后者
去查队列容量与导出器健康（`/observability/pipeline`）。两类原因分开计数、互不混入。

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
curl -s localhost:8080/observability/metrics           # 接口维度指标（全量，不受采样影响）
curl -s localhost:8080/observability/drops             # 丢弃原因计数（含独立的 NOT_SAMPLED）
curl -s localhost:8080/observability/pipeline          # 队列容量/积压/已接受/已导出
curl -s localhost:8080/observability/sampling/config   # 当前默认采样比例与按接口覆盖
curl -s localhost:8080/observability/sampling/stats    # 各接口采样计数与实际生效比例
curl -s -XPOST 'localhost:8080/observability/sampling/default-rate?rate=0.0'     # 运行时改成全弃
curl -s -XPOST 'localhost:8080/observability/sampling/default-rate?rate=1.0'     # 恢复全采
curl -s -XPOST 'localhost:8080/observability/exporter/availability?available=false'  # 模拟导出器宕机
curl -s -XPOST 'localhost:8080/observability/exporter/availability?available=true'   # 恢复
```

采样演练：把默认比例调成 `0.0` 后连续访问 `/demo/work`，业务响应仍 200、
`/observability/metrics` 的计数照常增长，但 `/observability/drops` 中只有
`NOT_SAMPLED` 递增、`/observability/pipeline` 的 `accepted` 不增长，WARN 日志打印
`requestId` 与判定依据（比例、来源、桶、阈值）。调回 `1.0` 后新请求立即恢复导出，
无需重启。

在途/新请求行为的本地验证（需要一个处理过程中可以挂起的接口；自动化测试用的是
`SamplingEndToEndTest` 里的测试专用门控端点 `/test/inflight`，思路相同，可手工复现）：

1. 起一个会阻塞一段时间的请求（例如在断点处挂起，或接口内等待外部信号），此时它已在
   过滤器入口按当前比例（如 `1.0`）完成判定；
2. 在它仍在处理时，`curl -XPOST '.../sampling/default-rate?rate=0.0'` 把比例改成全弃；
3. 放行该请求：它的事件**仍然导出**（在途请求结论不变），`/observability/drops` 中
   `NOT_SAMPLED` 不因它增长；
4. 改完之后**新发起**的请求则按 `0.0` 立即全弃，只有 `NOT_SAMPLED` 增长、`accepted`
   不增长。反向（`0.0 → 1.0`）同理：在途请求仍不导出，新请求立即恢复导出。

## 测试

```bash
mvn test
```

| 测试类 | 覆盖场景 |
|---|---|
| `RequestContextPropagationTest` | 16 请求 × 8 分支并发归属、重复注入拒绝、池化线程不串号、无上下文不伪造（无回归） |
| `MetricsRegistryConcurrencyTest` | 16 线程 × 5000 次并发计数精确一致、接口间隔离、直方图全覆盖（无回归） |
| `MetricsExportQueueTest` | 队列满非阻塞拒绝、导出超时、导出器不可用/卡死/异常、关闭语义、正常路径零丢弃（无回归） |
| `ObservabilityEndToEndTest` | 真实 HTTP 栈：请求标识回显、扇出归属、失败计数、导出器宕机时业务不受影响且丢弃可见（无回归） |
| `SamplerTest` | 边界 0.0/1.0 精确、按接口覆盖走默认、内点比例近似、同一请求反复/并发判定稳定（同一实例）、运行时改比例立即生效、在途请求翻盘防护、并发判定 + 默认/单接口比例并发调整计数精确、并发读统计恒满足 `considered = sampled + notSampled` 且 `notSampledCount` 单调不丢更新、缺失上下文不伪造、非法比例拒绝、跨实例可复现 |
| `SamplingEndToEndTest` | 真实 HTTP 栈：全弃时业务正常且指标仍全量、`NOT_SAMPLED` 与队列原因分列、全采全导、失败仍计数、按接口覆盖与运行时调整立即生效、**改比例时在途请求结论保持不变（全采→全弃仍导出、全弃→全采仍不导出）且改后新请求立即按新比例走** |

测试日志均打印请求标识与判定依据（`verdict:` 行），可复现验证。
