# M4 实施准备：方案包、编译复用与规模

> 状态：实施计划草案，M4 未开工；M3 仍按其工作包进行验收。
> 核对日期：2026-10-04。仅阅读当前仓库和锁定内核源码，本次没有执行构建、性能测量或发布。
> 依据：[路线图 M4、T1–T6、R1–R10](../roadmap.md)、[架构](../architecture.md)、
> [M2 偏差记录](m2-work-packages.md)、[M3 工作包](m3-work-packages.md)、
> [M2 性能预算](../performance-m2.md)、[Normein 发布计划](../normein-publication.md)。

本计划把 M4 的退出条件拆为可核对的工作，不将准备稿、已有源码或单案例会话复用计为交付。
正式开工时应另立 `m4-work-packages.md`，锁定契约、独立来源、测量预算和实际验收记录。

## 1. 已定范围与待定事项

- R1/R3/R5：通用库及本机参考工具；八个展示应用仍在 `apps/`，包内保留领域内容，
  应用不发布为 Maven 库。批量宿主、加载器、迁移工具与前端不得识别应用 ID。
- R2：期间、首末存量、流量及 `prev` 语义继续沿用；批量与动态表格不得更换汇总口径。
- R6：BUSINESS 诊断保留数值；宿主决定业务是否可继续。技术失败保留 Nil/失败状态，
  不用零、旧结果或未收敛近似值补齐批次。
- R7：旧版本并存；升级必须生成可审阅、显式应用的迁移。M3 的 `2025.2`、`2025.3`、
  `0.1` 等版本仍按原字符串精确匹配，不能自动补 `.0` 或选择 latest。
- M4 新版本及兼容范围采用 strict SemVer。包版本、方案版本、引擎版本、DSL 版本、
  JSON 契约及 `mantra.calc` 语义版本分别记录，不用一个版本替代全部身份。
- R8 是公开文档语言：README、包使用说明、嵌入教程及 API 文档用英文；本设计稿可暂用中文。
  R8 没有决定清单或压缩格式。
- 包格式问题已发给维护者，**答复仍待定**。本文按推荐方案准备：`manifest.json`、
  directory/classpath 资源，classpath-JAR 是资源容器；额外独立 ZIP 导入不是已确认能力。
  若答复要求 ZIP，增加独立导入与压缩资源预算工作包，不把 ZIP 等同 classpath-JAR。
- C3/C5/C7/C8：金额、舍入、来源和各输出保持一致；先冻结独立预期，再实现；接口变更版本化。

M4 正式接入以 M3 的验收、实际基准、远端 CI 与清理记录完成为前置；本稿不判断 M3 已完成。
方案包格式的待答复问题不阻止 M3 的现有实施。

## 2. 现有公开能力及真实缺口

| 当前能力 | 可以复用 | M4 尚需交付 |
| --- | --- | --- |
| `Mantra.loadSchema(SourceText, SourceResolver)`、`ClasspathSources` | 同一 DSL 读取及相对 include | 包清单、资源边界、兼容范围与统一指纹；现有 classpath include 可向上归一化，不能充当包沙箱 |
| `CaseData`、`Value`、`ParameterSet` | 类型化输入、参数、显式 Nil/零/false | 方便构造、准确来源身份及输入验证入口；不要求宿主手写完整案例文本 |
| `Mantra.calculate`、`CalculationView`、`CalculationReader` | 同一只读结果、归约及受控读预算 | 编译模板与不同案例身份之间的公开执行 API；自定义输出仅消费视图 |
| `CalculationSession` | 同线程增量编辑、不可变结果、实际复用统计 | 跨不同 case ID 的编译复用；线程生命周期和批量所有权；现有重绑定兼容条件包含 case ID |
| `CaseGraphRunner`、`CasePackageResolver` | exact 源绑定、links、来源修订、共享图预算 | 包资源参与加载、跨包授权、批次级约束；不能把图默认 64 个案例改成批次可无限执行 |
| `CasePackageLoader` | 目录中的方案/参数/版式/数据捕获，根文件能力授权 | 不靠扫描全部文件猜绑定的包目录及 classpath 入口；来源图依旧采用同一 runner |
| 参数元数据与覆盖层 | `valid-from` 等现有字面元数据和逐层来源 | 有效期校验、选择日期/期间、重叠与缺档规则、按键解析参数表 |
| `ExcelExport`、`ExcelWorkbook` | 固定记录的公式、输入地址、aggregate 地址、审计过期 | 真正插入/删除记录后的动态范围、成员与输出；当前地址表也是生成时快照 |
| Normein 公开 execution plan/session | VALUE_ONLY、typed frame、物理计数、关闭协议 | Mantra 层计划与运行绑定分离；是否需新增内核能力由契约 PoC 决定，不修改内核 |

当前 `Mantra.plan` 和 `core.engine.CalculationPlan` 都不是公开嵌入接口。不能公开内部类，
也不能用循环调用 `Mantra.calculate`、改相同 case ID 或只看耗时来证明“编译一次”。

## 3. 工作包与推进顺序

| 工作包 | 具体交付 | 前置与完成证据 |
| --- | --- | --- |
| M4-0 契约与来源 | 包清单/资源规则、SemVer/legacy、迁移操作、compiled/batch 生命周期、参数期效、动态 XLSX 支持范围；RFC 0005 候选 | 先独立冻结原始事实和参考脚本；把推荐假设与人类答复分开记录 |
| M4-1 包加载与校验 | directory/classpath 统一资源端口、strict manifest、来源与指纹、兼容诊断、只读包与可写案例分离 | 包格式答复；缺资源、越界、重复 ID、内容改变、兼容不满足测试 |
| M4-2 版本及迁移 | SemVer 范围处理、legacy exact、迁移计划/预览/显式应用、期效选择 | M4-1；旧案例不变、迁移 diff、来源身份、stale/失败不写入、undo/redo |
| M4-3 编译嵌入 | 不含运行状态的模板、类型化 bind/evaluate、公开统计与关闭协议 | 类型/缓存契约 PoC；不同 case ID 共享编译，值/来源/条件均独立 |
| M4-4 批量及大表 | 有界流式顺序批量、逐项结果、批次控制、按键参数解析、10,000 案例驱动 | M4-3；真实计数、取消/期限/部分失败、结果释放、独立逐值核对 |
| M4-5 动态 XLSX | 结构化表格及公式范围、插入/删除/排序、成员输出、条件/期末/来源/容量标志 | 可与 M4-3 并行做能力 PoC；两个领域真实文件重算，不经重导出补结果 |
| M4-6 应用与工具 | 八个应用通过包加载；IFRS 16 公共 API 批量示例；ESt 显式迁移；CLI/工作台统一入口 | M4-1/2/4/5；§2.4 各格式、地址、来源及失败展示完整 |
| M4-7 ABI 与发布准备 | 公共 API dump/变更审阅、POM/制品/干净 consumer、英文文档与发行清单 | ABI 初始盘点可提前；最终基线包含已定接口且不公开 apps/benchmarks |
| M4-8 完成验收 | 全回归、同平台性能、批量预算、CI、原始数据、资源清理 | 所有前置满足；只写实际执行结果；发布状态单独记录 |

M4-1 与 M4-3 可并行实现，但公共接口必须先冻结。M4-5 应提前做技术探针，避免到里程碑末尾
才发现所承诺的公式在目标 Excel 或 POI 中不能重算。性能测量安排在编译、测试与其他任务停止后。

## 4. 包、资源及绑定

推荐清单的待确认字段为 `formatVersion`、`id`、strict SemVer `version`、`engineRange`、
DSL/函数库兼容标识、方案入口及其**真实精确版本**、参数与版式入口、导入模板、资源列表和来源摘要。
包依赖必须明确身份；绑定成功后记录实际被解析版本和资源哈希，不能只有范围或路径名。
字段及 JSON schema 在 M4-0 固定，本稿中的字段名不是已实现 DSL。

1. 资源端口提供包内逻辑路径及有界读取；同一捕获字节用于解析、校验和哈希，避免校验后又读变化的文件。
   directory 路径同时检查归一化路径与 real path；classpath 限定授权资源根。
   拒绝绝对路径、根外 `..`、符号链接逃逸、重复逻辑路径及缺失入口。
2. 清单、schema/includes、parameters、layout、templates、实际导入 data 都有角色、
   逻辑身份、大小和 SHA-256；layout 或模板改变也应改变相关来源修订。
   内容哈希证明内容一致，不证明出版者可信；来源信任由宿主授权的本地/classpath 根提供。
3. schema/参数读取留 core，layout 读取留 render，工作区编辑及 source 导入适配保持单向依赖。
   需要拆通用 package 模块时先画依赖图；core 不反向依赖 workbench、render 或应用。
   包接口不得要求嵌入者运行服务器。
4. classpath-JAR 包资源只读。案例及用户覆盖放在宿主明确授权的可写 workspace，
   工作台不能把“保存”做成修改 JAR。包资源授权不扩大案例 links 或本地 data 文件权限。
5. `de-est` → `de-gewst` 的跨包来源必须显式接入已授权 catalog/resolver。
   现有相对 links 在 directory 工作区继续兼容；classpath 场景先规定逻辑工作区和依赖映射，
   不让 `../../../` 成为可访问任意包或文件的能力。源案例继续用其自身版本、参数及版式。
6. 验收同一资源以 directory/classpath 两种入口加载，比较全部数字、诊断、身份和资源内容摘要。
   checkout 的绝对位置不应污染包内容身份；用户选中的外部文件能力另有明确来源身份。
   若增加 ZIP 导入，另检验压缩/解压大小、条目数、重复条目和解压路径；不得调用包中的脚本。

## 5. 版本、显式迁移与参数期效

- 新发布包/方案采用 strict SemVer；范围语法、预发布版本、拒绝非法字符串的规则在 M4-0 冻结。
  案例和 link 仍精确固定实际方案版本。目录有多个候选时禁止任意选第一个或隐式 latest。
- 旧 `2025.2`、`0.1` 等保留原身份，只支持 exact；不得因新范围解析器而自动解释成 `2025.2.0`。
  原先未声明版本的 schema 保留 null，不伪造 sentinel 或版本号；有歧义时要求明确绑定。
  legacy 到 SemVer 的映射本身是可审阅迁移的一部分，不是 reader 的归一化。
- 通用迁移计划包含 source/target 精确身份、基础 graph revision、作者给出的字段/参数/绑定变化、
  不支持项及操作后 diff。规则与业务解释属于应用；引擎负责可执行操作、验证和原子应用。
  新目标 schema 必须可加载，需迁移的原始 case 和 links 不得被编译缓存暗中重写。
- 复用 `CaseTextEditor` 的输入、表行、参数、扩展和绑定操作；目前 `SetMeta` 不允许改 schema/version。
  新增专门的 schema/version 迁移操作及验证流程，不能把输入切换塞进 unrestricted SetMeta。
- ESt 展示选择真实存在的旧版本与新版本，例如 `2025.2` 到当前 M3 的 `2025.3`，
  或在 M4 独立定义 SemVer 目标。路线中的 `2025.1 → 2025.2` 只是例子，不应虚构不存在的历史版本。
  不改旧方案、冻结事实或历史 manifest；迁移后预期从独立事实计算，新增字段的默认/放弃选择明确展示。
  若两版领域口径不同，审阅展示应解释差额，不能以“迁移无变化”掩盖真实差额。
- 预览不写盘；显式应用检查原 graph revision，stale、技术失败及无效目标不提交。
  BUSINESS 诊断保留并可提交（R6）。证明完整 round-trip、一次应用、undo/redo 和旧版重新打开仍有效。
- 推荐参数有效期采用显式宿主 `effectiveDate` 与 `[valid-from, valid-until)`；
  年/月参数期间如何转成日期由应用或显式宿主策略声明，不取系统“今天”。这是待锁定的工作包语义。
  自动选择时重叠、缺档报诊断，不默选最后一个；显式 what-if 覆盖继续可用并注明来源/越界状态。
  既有元数据未声明终日的参数集保持兼容，不推断年度终日。
- 按键参数解析由宿主在求值前选择实际所需记录，记录表来源/修订、键、日期及选择结果。
  不将整表塞成巨大内核值，不在导入器偷偷执行税率、折旧或其他业务计算。

## 6. 编译模板、类型化输入及缓存

推荐 API 职责是 `compile` 产生只含冻结定义的模板；`bind/evaluate` 接受独立的类型化案例；
需要增量编辑时从模板打开线程所有者明确的 session。类名、签名及是否拆额外接口留 M4-0 锁定。
返回结果只经 `CalculationView/CalculationReader`，不要公开 planner、compiled expression 或 evaluator。

- 编译身份至少含 schema 内容/精确版本及 includes、定义与扩展/绑定公式、输入和参数类型轮廓、
  有序参数配置、DSL/`mantra.calc`/Normein artifact 及语义身份、影响编译的预算/选项。
  参数值能否只重绑定须契约测试证明；否则纳入编译配置分组，不夸大复用。
  layout 内容参与展示与结果修订，但不应使纯数值编译无谓失效。
- typed 输入沿声明类型转换 Integer/Long/Decimal，并保留原诊断与字段路径；
  RFC 0001-C 的整数转换策略仍需与下一 Normein 版本核对，不能以“BigDecimal 恰好整数”猜测内核对象类型。
- case ID、普通事实值、活动成员、来源位置和链来源是运行绑定，不能从前一案例泄漏。
  相同逻辑但不同文件位置的扩展必须仍给出正确 source span；如不能重绑定位置则明确形成独立配置。
- 编译复用与结果复用分开：相同输入的不同 case/revision 可共享编译，不能共享错误身份、LINK 来源或诊断归属。
  成员增加/删除、空维、条件反转、Nil/零/false、参数类型变化、公式变化均有真实隔离测试。
- 公开实际编译/重绑定/公式执行统计或有意义的观察端口；不同 ID 的一万个案例在同一配置中
  应只建立一次逻辑编译模板。若内核计划按表达式分组，分别报告模板数、表达式编译数及 session 数。
- 默认先顺序执行；冻结模板若要跨线程共享，须证明内部没有可变 session 或借用 row。
  `CalculationSession` 和 Normein runtime session 继续同线程打开、使用及关闭；不得以 `@Synchronized`
  声称 session 可转移。结果是脱离状态的快照。
- 有界缓存由宿主/编译器实例拥有并可释放，关闭应确定清理所有 owner-thread session。
  不以无限全局 Map 缓存方案或案例；失败、取消与预算 profile 变化后的再次请求必须真实恢复。

## 7. 批量、10,000 案例与大表验收

IFRS 16 示例放在 `apps/ifrs-leases`，仅依赖公开 API 和包资源；不作为库发布。
宿主以类型化 facts/参数生成 10,000 个不同案例身份，使用独立 Decimal 脚本冻结每期负债、
利息、支付、使用权折旧、期初/期末及组合总数。至少同时以费用/分配或资产领域验证通用嵌入语义。

- 顺序有界 iterable/回调输出优先，不要求同时构造一万个全文本案例或保留全部结果/审计树。
  保留输入序号、case ID、实际来源 revision、succeeded/validationPassed、诊断及逐项结果。
  技术失败明确标记；fail-fast/continue-on-item-failure 由宿主选项确定，禁止 catch-all 转零。
- 批次大小、总工作、总期限和取消是批次控制；每项沿用运行/图/公式预算。
  批次停止不应在下一项重领期限或总预算；BUSINESS 不自动等于批次技术失败。
  单图默认 64 cases 不能被当成一万个顶层案例的限制，也不能被删除以避开批次预算。
- 10,000 案例是 batch count，不是单内核 collection 大小。保留现有硬上限；
  参数表按键选择、显式可追溯预聚合、RFC 三条大表策略分别验证。
  流式读取只适用于可类型化的数据适配，不自动更改 `table` 字面量或业务汇总语义。
- 性能分开计时：包读取/校验、cold compile、warm bind/compute、输出读取、抽样 FULL audit/Explain、
  流式 sink 及释放。全批 ordinary 用 VALUE_ONLY；少量抽样审计不冒充全批 FULL 的成本。
- 记录来源源码指纹、真实 compile/session/usage 计数、环境、wall time、吞吐、heap-pool peak、
  中位数/p95及所有原始样本；不得把 heap-pool peak 写成 RSS。使用相同配置与重复次数对比 M0/M2/M3。
- 现有 [M2 参考预算](../performance-m2.md#initial-budgets-for-the-reference-device) 继续回归；
  **目前没有 10,000 案例预算**。M4-0/4 用冻结独立 workload 做 PoC、提出明确数值预算，
  在最终优化及验收前写入工作包。不能把未测默认值称为人类已确认，或测完后只贴成绩而无退出门槛。
- 每个案例的全部有意义数字先独立验证；汇总样本来自参考算法，不能只把引擎结果相加。
  控制流单测另含第 N 项失败、取消、超额、结果消费方抛错和释放后使用；终止后所有 runtime 已关闭。

## 8. 动态 XLSX：必须补齐的 T1 偏差

M2 明确推迟的是 XLSX 增删记录动态范围。工作台编辑后“重新导出正确”是当前能力，
不能作为此项完成证据；清空单元格也不等于删掉记录。

1. 先制定支持矩阵：记录的插入/删除/排序、表内字段类型及 Nil、`dimension :from`、
   外键/父关系、条件/校验、spread/分摊、比率、期间 `prev`/boundary、matrix/transpose。
   限制按原语及公式结构报告，不按应用名豁免。
2. 动态源范围必须是 Excel 的实际表格/受支持命名范围；不能只更新 table 的 ref 而下游
   `map`、SUM、成员域、分摊助手与结果坐标仍固定。新增合法 key 必须参与结果，删除 key 不残留贡献。
3. 推荐先证明不需宏/外部服务、POI 可重算的公式，在显式导出容量/工作量预算内支持真正成员增删。
   容量、支持结构及超额行为写入导出报告；不因固定隐藏助手不足而返回不完整数值。
   若要采用 Excel 365 动态数组而 POI 不支持，先解决目标 Excel 版本和验收执行器，不能保留旧 cached 值假装重算。
4. 两个独立事实夹具至少覆盖成本来源/分配与资产/租赁成员逐期表。
   先固定插入前、插入后、删除后、乱序后的完整预期，并验证父键改动、第一/末行、空表、真实零、
   条件不适用、重复/未知 key 和容量边界。新资产/租赁成员不能只有总数而没有逐期值。
5. 测试实际保存 `.xlsx`，插入/删除真实行并更新 Excel 按该操作更新的表范围/公式引用，
   重开文件、清除 evaluator cache、重新计算全部相关单元格，与从相同新事实得到的引擎结果及
   独立参考同时比对；测试不能只手工改已有值或换一份重导出的工作簿。
6. 生成时 `address`/`recordAddress`/`tableAddress` 和外部 JSON 是快照；记录变化后对新 key
   必须有可定位的表内身份，重导入/查询地址按当前表结构重建，不能沿用失效 row index。
   API 若继续只提供生成时地址，KDoc 与能力报告必须明确范围，不能声称实时 host 地址同步。
7. 保持已确认的审计规则：变更后生成时快照明确过期；支持的 live formula 仍重算。
   新成员、行数和表关系变化也要触发过期；重新生成审计需新的引擎运行，不由 Excel 伪造 trace。
   M3 的 LINK 值仍是带来源批注的只读快照，不新增跨工作簿公式。

能力 PoC 如只能追加旧成员的费用流水，必须明确尚未解决成员域增删；不得自行把 T1 的承诺缩成该子集。

## 9. API 兼容与发行能力

- 先盘点真实 public ABI，再建立 dump/check。除了 `api/view`，当前公开方法签名还暴露
  `Mantra`、`Diagnostic`、`CaseData/Value/Schema`、`ParameterSet/SourceResolver`、layout 和
  `ExcelWorkbook.workbook` 的 POI 类型；不能从检查中排除这些可达类型却宣称 API 已受保护。
  不拟公开的内部类型先明确 internal/弃用与版本变化，不能把第一份 dump 当作设计审阅。
- 各库模块 API baseline 进入 CI；新增、删除、参数默认值/JVM 签名变化有可审阅 diff。
  Java 与 Kotlin 干净 consumer 验证 POM 的 compile/runtime 依赖，尤其 Normein 与公开 POI 类型。
  保留 JSON strict schema/generated-client 检查；wire 有不兼容改变才提升契约，不自动以 M4 名字更改 wire。
- 当前 Mantra 使用锁定 composite checkout；现有 CI 的私有 Normein 只读 deploy key
  不是 Maven Central 的 namespace、上传或签名凭据。仓库公开不意味着外部消费者已能解析库制品。
- Normein 源码确有 staging、Central Portal 上传/签名流程及 clean-consumer fixtures。
  本次只读**未验证** namespace 所有权、凭据配置、远端制品是否已上传；脚本存在不能等于发布完成。
- 按 R4/C6 先提下一个 RFC；可用现有公开 plan/session 的部分先证明，无新需求不为编号强改内核。
  若需要跨线程模板保证、typed 多案例物理复用或准确预算能力，Normein 单独实现/验证/发布；
  Mantra 只经契约测试接入已发布 commit，不改 `.deps`、不采用内部 cast/反射。
- 发布顺序：Normein 审阅后的制品及 POM验证 → Mantra 锁定/契约接入 →
  无 composite substitution、无私有 checkout 的独立消费构建 → Mantra 受选库模块及 CLI/工具分发。
  `apps/` 与 `benchmarks` 不进入 Maven 发布集合；示例方案包随源码/演示分发，其清单遵守 R8/R9。
- 本地 staging、二进制/sources/API文档、许可证及第三方 notices、dependency POM、签名验证和
  clean consumer 先成为可审阅产物；真实上传能力和授权另行核对。不读取或在报告输出密钥，
  不因远端仓库已获公开授权就推断不可逆制品发布也已完成。

## 10. 退出清单与待确认问题

M4 退出应同时具备：八个应用经 directory/classpath 包加载满足 §2.4；真实不同案例身份的编译复用；
10,000 案例的类型化批量示例、独立逐值参考及已冻结预算；ESt 显式迁移与旧案例可重开；
参数有效期/按键解析；动态 XLSX 插入删除闭环；API 兼容门、干净 consumer、全部现有性能回归、
真实 CI、英文文档和任务资源清理。任何仍未完成的 T1 子项保留为偏差，不写成已经交付。

| 问题 | 推荐与影响 | 是否必须先得到人类选择 |
| --- | --- | --- |
| `manifest.json` + directory/classpath-JAR，还是额外独立 ZIP 导入 | 前者先交付，ZIP 需额外解压入口和资源预算 | 已向维护者提问，答复待定；本稿按推荐假设 |
| 动态 XLSX 最低 Excel 版本与完整成员增删范围 | 先证明无宏、可核对的公式/容量方案；不能实现时给出 Excel 365 或明确受支持子集的具体取舍 | PoC 出现不可兼容选择时及时问，不预先制造阻塞 |
| 编译/批量是否要求默认并行 | 推荐顺序流式，worker 各自持有 session；并行是显式选项并另测内核保证 | 可按工程判断锁工作包；路线未要求默认并行 |
| 参数期效的日期、半开区间及 what-if 处理 | 推荐显式 effectiveDate/期间策略，不取今天；自动重叠/缺档报诊断 | 属语义契约，应先写示例并审阅；发生领域冲突才问 |
| 10,000 案例硬性能门槛 | 用 PoC/基线提出数值与设备口径，再冻结验收预算 | 当前没有已确认数值，不得虚构；由工作包明确采纳过程 |
| Maven namespace/上传/签名及对应 Normein 制品 | staging/consumer 先做；真实能力和发布授权逐项核实 | 是发布前置，非当前源码实施阻塞；无凭据不能称已发布 |

上述问题不重问 R1–R10。正式工作包记录最终答复、技术探针、API/manifest schema、
预算及验收命令；本稿中的建议类名、字段和新任务不能当作当前可运行命令。
