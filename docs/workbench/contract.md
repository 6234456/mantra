# Mantra 工作台契约 v1（草案）

> 状态：草案，尚未实现 · 2026-09-27
> 相关文档：[架构](../architecture.md) · [引擎与应用职责契约](../engine-application-boundary.md) · [RFC 0001](../rfc/0001-normein-dsl-kernel-extensions.md) · [界面规格](ui-spec.md) · [工作包](work-packages.md) · [设计稿源文件](design/)

## 1. 定位

工作台（Workbench）是 Mantra 文档的通用阅读器和结构化编辑器。它处理四类文档：方案（`schema`）、案例（`case`）、参数集（`parameters`）和版式（`layout`）。

- **不引入界面 DSL。** 界面的结构来自 `SchemaMap`，表格、格式和样式来自版式层的 `WorkingPaper`，数值、追溯和诊断来自引擎。
- **界面编辑 DSL。** 界面上的每个编辑都落到案例或版式文档里已有的形式（§7）。离开工作台，文本本身足以复现全部结果。
- **对所有方案通用。** ESt 2025、IAS 36、SAP CO 三个验收方案用来检验通用性，界面不为它们做特例。

本文约定引擎、服务和前端之间的契约，包括模块边界、标识与修订、数据文档、编辑语义、HTTP 接口、安全与资源上限，以及验收方式。界面细节见[界面规格](ui-spec.md)，分工见[工作包](work-packages.md)。

v1 不包括：
- 多用户与权限、远程部署、实时协作；
- 方案编辑（方案仍由领域应用以文本维护）；
- 领域专用视图。

## 2. 原则

| # | 原则 | 检查方式 |
| --- | --- | --- |
| W1 | 文档是唯一事实来源；工作台没有私有数据模型 | 丢弃服务端状态后，从工作区文本重新加载，得到相同的修订和结果 |
| W2 | 界面不含业务逻辑 | 前端显示的每个数字都来自引擎；前端不按方案 id、节点 id 或领域词分支；前端源码中不出现三个验收方案的标识符（CI 检查）。前端只能为作图从引擎值推导几何量（如条形长度），不能把推导结果当作数字显示 |
| W3 | 引擎计算，版式排列，界面绘制 | 表格的行、列、格式和样式只来自 `WorkingPaper`；显示哪些行由版式决定，前端不做取舍 |
| W4 | 精确数值 | 数值以十进制字符串传输（§6.1）；显示文本由服务端按版式格式化；用户输入的原文由服务端解析 |
| W5 | 契约先行、带版本 | 每个响应带契约版本；JSON Schema 与 golden 文件随代码提交；不兼容的变化提升主版本 |
| W6 | 通用性 | 三个验收方案不改前端代码即可完整浏览、编辑和导出 |
| W7 | 本地优先、默认安全 | v1 只监听回环地址，只读写工作区目录内的文件（§9.5） |

## 3. 分层与模块

```text
workbench-ui（TypeScript；绘制与交互）
      │  HTTP/JSON + SSE（§9）
mantra-server（HTTP 路由、并发控制、文件监视、静态资源）
      │  Kotlin API
mantra-workbench（通用服务：工作区、修订、Explain、Compare、案例编辑、公式编辑、导入）
      │
mantra-core（计算与只读结果视图） · mantra-render（WorkingPaper） · mantra-excel（XLSX）
      │
Normein（固定 commit）
```

| 模块 | 状态 | 职责 | 不包含 |
| --- | --- | --- | --- |
| `mantra-core` | 现有 | 计算；新增只读结果视图（§5） | HTTP、会话、文件监视 |
| `mantra-render` | 现有 | 版式 → `WorkingPaper`；新增按板块取表和 JSON 投影 | 交互状态 |
| `mantra-excel` | 现有 | XLSX 导出、按命名单元格回读 | — |
| `mantra-workbench` | 新增 | §4–§8 的全部服务；可被 CLI 复用（如 `mantra explain`、`mantra diff`） | 领域名词、HTTP |
| `mantra-server` | 新增 | §9 的接口 | 计算与业务规则 |
| `workbench-ui` | 新增 | 外壳、导航、绘制、编辑交互 | 数值运算、领域分支 |

依赖只能向下。`mantra-workbench` 和 `mantra-server` 与引擎库一样，不含业务逻辑。

## 4. 工作区、标识与修订

### 4.1 工作区

工作区是一个目录。服务启动时扫描其中的 `.mantra` 文件，并按根形式分类：`schema`、`fragment`、`case`、`parameters`、`layout`。

- 案例通过 `:schema` 找到方案。
- 未声明 `:schema` 的案例只读显示，并附诊断（拟新增代码 `MANTRA-WORKBENCH-CASE-SCHEMA`）。
- 案例使用的参数集、版式和数据来源都在案例文本中声明（D1，见 §4.4）。

### 4.2 标识

| 对象 | 标识 | 例 |
| --- | --- | --- |
| 案例 | 工作区相对路径（URL 中编码） | `de-est-2025/case-mustermann.mantra` |
| 方案 | 方案 id + `:version` | `de.est/2025` · `2025.1` |
| 板块 | 节 id，即 `SchemaMap.panels[].id`，也等于 `PaperTable.id` | `einkuenfte` |
| 节点 | 顶点 id | `bruttoarbeitslohn` |
| 成员坐标 | 按节点维度顺序排列的成员键；标量为空列表 | `["B"]` |
| 表格输入的单元 | 行 + 列。表格作为维度来源（`:from … :key`）时，行用键列的值；否则用从 0 开始的行号，行号只在同一修订内有效 | `{"row": "leipzig", "column": "mieten"}` |
| 文档位置 | 文档路径 + 行、列（从 1 开始）+ 起止偏移 | `case-mustermann.mantra:17:6` |

地址（address）在 JSON 中统一写成对象：

```json
{"node": "bruttoarbeitslohn", "coord": ["B"]}
{"node": "vermietungsobjekte", "cell": {"row": "leipzig", "column": "mieten"}}
```

URL 中使用字符串形式 `节点[@成员/成员…][#行.列]`，例如 `bruttoarbeitslohn@B`、`vermietungsobjekte#leipzig.mieten`。其中的保留字符（`@`、`/`、`#`、`.`）按百分号编码。

### 4.3 修订

- **修订号 `revision`**：参与计算的全部文档内容的 SHA-256，取前 16 位十六进制。文档包括案例、方案及其 `include` 片段、参数集、版式和数据文件。
- **读写校验**：所有读取响应都带 `revision`；所有写入请求都带 `baseRevision`，与当前修订不一致时返回 409（§9.3）。
- **结果缓存键**：`revision` 加引擎指纹，即 Mantra 版本、`normein-build.lock` 中的 commit，以及内核语言、标准库和求值器版本。修订只标识文档状态，引擎升级不改变修订，但会使缓存失效。

### 4.4 案例绑定（D1 已定）

参数集和版式写在案例元数据中；数据来源及映射写在案例的 `(sources …)` 中。案例连同它引用的工作区文件，就能复现计算和呈现。服务端会话状态不保存绑定。`setBindings` 修改这些文本声明（§7）。

```clojure
(case example
  {:schema "de.est/2025"
   :parameters ["de.est/params-2026"]
   :layout "de.est/steuerberechnung"}
  (sources
    (csv {:path "imports/payroll.csv"
          :mode :wide
          :member-column "Person"
          :columns {"Bruttolohn" "bruttoarbeitslohn"}})
    (json {:path "imports/other.json"
           :mapping {:spenden "donations.total"}}))
  (inputs {:spenden 450}))
```

- `:parameters` 是按优先级从低到高排列的参数集 id 列表；空列表表示不使用参数集。案例的 `(params …)` 覆盖它们。`:layout` 是版式 id；省略时使用 §6.4 的默认表风格，不隐式选择工作区中的某个版式。已有案例未声明这些字段时仍可读取。
- `(sources …)` 按声明顺序应用；后面的来源覆盖前面的来源，案例的 `(inputs …)` 最后覆盖来源值，与现有 `DataSources.apply` 一致。省略表示没有数据来源。`csv`、`json`、`xlsx` 的选项对齐现有 `DataSource` 构造参数；`csv :mode :wide` 和 `:member-column` 是 WP11 新增能力（G7）。JSON `:mapping` 的键是目标输入 id、值是源文件内路径；CSV `:columns` 的键是源列名、值是目标输入或表格列 id，具体取决于模式。
- `:path` 相对于案例文件所在目录解析，规范化后必须留在工作区内；不存在、重复或无法解析的 id 与路径给出带位置的诊断。导入模板可供复用；应用模板时把完整来源声明写入案例，避免结果依赖模板的后续修改。

## 5. 只读结果视图（mantra-core）

对应[边界文档](../engine-application-boundary.md)“下一步”第 5 项。

现状：`CalculationResult` 直接暴露 `CalculationPlan`，渲染、XLSX 和 `StructureJson` 都读取具体的顶点类型。

v1 定义一个只读视图（暂名 `CalculationView`），包含：

- **方案元数据**：id、版本、标题、期间、主线声明。
- **结构**：`SchemaMap`，即板块、角色、流、主线入口、面包屑、通用输入和参数。
- **节点元数据**：
  - 种类：`input`、`param`、`line`、`total`、`choice`、`formula-slot`、`extension`；
  - 标签、类型、维度、`:op`、`:reference`、`:source`、`:note`、`:class`；
  - 应用属性（如 `:kz`）、公式原文及其位置；
  - 是否用户自定义、所属 slot。
- **输入约束**：类型、默认值、`:min` / `:max` / `:options` / `:columns` / `:references`、`:help`、`:unit`。
- **每次计算的结果**：活动成员、每个坐标的值、是否适用、`NodeTrace`，以及诊断。

`mantra-render`、`mantra-excel` 和工作台只依赖这个视图；`Planner`、编译表达式和执行缓存留在引擎内部。验收方式：用测试检查这三个模块不再导入 `engine` 包中的顶点类型。

## 6. 数据文档

所有响应使用同一个外层：

```json
{
  "contract": "mantra.workbench/1",
  "revision": "3f6c0e1a9b2d4c75",
  "engine": {"mantra": "0.1.0-SNAPSHOT", "normein": "be7648b5"},
  "data": {}
}
```

### 6.1 值的编码

| 引擎值（`Value`） | JSON |
| --- | --- |
| `Nil` | `null` |
| `Num` | `{"n": "83217.90"}`：十进制字符串，保留标度 |
| `Bool` | `true` / `false` |
| `Kw` | `{"kw": "zusammen"}` |
| `Text` | `"…"`：裸字符串只表示文本 |
| `Date` | `{"date": "2025-12-31"}` |
| `Vec` | `[…]` |
| `MapV` | `{"map": [[键, 值], …]}`：保持顺序 |

值中不出现 JSON 数字。现有 `StructureJson` 把 `BigDecimal` 写成 JSON 数字（例如 `"value": 17996.0`），JavaScript 读入后会变成浮点数，v1 要改成上表的编码。

需要显示的地方同时给出 `display`。它由服务端按当前版式的区域、精度、负数样式和零值规则格式化，与纸面使用同一套 `Formatting`。

### 6.2 Structure（结构）

在现有 [StructureJson](../../mantra-core/src/main/kotlin/com/xqiou/mantra/core/structure/StructureJson.kt) 的基础上扩展；当前输出样例见 `examples/build/out/est-2025-structure.json`（运行 `./gradlew test` 生成）。

保留的字段：
- `schema`、`title`、`mainline[]`、`generalInputs`、`params`；
- `panels[]`：`id`、`title`、`role`、`step`、`parent`、`dims`、`result`、`breadcrumb`、`entries`、`fields`、`nodes`、`imports`、`exports`。

v1 的变化：

| 变化 | 原因 |
| --- | --- |
| 数值改用 §6.1 编码，并移出结构，归入 Run | W4；结构只随文档变化，值随计算变化 |
| 面包屑首项由 `{"label": "mainline"}` 改为 `{"kind": "mainline"}` | “mainline”是界面文字，由前端本地化 |
| 新增 `nodes`：节点 id → 元数据（§5） | 前端不必从表格反推节点信息 |
| 新增 `slots`、`formulaSlots`：id、标题、所在板块、`:uses`、默认公式、当前扩展或绑定 | 用于扩展页和公式槽卡片 |
| 新增 `schemaVersion`、`headline`（§10） | 用于顶栏和案例列表 |

`headline` 是节点 id 字符串（无法解析时为 `null`），包括未声明时的主线末步默认值。`groupTitles` 是已使用的显式输入组键到显示标题的映射；`fields[]`/`generalInputs[]` 含 `group` 和 `groupTitle`，`nodes` 中还包含 `signLabels` 与 `group`。这些字段只传递展示提示，不参与计算。

### 6.3 Run（一次计算的结果）

```json
{
  "succeeded": true,
  "members": {"person": [{"key": "A", "label": "Person A"}, {"key": "B", "label": "Person B"}]},
  "values": {
    "bruttoarbeitslohn": {
      "A": {"value": {"n": "68500"}, "display": "68.500,00", "active": true, "origin": "case"},
      "B": {"value": {"n": "31200"}, "display": "31.200,00", "active": true, "origin": "case"}
    },
    "zu-versteuerndes-einkommen": {
      "": {"value": {"n": "83217.90"}, "display": "83.217,90", "active": true}
    }
  },
  "diagnostics": []
}
```

- **坐标键**：成员键用 `/` 连接，标量为 `""`（与现有 `StructureJson` 一致）。
- **`origin`**：只出现在输入上。
  - 已有取值：`case`、`default`、`implicit`，来自 `InputOrigin`。
  - 待补取值：值来自数据来源时为 `source:<描述>`（缺口 G3，§10）。
- **参数**：值带 `source`，即现有的 `ParamVertex.source`。
- **不适用的值**：`active: false`，`display` 按版式处理（例如 “entfällt” 或留空）。

### 6.4 Paper（版式结果）

Paper 是 `WorkingPaper` 的 JSON 投影。请求参数为版式 id 和可选的板块 id。

Paper 顶层的 `headline` 为 `{node, label, value}` 或 `null`；`inputGroups[]` 为 `{key, title, inputs}`。`label` 与表行标签已经按 `:sign-labels` 和当前结果解析，`value` 仍使用版式格式化的带符号结果。输入组仅供导航，不改变表行的顺序与数值。

- **板块视图**：`PaperTable.id` 等于节 id，所以板块视图就是 id 相同的那张表，外加它引用的附表行。
- **版式未给板块建表时**：服务端按该节的默认表风格（节的 `:layout` 选项或预设）生成（缺口 G2）。

现有字段保持不变：标题、表头、概览、表、列、行、审计条目、图例、诊断。唯一的改动是每个单元格改为对象：

```json
{"text": "68.500,00", "address": {"node": "bruttoarbeitslohn", "coord": ["A"]}, "editable": true,
 "style": {"weight": "normal", "tone": "default", "fill": "none"}}
```

- **`address`**：把单元格与 Explain、编辑和 Herkunft 关联起来，前端不必根据列角色推算成员。
- **`editable`**：只有输入单元格为真。从其他板块导入的值永远只读，并通过 `imports` 链接到来源。
- **行标志**：沿用 `RowFlag`（`INACTIVE`、`USER_DEFINED`、`SELECTED`、`FOOTED`、`INFO`、`GRAND`、`NEGATED`），另新增 `EXPLAINS_ZERO`，标记“非零输入经规则变为零”而保留的行。`:hide-zero` 已经保留这类行，v1 只是把原因显式标出。
- **样式**：只有 `weight`、`tone`、`fill` 三类受控属性，与 layout 的 `style` 相同；前端把它们映射到设计令牌。

### 6.5 Explain（一个值的计算过程）

请求：地址，外加可选的深度。响应示例（节选，ESt 样例 [schema.mantra:153](../../examples/de-est-2025/schema.mantra)）：

```json
{
  "address": {"node": "ermaessigung-35a"},
  "label": "Steuerermäßigung für haushaltsnahe Dienstleistungen und Handwerkerleistungen",
  "kind": "line",
  "formula": {
    "text": "(min (max 0 (- tarifliche-est ermaessigung-35)) (+ (min (* 0.2 haushaltsnahe-dienstleistungen) 4000) (min (* 0.2 handwerkerleistungen) 1200)))",
    "location": {"document": "de-est-2025/schema.mantra", "line": 154, "column": 7}
  },
  "result": {"value": {"n": "740.0"}, "display": "740,00", "rounding": null},
  "status": "active",
  "steps": [
    {"text": "(max 0 (- tarifliche-est ermaessigung-35))", "value": {"n": "15676"}, "display": "15.676,00"},
    {"text": "(min (* 0.2 haushaltsnahe-dienstleistungen) 4000)", "value": {"n": "240.0"}, "display": "240,00"},
    {"text": "(min (* 0.2 handwerkerleistungen) 1200)", "value": {"n": "500.0"}, "display": "500,00"}
  ],
  "branches": [],
  "references": [
    {"address": {"node": "haushaltsnahe-dienstleistungen"}, "label": "Arbeitskosten haushaltsnahe Dienstleistungen",
     "value": {"n": "1200"}, "display": "1.200,00", "kind": "aligned", "origin": "case"}
  ],
  "parts": [],
  "options": [],
  "reference": "§ 35a Abs. 2, 3 EStG",
  "truncated": false
}
```

| 字段 | 来源 | 状态 |
| --- | --- | --- |
| `formula`、`references`、舍入前原值、`rounding` | `NodeTrace.Computed` | 已有 |
| `parts` | `NodeTrace.Sum`：组成项、符号、是否横向合计 | 已有 |
| `options` | `NodeTrace.Choice`：全部选项、可用性、被选项 | 已有；每个选项与被选项的差额 `difference` 由引擎补算 |
| `status` 与不适用原因 | `NodeTrace.Inactive` / `Failed` | 已有 |
| `steps`（子表达式的中间值）、`branches`（`if` / `cond` 实际走的分支） | 内核 FULL trace + `sourceIndex` + trace 值渲染（RFC 0001-F） | 需采用新内核（WP1） |
| 公式与步骤的精确起止位置 | `hostPosition` | 需采用新内核 |

- **预算**：一次 Explain 只对一个节点的一个坐标以 FULL trace 重新求值。渲染量受内核的 trace 渲染预算约束（默认总计 1,000,000 字符，单个值 4 KiB），超出时返回 `truncated: true`。
- **来源树（Herkunft）**：前端逐层按需请求，每层是一次 Explain，`references` 中的地址就是下一层。`depth` 默认 1，最大 5。依赖图无环（`MANTRA-CYCLE` 已保证），所以展开一定会终止。

### 6.6 Diagnostics（诊断）

现有的 `Diagnostic` 字段是 `severity`、`code`、`message`、`location`（文档、行、列）和 `nodeId`。v1 增加起止偏移、地址和相关位置：

```json
{
  "severity": "error",
  "code": "MANTRA-INPUT-TYPE",
  "message": "…",
  "location": null,
  "address": {"node": "kinder"},
  "related": []
}
```

上例是界面把 “1,5” 提交给整数输入 `kinder` 时的结果：服务端按区域规则解析得到 1.5，不是整数，于是拒绝写入（§7.1）。文档没有被改动，所以没有 `location`，只有 `address`。

- **`address`**：诊断针对的输入或节点，以及成员和单元格。录入页据此把错误放到对应字段下方。
- **`location`**：有文档位置时，带行、列和 `startOffset` / `endOffset`。输入错误应指向案例文件中的值，而不是方案中的声明。这是[架构 §9](../architecture.md)记录的已知限制（缺口 G4）。
- **代码与消息**：代码稳定，Mantra 为 `MANTRA-<AREA>-<DETAIL>`，内核为 `DSL-*`。`message` 目前是英文；界面按代码显示本地化说明，消息原文作为细节（D2）。

### 6.6.1 只读测试数据清单

`mantra fixtures <case.mantra> [more cases...] --out <dir> [--workspace <dir>]` 在输出目录下写出 `index.json` 和每个案例的
`structure.json`、`run.json`、`paper.json`、`diagnostics.json`。四份文档都使用 §6 的
`contract`/`revision`/`engine`/`data` 外层。清单本身只用于静态文件分发，不是 HTTP API 响应：

```json
{"cases":[{"id":"example/case.mantra","title":"Example","files":{"structure":"/fixtures/example-case-1234abcd/structure.json","run":"/fixtures/example-case-1234abcd/run.json","paper":"/fixtures/example-case-1234abcd/paper.json","diagnostics":"/fixtures/example-case-1234abcd/diagnostics.json"}}]}
```

`id` 与 §4.2 的案例标识相同，是工作区相对路径；`--workspace` 指定该路径的根目录，
省略时使用所给案例目录的共同父目录。输出子目录使用可安全分发的独立名称。
`files` 的值是可由浏览器直接请求的路径。后续 Explain 工作包可增加
`files.explains`（地址字符串到 Explain 文件路径的映射）。

`fixtures` 命令针对仓库中的独立验收方案：案例文件旁须有 `schema.mantra`。
若案例声明 `:layout`，须有同目录 `layout.mantra` 且其 id 与绑定一致；未声明时使用默认版式，
与工作区服务一致。此命令仍不按案例绑定解析工作区中任意路径的方案、参数集或数据文件。
完整的案例绑定与所有参与文件的修订计算由后续工作区服务实现（WP7、WP11）。

### 6.7 Compare（两次计算的差异）

请求包含基准（当前案例）和一个变体。变体可以是另一组参数集、另一份案例，或一组尚未写入的编辑（§7.1 的预演）。

`POST /cases/{case}/compare` 的 JSON 请求为 `{"variant":{"parameters":["参数集 id"],"case":"工作区相对案例路径"}}`。`variant` 必填，`parameters` 与 `case` 至少提供一个；两者均可单独使用。基准使用 URL 中案例绑定的参数集和版式；变体案例的版式不参与比较。提供 `parameters` 时，有序列表**替换**变体案例自身的参数集绑定（空数组表示不使用参数集）；省略时沿用变体案例的绑定。省略 `case` 时使用 URL 中的案例。另一案例必须使用相同方案 id；不存在的案例返回 404，未能解析的参数集返回 422，错误类型、非规范案例路径、重复 JSON 键及额外字段返回 400。请求不写文档，因此不使用 `baseRevision`。未写入的编辑预演在 WP6 接入之前返回 501。

```json
{
  "variant": {"parameters": ["de.est/params-2026"]},
  "mainline": [
    {"step": 1, "panel": "zve", "node": "zu-versteuerndes-einkommen", "coord": [],
     "base": {"n": "83217.90"}, "variant": {"n": "83061.90"}, "delta": {"n": "-156.00"},
     "basePresent": true, "variantPresent": true,
     "display": {"base": "83.217,90", "variant": "83.061,90", "delta": "-156,00"}}
  ],
  "changes": [],
  "parameterChanges": [{"node": "tarif-gfb", "coord": [], "base": {"n": "12096"}, "variant": {"n": "12348"},
    "delta": {"n": "252"}, "basePresent": true, "variantPresent": true,
    "display": {"base": "12.096,00", "variant": "12.348,00", "delta": "252,00"},
    "baseSource": "schema", "variantSource": "de.est/params-2026"}]
}
```

- 差值由引擎计算（W2）。`changes` 列出所有值有变化的节点和坐标，按主线步骤和板块分组。
- 编辑成功后的响应也附带同样结构的差异（§7.3），界面用它显示“Wirkung der Eingabe”。

WP5 的确定规则：只能比较同一个方案 id 的两次计算。`mainline` 仅列结果值变化的主线步骤，顺序与 Structure 的 `mainline` 相同；每项是该板块 `result` 节点的标量或成员坐标差异。`changes` 按主线入口步骤、板块顺序分组，形如 `{step, panel, items:[{node, coord, base, variant, delta, display}]}`；不进入主线的辅助板块 `step` 为 `null`，未归属板块的一般输入使用 `panel: null`。参数不重复放入 `changes`，只放在 `parameterChanges`。

`variant.parameters` 是请求的有序参数集 id，案例 id 改变时再附 `variant.case`；尚未写入的编辑预演由 WP6 在请求层标记。

两个坐标只要数值大小不同就算变化；十进制标度不同但数值相等不算变化。非数值按值类型比较，`delta` 为 `null`。某侧没有该坐标时对应值为 `null`，并在差异项上标 `basePresent` / `variantPresent`，以区别“存在且值为 nil”。数值两侧均存在时 `delta = variant − base`；其他情况为 `null`。`display` 按请求版式格式化，各值为字符串或 `null`。节点/坐标、板块和步骤均有确定顺序，重复计算得到相同的文档。

### 6.8 Parameters（参数分层）

每个参数列出各层的值和出处：

响应 `data` 为 `{"parameters": [参数对象, …]}`，数组按方案声明顺序排列；每个参数对象如下：

```json
{
  "id": "tarif-gfb", "label": "Grundfreibetrag (Ende Zone 1)", "reference": "§ 32a Abs. 1 Satz 2 Nr. 1 EStG",
  "layers": [
    {"layer": "schema", "value": {"n": "12096"}, "declared": true},
    {"layer": "parameters", "set": "de.est/params-2026", "value": {"n": "12348"}, "declared": true,
     "reference": "§ 32a Abs. 1 Satz 2 Nr. 1 EStG i. d. F. ab VZ 2026"},
    {"layer": "case", "value": null, "declared": false}
  ],
  "effective": {"value": {"n": "12348"}, "layer": "parameters", "set": "de.est/params-2026"}
}
```

优先级沿用引擎的规则：方案默认 < 参数集（按给定顺序）< 案例的 `(params …)`。`ParamVertex` 和只读结果视图保留每层的值及出处。

`layers` 总是以 `schema` 开始，随后按请求顺序列出每个参数集**实际声明**的该参数，最后列出 `case`（未覆盖时 `value: null`、`declared: false`）。每个参数集层携带其 id 与该值自己的引用；`effective` 指向最后一个已声明的层。`declared` 区分未覆盖与明确写入的 `nil`。即使多个参数集写入相同数值，仍保留每层的来源。参数比较只列最终有效值发生变化的参数，内容与 `mainline` 的 `{node, coord, base, variant, delta, display}` 相同，并附两侧的有效来源。

CLI 使用 `mantra diff <schema.mantra> --case <case.mantra> --variant-parameters <file[,file...]>`，可选 `--base-parameters <file[,file...]>` 和 `--variant-case <case.mantra>`；两侧参数路径列表从低到高优先，文件均按原样加载。默认输出带 §6 统一外层的 Compare JSON，`--out` 写入文件；`--format text` 输出简表。工作区只读服务已将案例绑定中的参数集 id 解析为路径，并通过 `GET …/parameters` 展示分层；`POST …/compare` 已支持有序参数集或另一份案例的只读变体计算。
CLI 的 `revision` 对参与文件按逻辑角色标记并哈希内容：方案及片段、两侧案例、版式，以及带索引的两侧参数列表。文件在方案目录以外时，绝对路径不进入哈希；参数列表的顺序会影响修订号。

### 6.9 Export（导出）

- **XLSX**：调用 `ExcelExport.workbook(result, layout, options)` 下载实际工作簿；预览响应附带同次构建所得的导出报告：公式单元格数、输入单元格数、命名区域数、只写值的回退清单和求值错误。界面上的 “Formeltreue” 显示的就是这份报告。
- **工作表预览**：`GET /cases/{case}/export-preview?sheet=<工作表名>&layout=<版式 id>` 返回标准 envelope。`data.sheets[]` 按实际工作簿顺序包含 `name`、`rows`、`columns`；`data.selectedSheet` 是实际选择的工作表名，省略 `sheet` 时选第一张；`data.preview` 包含 `rows`、`columns`、`truncated` 和前 50 行、前 20 列的非空单元格（`address`、`kind`、`value`、`formula`）。`formula` 不带前导 `=`；数值 `value` 是十进制字符串，公式单元格的值来自工作簿求值缓存，求值失败可为空。`data.names[]` 包含工作簿命名区域的 `name`、`refersTo`。`data.report` 直接映射 `ExcelReport`：`formulaCells`、`inputCells`、`names`、`fallbacks[]`（`sheet`、`cell`、`nodeId`、`reason`）、`evaluationErrors[]`。未知工作表返回 404。预览和下载均从当前案例、参数和版式重新构建，不能使用静态示例统计。
- **HTML 和文本**：调用 `Render.html` 与 `Render.text`。

## 7. 编辑语义

### 7.1 操作

| 操作 | 参数 | 写入位置 | 引擎校验 |
| --- | --- | --- | --- |
| `setInput` | 地址；用户原文 `text`，或已编码的 `value` | `(inputs {…})`；维度化输入写成员映射 | 未知输入、类型、`:min` / `:max`、`:options` |
| `clearInput` | 地址 | 删除条目或成员键，回到默认值 | — |
| `insertRow` / `updateRow` / `deleteRow` / `moveRow` | 表格输入、行、记录 | `(inputs {:<表格> […]})` | 列类型、`:references`、键唯一 |
| `setParam` / `resetParam` | 参数 id；原文或值 | `(params {…})` | 未知参数、类型 |
| `addExtension` / `updateExtension` / `removeExtension` | slot id、行 id、标题、公式原文 | `(extend <slot> (line …))` | slot 存在、公式能编译、维度、循环 |
| `bindFormula` / `unbindFormula` | formula-slot id、公式原文 | `(bind <id> …)` | 类型、维度、`:uses`、循环 |
| `setMeta` | 元数据键（`title`、`subject`、`period`、`prepared-by`、`reviewed-by`、`date`、`reference`）与文本 | 案例元数据映射 | — |
| `setBindings` | 参数集、版式、数据来源 | 案例元数据 `:parameters`、`:layout` 与 `(sources …)`（§4.4） | 能否解析 |

- **原文解析**：用户原文由服务端按版式区域解析，规则与 CSV 导入相同（德语默认 `1.234,56`）。前端不解析数字（W4）。
- **原子批处理**：一个请求可以包含多个操作，按顺序原子执行。服务端先在内存副本上应用这些操作，再读取、计划、计算。
  - 出现读取、案例、输入、公式或循环错误时（`MANTRA-READ-*`、`MANTRA-CASE-*`、`MANTRA-INPUT-*`、`MANTRA-FORMULA`、`MANTRA-CYCLE`），整批拒绝，返回 422，文件保持不变。
  - 运行时错误（`MANTRA-EVALUATION`）不阻止写入，与现在返回部分结果的行为一致。
- **预演**：`POST …/preview` 执行同样的步骤但不写入，返回结果和差异，用于公式编辑预览和假设分析。

### 7.2 最小改动回写

- **只改受影响的形式。** 注释、空白、其他条目的顺序和写法逐字节保持不变。
- **新条目的位置。** 追加到对应映射的末尾，沿用前一条目的缩进。对应形式不存在时新建（例如 `(inputs {…})`），放在第一个 `(defn …)` 之前或文档末尾。
- **字面量写法。** 写出的字面量必须能被 Normein 读取器原样读回：
  - 小数用普通十进制写法，并保留用户输入的标度；
  - 关键字写作 `:x`；
  - 字符串按读取器规则转义。
- **往返不变式**（用性质测试检查）：
  - `read(write(doc, ops))` 在语义上等于 `apply(read(doc), ops)`；
  - 编辑区间以外的文本逐字节相同。
- **依赖**：需要每个子形式的起止偏移。候选内核的 `readForms` 和 `DslSourcePosition(startOffset, endOffset)` 满足这一要求（WP1）。

### 7.3 并发、撤销与响应

- **并发**：每个案例同一时间只允许一个写入；读取使用不可变快照，可以并发。
- **外部修改**：服务监视工作区文件。文本编辑器改动文件后，服务重新加载，并通过 SSE 推送 `documentChanged`（附新修订），界面随之刷新。此后若再用旧的 `baseRevision` 写入，会得到 409。
- **撤销与重做**：服务为每个案例在内存中保存最近 50 个文档版本。撤销是一次普通写入，同样检查修订。持久的历史交给版本控制（D4）。
- **写入成功的响应**：新修订、改动的文档路径、诊断，以及前后两次计算的差异（格式同 §6.7）。

## 8. 数据接入与公式编辑

### 8.1 导入

- **格式**：CSV 和 JSON（mantra-core 的 `CsvSource`、`JsonSource`），以及按命名单元格回读的 XLSX（mantra-excel 的 `XlsxSource`）。类型转换和校验都留在引擎，所以各通道行为一致。
- **流程**（设计稿 “Datenquellen”）：
  1. 选择文件；
  2. 检测列、样本行、分隔符和小数点；
  3. 把列映射到输入、成员或表格列，并预览诊断；
  4. 应用。
- **应用的结果写成案例中的数据来源声明**（§4.4），而不是一次性复制值。好处是：
  - 值的出处可以追溯（`origin: source:<描述>`）；
  - 手工值按现有规则优先于数据来源（`DataSources.apply` 让案例的 `(inputs …)` 最后生效），界面把这种情况标为“被手工值覆盖”。
- **映射模板**（“Zuordnung als Vorlage speichern”）就是一份可复用的来源声明，由领域应用随方案提供，或由用户保存在工作区。它不是界面配置。
- **宽表模式（缺口 G7）**：设计稿中的工资单 CSV 是宽表，一行对应一个成员，每列对应一个不同的输入。现有 `CsvSource` 只支持两种格式：表格输入的行，以及 `input;value` / `input;member;value` 成对格式。需要增加“宽表行 → 某一成员的输入”模式。

### 8.2 公式编辑

- **范围**：v1 只编辑案例中的 `(extend …)` 行和 `(bind …)` 公式，不在界面中编辑方案。
- **服务端封装 `DslAuthoringService(environment, scope)`**，提供：
  - 补全：当前 slot 可见的输入、行和参数（`formula-slot` 受 `:uses` 限制），以及 Normein 标准函数和 `mantra.calc@1`；
  - 悬停：签名、说明、当前计算中的值；
  - 诊断：带起止位置。
- **位置换算**：使用内核的 `hostPosition`。采用后删除 Mantra 目前的二次坐标重锚（WP1）。
- **预览**：通过 `…/preview`（§7.1）返回该行的值和差异，对应设计稿中的 “540,00 ▲”。
- **上限**：公式原文受内核读取器上限约束；补全最多返回 200 项。

## 9. HTTP 接口（`mantra serve`）

### 9.1 端点

基址为 `http://127.0.0.1:<port>/api/v1`，使用 UTF-8 JSON，外层见 §6。

只读服务阶段的 `/workspace` 数据为 `{cases, schemas, parameters, layouts, diagnostics}`。
`cases[]` 至少含工作区相对路径 `id`、`title`、`schema`、`period`、`revision` 和该案例的 `diagnostics`；
其余三个清单的元素含 `id`、工作区相对 `path`，并可带 `title`。工作区外层 `revision`
是扫描到的全部 `.mantra` 文件内容的摘要；每个案例的四类响应使用 §4.3 所述的参与文件摘要。
缺少方案或绑定文件的案例仍列在 `/workspace` 中并带诊断；请求其结果返回 422。
`/workspace` 的 JSON Schema 见 `schema/workspace.schema.json`。

只读阶段提供 `/workspace`、`structure`、`run`、`paper`、`diagnostics` 和 `parameters`。其余 §9.1
端点在对应工作包完成前返回 501 与 `MANTRA-WORKBENCH-UNAVAILABLE`，不会生成占位结果。
尚未实现的 `(sources …)` 绑定同样报告诊断并拒绝计算。`GET /cases/{case}/diagnostics`
直接返回 §6.6 的诊断文档。服务可通过 `--ui <dist目录>` 指定 live 前端构建产物；
省略时查找当前目录的 `workbench-ui/dist`，存在时托管静态资源及前端路由回退。
409 修订冲突检查在 WP6 的写端点接入时验证；只读阶段没有会修改文档的请求。

| 方法 | 路径 | 内容 |
| --- | --- | --- |
| GET | `/workspace` | 方案、案例、参数集、版式清单及工作区诊断 |
| GET | `/cases/{case}/structure` | Structure（§6.2） |
| GET | `/cases/{case}/run` | Run（§6.3） |
| GET | `/cases/{case}/paper?layout=&panel=` | Paper（§6.4） |
| GET | `/cases/{case}/diagnostics` | Diagnostics（§6.6） |
| GET | `/cases/{case}/explain?address=&depth=` | Explain（§6.5） |
| GET | `/cases/{case}/parameters` | 参数分层（§6.8） |
| POST | `/cases/{case}/compare` | Compare（§6.7） |
| POST | `/cases/{case}/preview` | 预演编辑，不写入（§7.1） |
| POST | `/cases/{case}/edits` | 编辑并写入（§7） |
| POST | `/cases/{case}/undo`、`/cases/{case}/redo` | 撤销、重做 |
| POST | `/cases/{case}/authoring/complete`、`/hover`、`/check` | 公式编辑（§8.2） |
| POST | `/cases/{case}/imports/inspect`、`/imports/apply` | 导入（§8.1） |
| GET | `/cases/{case}/export.xlsx`、`/export.html`、`/export.txt`（可带 `?layout=`） | 导出（§6.9） |
| GET | `/cases/{case}/export-preview`（可带 `?sheet=`、`?layout=`） | 工作簿预览与保真度报告（§6.9） |
| GET | `/events` | SSE：`documentChanged`、`revision` |

### 9.2 前端路由

前端路由与界面语言无关。设计稿 “Informationsarchitektur” 中的德语路径只是示意。

| 页面 | 路由 |
| --- | --- |
| 总览 | `/cases/{case}/overview` |
| 板块 | `/cases/{case}/panels/{panel}?cell=<地址>` |
| 来源树 | `/cases/{case}/provenance/<地址>` |
| 录入 | `/cases/{case}/inputs[/{group}]` |
| 参数 | `/cases/{case}/parameters?compare=<参数集>` |
| 数据来源 | `/cases/{case}/sources` |
| 自定义 | `/cases/{case}/extensions` |
| 校验 | `/cases/{case}/diagnostics` |
| 导出 | `/cases/{case}/export` |

### 9.3 错误

| 状态 | 情形 | 响应体 |
| --- | --- | --- |
| 400 | 请求格式错误、地址无法解析 | `{"error": {"code": "MANTRA-WORKBENCH-REQUEST", "message": "…"}}` |
| 403 | `Host` 非回环地址或写请求缺少/提交错误会话令牌 | 代码 `MANTRA-WORKBENCH-HOST` 或 `MANTRA-WORKBENCH-TOKEN` |
| 404 | 案例、板块或节点不存在 | 同上，代码 `MANTRA-WORKBENCH-NOT-FOUND` |
| 405 | HTTP 方法不适用 | 代码 `MANTRA-WORKBENCH-REQUEST` |
| 409 | `baseRevision` 与当前修订不一致 | 代码 `MANTRA-WORKBENCH-CONFLICT`，附当前修订 |
| 413 | 请求或上传超过上限 | 代码 `MANTRA-WORKBENCH-TOO-LARGE` |
| 422 | 编辑被引擎拒绝 | 附 §6.6 格式的诊断 |
| 422 | 工作区文档缺失、绑定尚不可用或无效 | 代码 `MANTRA-WORKBENCH-DOCUMENT`，附诊断 |
| 501 | 端点所属工作包尚未接入 | 代码 `MANTRA-WORKBENCH-UNAVAILABLE` |
| 500 | 内部错误 | 附关联 id，不向客户端返回堆栈 |

`MANTRA-WORKBENCH-*` 是拟新增的代码。

### 9.4 资源上限

| 项 | v1 默认 |
| --- | --- |
| 请求体 | 1 MiB |
| 导入文件 | 10 MiB |
| 工作区扫描 | 最多 4,096 个 `.mantra` 文件（只读阶段） |
| 单个文档 | 内核读取器上限：固定版本为 65,536 字符；候选内核的 `readForms` 为 1,048,576 字符 |
| 求值 | 内核预算（每个计数器 100,000）与值上限（集合 10,000 项、嵌套深度 32） |
| Explain | 内核 trace 渲染预算；深度 ≤ 5 |
| Compare | 每次一个变体 |
| Compare 参数集列表 | 最多 128 项 |
| 撤销历史 | 每个案例 50 个版本 |

### 9.5 安全

- **监听地址**：只监听 `127.0.0.1` 和 `::1`。`Host` 头不是回环地址的请求一律拒绝，以防 DNS 重绑定。不开启 CORS。
- **防跨站写入**：写操作必须在请求头中携带会话令牌。令牌在服务启动时生成，随 `index.html` 下发，使其他网页无法冒充界面写入。
- **令牌传递**：`index.html` 内注入 `<meta name="mantra-session-token" content="…">`；未来写接口使用 `X-Mantra-Token` 请求头。只读阶段也先对全部 POST 请求验证令牌。
- **文件访问**：限定在工作区内。先规范化路径再检查前缀，拒绝符号链接越界。上传的文件只在“应用”时写入工作区的 `imports/` 目录。
- **网络**：服务本身不发起任何网络请求。

## 10. 缺口与需补的呈现提示

| # | 缺口 | 位置 | 工作包 |
| --- | --- | --- | --- |
| G1 | 只读结果视图 | mantra-core | WP2 |
| G2 | 按板块取表（版式未声明时用默认表）与 Paper JSON | mantra-render | WP3 |
| G3 | 输入值来自哪个数据来源 | mantra-core `DataSources` | WP11 |
| G4 | 输入诊断指向案例中的值，并带地址 | mantra-core | WP3 |
| G5 | 参数各层的值 | mantra-core | WP5 |
| G6 | 工作簿结构描述（用于预览） | mantra-excel | WP12 |
| G7 | CSV 宽表模式 | mantra-core `CsvSource` | WP11 |
| G8 | 子表达式中间值、分支与精确位置 | 采用新内核 | WP1、WP4 |

下面三项呈现提示须满足：
- 与领域无关，只描述“是什么”，不描述控件、颜色或位置；
- 对纸面、XLSX 和 Web 同样成立；
- 不改变数值；
- 按 CLAUDE.md，每项需要 mantra-core 或 mantra-render 测试，并有两个领域用例。

| 提示 | 写在 | 未声明时 | 用例 |
| --- | --- | --- | --- |
| 按符号取标签 `:sign-labels {:positive "…" :negative "…" :zero "…"}` | 计算项属性 | 显示项标签和带符号的值 | ESt 的 `abrechnungsergebnis`（Nachzahlung / Erstattung）；SAP CO 的标准成本差异（ungünstig / günstig） |
| 标题结果 `:headline <节点>` | 方案元数据 | 主线最后一步的结果 | 顶栏、总览结果框、案例列表 |
| 输入分组 `:group <键>` 与分组标题 | 输入属性 | 按板块归属；通用输入按声明顺序 | 录入页的分组导航 |

WP13 的具体声明与解析规则：方案元数据用 `:headline <节点符号>` 指向一个已声明的计算节点；省略时取主线最后一节的结果节点。方案元数据用 `:group-titles {:键 "标题"}` 声明输入组标题，输入属性用 `:group :键` 引用；未声明标题时显示键名。未指定组的输入继续按板块归属，通用输入保持声明顺序。`:sign-labels` 的三个键均需是字符串；按未取绝对值的计算结果的正、负、零选择标签。维度节点的整行标签按横向合计选择，成员值仍保持原数值和符号；缺少对应符号的标签时回退原标签。Excel 的标签公式引用结果单元格，使重新计算后标签同步更新。

## 11. 决策与待决问题

| # | 问题 | 决定或建议 |
| --- | --- | --- |
| D1 · 已定 | 案例使用的参数集、版式和数据来源写在哪里 | 写进案例文本，语法与优先级见 §4.4；由 WP6 和 WP11 实现 |
| D2 | 界面语言与诊断消息的多语言 | 界面文字用 de/en 资源包；诊断按代码本地化，消息原文作为细节 |
| D3 · 已定 | 前端技术栈 | React + TypeScript + Vite；公式编辑用 CodeMirror 6。前端类型由 JSON Schema 生成；Vite 构建为静态资源，由 `mantra-server` 提供 |
| D4 · 已定 | 版本控制 | 将当前工程建立为 Git 基线（WP0），后续工作包使用独立分支或工作树 |
| D5 | 方案的语义版本与旧案例迁移如何在界面中呈现 | 依赖边界文档“下一步”第 6 项 |

## 12. 验收

- **契约测试**（mantra-workbench）：
  - 三个验收方案各有 Structure、Run、Paper 和 Diagnostics 的 golden 文件；
  - 另有选定地址的 Explain，以及 ESt 2025 对 `params-2026` 的 Compare；
  - golden 文件和服务端测试中的每个响应都通过 JSON Schema 校验。
- **数值来源**：golden 记录的是引擎输出，验收断言仍须来自独立来源（CLAUDE.md）。例如 ESt 用 `verify_expected.py` 独立复算（需扩展到 2026 参数），IAS 36 用 IE8 公布的数字。
- **回写**：§7.2 的往返性质测试。
- **服务**：状态码、修订冲突、路径越界、`Host` 检查、令牌、资源上限。
- **前端**：
  - 组件测试只使用 golden 文件。
  - 端到端测试在 `mantra serve` 上对三个方案跑同一流程：
    1. 打开总览；
    2. 从罗盘进入板块；
    3. 选中单元格查看 Rechenweg；
    4. 修改输入并查看影响；
    5. 提交非法值并查看诊断；
    6. 下载 XLSX。
  - 流程由数据驱动，不含方案专用代码。
- **通用性检查**：由三个方案生成标识符清单，CI 检查前端源码中不出现其中任何一个。

## 变更记录

| 日期 | 版本 | 内容 |
| --- | --- | --- |
| 2026-09-27 | v1 草案 | 初稿 |
| 2026-09-27 | D1、D3、D4 | 案例绑定写入案例文本；前端采用 React、TypeScript、Vite 和 CodeMirror 6；建立 Git 基线 |
| 2026-09-27 | WP3 | 规定可静态分发的 fixture 清单格式（§6.6.1），四类 fixture 仍使用统一响应外层 |
| 2026-09-27 | WP7 只读阶段 | 细化 `/workspace`、诊断端点、未接入端点状态与静态前端/令牌约定 |
| 2026-09-27 | 验收案例绑定 | 四个验收案例显式绑定版式；fixture 只在案例声明时加载同目录版式，与 live 服务一致 |
| 2026-09-27 | WP5 | 明确 Compare 的同方案、坐标、分组、十进制差值和 CLI 外层；明确参数分层顺序及顶层数组 |
| 2026-09-27 | WP5 修正 | CLI diff 修订哈希改用稳定逻辑角色与有序参数索引，不包含检出目录绝对路径 |
| 2026-09-27 | WP7 接入 WP5 | GET 参数分层接入工作区绑定，POST Compare 保留 501 待请求解析 |
| 2026-09-27 | WP7 Compare | POST Compare 接入工作区解析与请求校验，支持有序参数集、另一案例及只读修订 |
