# Mantra DSL 参考（v0.1）

所有 Mantra 文档都使用 Normein DSL 的读取语法（Clojure 形式：列表、向量、映射、关键字、字符串、数字、`;` 注释）。公式部分就是 Normein 表达式，函数与语义见 Normein 文档 `dsl-clojure-compatibility.md`；本文只描述 Mantra 的宿主形式。

文档类型由根形式决定：

| 根形式 | 用途 | 通常文件名 |
| --- | --- | --- |
| `(schema <id> {meta} …)` | 计算方案（领域应用/模板提供） | `schema.mantra` |
| `(fragment …)` | 被 `include` 的方案片段 | `*.mantra` |
| `(case <id> {meta} …)` | 案例数据（用户提供） | `case-*.mantra` |
| `(layout <id> {opts} …)` | 表格版式（用户/应用提供） | `layout*.mantra` |

---

## 1. 方案（schema）

```clojure
(schema de.est/2025
  {:title "Einkommensteuer 2025" :subtitle "…" :version "2025.1" :period "VZ 2025" :preset :de-staffel-4}
  <声明>*)
```

### 1.1 声明

| 形式 | 说明 |
| --- | --- |
| `(include "片段.mantra")` | 在当前位置插入片段的声明与项（仅顶层） |
| `(param id <字面量> {opts})` | 模板参数；案例可用 `(params {...})` 覆盖。数字、关键字、字符串、布尔、向量、映射均可 |
| `(input id :type {opts})` | 不在表中显示的输入。类型：`:decimal`（默认）`:integer` `:boolean` `:keyword` `:text` `:date` `:table` |
| `(dimension id {opts})` | 维度：`:members [{:key :A :label "…" :when <表达式>} …]` 或 `:from <表格输入> :key :列 :title :列`；表格维度可用 `:parent <维度> :parent-key :列` 声明成员关系，由引擎检查父成员是否存在；`:total-label` |
| `(defn name [^Type arg …] body)` | 辅助函数（Normein 命名定义）。参与算术的参数需要类型注解，如 `^Decimal` `^Integer` `^Boolean` |

`input` 选项：`:label` `:per dim|[dims]` `:default` `:optional true` `:options {:kw "说明"}|[:kw …]` `:columns {:列 :类型 …}`（表格；类型后加 `?` 表示可空，如 `:decimal?`）。表格还可用 `:references {:列 目标维度}` 声明外键；引擎要求每个非空值指向目标维度的活动成员。例如费用流水可写 `:references {:order-id order}`，case 只填写 `:order-id :O100`。

### 1.2 计算项

| 形式 | 说明 |
| --- | --- |
| `(section id "标题" {opts} 项*)` | 分组。含 `total` 时为不透明节（Staffel），结果为最后一个 total |
| `(field id "标题" {opts})` | 就地显示的输入（同时声明一个 input；默认继承所在节的维度） |
| `(line id "标题" <公式> {opts})` | 计算行 |
| `(formula-slot id "标题" <默认公式> {opts})` | 应用开放的公式扩展点；类型、维度、舍入与表格位置由方案固定，可用 `:uses [输入或行名 …]` 限制用户公式可引用的根；案例用 `(bind id <公式>)` 替换公式 |
| `(total id "标题" {opts})` | 运行合计检查点：上一个 total + 其后的带符号贡献项 |
| `(choice id "标题" {:rule :min|:max} (option :key "标题" <公式> {:when …})+)` | 择优（Günstigerprüfung、higher-of） |
| `(slot id "标题" {opts})` | 用户扩展点，由案例 `(extend id …)` 填充 |
| `(note "文字")` | 说明行 |

通用选项：

| 选项 | 适用 | 含义 |
| --- | --- | --- |
| `:op :plus|:minus|:info` | line field choice section | 对所在 Staffel 的贡献（默认 `:plus`） |
| `:per dim` / `:per [d1 d2]` / `:per []` | 全部 | 维度；显式指定时替换继承的维度 |
| `:when <表达式>` | line total choice section option | 适用条件；不成立时数值为 0、非数值为 nil，并标记为不适用 |
| `:round n` / `:round [n :floor]` | line choice | 结果舍入。模式：`:half-up` `:half-even` `:half-down` `:floor` `:ceiling` `:down` `:up` |
| `:type` | line | 结果类型，默认 `:decimal` |
| `:spread true` | line | 在去掉最后一个维度的上下文中求值一次，结果映射分发到成员（分摊） |
| `:aggregate true|false` / `:aggregate :sum|:none` | line | 是否允许对成员值求横向合计；单位成本等比率用 `false`，跨成员比率由独立公式计算 |
| `:display :inline|:schedule|:hidden` | section | 默认表现：内联、单独成表（附表）、隐藏 |
| `:layout :tiered|:matrix` | section | 默认表格风格 |
| `:reference` `:note` `:source` | 全部 | 规范出处、注释、来源；核心固定名称统一为英文 |
| `:format :amount|:percent|:number|:integer` `:precision n` | 全部 | 数字显示 |
| `:hidden true` | 全部 | 计算但不显示 |
| `:class :name` / `:class [:name :other]` | 计算项、节、说明 | 可复用的样式标签；只进入版式结果，不影响计算 |
| `:sign-labels {:positive "…" :negative "…" :zero "…"}` | 计算项 | 按未取绝对值的结果选择标签；未给出的分支沿用原标签，不改变数值 |
| `:group :key` | input / field | 录入分组；标题在方案元数据的 `:group-titles {:key "标题"}` 声明，缺省时显示键名 |
| `:headline node-id` | schema 元数据 | 主要结果节点；未给出时采用主线最后一节的结果 |
| 其他关键字 | 全部 | 作为应用自定义属性传给表现层，例如德国税务应用的 `:kz`、`:zeile` |

### 1.3 公式中的名字

| 写法 | 含义 |
| --- | --- |
| `bruttoarbeitslohn` | 另一行/输入/参数。与当前上下文同维度时为当前成员的标量，维度更多时为成员映射 `{:A … :B …}` |
| `all.weighted-amount` | 某个维度化行的完整成员映射 |
| `relation_order` | 对声明了 `:parent` 的 `order` 维度，引擎生成的子成员→父成员映射；可传给 `dim/rollup` |
| `cgu`、`person` | 当前成员记录：`cgu.carrying-amount`、`person.label`、`person.key`、`person.index` |
| `(my-fn …)` | `defn` 定义的辅助函数 |

### 1.4 内核函数（`mantra.calc@1`）

| 函数 | 说明 |
| --- | --- |
| `(alloc/pro-rata amount weights scale)` | 按权重映射分摊并舍入到 scale，最大余数法保证合计精确 |
| `(alloc/capped amount weights caps scale)` | 带上限的分摊（注水法），超出部分分给未封顶成员 |
| `(calc/stepwise amount [[上限 税率] … [nil 税率]])` | 分段计算，每段只对本段金额适用本段税率 |
| `(dim/sum m)` `(dim/min m)` `(dim/max m)` | 成员映射的合计/最小/最大 |
| `(dim/rollup values relation target)` | 按已声明的子→父成员关系，把 `values` 中属于 `target` 的来源成员值求和；例如 `(dim/rollup all.actual-order-cost relation_order product.key)` |
| `(fin/df rate t scale)` | 折现因子 1/(1+rate)^t |
| `(fin/npv rate [cf1 cf2 …] scale)` | 期末现金流现值 |

另可使用全部 Normein 标准函数（`min` `max` `if` `cond` `let` `decimal/round` `decimal/floor` `decimal/divide` …）。注意：`/` 遇到无限小数会报错，需要舍入时使用 `decimal/divide` 或 `:round`。

---

## 2. 案例（case）

```clojure
(case mustermann-2025
  {:schema "de.est/2025" :title "…" :subject "…" :period "VZ 2025" :prepared-by "…" :reviewed-by "…" :date "2026-03-15" :reference "…"}
  (inputs {:veranlagungsart :zusammen
           :bruttoarbeitslohn {:A 68500 :B 31200}      ; 维度化输入：按成员键给值
           :vermietungsobjekte [{:id :leipzig :mieten 9600 …}]})
  (params {:kirchensteuersatz 0.08})                   ; 覆盖模板参数
  (extend weitere-sonderausgaben                       ; 填充 slot（渲染时 ▲ 标记）
    (line schulgeld "Schulgeld (30 %, max. 5.000 €)" (min 5000 (* 0.3 1800))))
  (defn …))                                            ; 用户辅助函数
```

案例声明 `:schema` 时，引擎要求它与加载的方案 ID 一致；正式应用案例应始终声明该字段。省略时可用于临时计算。案例只能提供方案已声明的输入和参数，`extend` 只能指向方案开放的 `slot`，`bind` 只能指向方案声明的 `formula-slot`。绑定公式在原行的维度上下文中编译，必须满足原行的结果类型及应用声明的 `:uses` 引用范围；循环依赖会被拒绝。它只替换公式，不改变行的运算符、舍入规则或版式。

例如，IAS 36 应用开放 `weighting` 后，用户可在案例中写 `(bind weighting (* remaining-life remaining-life))`。完整示例见[自定义加权案例](../examples/ifrs-ias36-corporate-assets/case-custom-weight.mantra)。

---

## 3. 版式（layout）

```clojure
(layout de.est/steuerberechnung
  {:preset :de-staffel-4 :title "…" :subtitle "…" :locale "de-DE" :language :de
   :precision 2 :percent-precision 2 :negative :minus|:parentheses :zero "–" :grouping true
   :hide-zero true :show-inactive false :expand-members false :signed false
   :row-numbers :global|:table
   :explain :appendix|:none :header [:subject :period :schema :prepared-by :reviewed-by :date :reference]}
  (operators {:plus "+" :minus "./." :total "=" :info ""})
  (columns :tiered :operator :label (col :pre {:header "Detail"}) :main)
  (columns :matrix :label (members person) :cross-total :status)
  (style {:all true} {:weight :normal|:bold :tone :default|:muted|:accent :fill :none|:subtle|:accent})
  (style {:class :variance} {:weight :bold :tone :accent})
  (style {:section component-costs :depth 1} {:weight :bold})
  (style {:height 0 :kind :value} {:tone :muted})
  (style {:nth-child :even :kind :value} {:fill :subtle})
  (style {:has-row-number true :column :row-number} {:tone :muted})
  (table <section-id> {:title "…" :style :tiered|:matrix :expand-members true} (col …)*)
  (schedule <section-id> …) (inline <section-id> …) (hide <item-id> …))
```

* 未声明 `table` 时：方案根节一张表，外加每个 `:display :schedule` 的节各一张表。
* 声明了 `table` 时：只渲染这些表；在父表中，被单独成表的节显示为引用行“(→ Tabelle n)”，直接引用其他表中结果的行也会注明来源表。
* `:hide-zero`：隐藏全零行与全零节；如果某行把非零输入变成了零（Freigrenze、免征额），该行保留作为解释。
* `:signed true`：扣减项以负数显示（IFRS 风格），适合没有运算符列的版式。
* `:row-numbers :global|:table`：自动加入行号列；`:global` 跨表接续，`:table` 每张表从 1 开始。省略时沿用预设或列配置，已有行号列按每表从 1 开始。行号不需要再写 `(col … {:content :row-number})`。
* `columns` 中内置列直接写关键字（如 `:label`、`:status`、`:cross-total`）；只有需要更改表头、宽度、对齐或使用不同列名时才写 `(col :name {选项})`。列名和内容相同可省略 `:content`。维度成员列仍写 `(members dim)` 或 `(member dim :key)`。
* 命名约定：同一内置概念只用一个名称（例如 `:row-number`、`:operator`、`:cross-total`），预设已给出的值不必重复声明。layout 顶层选项表达整份底稿的规则；`columns` 只表达列顺序与必要的列覆盖；`style` 只表达外观。
* `style` 只有一种语法：`(style {选择器} {样式声明})`，两个参数都必须是 map。`{:all true}` 是默认规则，单独使用；`{:class :variance}` 匹配 schema 项的类标签。未匹配到任何规则的类标签仍保留，但不改变外观。
* 选择器还可以组合 `:section`（匹配所在 section 路径中的任一级）、`:depth`（从本表根节向下的距离）、`:height`（到最远可见叶子的距离）、`:indent`（显示缩进）、`:nth-child :odd|:even`、`:kind`（如 `:value`、`:heading`、`:total`）、`:has-row-number true|false`、`:column`（列 ID 或内容角色，如 `:row-number`、`:member`）、`:class`。`depth` 和 `height` 采用 [D3 hierarchy](https://d3js.org/d3-hierarchy/hierarchy) 的方向与起点：根的 depth 为 0，叶子的 height 为 0。根节作为表格容器不单独显示一行；隐藏项不计入当前表的 height，独立附表在父表中是引用叶子。`:indent` 只控制视觉缩进，不改变层级关系。
* 例：若 `(table costs)` 是表根，`costs` 的 depth 为 0；直属 `material` 行与 `overhead` 节标题的 depth 为 1；`overhead` 下的 `power` 行与 `maintenance` 节标题的 depth 为 2；`maintenance` 内的 `repair` 行的 depth 为 3。`material`、`power`、`repair` 的 height 为 0，`maintenance` 的 height 为 1，`overhead` 的 height 为 2，`costs` 的 height 为 3。`{:section overhead}` 匹配该节及其子树。
* `:nth-child` 与 CSS 的 `tbody > tr:nth-child(odd|even)` 一样，按每张表的可见表体行从 1 计数，节标题和说明行也占位置；组合 `:kind :value` 只限制哪些行上色，不改变奇偶计数。
* 样式优先级固定为：输出端内置样式 → layout 中匹配规则的声明顺序；后匹配的规则只覆盖它显式设定的属性。`:has-row-number` 匹配最终表格是否有行号列；`:column :row-number` 只匹配行号单元格。
* `style` 目前只接收 `:weight`、`:tone`、`:fill` 三类有限属性；HTML 与 XLSX 映射同一组含义，文本输出忽略视觉属性。它不能改变行值、舍入、聚合或依赖。

### 3.1 列内容函数

| 内容 | 说明 |
| --- | --- |
| `:label` | 标题（按层级缩进） |
| `:operator` | + ./. = 等 |
| `:row-number` | 行号列的内置内容；通常由 `:row-numbers` 自动加入（HTML 中链接到审计轨迹） |
| `:reference` `:note` `:source` | 核心定义的项元数据 |
| `(attribute :name)` | 应用自定义元数据列，例如 `(attribute :kz {:header "Kz."})`；列名与领域术语由应用决定 |
| `:status` | 标记：Σ 已汇总、✓ 被选项、▲ 用户自定义、– 不适用 |
| `:value` | 行值（维度化行为横向合计） |
| `:pre` / `:main` | Vorspalte / Hauptspalte |
| `:cross-total` | 成员横向合计（矩阵表） |
| `(members dim)` / `(member dim :key)` | 每个成员一列 / 指定成员列 |
| `:formula` / `:explain` | 公式原文 / 代入数值后的算式 |

### 3.2 预设

| 预设 | 用途 |
| --- | --- |
| `:de-staffel-4` | 德国四栏计算表：Zeile · Bezeichnung · Vorspalte · Hauptspalte（+ Rechtsgrundlage）；矩阵：Person A · Person B · Gesamt |
| `:de-staffel-3` | 三栏：Bezeichnung · Vorspalte · Hauptspalte |
| `:ifrs-schedule` | IFRS 附表：成员列 + Total，括号负数，0 位小数，扣减项带符号 |

---

## 4. 诊断代码（节选）

| 代码 | 含义 |
| --- | --- |
| `MANTRA-READ-*` | 读取/字面量/选项错误 |
| `MANTRA-SCHEMA-*`、`MANTRA-LINE-*`、`MANTRA-CHOICE-*` | 方案形式错误 |
| `MANTRA-ID-RESERVED` / `MANTRA-ID-DUPLICATE` | 标识符与 Normein 函数名冲突 / 重复 |
| `MANTRA-FORMULA` | Normein 编译错误（已换算到文档中的行列） |
| `MANTRA-CYCLE` | 循环依赖（附完整路径） |
| `MANTRA-TOTAL-DIMS` | 合计的组成项维度不足 |
| `MANTRA-CASE-*`、`MANTRA-INPUT-*` | 案例数据错误（未知输入、类型不符、缺失） |
| `MANTRA-EVALUATION` | 运行时错误（含成员坐标）；其余计算继续进行 |
| `MANTRA-LAYOUT-*` | 版式错误 |
