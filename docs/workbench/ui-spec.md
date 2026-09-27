# 工作台界面规格 v1（草案）

> 状态：草案 · 2026-09-27
> 依据：设计画布（12 张画板）。源文件在 [design/](design/)；在线画布为 https://claude.ai/artifact/STdcAnLZ5HeeHBVgME9rP4 ，默认只有所有者可见。
> 数据来源与行为以[工作台契约](contract.md)为准；本文与契约冲突时，以契约为准，并在 §8 记录差异。

## 1. 如何使用本文

- **画板的作用**：画板是布局、视觉和交互的参照，不是像素级规范。
- **画板中的数据**：数字和文字来自验收样例，部分为示意（§7）。实现中不得写死这些数据；测试数据一律用契约的 golden 文件。
- **实现方式**：按屏幕（§4）和组件（§5）实现。每个组件只读取表中列出的契约字段，不按方案 id 或节点 id 分支（契约 W2）。

## 2. 设计语言：“Arbeitspapier”

整体风格像一份审计底稿：纸色底、浅色纸张卡片、墨色正文。颜色表达结构：

| 含义 | 表现 |
| --- | --- |
| 主线；可编辑的输入 | 墨蓝 |
| 汇入主线的旁支 | 赭橙 |
| 不汇入主线的辅助计算 | 灰色虚线 |
| 错误 | 深红加图标 |

审计底稿的惯例同样适用：输入用蓝字，计算结果用黑字。状态符号与纸面的 `:status` 列一致：✓ 被选项、▲ 用户自定义、Σ 已汇总、– 不适用。

### 2.1 颜色令牌

以 CSS 自定义属性实现，组件只使用令牌。

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `--paper` | `#F4F1EA` | 页面底色 |
| `--sheet` | `#FFFDF8` | 卡片、表格等纸张面 |
| `--field` | `#FFFFFF` | 输入框底色；蓝底按钮上的文字 |
| `--bar` | `#FBF9F4` | 顶栏、悬停行 |
| `--rail` | `#EFEBE2` | 左侧导航、标签底色、弱填充 |
| `--ink` | `#1C1B18` | 正文、计算结果 |
| `--ink-2` | `#4F4A40` | 次要文字 |
| `--ink-3` | `#6E685C` | 标签、说明 |
| `--line` | `#DDD5C6` | 边框 |
| `--line-soft` | `#EDE7DB` | 表格行分隔 |
| `--line-aux` | `#C9C0AE` | 辅助计算的虚线框 |
| `--blue` | `#1E4A8A` | 主线、输入、链接、主按钮 |
| `--blue-strong` | `#15345F` | 悬停；蓝色浅底上的文字 |
| `--blue-tint` | `#E4EBF6` | 选中、当前位置 |
| `--blue-field` | `#EEF3FA` | 输入单元格底色 |
| `--blue-line` | `#BFD0E8` | 蓝色浅底的边框 |
| `--siena` | `#B24E17` | 旁支连线、旁支标记 |
| `--siena-strong` | `#8F3E12` | 橙色浅底上的文字 |
| `--siena-tint` | `#F7E7DB` | 旁支强调底色 |
| `--siena-field` | `#FBF1E9` | 旁支浅底 |
| `--siena-line` | `#E3C9B5` | 橙色浅底的边框 |
| `--aux` | `#7A7466` | 辅助计算的文字与图标 |
| `--error` | `#9E1B12` | 错误文字与图标 |
| `--error-tint` | `#F6E1DE` | 错误底色 |

v1 只有浅色主题。所有颜色都经过令牌，以后加深色主题时不需要改组件。

### 2.2 字体

| 字体 | 用途 |
| --- | --- |
| Newsreader | 页面标题、卡片标题、结果大数字 |
| IBM Plex Sans | 界面文字、表格 |
| IBM Plex Mono | 公式、代码摘录、标识符、路径、诊断代码 |

- 数字一律使用 `font-variant-numeric: tabular-nums lining-nums`，保证列内对齐。
- 字号层级（px，字号/行高）：

| 层级 | 字号 | 字体 |
| --- | --- | --- |
| 分组标签 | 11，大写，字距 0.08em | Plex Sans |
| 次要文字 | 12 | Plex Sans |
| 正文与表格 | 13/17–18 | Plex Sans |
| 强调 | 14 | Plex Sans |
| 卡片标题 | 19/24、22/28 | 衬线 |
| 页面标题 | 30/36、32/38 | 衬线 |
| 信息架构页标题 | 40/46 | 衬线 |

### 2.3 形状与间距

- **圆角**：输入框和标签 6 px；卡片和表格 8–10 px；大容器 12–14 px。
- **间距**：基于 4 px 网格，常用 8、12、16、22、32。
- **外壳尺寸**：顶栏高 60 px；左侧导航宽 272 px；主区内边距 28 / 32 / 32 px。画板宽 1440 px。

## 3. 外壳

所有页面共用同一外壳，源自画板 `Main.dc.html`。

**顶栏**
- 案例切换：标题 · 期间 · 案例标识。
- 方案标签：方案 id + 版本；参数集标签。
- 状态：已保存的修订、诊断数量。
- 标题结果（`headline`，按符号取标签，见契约 §10）。
- 导出按钮。

**左侧导航（Fallnavigation）**
- 主线：步骤编号、标题、结果值，竖向相连。
- 旁支：挂在它汇入的第一个主线步骤下（取 `entries` 中步骤最小的一项）。汇入多个步骤时注明“→ 1 · 2 · 3”。
- 辅助计算：单列，用虚线框。
- 横向功能区：Stammdaten & Eingaben、Parameter、Datenquellen、Anpassungen、Prüfungen、Export。

**外壳所用数据**

| 数据 | 契约字段 |
| --- | --- |
| 主线与板块 | `structure.mainline`、`structure.panels[].role`、`entries` |
| 结果值 | `run.values[<result>].display` |
| 标题结果 | `structure.headline` + `run` |
| 诊断数量 | `run.diagnostics` |

## 4. 屏幕

### 4.1 总览 · `Main.dc.html` · `/cases/{case}/overview`

- **组成**：
  - 主线图：主线站点、从旁支卡片汇入站点的橙色曲线、虚线的辅助卡片；
  - 结果框（标题结果）；
  - 三张卡片：Stammdaten 摘要、Stand des Falls（诊断、修订、参数集）、Vergleich（与所选参数集的差异，数据来自 Compare）。
- **交互**：点击站点或卡片进入对应板块；点击比较卡进入参数页。
- **验收**：
  - 三个方案都能正确绘制主线图：ESt 4 步，IAS 36 5 步，SAP CO 按其方案而定。
  - 步骤多于 6 个时换行，不出现横向溢出。

### 4.2 板块 · `Panel.dc.html` · `/cases/{case}/panels/{panel}?cell=`

- **组成**：
  - 主线罗盘：列出全部主线站点，突出本板块 `entries` 中的步骤，并显示芯片“Fließt ein in Schritt n · <viaLabel>”；
  - 面包屑（`breadcrumb`）；
  - Staffel 表或矩阵表（Paper）；
  - 右侧 Rechenweg 检查器；
  - 底部“Wird verwendet in”（`exports`）。
- **表格**：
  - 行来自 Paper。
  - 输入单元格（`editable`）用蓝底蓝字，可以编辑。
  - 导入值只读，带到来源板块的链接。
  - 小计和合计行画分隔线。
  - `EXPLAINS_ZERO` 行照常显示，并注明原因。
  - 提供“显示零值行”切换，默认值取自版式的 `:hide-zero`。
- **检查器**：显示当前单元格的 Explain，包括公式、代入值、分支说明、输入及其来源，并链接到来源树。
- **验收**：
  - 打开 `kinder-pruefung` 时，罗盘同时突出 1、2、3 三步。
  - 选中单元格时，URL 中的 `cell` 同步更新。
  - 表格可以只用键盘操作。

### 4.3 来源树 · `Pruefpfad.dc.html` · `/cases/{case}/provenance/<地址>`

- **组成**：
  - 来源树：根是给定地址；子节点是 Explain 的 `references`，逐层按需加载；叶子是输入或参数，并标明来源（案例、默认、数据来源文件、参数集）。
  - 中间值面板：显示 Explain 的 `steps`。
- **验收**：
  - 从 `abrechnungsergebnis` 能一路追到输入。
  - 达到深度上限时给出提示，并提供继续展开的入口。

### 4.4 择优视图 · `Guenstiger.dc.html`

择优视图不是单独的路由。当板块包含 `choice` 时，板块页顶部显示它。

- **组成**：
  - 各选项的数值与条形图，被选项打 ✓；
  - 差额（“Vorteil 142,00”），取自 Explain 的 `options[].difference`，前端不自行相减；
  - 影响列表：每个 `entries` 项的步骤、承接行及其值；
  - 计算基础：Explain 的 `references`。
- **验收**：罗盘上高亮板块汇入的全部步骤。

### 4.5 录入 · `Eingaben.dc.html` · `/cases/{case}/inputs[/{group}]`

- **组成**：
  - 分组导航：先是通用输入（`generalInputs`），再按板块列出该板块的 `fields`；有 `:group` 提示时按提示分组（契约 §10）。
  - 维度化输入按成员分列（如 Person A / Person B）。
  - 每个字段显示标签、应用属性（如 Kz）、`:help`、`:unit`、来源标签（`origin`），以及地址匹配的诊断。
  - 表格输入编辑器（如 Vermietete Objekte）：增、删、移动行。
  - “Wirkung der Eingabe”：从字段所在板块到主线的路径（`entries`），保存后附编辑响应中的差异。
- **控件按类型选择**：

| 类型 | 控件 |
| --- | --- |
| 小数、整数 | 文本框，原文交给服务端解析 |
| 布尔 | 开关 |
| 带 `:options` 的关键字 | 单选或下拉 |
| 日期 | 文本框，由服务端解析 `dd.MM.yyyy` |
| 文本 | 文本框 |
| 表格 | 网格编辑器 |

- **验收**：
  - 提交非法值时，错误显示在字段下方，用户的原文保留，文件不变。
  - 保存后显示影响列表。

### 4.6 参数 · `Parameter.dc.html` · `/cases/{case}/parameters?compare=`

- **组成**：
  - 三层参数表：方案默认、参数集、案例覆盖；高亮生效值及其所在层。
  - 与另一参数集比较：差异表，加对本案影响卡（Compare 的 `mainline`）。
- **编辑**：只编辑案例覆盖层，对应 `setParam` 和 `resetParam`。

### 4.7 数据来源 · `Datenquellen.dc.html` · `/cases/{case}/sources`

- **组成**：
  - 四步流程：选择文件、检测、映射、检查与应用。
  - 映射表列出源列、样本值、目标输入（含 Kz）和状态（已映射、不导入、无对应输入）。
  - “另存为模板”。
  - 其他来源：XLSX 回读、JSON、CSV。
- **前提**：案例绑定按契约 §4.4 实现，宽表模式（G7）完成。

### 4.8 导出 · `Export.dc.html` · `/cases/{case}/export`

- **组成**：
  - 工作表列表，顺序与主线一致；
  - 所选工作表的预览（需要缺口 G6）；
  - 公式保真度：公式单元格数、只写值的回退数、命名区域数，都取自导出报告；
  - 选项（版式等）和下载。

### 4.9 校验 · `Pruefungen.dc.html` · `/cases/{case}/diagnostics`

- **组成**：
  - 诊断列表，可按严重程度过滤。
  - 详情：代码、消息、带行号的文档摘录，用插入符标出起止位置（`location`），以及相关位置。
  - 跳转：到对应字段或板块（`address`）。

### 4.10 自定义 · `Anpassungen.dc.html` · `/cases/{case}/extensions`

- **组成**：
  - 方案开放的 slot 与 formula-slot 列表。
  - 行编辑器：标题和公式（CodeMirror），带补全弹窗、诊断和预览值（▲ 标记）。
  - 公式槽卡片：默认公式、当前绑定、`:uses` 允许的引用范围。

### 4.11 IAS 36 · `IAS36.dc.html`

用于检验通用性：同一外壳，英文界面。

- 5 步罗盘；
- 矩阵表：CGU 成员列加 Total；
- 分摊条：长度按引擎值缩放，显示的数字全部来自引擎；
- 公式槽卡片（`weighting`）。

### 4.12 信息架构 · `Navigation.dc.html`

说明四项内容：导航层级（工作区 → 案例 → 总览 → 板块 → 行）、板块与 `SchemaMap` 字段的对应、直达地址、颜色语言。直达地址以契约 §9.2 为准。

## 5. 组件清单

| 组件 | 用于 | 数据 |
| --- | --- | --- |
| AppBar、CaseSwitcher、StatusBadge | 外壳 | workspace、structure、run |
| MainlineRail | 外壳 | structure.mainline、panels、entries |
| MainlineMap | 总览 | 同上 |
| Compass | 板块、择优、IAS 36 | panels[].entries |
| Breadcrumb | 板块 | panels[].breadcrumb |
| ResultBox | 总览、顶栏 | structure.headline、run |
| PanelTable | 板块、IAS 36 | paper.tables[] |
| Cell | PanelTable | 单元格对象（契约 §6.4） |
| Inspector | 板块 | explain |
| ProvenanceTree、IntermediateValues | 来源树 | explain |
| ChoiceComparison、EntriesImpact | 择优 | explain.options、entries、run |
| InputForm、InputControl、TableInputEditor | 录入 | structure.nodes、run、diagnostics |
| SourceTag | 录入、板块 | run.values[].origin |
| ImpactPath | 录入 | entries、编辑响应中的差异 |
| ParameterLayers、CompareCard | 参数、总览 | parameters、compare |
| ImportStepper、MappingTable | 数据来源 | imports/inspect |
| ExportSheetList、SheetPreview、FidelityReport | 导出 | 导出报告 |
| FindingsList、FindingDetail | 校验 | diagnostics |
| SlotList、LineEditor、FormulaSlotCard | 自定义、IAS 36 | structure.slots、formulaSlots、authoring |
| AllocationBar | IAS 36 | run（成员值与横向合计） |

`Cell` 的状态包括：计算值、输入、输入编辑中、输入有错、导入（只读）、选中、不适用、用户自定义（▲）、有原因的零。

## 6. 通用交互与状态

- **加载与出错**：加载时显示骨架屏。请求失败时显示横幅，附诊断代码。空状态要说明原因。
- **选中与地址**：选中的单元格写入 URL；刷新页面或分享链接后能回到同一位置。
- **键盘**：
  - 表格按网格模式操作：方向键移动，Enter 编辑，Esc 取消，Tab 离开表格；
  - 焦点始终可见。
- **编辑流程**：修改后进入等待状态，然后分三种结果：
  - 成功：更新值，并在影响列表中高亮发生变化的值；
  - 422：错误显示在字段下方，保留用户原文；
  - 409：提示文件已在别处修改，提供重新加载。
- **外部修改**：收到 `documentChanged` 后重新获取数据。如果此时有未提交的编辑，先询问用户。
- **零值行与不适用行**：默认显示方式取自版式（`:hide-zero`、`:show-inactive`），界面提供切换。
- **无障碍**：
  - 对比度符合 WCAG 2.2 AA；
  - 不只靠颜色传达信息：错误要有图标和文字，输入要有输入框外观；
  - 使用画板中的 aria 标签。
- **语言**：
  - 界面文字放在 de/en 资源包中；
  - 业务文字来自文档；
  - 数字的显示文本来自服务端。

## 7. 画板中的数据

- **真实数据（引擎输出）**：
  - ESt Mustermann 案例的全部数值，以及与 `params-2026` 的比较；
  - IAS 36 IE8 的分摊表；
  - 导出文件中的 F13 公式和 221 个命名区域；
  - 诊断代码（均为代码中已有的代码）。
- **示意数据**：
  - 导入文件名和其中的 “Steuerklasse” 列；
  - 录入页的 “1,5” 报错；
  - 校验页的诊断清单；
  - 自定义页的草稿公式；
  - 案例切换中的案例名称。

## 8. 与画板的已知差异（以本文为准）

1. 路由与界面语言无关；画板中的 `/faelle/…` 只是示意（契约 §9.2）。
2. 顶栏的 “Nachzahlung 1.462,24 €” 需要 `:sign-labels`（契约 §10）。实现之前，显示项标签 “Nachzahlung (+) / Erstattung (−)” 和带符号的值。
3. 择优视图中的差额由引擎在 Explain 中给出，不在前端相减。
4. 分摊条等图形的长度可以由前端按引擎值缩放，但显示的数字全部来自引擎。
5. 导出预览已通过 G6 的工作簿结构描述实现；为控制响应和界面尺寸，每张表只显示前 50 行、前 20 列，完整内容通过 XLSX 下载。fixture 模式提供逐表预览，下载仅在 live 模式可用。画板中的具体公式与统计值只随当前案例和版式的导出报告显示。
6. 数据来源页依赖契约 §4.4 的案例绑定和缺口 G7。
