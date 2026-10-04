# M3 工作包：案例链接、有界迭代与运行预算

> 状态：实现中；验收待完成。
> 开工日期：2026-10-04；M2 源码、CI 与性能记录已归档。
> 依据：[路线图 M3](../roadmap.md)、[内核准备](m3-kernel-preparation.md)、
> [领域准备](m3-domain-preparation.md)、[RFC 0004](../rfc/0004-case-links-and-run-budgets.md)。

M3 交付前一案例及另一方案的结果作为有来源的输入，支持纯标量有界迭代，
并限制一次顶层运行中成员、行、任务及链接的累积工作。应用、前端、导出器不得添加税种特例。
本稿锁定实施默认值，不表示这些能力已经存在。

## 1. 前提与版本

- M2 的验收、远端 CI、性能记录和任务资源清理完成后才开始迁入源码。
- Normein 保持 `0.3.0` / `0a3ae1de844c92635fbbc03406a13cb0e8920c03`。
  不修改 checkout、锁文件、内部 API 或反射访问。
- `mantra.calc` 由 1 升为 2；新收敛函数及现有函数的工作量计费共同进入语义版本、
  provider/artifact identity、environment fingerprint、函数目录与文档。
- Mantra 里程碑版本为 0.4；工作台响应升级为 `mantra.workbench/4`，strict schemas、
  generated TypeScript、金本、fixtures 和前端一起更新。
- 方案版本先用非空不透明字符串精确比较，兼容现有 `2025.2` 和 `0.3`。
  不引入 SemVer 范围选择、隐式 latest、包仓库或自动迁移；那些属于 M4。
- 人类已确认输入变化后保留生成时审计快照并标过期。预算选择尚未获人类答复，
  本任务按推荐的分层方案作实施假设，尚无用户答复；不伪称用户已批准。

## 2. 工作包与顺序

| 包 | 交付 | 前置与验收 |
| --- | --- | --- |
| M3-0 契约 | RFC、DSL、地址、预算失败、版本及生命周期契约 | 先于实现；本稿中的默认假设可据后续明确答复修订 |
| M3-1 运行控制 | RunLimits/RunControl/RunUsage、内部共享 RunContext、两种内核路径控制 | 单位可核对；超额、取消、期限、重复请求的独立 epoch |
| M3-2 枚举及收费 | CoordSpace、任务/行/扫描收费、现有函数实际工作量收费 | 不先构造过大列表；fixed/ancestor/空轴语义；M2 基线及增量回归 |
| M3-3 纯收敛 | calc/converge、calc@2、目录/authoring/错误投影 | 严格 callback、闭式值、振荡、Nil、回调失败、预算和真实 trace |
| M3-4 版本及 links | reader/model、纯 binder、公共链接运行入口、共用解析器 | exact version、独立源绑定、环/别名/深度、冲突、Nil、修订传播 |
| M3-5 底稿及工具 | wire/4、案例链、跨案例 Explain、SSE、CLI、XLSX | 数值只读引擎视图；审计过期；链接快照和有界迭代表 |
| M3-6 领域展示 | 新 ESt 版本、2024 亏损余额、GewSt、奖金及现金流 | 独立来源先冻结；所有主要/边界案例满足 §2.4 |
| M3-7 完成验证 | 全回归、性能、文档、CI、资源清理 | 退出清单只填写实际执行结果 |

M3-1/2 与 M3-3 可以分开推进。M3-4 必须在共享运行上下文可复用后集成，
不能为了展示 links 先加一个每源案例重新领取预算的临时实现。

## 3. 运行预算接口

建议公开 `CalculationOptions(limits, control, formulaBudgetLimits)`，保留原便捷入口。
`RunControl` 提供取消信号和可选绝对期限；可测试的内部 `RunContext` 保存运行起点、
有效期限、计数与当前位置。`RunUsage` 是脱离运行状态的不可变快照。

| RunLimits 字段 | 默认 | 单位 |
| --- | ---: | --- |
| maxCases | 64 | 含 root 的规范案例绑定身份数 |
| maxLinkDepth | 16 | root 深度为 0；递归进入前检查 |
| maxLinkMappings | 1,024 | 实际绑定的 from→to 映射；缓存源也计映射 |
| maxCoordinateProduct | 100,000 | 单 scope 展开的坐标乘积 |
| maxCoordinateVisits | 5,000,000 | 实际枚举出的坐标；重复扫描仍收费 |
| maxInputRows | 100,000 | 本次解析/导入的表行 |
| maxTasks | 1,000,000 | 未完成任务真正开始执行的次数 |
| maxFormulaExecutions | 100,000 | VALUE_ONLY/FULL 的真实公式执行 |
| maxHostScans | 10,000,000 | 导入、归约及域解析扫描的元素 |
| maxParticipatingBytes | 64 MiB | 本次读取的参与文件内容字节 |
| maxDuration | 60 秒 | 一次顶层运行的期限 |

这些是可配置的默认值，不是已经测得的硬性能承诺。计数使用 Long、溢出安全加法/乘法，
在执行或分配前检查。上限为零可阻止相应工作；maxCases 至少 1，duration 必须为正。
服务器只接受受宿主配置约束的 profile，不能由任意 HTTP 数据解除上限。
同平台基线必须验证默认值有正常案例余量，并报告实际 usage；未测量前不得声称性能通过。

有效期限是 `min(start + maxDuration, callerDeadline)`；所有链接共享同一控制和上下文。
唯一源案例缓存不计再次公式执行，映射和访问仍有工作。增量请求只计实际重算；
读文件、绑定、枚举及投影工作不能用 RecalculationStats 的 reusedTasks 冒充零成本。

公开独立 `CalculationSession.recalculate` 每次建立 fresh epoch。链接 runner 从加载开始建立
一次上下文，预绑定与内部 calculate/recalculateBound 使用它，不能再调用会刷新预算的
普通公共 recalculate 入口。内部共享上下文不暴露为可随意 reset 的公开可变对象。

单公式使用当前公开 `DslBudgetLimits`。调用者覆盖应与完整 defaults 合并，缺失 counter
不能变成 Long.MAX_VALUE；首版只允许降低内核默认 ceiling。
VALUE_ONLY 在 openValueOnlySession 的 `DslExecutionSessionOptions` 固定该 profile，
每行 `DslValueOnlyInput` 传取消/期限。改变 profile 就重建会话。
FULL 在 `DslEvaluationRequest.budgetLimits` 与 `DslEvaluationInput` 传同等限制和控制。

预算、取消与期限失败停止整个请求，是技术失败，报告阶段、counter、limit、attempted、
案例、节点及坐标。允许显示清楚标为失败/过期的历史快照，不允许把旧值当作本次完成结果。
审计收集配额另行保持 best effort：截断警告不会使已经计算的值失败。

## 4. 组合与实际工作收费

- 统一 CoordSpace 负责所有成员笛卡尔枚举。零维标量产品为 1，任一空轴产品为 0。
  展开前校验乘积，并按 fixed 直接生成匹配坐标；保留轴声明顺序、ancestor 约束和真实成员。
  不用先生成整个产品再过滤的实现消耗合法案例的预算。
- MemberGraph 未完成任务进入 execute 前计一次；缓存访问不伪计为执行。Spread producer、
  guards、input validation、slice barriers 和 prev 依赖仍是独立真实任务。
- KernelExecution 每次真正调用内核前计公式；callback 调用属于本公式内核预算，
  不能把 1,000 次 callback 虚报成 1,000 个宿主公式。
- 核心归约、输入导入和域解析逐项收费；读取之前控制文件字节，解析期间控制表行。
- mantra.calc 的 member/band 扫描、分配排序与水填充、金融运算、收敛循环在真实工作点
  通过 runtime.charge 收费及 checkpoint。不能只按 args.size 收费。
  测试记录确定的工作单位，不以纳秒猜测 BigDecimal 或排序成本。
- 对外冻结视图仍不可持有可变 RunContext。计算外的渲染/导出请求拥有自己的请求控制，
  不能把后来的任意 view.reduce 操作悄悄记入已发布的 RunUsage。

## 5. 链接及最小版本能力

DSL 和 binder 见 RFC 0004。被链接源按自身 schema-version、parameters、layout 和 sources
独立解析计算；消费者不注入自己的参数。来源记录案例身份、节点、完整坐标、方案版本和 revision。
Nil/不适用源/源技术失败严格拒绝，绝不触发目标 default/implicit-zero 或旧缓存。
零和 false 使用显式 presence 正常绑定；本地或导入事实与链接同目标冲突拒绝。

源 BUSINESS ERROR 可以传递忠实值并保留来源 findings；整链 succeeded 仍为 true，
validationPassed 为 false。BUSINESS WARNING 不使 validationPassed 为 false。
源技术失败使链接链失败。source diagnostics 用真实案例地址区分，不伪改为本地税务错误。

纯 binder Success 只代表语法/type/presence/conflict 的原子绑定。目标派生域可由链接值决定，
runner 在真实目标计算后验证所有 to 坐标确实存在且 active，才发布 graph Success；无效坐标
为技术失败，不静默丢输入，也不先跑无链接默认案例来猜成员域。

版本通过 (schema id, exact version) 唯一解析。同 ID 旧版保留；原案例若需新增显式
schema-version，以固定原绑定为目的，不自动切至新版本、不改原数据或原方案计算语义。
缺失、歧义和版本不匹配均明确诊断，不能读第一个或最新的 schema 文件。

## 6. 会话、审计和工作台

- SDK session 的 open/recalculate/close 由同一线程完成。错误线程操作在状态修改之前拒绝。
  只有脱离 session 的不可变结果可跨线程读取。
- 工作台专属执行器拥有缓存 SDK sessions；创建、重算、替换、LRU 和关闭都在 owner 线程。
  等待不持有提交锁，错误保持原类型，关闭幂等且等待 owner 退出；中断不能跳过资源清理。
- linked source 变动进入下游 revision，即使金额相同。缓存 key 包括规范案例绑定身份及
  参与源码/数据内容，不能只用 schema id、basename 或 mtime。
- wire/4 地址增加 case；Run 提供案例链、来源与预算快照，Explain 可按真实源地址展开。
  源 revision 不匹配时返回过期信息，不能把新源码位置拼到旧 trace。
- 输入变化继续保留生成时审计快照并标过期；新的 FULL 审计由同次真实执行产生。
  VALUE_ONLY 无 trace，不通过重复运算伪造步骤。真实 callback 的 eventId/invocationIndex
  可保留，但 invocationIndex 不自动等于 converge 的迭代编号。
- 纸面注明链接案例及修订。XLSX 链接值为本次绑定快照输入并带 provenance 批注，
  不生成跨工作簿公式、不声称源文件变化会自动重算该工作簿。

## 7. 收敛及 XLSX

`(calc/converge f init max-iterations tolerance)` 的 scalar BigDecimal 契约、错误和收费
见 RFC 0004。FULL 保留真实重复 callback 源步骤及截断标志；失败不返回最后近似值。
相邻差的停止条件不等价于方程残差或全局误差保证，应用用独立 closed form 与 check 核对。
逐轮舍入属于 callback 方程本身：初值、舍入模式/尺度、容差与上限共同定义运行结果。
不能宣称存在未舍入闭式解就必定收敛，或舍入迭代的结果与初值无关。

callback 的静态契约为 Decimal→Number（Number 是 Integer/Long/Decimal union）。
init 先转 Decimal，每次 callback 返回数值后转 Decimal 作为下一次参数；支持 `^Decimal`，
`^Integer` 参数不兼容。M3 合同 PoC 须验证 untyped lambda、named defn、`^Decimal`、
返回 Integer 常量及拒绝 `^Integer`，不能把 Number→Number 当成逆变规则下的通用替代。

XLSX 采用有界隐藏迭代表，停止后用 IF 保持当前值，未停止才计算下一 callback。
literal iteration limit 按 1..1000 展开；dynamic limit 在 1000 硬上限内预分配且校验有效范围，
分配前检查导出工作量预算。不打开 Excel 全局迭代设置，不插入引擎算出的静态收敛值来假称动态支持。
已有可翻译 scalar fn/let closure 与具名函数须保持捕获和当前坐标引用。
不支持的结构明确拒绝/报告能力，展示应用不能使用 fallback。
Excel binary64 与引擎 BigDecimal 的任意零容差等价不能承诺；展示显式每轮 cents 舍入并逐值核对。

## 8. 领域与退出条件

领域金额先按 m3-domain-preparation.md 冻结独立原始事实、脚本、references 和 SHA-256 manifest。
建议保留 ESt 2025.2，新版为 2025.3；亏损专用 de.est-loss/2024@2024.1 仅计算余额。
GewSt 2025.1 展示单一自然人独资企业及虚构 Hebesatz。方案文件位置在开工时依原应用结构确定。

必须完成：

- 2024 loss 45,000 → 2025 opening 45,000 / used 32,000 / closing 13,000；来源修订与真实零。
- 同一 GewSt 源的 messbetrag 3,605 和应缴税款映射到 ESt；两 mapping、一次源计算。
- 奖金 100000/11：每轮 HALF_UP cents、init=0、tolerance=0；主金额 9090.91。
  同时核对 P=0.01/r=0.5 的 0↔0.01 周期：即使未舍入方程有解，也必须报未收敛。
- 现金流 4000/3：N=1000/q=0.25、每轮 HALF_UP cents、tolerance=0；
  init=1000 收敛至 1333.33，init=2000 收敛至 1333.34，两者均为舍入方程不动点。
  精确闭式值是未舍入方程的解，不能把后者金本改成 1333.33 或声称初值无关。
- 域无关 contract fixtures：Nil/false/zero、重复目标、本地冲突、参数独立、版本错配、路径别名、
  环、深度、共享预算、取消、期限、同值不同 revision、源 BUSINESS/技术失败。
- callback lexical capture、具名函数、错 arity/返回型、振荡、发散、不足迭代、嵌套预算、
  VALUE_ONLY/FULL 一致、trace 真正重复及截断；HTML/Text/XLSX 合法结果逐值一致。
- 通用工作台无需领域分支；跨案例 Explain、SSE、编辑与审计过期浏览器流程；CLI 同一绑定规则。
- 全 JVM/前端/边界/格式/公开 API 检查及远端 CI；M0/M2 同平台性能复测、usage、退化说明。
- catalog/session/executor 与所有任务浏览器/服务清理，关闭后无任务进程、线程或临时产物残留。

## 9. 真实阻塞与验证记录

默认分层预算、标量收敛及来源链接可使用当前公开 API，不需要新增人类架构选择。
如果后续明确要求“全部 VALUE_ONLY 公式的真实内核 counter 精确共享且可抢先停止”，
或要求 runtime 自定义结构化迭代 trace，这不是当前 API 能力；前者需 Normein RFC 与公开新 revision，
后者需先约定宿主独立证据或内核扩展。不能先改 pin、用 cost metrics 冒充 counter 或伪造 trace。

实施、验证、性能及清理记录：实现中；尚未填写未经执行的绿色状态。
