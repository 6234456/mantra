# M3 内核接入准备：收敛求解与整次运行预算

> 状态：待开工的可行性审查，**不是 M3 实现或验收记录**。
> 审查日期：2026-10-04。只读核对锁定源码；本次没有修改 Normein、版本或引擎实现，
> 没有为这份准备稿运行 Gradle。

依据：[路线图 M3](../roadmap.md#M3-跨案例延续与有界迭代v04)、
[RFC 0001 契约 G 与资源上限](../rfc/0001-normein-dsl-kernel-extensions.md)。
锁定基线见 [normein-build.lock](../../normein-build.lock)：`normein-dsl 0.3.0`，
`0a3ae1de844c92635fbbc03406a13cb0e8920c03`。
下面的源码位置均相对于锁定 checkout 的
`core-libs/normein-dsl/src/main/kotlin/com/xqiou/normein/dsl/`，不是对未来 API 的假设。

## 可直接使用的公开 API

| 能力 | 已有公开入口及源码 | 对 M3 的意义与限制 |
| --- | --- | --- |
| 纯宿主高阶函数 | `library/DslCallableModels.kt`：`DslFunctionSpec`、`DslFunctionSignature`、`dslFunctionHandler { arguments, overloadId, runtime -> ... }`，默认 `purity = PURE` | 可在 `mantra.calc` 注册收敛函数。回调仍由内核执行，宿主不解释 lambda、不访问应用数据或文件。 |
| 严格调用回调 | `runtime/DslFunctionRuntime.kt:27–40`：`invokeCallable(callable, arguments)`、`charge(counter, amount)`、`checkpoint()` | 每次迭代调用同一个内核回调，捕获变量与词法作用域保持内核语义。使用严格一参数调用，不用截取多余参数的 leading-argument 扩展。 |
| 回调静态契约 | `type/DslType.kt`：`DslTypes.function(signatures)`、`DslFunctionTypeSignature` | 可声明 number → number 回调。首次实现应限制为标量数值，不能未经设计就声称支持任意记录、向量或业务状态。 |
| 回调与 trace 同源 | `runtime/DslKernelEvaluator.kt:921–955`：公开回调调用使用 `currentTraceContext()`，递归调用继续使用原内核 evaluator | FULL 路径能记录真实回调表达式执行，仍使用编译后的 source index。重复执行同一源位置是多次真实执行，不能去重成一次或用最终值填补历史。 |
| 单次求值预算 | `runtime/DslBudget.kt`：`DslBudgetCounter`、`DslBudgetLimits`、`DslBudgetUsage`；`DslEvaluationRequest.budgetLimits` | 可对单个公式及回调的全部执行限制节点、函数调用、数值操作、迭代等工作。独立创建的宿主 `DslBudget` 不会自动进入内核。 |
| 取消与截止时间 | `DslEvaluationInput`、`DslValueOnlyInput`、`DslValueOnlyStableInput` 的 `cancellation` / `deadline`；`DslCancellation` | 同一个宿主运行控制可传入所有公式及链接计算；取消、截止时间由内核 checkpoint/charge 检查。宿主解析、成员枚举、归约及链接读取仍须自己检查控制。 |
| 无审计求值 | `runtime/DslExecutionPlan.kt`：`DslExecutionSessionOptions.budgetLimits`、`openValueOnlySession`、`evaluateValueOnly(input, sink)` | 延续 M2 的 VALUE_ONLY 会话。预算在行准备和每个 output 前重置；会话复用不意味着累积预算。公开 session 没有每行替换预算上限的参数。 |
| 审计求值与用量 | `DslEvaluationOutcome.Success/Failure.receipt`；`identity/DslDurableExecutionModels.kt:360–402` 的 `budgetUsage`；`trace/DslTrace.kt` 的 `DslTraceLimits` | FULL/receipt 路径可读取成功或失败的具体 `DslBudgetUsage`。trace 配额与计算预算分开；trace 截断应标记，不应让已收敛值变成失败。 |
| VALUE_ONLY 运行成本指标 | `runtime/DslExecutionCostMetrics.kt:218–229`：可选 `DslExecutionCostMetricsProvider`、`executionCostSnapshotOrNull()`、`executionCostDeltaSince()` | 可观测行数、输出数、输入准备、后端、资源复用、预算失败次数。它们不是 `EVALUATED_NODES` / `NUMERIC_OPERATIONS` 等预算计数，不能拿来冒充整次运行的实际内核工作量。 |
| VALUE_ONLY 借用结果 | `runtime/DslValueOnlyExecution.kt`：`DslBorrowedValueRow` | 只有值、descriptor、诊断等；没有公开 `budgetUsage`。借用 row 不能在 sink 回调结束后保留。 |

`invokeCallable` 在实现里收费 `FUNCTION_CALLS`，回调体继续使用当前预算。收敛函数自身的
Kotlin 循环、差值、绝对值与比较不会因为回调存在而自动收费，必须显式调用 `runtime.charge`。
不要 catch 内核预算、取消或截止时间异常后转成“未收敛”，它们应保留自己的失败原因。

## `calc/converge` 的最小契约建议

建议形式为 `(calc/converge f init max-iterations tolerance)`，匹配 RFC G，注册于
`mantra.calc@2`。下面是待 M3 工作包锁定的宿主契约，并非已可运行的函数。

1. `init` 与回调结果是非 nil 标量数值；使用 BigDecimal，不经 Double。`max-iterations`
   是精确整数，范围 `1..1000`；`tolerance >= 0`。非法参数或回调结果是技术错误。
2. 从 `current = init` 开始，每步先收费并调用 `f(current)` 得到 `next`；
   若 `abs(next - current) <= tolerance`，返回 **next**。计数是一致的回调调用次数，
   不是先把初值算作一次迭代。容差为零时要求精确不动点。
3. 达到上限仍未满足条件，产生 `MANTRA-CALC-NOT-CONVERGED`，不返回最后一次近似值，
   不自动用闭式解救场。两周期振荡、发散以及存在解但固定点迭代不收敛，都属此情况。
4. 内核宿主函数异常只允许 `DSL-*` 代码（`DslFunctionInvocationException` 有格式验证）。
   handler 应抛 `DSL-MANTRA-CALC-NOT-CONVERGED`，Mantra 在统一诊断投影中明确映射到
   公共 `MANTRA-CALC-NOT-CONVERGED`，类别 EVALUATION，保留调用源位置和成员坐标。
   不把所有回调异常都映射成这一个代码。
5. 每步计 `ITERATIONS`，宿主数值工作计 `NUMERIC_OPERATIONS`；`invokeCallable` 的收费
   由内核负责，避免重复计其回调体。嵌套收敛调用共用该公式的内核预算。

相邻迭代差满足容差，是本函数的停止条件；它不自动证明业务方程残差或全局误差满足相同容差。
需要这种保证的应用应另设通用 check/reconcile，并用独立闭式解核对。首版不要把某个业务的
税率、利润口径、现金流方程或单元格 ID 写入 handler。

## 审计证据的可行边界

当前内核支持 FULL 真实回调 trace，但 `DslFunctionRuntime` 没有公开的“自定义 trace 事件”
或“给每个迭代附加序号/残差属性”接口。因此：

- 可先展示内核实际记录的重复回调源步骤、真实结果与截断标志，沿用 M1 总审计配额；
- 不能凭最终数值重演迭代，再把重演过程当作原执行证据；
- 在没有独立宿主证据契约前，不能宣称 trace 已提供完整的迭代次数或每步残差；
- 若 M3 要求结构化迭代序号、停止原因、残差历史，需先决定这是独立宿主返回证据，还是向
  Normein 提交可审阅的 trace 扩展 RFC。仅实现 RFC G 的标量函数不以该扩展为前置阻塞。

Trace 的 `maxEvents`、`maxChildrenPerNode`、text 配额均有界。一次迭代很多的合法计算可能
只保留部分执行证据；`TRUNCATED` 与计算失败应保持区分，VALUE_ONLY 不生成伪 trace。

## 整次运行预算：已有能力与真实缺口

路线图写的是成员组合 × 行 × 链接案例的运行上限，以及领域函数按实际工作量收费。这一层
可以用宿主计数器实现，不必假设内核已有 run budget。建议先固定下列分开的单位：

| 宿主运行维度 | 收费位置 | 防止的失控 |
| --- | --- | --- |
| 唯一链接案例与链接边数 | 案例解析/链接绑定前；已缓存案例不伪计为再次执行，但访问边仍受限 | 分叉链接图、不断访问缓存结果的巨量链接 |
| 链接深度 | 递归栈进入前；退出释放，检测规范路径及绑定身份的环 | 深层依赖及循环 |
| 成员组合和预计坐标工作 | 笛卡尔积展开前，用溢出安全乘法校验；域变化后再核对 | 先创建巨大列表再检查预算 |
| 实际公式执行、任务执行 | `MemberGraph` / `KernelExecution` 的真正执行点，缓存复用单独报告 | 链接案例和多维节点累积放大 |
| 宿主归约、输入导入与库函数工作 | 在扫描/迭代前或逐步收费，并检查共同取消/期限 | 避开内核表达式计数的 Kotlin 工作 |
| 墙钟/取消 | 所有宿主阶段及传入两条内核求值路径的共同运行控制 | 很少任务但每个任务都很昂贵 |

预算生命周期属于一次顶层请求，其所有链接共享同一个上下文，不能每个链接案例重新领取
一整份额度。增量请求只给实际重算收费，但绑定、成员枚举、投影等仍有工作；
`RecalculationStats` 的任务/公式计数不足以代表这些阶段全部成本。失败停止整个请求，报告
阶段、上限、已用量和案例/节点/坐标，不作为 BUSINESS 失败，也不返回冒充完整运行的结果。

库函数计费需要随 `mantra.calc@2` 明确，并覆盖已有函数：`dim/*` 扫描成员，`table/band`
与 `calc/stepwise` 扫描带宽，`alloc/pro-rata` 的排序/分摊，`alloc/capped` 每轮活跃成员，
`fin/*` 的期限/现金流运算，以及 `calc/converge` 每步。当前统一 handler 只按参数个数收费，
不能把“参数收费 + 有内核预算”描述为已经按实际工作量收费。先写统一计费单位与契约测试，
不逐函数臆测纳秒成本。

### 不能依赖的当前 API

锁定版本没有：

1. 传给不同 evaluation/session 的共同 `DslBudget` 入口；内部 reset/seed 不是公共接口。
2. VALUE_ONLY 每个 output 的完整 `DslBudgetUsage` 回调，或 live `runtime.usage()`。
3. VALUE_ONLY 每行动态调整各内核预算 counter 的公共参数。
4. 宿主自定义 trace 事件入口。

所以，“所有 VALUE_ONLY 公式的全部真实内核 counter 合并成一个精确、可抢先终止的全局额度”
**当前不能仅靠公共 API 实现**。cost metrics 不是这些 counters；切成 FULL receipt 再求和
会破坏 M2 的无审计快路径，且只在公式结束后发现总量超限，不能冒充同一能力。

按路线图采用宿主任务/成员/链接额度 + 每公式真实内核预算 + 共同期限，可以推进 M3；
它是分层预算，不是一个精确内核计数器之和。若维护者将后者列为 M3 硬退出条件，则这是
**真实的 Normein 新 revision 阻塞**：需要先提交 RFC，获得公开共享预算/用量能力并发布，
再按契约测试接入及更新锁。没有已发布 revision 时不得先写内部 cast、反射、修改 `.deps`，
也不得预先声称此条件已实现。路线图 T3 的整数策略/文档协同发布仍单独跟踪。

## 两个独立闭式解验收场景

应用位置在 M3 工作包决定；建议放通用演示案例，不给引擎添加业务特例。数值是虚构事实，
不涉及真实税制口径。独立脚本用 Fraction/Decimal 解方程，不复制 `calc/converge` 循环。

### 奖金以扣除奖金后的利润为基数

设奖金前利润 `P = 100000`，比例 `r = 0.10`，奖金 `B = r × (P − B)`。
闭式解为 `B = rP / (1 + r) = 100000 / 11`，金额按 half-up 两位为 `9090.91`。
方案回调可每步按相同金额规则舍入，初值 0，容差 0；独立校验同时核对方程残差和
`B`、`P − B` 的金额。不要把未舍入的闭式解与有舍入的迭代不加说明地逐位比较。

覆盖比例零、利润零、有符号利润、预算耗尽、以及固定点方法不收敛但方程有解的情况：
`P=1,r=1` 时闭式解 `0.5`，从 0 迭代 `1−B` 却形成 0/1 两周期，必须报未收敛。
舍入造成两个相邻金额之间振荡也应明确失败，不自动选小值或大值。

### 现金流净额到毛额的循环扣款

设要求净流入 `N = 1000`，扣款比例 `q = 0.25`，毛流入满足 `G = N + qG`。
闭式解 `G = N / (1−q) = 4000/3`，half-up 两位为 `1333.33`，与 RFC G 的样本一致。
回调 `(decimal/round (+ net (* rate gross)) 2)`、初值 `net`、上限 60、容差 0 应达到
精确金额不动点；独立核对毛额、扣款与净额以及舍入残差。

覆盖扣款比例零、净额零、`q=1` 无解/不收敛、`q>1` 发散、很小容差和不足迭代次数。
不同初值要得到同一合法金额不动点，或清楚报告金额舍入下多重不动点/振荡的限制。
callback 返回 nil、文本、错 arity 以及自身算术失败均有独立技术错误测试。

两类样本都需核对普通 VALUE_ONLY 与 FULL 的值/失败一致；FULL 是同次真实执行的证据，
重复源码步骤保留、超审计配额标截断。XLSX 的动态收敛翻译尚未设计，不能靠开启 Excel
迭代计算或写入生成时静态值宣称支持；M3 开工时须明确可维护翻译或显式的导出能力边界。

## 开工前最小切分

1. 锁定标量收敛及诊断契约，写库函数直接契约测试；实现保持独立、纯、收费可核对。
2. 定义运行预算与链接的请求上下文、各单位、失败结果，以及两条求值路径的取消/期限传递。
3. 更新统一库函数计费，测试超额、取消及嵌套回调，随后提升 `mantra.calc` 到 2。
4. 若要求精确跨公式内核计数或结构化自定义迭代 trace，先走 Normein RFC/发布接入流程。
5. 应用闭式脚本先冻结，再接核心、工作台、CLI、HTML/Text/XLSX 验收与性能记录。

本稿只提供以上源代码可行性结论。M3 的 links DSL、公共预算 API、版本接入、收敛函数、
XLSX 方案及领域验收均尚未实施。
