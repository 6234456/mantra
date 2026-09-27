# Mantra 架构设计：财税计算方案（Berechnungsschema）的内核

> 状态：v0.1 基线实现 · 2026-09-26
> 相关文档：[引擎与应用职责契约](engine-application-boundary.md) · [DSL 参考](dsl-reference.md) · [RFC 0001：Normein DSL 内核扩展需求](rfc/0001-normein-dsl-kernel-extensions.md) · [工作台契约](workbench/contract.md)

## 1. 目标与边界

财务与税务领域存在大量**形式化、流程化**的计算：德国个人所得税从七类收入汇总为应税所得；IAS 36 中总部资产先分摊到各 CGU，再进行减值测试；SAP CO 风格产品成本将初级和次级成本归集、分配到工单，再汇总为产品的加权单位成本。它们的共同点是：**一条清晰的线性主线 + 多个分支（汇入、分摊、条件、择优）**，而最终都以某种**计算表格**（三栏/四栏 Staffel、矩阵、底稿）呈现。

Mantra 的目标是把这些计算中**本质的、跨领域不变的模式**抽取为引擎的内置能力，用户只需用 DSL 组合这些能力来表达自己的业务逻辑。

边界（v0.1 起强制执行）：

| 层 | 归属 | 内容 | 不包含 |
| --- | --- | --- | --- |
| Normein DSL 内核 | `6234456/normein`（固定 commit 引用） | 表达式语言、类型系统、BigDecimal 精确计算、运行时预算 | Mantra 不修改它；需求写入 RFC |
| Mantra 引擎库 | 本仓库 `mantra-core`、`mantra-render`、`mantra-cli` | 通用计算组件、通用表格组件、常用预设 | **任何具体业务逻辑**（税法条文、准则步骤） |
| 领域应用 | `examples/`（将来迁往 monorepo 的独立应用） | ESt 2025、IAS 36、SAP CO 风格成本归集等具体计算方案、案例与版式 | 引擎代码 |

`examples/` 中的三个方案只是**验收样例**：证明引擎的通用能力足以覆盖不同领域，它们不属于引擎库，也不随库发布。

## 2. 模式抽取：计算方案的本质内核

对德国所得税（R 2 EStR）、IAS 36、以及典型审计底稿进行拆解后，得到下列跨领域的基本模式。每个模式都对应一个引擎内置能力（calculation primitive）。

| # | 模式 | 典型实例 | 引擎能力 |
| --- | --- | --- | --- |
| P1 | **具名数量 / 行（Posten）** | Bruttoarbeitslohn, carrying amount | `line`、`field`、`param`、`input` |
| P2 | **带符号的逐级汇总（Staffel）**：结果 = Σ ± 组成项，中间有“=”检查点 | Summe der Einkünfte → GdE → Einkommen → zvE | `section` + `total`（运行合计），`:op :plus/:minus/:info` |
| P3 | **变换（函数）** | § 32a 税率表、折现 | Normein 表达式 + `defn` |
| P4 | **限额与下限** | Höchstbetrag、Pauschbetrag（取大）、不得为负 | `min`/`max`（Normein） |
| P5 | **阈值规则** | Freibetrag（扣到为止）vs Freigrenze（全有或全无）vs Milderungszone | 表达式 + `:when` |
| P6 | **分段 / 累进** | zumutbare Belastung 分段计算、累进税率 | `calc/stepwise`、`cond` |
| P7 | **择优 / 比较计算（Günstigerprüfung, higher-of）** | Kindergeld vs Kinderfreibeträge；可收回金额 = max(FVLCD, VIU) | `choice {:rule :min/:max}` |
| P8 | **维度复制与横向合计（cross-footing）** | 夫妻两人、多个 CGU、多个出租物业 | `dimension`、`:per`、自动横向合计、`all.<id>` |
| P9 | **分摊（Umlage / allocation）**：按键分配且精确合计 | 总部资产分摊；损失按账面价值比例回分 | `alloc/pro-rata`（最大余数法）、`alloc/capped`（带上限的注水分配）、`:spread` |
| P10 | **货币时间价值** | VIU 的 DCF | `fin/df`、`fin/npv` |
| P11 | **适用条件（Anwendungsvoraussetzungen）** | 仅单独申报的单亲；仅 LuF 收入为正 | `:when`（行级、节级、选项级、维度成员级） |
| P12 | **舍入约定** | zvE 与税额向下取整到欧元；Soli 舍去分以下 | `:round [scale :mode]`、`decimal/floor` |
| P13 | **规范出处** | § 10b Abs. 1 EStG；IAS 36.104(b) | 核心 `:reference`、`:source`；应用自定义属性如 `:kz`、`:zeile` |
| P14 | **用户扩展点** | “weitere Sonderausgaben”；IAS 36 资产加权方法 | `slot` + `(extend …)`；`formula-slot` + `(bind …)` |
| P15 | **可追溯性（Prüfpfad / audit trail）** | 公式、代入值、结果 | 每个值附带 trace；渲染为“Berechnungsnachweis” |

尚未纳入 v0.1、但已识别的模式（见 §8 路线图）：多期状态与结转（Verlustvortrag、递延税滚动）、有界迭代/循环计算（利息上限、税上税）、二维及以上维度的矩阵呈现（CGU × 年度）。

## 3. 分层架构

```text
应用 schema + 用户 case
          │
          ▼
     mantra-core ─────────▶ CalculationResult
          │                       ├─ + layout ─▶ mantra-render ─▶ HTML / Text
          ▼                       └─ + layout ─▶ mantra-excel  ─▶ XLSX
       Normein
```

* **计算层（mantra-core）**：读取方案（schema）与案例（case），构建依赖图，逐顶点求值。只产出数值、成员、状态和 trace，与任何表格样式无关。
* **表现层（mantra-render）**：读取版式（layout），把同一个计算结果投影为若干张表（WorkingPaper 网格模型），再由 HTML/Text 渲染器绘制。版式永远不改变数值。
* **同一结果、多种呈现**：同一次计算可以渲染成四栏 Staffel、人员矩阵、IFRS 底稿或 XLSX，互不影响；XLSX 的部分复杂公式仍会退化成只写值，见 §9。

## 4. 计算层语义

### 4.1 方案读取

所有 Mantra 文档都用 Normein 的公开只读器 `DslFormReader` 读取（同一套 Clojure 形式语法）。宿主形式（`schema`、`section`、`line` …）由 Mantra 解释；嵌入的公式原文切片后交给 Normein 编译器。由于 Normein 读取器每次只读一个根形式且有 8,192 token 上限，大方案用 `(include "fragment.mantra")` 拆分（见 RFC 0001-A）。

### 4.2 依赖图

顶点类型：

| 顶点 | 来源 | 计算方式 |
| --- | --- | --- |
| Param | `param` | 模板字面量，可被案例覆盖 |
| Input | `input`、`field` | 案例数据（带类型校验、默认值） |
| Line | `line` | Normein 表达式 |
| Total | `total` | 引擎内置的带符号求和 |
| Choice | `choice` | 各选项表达式 + min/max 规则 |
| Dimension | `dimension` | 解析活动成员（静态成员 + 条件，或来自表格输入） |
| Condition | 节的 `:when` | 在节自身的维度上下文中求值 |

依赖来自 Normein 编译结果的 `requiredRoots` 与 `all.<id>` 字段路径引用；Total 依赖其组成项。拓扑排序确定求值顺序，环路以完整路径报告（`a → b → a`）。

### 4.3 Staffel 语义（运行合计）

* 节（section）中的项按 `:op` 贡献：`:plus` +1、`:minus` −1、`:info` 0（仅展示/辅助）。
* `total` 是**检查点**：值 = 上一个 total + 其后所有贡献项。因此一个节可以连续出现多个“=”行，正是德国 Staffel 的结构（Summe der Einkünfte = … GdE = … Einkommen = … zvE）。
* 含 total 的节是**不透明节**，它以最后一个 total 作为结果，以节的 `:op` 贡献给父节；不含 total 的节是**透明节**（纯分组），其贡献项直接穿透到父节。
* 组成项的维度多于 total 时自动**横向合计**（夫妻两人的收入汇入共同的 Summe der Einkünfte）；维度少于 total 则报错。
* 在构造上保证“显示的组成项之和 = 显示的合计”（fußen），渲染器用 Σ 标记。

### 4.4 维度

* `(dimension person {:members [...]})`：静态成员，每个成员可带 `:when`（如 Person B 仅在合并申报时存在）。
* `(dimension objekt {:from vermietungsobjekte :key :id :title :bezeichnung})`：成员来自表格输入的行；可通过 `:parent <维度> :parent-key :列` 声明父成员关系，校验引用完整性。
* 带 `:per` 的项对每个活动成员求值。公式中：
  * 同维度的引用自动**对齐**为当前成员的标量；
  * 更多维度的引用得到**成员映射**（`{:A 60000 :B 40000}`），用 `dim/sum`、`dim/min`、`dim/max` 聚合；
  * `all.<id>` 在任意上下文中取某个维度化行的完整映射（如分摊键 `all.weighted-amount`）；
  * 维度符号本身（`cgu`、`person`）是当前成员记录：`cgu.carrying-amount`、`person.label`。
* `:spread true`：在去掉最后一个维度的上下文中求值一次，返回成员映射后分发给成员——这就是“扇出”（分摊）原语。
* 表格维度声明 `:parent` 后，引擎生成 `relation_<子维度>` 映射；`dim/rollup` 按此关系将子成员的金额或数量汇入一个父成员。应用只声明业务上的父子键，聚合算法留在引擎。

### 4.5 分支

| 分支形式 | 语义 | 表现 |
| --- | --- | --- |
| 行/节 `:when` | 条件不成立时不适用：数值取 0、非数值取 nil，标记为 inactive | 默认隐藏，或显示“entfällt” |
| 维度成员 `:when` | 成员不参与 | 不出现在成员列中 |
| `choice` | 计算全部可用选项，按规则择一 | 列出各选项，✓ 标记被选项 |
| 公式内 `if`/`cond` | 普通表达式分支 | 审计轨迹中显示代入值 |

节条件由引擎自动继承。子项维度少于所在节时，引擎在相同成员上对齐条件，并仅在存在一个同时满足所有嵌套节条件的成员组合时让子项生效；方案无需声明聚合方式。核心求值器与 XLSX 公式导出器共用 `SectionGuards.align` 生成成员组合，分别计算布尔结果和对应的单元格条件公式。

### 4.6 数值与舍入

全部数值是 BigDecimal；Normein 的 `/` 不做隐式舍入（非有限小数直接报错），需要舍入的地方必须显式写 `decimal/divide … scale` 或 `:round`。税表中的“向下取整到欧元”因此是精确的——样例中 zvE 一半的税额 9.439,9993 被正确地取为 9.439（浮点实现容易得到 9.440）。

### 4.7 追溯（trace）

每个值都带 trace：输入来源（案例/默认/隐式零）、参数是否被覆盖、公式引用及其取值、舍入前原值、合计组成、选择的各选项。表现层据此生成“Berechnungsnachweis / Audit trail”。

## 5. 表现层：版式 DSL 与内置表格原语

用户未来需要掌控计算表格的大体样式，因此表现层有自己的领域 DSL（由本项目拥有并扩展），所有表格结构都是**内置原语**，版式只做组合：

| 原语 | 作用 |
| --- | --- |
| `(table <section> {…})` | 一个方案节渲染为一张表；其他被单独成表的节在父表中显示为引用行 |
| `(columns :tiered|:matrix :label …)` | 列的数量与顺序；内置列直接用关键字 |
| `(col :name {…})` | 仅在需要自定义表头、宽度、对齐或列名时声明列选项 |
| `:row-numbers :global|:table` | 自动生成行号列，跨表接续或每表重新从 1 开始 |
| `(members <dim>)` / `(member <dim> :key)` | 每个成员一列 / 指定成员列 |
| `(operators {…})` | ./.、=、+ 等运算符号 |
| `(schedule …)`、`(inline …)`、`(hide …)` | 节作为附表、内联、隐藏 |
| 文档选项 | 预设、语言/地区、小数位、负数样式、零值、隐藏零行、审计附录、表头字段 |
| `:class` + `(style {选择器} {样式声明})` | 所有样式采用同一语法；`{:all true}` 匹配全部单元格，`{:class :name}` 匹配标签，还可按 section、`:depth`、`:height`、`:indent`、`:nth-child`、列或行类型匹配；HTML 与 XLSX 共用结果 |

列内容函数（内置目录）：`label`、`operator`、`row-number`、`reference`、`note`、`source`、`status`、`formula`、`explain`、`value`、`pre`（Vorspalte）、`main`（Hauptspalte）、`cross-total`、`member`、`members`。应用元数据使用 `(attribute :name)` 挂载，如 `(attribute :kz {:header "Kz."})`；核心不固定领域字段名。

预设（常用设定，不含业务逻辑）：`de-staffel-4`（Zeile | Bezeichnung | Vorspalte | Hauptspalte + Rechtsgrundlage）、`de-staffel-3`、`ifrs-schedule`（成员列 + Total，括号表示负数，0 位小数，扣减项带符号显示）。

版式 DSL 遵循“一个概念一个名称”：整份底稿的规则放在 layout 选项中（例如 `:row-numbers`），内置列直接用关键字表达顺序，`(col …)` 只写对默认列的必要覆盖，`style` 只处理外观。预设已经定义的值不重复声明。

方案可声明纯展示提示：计算项的 `:sign-labels` 按结果正负零选用标签，元数据的 `:headline` 指定主要结果，输入的 `:group` 与元数据 `:group-titles` 为录入导航命名。它们通过只读结果视图传给纸面、工作簿和结构 JSON，不参与计算；详见[工作台契约 §10](workbench/contract.md)。

Staffel 的栏位规则：表根节自身的项与检查点、以及直接子节的结果进入 **Hauptspalte**；嵌套子计算的组成项进入 **Vorspalte**（按深度缩进）；透明节不增加层级。矩阵表中维度化的行填入成员列并横向合计，标量行只填合计列。

## 6. 用户可定制的部分

| 定制对象 | 手段 | 位置 |
| --- | --- | --- |
| 事实数据 | `(inputs {…})` | case |
| 模板参数（年度数值、税率） | `(params {…})` 覆盖 `param` | case |
| 选择权（Wahlrechte） | keyword/boolean 输入，如 `:veranlagungsart`、`:guenstigerpruefung-kap` | case |
| 自定义行与公式 | 在方案声明的 `slot` 中 `(extend slot-id (line …))`，渲染时 ▲ 标记 | case |
| 应用开放的公式 | 在方案声明的 `formula-slot` 中 `(bind id <公式>)`，引擎校验类型、维度与依赖 | case |
| 辅助函数 | `(defn …)` | schema 或 case |
| 表格样式 | layout 文档 | layout |

引擎定义通用计算逻辑及其 DSL 接口；领域应用在此基础上定义业务流程、输入契约和可开放的定制点；用户通过 DSL 使用这些能力，提供案例数据、参数和应用允许的计算表达式。版式 DSL 开放内置表格、列、顺序、数字格式和受限样式规则，不允许版式改变计算结果。计算项可以声明 `:class :variance` 等标签，layout 用 `(style {:class :variance} {…})` 赋予外观；通用默认规则写作 `(style {:all true} {…})`。布局生成时，表现层为每个单元格提供所属表、section 路径、从表根向下的 `depth`、从叶子向上的 `height`、视觉 `indent`、表内行序、行类型、行号列配置和列角色等上下文；匹配规则按 layout 中的声明顺序合并。扩展选择条件或样式属性时沿这一接口增加受控词汇，不引入原生 CSS。

以 IAS 36 总部资产分摊为例，引擎提供 `line`、`dimension`、`alloc/pro-rata` 等原语及维度对齐、条件继承、精确分摊、横向合计与追溯；IAS 36 应用定义减值测试流程及 CGU、总部资产等输入；用户用 DSL 表达应用开放的资产价值加权方法，提供具体参数和事实数据，并选择底稿样式。加权方法属于领域应用或用户表达式，不固化在引擎中。

SAP CO 风格案例遵守同一边界：引擎负责表格记录、工单与产品维度、分摊、跨维度引用、合计及追溯；领域方案在 [schema.mantra](../examples/sap-co-product-cost/schema.mantra) 中定义费用分类、分配基数、工单归集、产品加权口径和标准成本差异。用户提供成本流水、工单数量、标准成本和允许调整的参数。工单到产品的关系由应用输入契约表达，成员对齐与计算依赖由引擎处理，用户不需要配置条件的跨维度生效方式。

`slot` 用于追加计算项；`formula-slot` 用于替换应用开放的计算公式。IAS 36 的 `weighting` 现已声明为公式扩展点；案例中的 `(bind weighting …)` 在原行维度下编译，继承结果类型、舍入规则、依赖检查和应用声明的 `:uses` 引用范围。应用仍决定哪些行可替换。

## 7. 与 Normein 的集成

* 以 composite build 引用固定 commit（`normein-build.lock`，与 invoice-parser 的做法一致），只替换 `com.xqiou:normein-dsl`。
* Mantra 自己的函数库 `mantra.calc@1`（`alloc/*`、`calc/stepwise`、`dim/*`、`fin/*`）作为普通领域库通过 `DslLibraryDescriptor` 组合到标准环境中；Normein 内核不做任何修改。
* 使用中发现的内核层需求记录在 [RFC 0001](rfc/0001-normein-dsl-kernel-extensions.md)。

## 8. 覆盖情况与迭代路线

| 能力 | ESt 2025 | IAS 36 IE8 | SAP CO 风格案例 | 状态 |
| --- | --- | --- | --- | --- |
| Staffel + 多检查点 | zvE、festzusetzende ESt、Abrechnung | 集团测试 | 来源、工单、产品、对账 | ✅ |
| 维度（静态/条件成员、表格成员） | 夫妻、出租物业 | CGU | 工单、产品 | ✅ |
| 横向合计 / `all.` | 收入汇总 | 分摊键 | 工单金额聚合到产品 | ✅ |
| 分摊（最大余数法） | – | 总部资产、损失回分 | 初级与次级成本池按工单基数分配 | ✅ |
| 择优 | Günstigerprüfung § 31 | max(VIU, FVLCD) | – | ✅ |
| 阈值与条件 | Freigrenze § 23、Soli、§ 24b、§ 13 Abs. 3 | FVLCD 可用性 | 直接费用与成本池分类 | ✅ |
| 分段计算 | zumutbare Belastung | – | – | ✅ |
| 用户扩展 | Schulgeld（slot） | – | – | ✅ |
| 表现：Staffel / 矩阵 / 附表 / 审计轨迹 | ✅ | ✅ | ✅ | ✅ |
| 带上限的分摊（IAS 36.105） | – | 函数已提供 | – | 🟡 样例待补 |
| DCF / 年度维度 | – | VIU 计算 | – | 🟡 函数已提供，二维呈现待做 |
| 多期结转、有界迭代 | Verlustvortrag § 10d Abs. 2 | – | – | ⏳ |

迭代计划：

1. **v0.2 时间维度**：年度维度与二维矩阵（CGU × 年），IAS 36 VIU 由现金流计算；AfA/资产变动表（期初 + 增加 − 减少 − 折旧 = 期末，即时间方向的 Staffel）；IFRS 16 租赁表。
2. **v0.3 企业税**：KSt/GewSt（Hinzurechnungen/Kürzungen、Freibetrag、Hebesatz）、IAS 12 递延税（逐项暂时性差异 × 税率）、税率调节表（Überleitungsrechnung）。
3. **v0.4 状态与迭代**：多期状态与结转、受控的定点迭代（领域库函数，基于 `reduce` 或 `invokeCallable`，见 RFC 0001 验收契约 G）、扩大 XLSX 公式覆盖范围、编辑器支持（基于现有 `DslAuthoringService`，位置映射依赖 RFC 0001-B）。
4. **monorepo**：把 `examples/` 中的方案迁为独立领域应用（如 `apps/de-est`、`apps/ifrs-impairment`），引擎作为共享库。

## 9. 已知限制（v0.1）

* 每个顶点、每个成员单独调用 Normein 求值（完整 receipt 路径）；对数百行的方案足够快，大批量场景应改用 Normein 的 execution plan / session（VALUE_ONLY）。
* 渲染器目前只支持一个成员维度作为列；其他维度在合计列中横向合计。
* 输入校验报告位置指向声明处，尚未指向案例文件中的具体值。
* 审计轨迹只替换根引用的值，不展示子表达式的中间值（见 RFC 0001-F）。
* SAP CO 风格案例的工单→产品汇总使用 `dim/rollup`，XLSX 可随工单的产品归属变化重算。公式翻译器还支持当前案例使用的单参数 `map(fn [row] …)`；费用流水的金额变化会传导到工单、产品与对账行。更广泛的高阶函数及增删表格行的动态公式范围尚未定义。
* `:aggregate false` 已成为引擎中的不可加总规则，结构化结果的 `crossTotal()` 返回 `null`，底稿和 XLSX 同步留空。跨成员单位成本由应用显式写成总成本除以总数量；通用的分子/分母比率聚合规则仍可进一步设计。
* SAP 案例已用维度父键检查工单所属产品，并用表格 `:references` 检查非空费用工单编号是否存在。条件性必填和基数校验仍待细化，例如直接费用必须填工单、成本池非零时分配基数之和必须为正。具体 SAP 科目分类仍由领域应用定义。
