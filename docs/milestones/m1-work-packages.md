# M1 工作包：校验、对账与审计呈现

> 开工：2026-10-04 · 实现基线：`4942364` · 状态：进行中
> 依据：[路线图 M1](../roadmap.md) · [架构](../architecture.md) · [工作台契约](../workbench/contract.md)

M1 完成所有校验、对账、比率聚合、同源审计和四个展示应用的退出条件；Normein 保持锁定且不修改。
业务规则属于方案，原语不包含领域标识符。实现前采用下述宿主 DSL 与结果契约。

## 语义与边界

- `(check id "标题" <boolean> {...})` 是具名业务校验。
- `(reconcile id "标题" <left-number> <right-number> {:tolerance 0.01 ...})` 核对两侧，差额为左减右；
  `abs(difference) <= tolerance` 通过。容差必须是非负十进制字面量，不进行隐式舍入。
- 两种节点均支持 `:per`、`:when`、`:severity :error|:warning`、展示元数据及节条件继承；
  默认 error，始终不贡献 Staffel。它们不进入普通计算公式的可引用根或 `all` 映射，避免业务
  判定改变数值链。公开视图保留每坐标的适用性、判定与对账两侧、差额、容差。
- 业务失败附稳定诊断与地址，不阻止后续求值或保存。`succeeded` 表示没有非业务错误，
  `validationPassed` 表示没有业务 error；业务 warning 不使该标志失败。语法、输入类型、
  引用及绑定非法维持拒绝行为。
- 诊断区分 structural/evaluation/business（文档读取也可进一步标 parsing），并可携带零起
  `rowIndex` 与列键。表格单元格使用案例原文位置；外部来源没有原文映射时明确退至输入声明。

## 输入校验

输入支持 `:required-when <boolean>`，表格支持非负 `:min-rows`。条件在独立校验顶点求值，
可以引用已经算出的行，不加入输入绑定依赖制造假环。所需的事实必须来自案例或数据来源；
省略、nil 和空白文字视为缺失，默认值或隐式零不替代必填事实，但计算仍使用原有默认值规则。
原有 required/min/max 属业务校验；类型、选项枚举、成员主键与外键完整性仍属输入契约。

表格列可沿用简单类型或使用声明对象；行公式里的 `row` 是当前原始记录：

```clojure
(input records :table
  {:min-rows 1
   :columns {:mode :keyword
             :target {:type :keyword? :required-when (= row.mode :direct)}
             :amount :decimal}})
```

列条件只报告缺失，不删除记录，也不以自动数值零掩盖缺失。

## 比率聚合

```clojure
(line unit-rate "Unit rate" (decimal/divide numerator denominator 4)
  {:op :info
   :aggregate {:ratio [numerator denominator] :round [4 :half-up]}})
```

分子和分母必须是与该行同维度的数值节点。成员值保留原公式，横向或跨维度合计使用
`Σ numerator / Σ denominator`，不对成员比率相加。舍入显式；省略 round 时只允许严格有限
十进制除法。零分母返回 nil，并给出业务 warning，不能伪造零比率；没有有效成员时返回nil且无此警告。
未定义比率贡献逐级传入Total，TracePart保留null而不是0。核心、底稿、XLSX一致。

只读`RatioAggregateTrace`保留引擎计算的完整分子/分母合计、有效成员数、显式舍入、结果和未定义原因，
成员明细最多64项；截断可见，完整合计不截断。`aggregate.<node>`与`all.<node>`成员映射分开，
部分汇总只读取已求值的Total证据。汇总审计不伪造内核逐步表达式或无限小数近似。

## 同源审计

`calculateForAudit` 在一次计算中捕获每坐标的内核逐步 trace；普通 calculate 不承担全量
trace成本。公开 trace 使用与 Explain 相同的投影，缺失或截断必须可见，不能用根值替换伪造
逐步轨迹。每坐标和整次计算均有采集预算，省略/保密的值使用内核渲染文本，不伪造 nil。
CLI 审计输出、工作台底稿/导出和应用验收使用审计计算入口。Text/HTML/XLSX消费同一底稿审计。
XLSX审计保留生成时的计算快照；修改输入、有效参数或“已提供”事实标记后，公式自动显示
“审计已过期”。主值与校验公式仍可重算；恢复全部原始输入后，快照状态恢复为有效。
此行为已由维护者确认。快照页保护用于防止误改，不是安全边界。

## 工作包与验收

| 工作包 | 交付 | 验收 |
| --- | --- | --- |
| M1-1 核心业务原语 | reader、依赖与维度规则、只读判定/对账、输入单元格定位、比率聚合 | 边界/条件/零分母/非阻断/错误来源/独立数值测试 |
| M1-2 同源审计 | 全坐标预算采集、统一 Explain 投影、纸面与 XLSX审计 | 短路/自定义公式/截断可见与输出一致性 |
| M1-3 XLSX与工作台 | 实时校验/对账/比率公式，JSON与UI诊断/单元格状态 | 编辑工作簿后重算；业务失败仍计算和保存；表格单元格导航 |
| M1-4 展示应用 | 三个已有应用迁用新原语；新增 `apps/ifrs-income-taxes` | 四应用所有案例/参数集HTML/Text/XLSX与独立金额核对、通用工作台、英文说明 |
| M1-5 完成验证 | DSL/API/JSON版本记录、目录与质量门、性能对比、远端CI | 新原语满足路线图§2.3、全部测试与清理通过 |

不修改内核；[RFC 0002](../rfc/0002-m1-validation-and-audit.md)记录本里程碑消费契约与新增需求评估。

## 验证记录（2026-10-04）

- 本地 `./gradlew --no-daemon check :mantra-cli:installDist` 通过：243 项 JVM 测试、
  23 项质量门回归，以及格式、边界、诊断目录与公开 API 检查。
- 前端 58 项测试、check 与 build 通过；包含四应用真实 v2 金本、业务失败定位、
  汇总审计，以及选中单元格在刷新和浏览器历史切换后的保持。
- 四应用共 22 组案例/参数集经独立复算核对，HTML、Text 与 XLSX 逐值验收通过。
- CLI 七类命令、业务失败仍输出与保存、独立汇总 Explain 经运行核对。
- [M1 性能对比](../performance-baseline.md#recorded-m1-comparison)保存 300 项样本、
  30 项统计及零回退/零求值错误的五个独立验证报告。审计计算新增成本与 XLSX 成本分别报告。
- 远端 CI 复验中；完成状态以绿色 CI 和浏览器任务清理核对为准。
