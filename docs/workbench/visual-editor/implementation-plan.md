# 可视化 DSL 编辑器：复用、设计 token、接口缺口与实施计划

> 状态：**设计规范（PLANNED）**。本文对照 Mantra `682bd2c` 与 Template Engine `6443c688` 的实际代码，
> 区分已实现能力、建议的契约与原型模拟。界面与键位见 [设计规范](design-spec.md)，状态与权限见
> [状态矩阵](state-matrix.md)。新增公共 API 前先在 [工作台契约](../contract.md) 定义合同；
> 通用引擎、工作台与 UI 不加入业务规则（CLAUDE.md、契约 W2）。

后续实现范围更新：`1165dfe` 已实现 G-A4／G-A14 的文件工作区只读候选切片，入口为独立的
`/template-preview` 页，合同见 [工作台契约 §7.1](../contract.md#71-操作)，启动与边界见
[用户说明](../../site/template-draft-preview.md)。本轮不改变以下设计选择和验收；录制作者原型
`/authoring` 尚未接入该真实接口，属性 owner、语义补丁和文件 Save 仍需各自合同。

2026-10-24 补充：[Invoice Template Builder 对照](invoice-builder-gaps.md) 固定参考仓库提交，
新增 G-A16–G-A21 的功能缺口和高级设置规格。该次补充为后续规划，不表示当时已实现运行界面或新合同；
已有引擎选项、缺少作者入口和需要新增 DSL 的事项分别处理。

2026-10-25：`67149fc` 已交付 G-A16／G-A18 的根 layout 14 项设置切片，合同见 §7.1，
实现和验证见 [根版式设置实施记录](layout-settings-progress.md)。inspect／set／unset 只修改客户端源草稿，
展示显式／继承／根有效值并绑定完整源修订；Apply 后仍由真实预览检查。通用属性目录、表级／样式编辑、
源历史、正式文件 Save 和发布不在该切片内。

同日后续：[本地样式 class 实施记录](style-classes-progress.md) 推进 G-A16／G-A17／G-A18 的根布局
有限定义切片。目录来自选中的引擎 preset、本地声明和布局字面量 selector；upsert／remove 修改同一份
源草稿，区分属性省略与 normal／default／none。引用范围仅为提交的根 layout，`:use` 删除保护允许
同名 preset 回退；非简单 selector 名称保留为只读。两个属性面板的未应用字段共同阻止 Preview，
跨面板修改令另一目录过期并保留缓冲；该切片本身未提供正式源历史、schema tag 分配或完整引用分析。

统一的 [客户端内存源历史](source-history-progress.md) 进一步推进 G-A18：手写根 schema／layout、
根设置及 class Apply 共用最多 50 条精确文本事务，Undo／Redo 只在原文匹配时推进；新编辑截断 redo。
它使旧候选标过期并保留独立保存基准和示例输入；未应用字段、加载或冲突时禁用，重新捕获源时清空。
仅提供显式按钮，没有全局快捷键或持久化；G-A6／G-A7、服务端 G-A12 与 D-A1 仍未关闭。

随后 [真实草稿恢复与 JSON 备份](template-recovery-progress.md) 实现 G-A12 的客户端切片：按 origin／
workspaceId／case 隔离自动副本，保留根源、原始覆盖及未 Apply 字段。恢复只接纳完整图和根 descriptor
匹配的源数据；跨工作区文件导入须显式确认，旧基准和损坏副本可下载，不自动 rebase。恢复清空结果与
内存历史，重新 Inspect／Apply／Preview；技术无效原文和输入仍可修复。服务端 G-A12、正式文件提交
和 D-A1 继续开放，不能把浏览器已备份显示为模板已保存或发布。

本轮已增强独立真实预览页的面板、保存基准、引擎差异和空结果反馈，见
[实现与验证](template-preview-enhancements.md)。它复用 G-A4／G-A14 的现有合同，不关闭 G-A20
多案例验收、正式作者 owner／源保存或高级属性目录的缺口。

随后 `e0d2148` 实现 G-A20 的只读多案例 CLI 首批切片，见 [验收合同](template-acceptance-contract.md)、
[实施记录](template-acceptance-progress.md) 与 [用户说明](../../site/template-acceptance.md)。
同一候选根 schema/layout 用各案例自己的句柄与完整源修订，叠加原始标量输入，分别核对独立预期的
精确值、display、active、业务判定与诊断多重集；执行后重新检查全部基准。capture 只捕获源，expect
仍为 null，必须明确填写后验收。该切片没有多案例 UI、表／维度输入、服务端记录、正式 Save 或发布，
因此 G-A20 仅为部分完成，不改变原型人工复核状态。

后续显式 [Paper 验收 v2](template-paper-acceptance-contract.md) 增加同次真实候选的标题、表样式／列顺序、
单元格文本／editable／最终有限样式，以及 includeZero 的展示存在性检查。结构化地址使用完整成员数组，
不读取 Run 拼接键；row/address 可取交集，父对象须存在且唯一，多匹配报技术错误。v1 schema／报告字段
和默认 capture 不变，--format 2 才捕获 v2；原始输入和 Run 值断言仍限标量。见
[实施记录](template-paper-acceptance-progress.md) 与 [用户说明](../../site/template-paper-acceptance.md)。

2026-10-26 增加 schema 展示属性的真实 owner／源操作，见 [合同](schema-properties-contract.md)、
[实施记录](schema-properties-progress.md) 与 [用户说明](../../site/schema-properties.md)。
`template-schema-properties` 用正式 SchemaReader 模型和精确范围定位 presentation-items：根 schema
标签、note 文本与 classes 可编辑，捕获 include 只读；隐藏／零值／不活跃声明不因 Paper 缺行而遗漏。
全部操作绑定初始精确草稿 owner，并按声明路径逐步重定位，返回可逆顺序补丁。合法的展示属性可在
公式绑定错误的草稿中编辑，但成功只代表源语义读回，实际值、有效样式和可见性仍须候选 Preview。
它推进 G-A1／G-A3／G-A16／G-A18 的部分范围，不提供完整 Outline、结构增删、UI／UX 签收、正式 Save、
源历史提交、服务端草稿、D-A1 或发布。随后 [属性面板](schema-properties-ui-progress.md) 已接入
schema 字段缓冲、统一源 Undo／Redo 和恢复 v2；[展示声明导航](schema-navigation-progress.md)
进一步接入真实按文档／path 分组的目录与精确源码定位，不推断计算可见性或 Paper 投影。

## 1. 能力现状

| 能力 | 状态 | 入口 |
| --- | --- | --- |
| Structure／Run／Paper／Explain／Diagnostics 读取，精确值编码 | 【已实现】 | 契约 §6、§9.1 |
| 诊断源码摘录 | 【已实现】 | `GET …/diagnostic-source`，契约 v4 附加节 |
| 案例操作的预演、CAS 提交、undo／redo、409 冲突 | 【已实现】 | 契约 §7 |
| 案例操作的候选 Paper（含基准修订与草稿序号） | 【已实现】，仅文件工作区 | `POST …/preview-paper`，[interaction-progress](interaction-progress.md) |
| 案例公式槽位与扩展行的补全、悬停、检查 | 【已实现】 | 契约 §8.2，`FormulaEditor` |
| 外部修改事件 | 【已实现】 | SSE `documentChanged`，携带工作区修订与路径 |
| 已保存案例的 XLSX 导出与保真报告 | 【已实现】 | `export.xlsx`、`export-preview`，契约 §6.9 |
| Paper 浏览：显示零值行、表内查找 | 【已实现】 | `includeZero`，`PaperTable` |
| 模板 owner 句柄、源投影与 Outline | 【部分已实现】根 schema 展示属性 owner、精确范围、声明路径及只读 include；完整 Outline／Paper 投影仍为【设计】 | G-A1、G-A2；[schema 属性合同](schema-properties-contract.md) |
| 模板语义操作与最小源补丁 | 【部分已实现】根 schema 的标签／note／classes 初始 owner 绑定、顺序／可逆补丁；展示属性页面已接入统一草稿／历史／恢复；更广操作仍待完成 | G-A3；[schema 属性记录](schema-properties-progress.md) |
| 模板草稿预览（draft Paper、Explain、诊断） | 【部分已实现】，仅文件工作区根 schema/layout 原文与标量示例输入；无文件 Save | `template-sources`、`template-preview`，独立 `/template-preview` 页；G-A4／G-A14 只读候选切片，G-A15 的 owner 诊断仍为设计 |
| schema 公式语言服务 | 【设计】 | G-A5 |
| 源历史、提交与多文档原子性 | 【设计】正式服务端历史／文件提交；真实预览已有 G-A18 的有界客户端内存 Undo／Redo，不持久化 | G-A6、G-A7、D-A1；[客户端历史](source-history-progress.md) |
| workspace fork | 【设计】 | G-A8 |
| 样式来源 | 【设计】 | G-A9 |
| 构建记录、兼容报告、发布 | 【设计】／计划 | G-A10、G-A11 |
| 本地恢复草稿 | 【部分已实现】真实预览的工作区隔离自动副本、JSON 备份、根源／原始输入／未 Apply 字段恢复；录制原型仍独立，服务端恢复仍为【设计】 | G-A12，[恢复记录](template-recovery-progress.md) |
| 高级设置、结构编辑与参数化 pattern | 【部分已实现】根 layout 14 项设置、本地 style-class 有限定义及根 schema 展示属性；通用结构／属性编辑与参数化 pattern 仍为【设计】 | G-A16–G-A19，[设置记录](layout-settings-progress.md)、[样式记录](style-classes-progress.md)、[schema 属性记录](schema-properties-progress.md) 与 [设置清单](invoice-builder-gaps.md#4-高级设置清单) |
| 多案例验收 | 【部分已实现】只读 CLI 的完整基准锁定、同候选标量输入／Run 断言与 finding；显式 v2 增加 Paper 标题／列／单元格最终文本和样式、结构化成员地址与零值可见性。界面、表／维度输入、Run 坐标和服务端记录仍为【设计】 | G-A20，[首批记录](template-acceptance-progress.md)、[Paper 记录](template-paper-acceptance-progress.md) |
| 输出能力档案 | 【设计】；独立 XLSX 审计不是目标能力档案 | G-A21 |

## 2. 组件复用清单

### 2.1 Mantra 现有组件

| 组件 | 位置 | 在作者编辑器中的用途 | 结论 |
| --- | --- | --- | --- |
| `PaperTable` 的单元格外观映射与查找 | `workbench-ui/src/ui/PaperTable.tsx` | 作者网格沿用 weight／tone／fill 到 token 的映射与表内查找语义 | 复用映射；作者网格是新组件（键位与可选单元格不同，见设计规范 §4.8） |
| `ExplainDetails`、`ValidationEvidence`、聚合证据 | `workbench-ui/src/ui/` | Inspector 的 Explain 标签 | 直接复用 |
| `explainDiagnostic` 诊断说明目录 | `workbench-ui/src/diagnosticMessages.ts` | Problems 列表的本地化说明与原始消息 | 直接复用 |
| `ThemeControl` 与 `theme.ts` | `workbench-ui/src/ui/ThemeControl.tsx` | 浅色／深色／跟随系统 | 直接复用 |
| `FormulaEditor` | `workbench-ui/src/ui/FormulaEditor.tsx` | 已绑定案例槽位目标与 live authoring 端点；草稿序号、迟到响应与 IME 守卫是参考实现 | 不直接复用；按其 CodeMirror 配置（CSP nonce、主题）与守卫写 DSL 公式栏 |
| `LiveData.previewPaper` | `workbench-ui/src/data.ts` | Example input 标签的候选 Paper | 直接复用（仅文件工作区） |
| `InputsPage` 控件 | `workbench-ui/src/ui/InputsPage.tsx` | 示例输入的类型化控件与错误保留 | 抽出单个输入控件后复用（后续切片） |

### 2.2 Template Engine 模块

依赖闭包按 `6443c688` 源码逐个 import 核对。`packages/*` 都没有发布到 registry（根 `package.json` 为 `private`），
`main`／`types` 都指向 `src/index.ts`；除 `theme-tokens`、`kernel-core` 等少数包外都依赖 `workspace:*`；
`office-ui` 与 `office-workbook-ui` 声明 `react`／`react-dom` `^18` peer。它们不是可直接安装的 React 19 SDK。

| 模块 | 实际依赖闭包 | 价值 | 结论 |
| --- | --- | --- | --- |
| `office-interaction-core/src/keyboard/sheet-keyboard-shortcut.ts`（127 行） | 无 import | Excel 键位的纯函数解析 | **暂不适用**：S1 以 ui-spec 为键位默认。若采用 `Spreadsheet keys` 偏好，再建议提取 |
| `office-interaction-core/src/input/keyboard-input-session.ts`（324 行） | 无 import | 输入代理与 IME 组合状态机 | **建议提取**：与下一行组成无依赖子包，供 Mantra 的输入代理使用 |
| `office-interaction-core/src/focus/focus-boundary.ts`（38 行） | 无 import | 焦点边界激活规则 | **建议提取**（同上） |
| `theme-tokens` | 无依赖；定义 `--te-*` 变量名与 `TEMPLATE_ENGINE_THEME_CSS` | Office 视觉 token | **暂不适用**：Mantra 保留自己的 token；只有复用 Template Engine React 组件时才需要 §3.3 的映射层 |
| `OfficeSidePaneShell` | `OfficeActionIcons` → `@fluentui/react-icons` 2.0.333、图标 provider；`theme-tokens`；指针拖动会话 | 停靠、调宽、标签页 | **暂不适用**：S1 用 CSS grid 实现停靠。提取需改为图标 slot 并去掉 Fluent 依赖 |
| `ApplicationStatusPill` | 同上图标闭包 | 24 px 胶囊，图标与文字共同表达 | **不适用**：六种状态是人工确认词汇，不能表达草稿状态或引擎 finding；只借鉴视觉规格 |
| `StandaloneSheetApplicationPanel`、`…ExportControl` | `office-ui`；问题目标是 `{sheetId, cellRef}` | 问题队列与导出控件 | **不适用**：目标是 A1 单元格。队列交互可借鉴 |
| `WorkbookCellStatusPillLayer` | `office-ui`、`theme-tokens`；依赖 SheetSkeleton 坐标 | Canvas 工作表上的装饰层 | **不适用**：作者网格是 DOM 表格 |
| `FormulaBar` | `office-ui`；A1 名称框、Excel 函数对话框、Ctrl+Enter 填充选区 | 公式栏外壳 | **不适用**；建议 Template Engine 将来提取语言无关的公式栏外壳，再评估 |
| `CellEditorOverlay` | `theme-tokens`；内置 Excel 补全优先于宿主补全 | 单元格编辑器 | **不适用**，直到有显式配置关闭内置补全 |
| `workbook-clipboard-transport` | `formula-engine`、`office-model-v2`、`theme-tokens` | Office 剪贴板 | **不适用**：A1 语义 |
| `office-layout-core`、`office-render-core`、`render-engine` 与 app 层 Canvas 宿主 | 多个 workspace 包；完整宿主在 `apps/manifest-runtime/src/office-v2` | Canvas 网格 | **暂不适用**：S1 使用 DOM。只有选择 Office 渲染路线时才做一次性 `OfficePackageModelV2` 展示缓存的证明 |
| `formula-engine`、`office-workbook-editor-core`、`OfficeSession`、`CommandPipeline`、税务应用 | — | — | **不适用**：会形成第二份可写模型或第二套计算 |

本切片没有已验证接入的 Template Engine 模块。

### 2.3 复用的前置条件

- **许可**：Mantra 为 Apache-2.0；Template Engine 仓库根目录没有 LICENSE，根 `package.json` 为 `private`。
  复制其源码进入 Mantra 需要版权方明确授权，并在文件头保留来源仓库、提交、原路径与许可。
- **复制（vendoring）**：只适用于无依赖的纯 TS 模块。附同步检查脚本，比较上游提交中的源文件摘要；
  维护方写明，漂移视为构建失败。
- **提取为共享包**：定义唯一入口（ESM 构建产物，不是 TS／JSX 源入口）、语义化版本、
  `react` peer 范围覆盖 18 与 19、不含 `workspace:*` 依赖、随包提供 CSS／token 与许可证文件，
  并在两个仓库的 CI 中验证。
- Template Engine 当前以上线为最高优先级（其 `.agentdocs/index.md`）。任何提取都需该项目维护者确认，
  不作为 Mantra 编辑器的前置条件。

## 3. 设计 token

### 3.1 沿用

工作台 [ui-spec §2](../ui-spec.md#2-设计语言arbeitspapier) 的颜色、字体、圆角与间距 token 全部沿用，
包括深色值（`workbench-ui/src/style.css`）。示例输入沿用 `--blue-field`／`--blue-line`／`--blue-strong`
的输入外观；技术错误与失败的 finding 沿用 `--error`／`--error-tint`，用图标与文字区分。

### 3.2 新增作者 token

| token | 浅色 | 深色 | 用途 | 对比度（文字／底） |
| --- | --- | --- | --- | --- |
| `--author-definition` | `#5a3f8f` | `#c9b6f2` | 模板定义角标、通道文字 | 8.13 ／ 8.63（对 `--sheet`） |
| `--author-definition-tint` | `#efe9f7` | `#2f2840` | 定义通道徽标底 | 6.95 ／ 7.65 |
| `--author-stale` | `#6f5410` | `#f0cf7a` | 过期预览与过期诊断文字 | 7.00 ／ 10.48（对 `--sheet`） |
| `--author-stale-tint` | `#f8efd5` | `#3d3420` | 过期横幅底色 | 6.20 ／ 8.13 |
| `--author-diff-add` / `-ink` | `#e3f1e3` / `#1f5c2e` | `#23382a` / `#a9dcb4` | 源差异新增 | 6.83 ／ 8.13 |
| `--author-diff-del` / `-ink` | `#f6e1de` / `#9e1b12` | `#4a2d2c` / `#ffaea5` | 源差异删除 | 6.39 ／ 6.96 |
| `--author-prototype` / `-tint` | `#5c4a00` / `#f3e7b3` | `#f2e08c` / `#3d3517` | 原型标签 | 6.94 ／ 9.20 |

全部文字对比度大于 4.5:1（WCAG 2.2 AA），以 WCAG 相对亮度公式计算。

尺寸：身份栏 52 px；Outline 240 px；Inspector 默认 360 px（320–520）；Drawer 默认 240 px（最小 160）；
公式栏最少 1 行、最多 6 行后滚动；窄屏左右留白 16 px，触控目标 ≥ 44 px。

### 3.3 复用 Template Engine 组件时的映射

只有决定复用 Template Engine React 组件时才在宿主作用域内声明，不全局注入 `TEMPLATE_ENGINE_THEME_CSS`：

| Template Engine 变量 | Mantra token |
| --- | --- |
| `--te-color-surface` | `--sheet` |
| `--te-color-canvas` | `--paper` |
| `--te-color-text` | `--ink` |
| `--te-color-text-muted` | `--ink-3` |
| `--te-color-border` | `--line` |
| `--te-color-border-subtle` | `--line-soft` |
| `--te-color-accent` | `--blue` |
| `--te-color-on-accent` | `--field` |
| `--te-color-danger` | `--error` |
| `--te-color-border-focus` | `--blue` |

## 4. 接口缺口

以下合同先写入 `docs/workbench/contract.md`（新版本节），附 JSON Schema、生成的 TypeScript 类型与 golden 后再实现。
所有响应使用 v4 外层，数值使用精确编码。

G-A4／G-A14 已按契约 §7.1 实现下述只读候选切片，并有 JSON Schema、生成的 TypeScript 类型和
回归检查；其余缺口不因此关闭。文档句柄绑定完整基准图和文档，不是 G-A1 的属性 owner 句柄。

| # | 缺口 | 最小合同 | 原型中的处理 |
| --- | --- | --- | --- |
| G-A1 | owner 句柄 | 每个可编辑属性返回不透明 `handle`、`kind`（label／title／note／formula／classes／layoutOption）、文档、显示范围、显示路径、`editable` 与原因、共享范围（include 它的 schema）、在 Paper 中的投影（面板、行 anchor、单元格）。句柄绑定精确文档修订与 graph 修订；删除或歧义使其失效 | 【部分已实现】schema presentation-items 的 label／text／classes owner，绑定完整基准、document handle、精确根草稿及声明路径；根可编辑、捕获 include 只读。通用属性、共享影响与 Paper 投影仍待完成；录制原型保持模拟 owner |
| G-A2 | 模板身份与 Outline | 模板 = schema id／version + layout id + 参与文档；Outline 包括隐藏、零值隐藏与不活跃声明及原因 | 【部分已实现】真实 presentation-items 目录按捕获文档和声明 path 分组／搜索，含隐藏／零值／不活跃声明，可精确定位源；不含全种类声明、计算可见原因或 Paper 投影。录制原型仍用模拟扫描器与 Paper flags |
| G-A3 | 语义操作与最小补丁 | `{baseRevisions, draftSequence, operations:[{op, handle, value}]}` → `{patches:[{document, startOffset, endOffset, text, inverse}], diagnostics}`；不变式同契约 §7.2：读回语义等价、编辑区间外字节不变 | 【部分已实现】schema setText／setClasses／unset 返回相对前一步的 UTF-16 补丁及 removedText，逆序精确还原；先绑定初始 owner，后按稳定声明路径定位，正式 reader 读回。展示属性 UI 已接入统一历史／恢复；其他语义操作仍待完成，原型补丁保持模拟 |
| G-A4 | 模板草稿预览 | 语义与 `preview-paper` 一致：`draftSequence`、全部参与修订、候选修订、`succeeded`、`validationPassed`、`diagnostics`、`run`、`difference`、`paper`；修订不一致 409；技术无效 422 | 【部分已实现】文件工作区 `template-sources`／`template-preview` 接受根 schema/layout 原文与标量示例输入，保持模板身份和依赖闭包，在同一隔离候选中返回 Structure／Run／Paper／Difference／诊断；返回前重验完整源修订，提供 409／422 与序号。独立 `/template-preview` 页已接入，无文件 Save；【录制原型】仍按文档集摘要查找响应，未录制显示无引擎预览，尚未接入真实 API |
| G-A5 | schema 公式语言服务 | 以句柄为目标的 complete／hover／check，作用域为该声明可见的节点、`defn` 与函数目录 | 【原型】补全来自录制的 Structure 与 CLI catalog |
| G-A6 | 源历史 | 服务端返回逆补丁；客户端保存事务；应用撤销时服务端核对基准修订 | 【原型】客户端历史，逆补丁在前端计算 |
| G-A7 | 提交 | 重新核对全部参与修订与句柄；409 返回当前修订与变化文档；422 返回技术诊断；成功返回新修订与写入的文档 | 【原型】模拟 409 与“已保存（未写入文件）” |
| G-A8 | workspace fork | 指定 package 与模板，复制依赖闭包到可写目录，保留 include、映射与依赖身份并返回摘要清单 | 未覆盖 |
| G-A9 | 样式来源 | 每个单元格按声明顺序列出贡献的预设规则、`style-class`、`style` 与 `:use` 合并及其源位置 | 【原型】只显示最终样式与可用 class |
| G-A10 | 构建记录 | 目标、源修订、引擎指纹、案例／参数／版式身份、状态、`ExcelReport`、已验证与未验证的兼容项、产物摘要、映射边车摘要 | 【原型】只显示录制的导出报告，不执行构建 |
| G-A11 | 发布与目录 | 版本化产物与 Template Engine 目录登记合同（计划） | 按钮禁用 |
| G-A12 | 恢复草稿 | 浏览器本地保存基准修订集、源原文及未提交输入；只作恢复，不作校验结果；服务端草稿另定 | 【部分已实现】真实 `/template-preview` 的自动副本与独立 JSON 格式，保留原始输入和布局／样式／schema 面板字段；工作区／完整图／descriptor 绑定，失配只供下载，恢复后重新检查；不存结果／权限／Undo 历史，不关闭服务端恢复或 D-A1。录制原型保持独立 |
| G-A13 | 模板级外部修改 | SSE 增加受影响的模板／case graph 修订，或由客户端按路径刷新参与修订 | 【原型】模拟外部修改按钮 |
| G-A14 | 草稿 Explain | 草稿预览的 trace 与步骤 | 【部分已实现】`template-preview` 可请求一个根案例地址的 Explain，证据与该次候选 Structure／Run／Paper 一致；独立真实预览页可查看。【录制原型】Inspector 仍使用录制状态的 Explain，尚未接入候选 Explain API |
| G-A15 | 无效草稿诊断的 owner | 诊断附 owner 句柄；现有诊断只有位置与地址 | 【原型】录制诊断的源范围映射到模拟句柄 |
| G-A16 | 类型化高级属性与能力目录 | 声明种类、源／目标身份、类型／范围、单位、显式／继承／最终值、来源、权限与支持原因；衔接 G-A1／G-A5／G-A9 | 【部分已实现】根 layout 14 项、选中 preset／本地 style-class，以及 schema 展示属性的声明状态、原始值、类型、范围与只读原因；不把原始标签／class 列表冒充最终 Paper。通用声明／单元格／目标目录仍为设计 |
| G-A17 | 结构化声明编辑 | 输入、维度、计算项、规则、表列和有限样式定义的增删重排；ID、依赖、引用与影响范围预检；复用 G-A3 最小补丁 | 【部分已实现】根 layout 本地 style-class 的有限 upsert／remove 及布局内硬引用保护；schema classes 赋值由独立 G-A18 属性接口提供，不等于声明结构编辑。输入／维度／计算／规则／表列、重命名重排与完整影响分析仍待实现 |
| G-A18 | 高级设置操作与编辑协调 | set／移除显式覆盖，保留 false／0／空文本语义；属性／局部与完整源码共用源事务、草稿序号、迟到保护及撤销 | 【部分已实现】根设置／本地 class 的现有页面协调，以及 schema 展示属性的精确初始 owner、完整修订、顺序可逆补丁；class 缺省与显式 [] 分开。schema 属性 UI 已接入字段缓冲、共用的最多 50 条精确源历史和恢复 v2；正式服务端历史、G-A7／D-A1 保存仍待实现 |
| G-A19 | pattern 实例化与共享影响 | 参数合同、ID 映射、命名冲突、依赖闭包、共享引用清单与显式复制／引用策略 | 【设计】；现有四个 pattern 是复制修改，不是参数化宏 |
| G-A20 | 多案例验收集 | 同一候选模板的多个案例、基准修订、独立预期值／finding／格式与差异；表／维度输入须扩展候选合同 | 【部分已实现】`e0d2148` 的 template-capture／template-verify：共享根候选、各自完整图与句柄、标量输入／结果、精确值／display／业务判定／完整诊断断言及末尾重查；capture 的 expect 为 null。后续显式 v2 增加 Paper 标题／列／最终单元格及结构化成员地址验收，v1 默认与 schema 不变；多案例 UI、表／维度输入、Run 坐标、独立记录服务和发布仍待实现，见 [首批合同](template-acceptance-contract.md) 与 [Paper 合同](template-paper-acceptance-contract.md) |
| G-A21 | 输出目标能力与兼容档案 | 绑定目标／版本与引擎指纹的支持／不支持／未知／未验证状态，关联 G-A10／G-A11 的报告和证据 | 【设计】；现有 Template Engine 完整兼容仍为 false |

G-A16–G-A21 的详细范围、参考证据及验收见 [新增功能缺口](invoice-builder-gaps.md#3-新增功能缺口)。
不重编号 G-A1–G-A15，也不把这些规划行视为已经生效的 HTTP／JSON 合同。

## 5. 分步实施

| 阶段 | 内容 | 仓库 | 验收 | 原型已证明 |
| --- | --- | --- | --- | --- |
| P0 合同 | G-A1–G-A7 的请求、响应、错误与不变式；schema、TS 类型、golden | Mantra | 契约评审；schema 测试 | 状态与交互可行 |
| P1 只读作者视图 | 入口、Outline、源码视图、诊断同步；不编辑 | Mantra | 4 个 pattern 与验收应用无 schema 专用代码即可导航（W6） | 网格与源码选择同步 |
| P2 文字属性 | label／title／note 的语义操作、补丁、草稿预览、源历史、单文档提交 | Mantra | 性质测试：编辑区外字节不变；迟到响应不覆盖；409 保留草稿 | 状态转换 |
| P3 公式 | `setFormula`、语言服务、草稿 Explain | Mantra | 技术错误阻止提交、修复后恢复；业务 finding 不阻止 | 录制的 422 范围与 finding |
| P4 class 与有限布局 | `setClasses`、`setLayoutOption`、样式来源 | Mantra | 样式不改值；来源链与引擎最终样式一致 | 最终样式变化 |
| P5 恢复与多文档提交 | 恢复草稿、D-A1 的提交策略 | Mantra | 中断与失败注入测试 | 恢复横幅 |
| P6 构建目标 | Mantra 运行模板与派生 Excel 的构建记录与报告 | Mantra | 零 fallback 与求值错误要求；报告与产物摘要一致 | 报告展示 |
| P7 下游 | 首个发布验证（`actuals-vs-baseline`）、Template Engine 目录登记、实例来源与分叉标记 | 两仓库 | 按 [template-directions](../../template-directions.md#first-publication-proof) 的导入、改值重算与精度检查 | 未覆盖 |

## 6. 待决策项

| # | 问题 | 选项 | 建议 |
| --- | --- | --- | --- |
| D-A1 | 多文档提交的原子性（S1 的 class 在 schema、布局选项在 layout） | a. S1 只允许单文档提交；b. 复核全部修订后逐文件原子替换，失败时用备份补偿；c. 写意图日志的事务提交 | 先定义 b 的失败语义与限制（同契约 §7.3：advisory 锁不能防止不遵守锁的编辑器）；未定前 UI 不宣称多文档事务 |
| D-A2 | 键位 | ui-spec 默认；或 `Spreadsheet keys` 偏好 | S1 采用 ui-spec；偏好另行评审 |
| D-A3 | 共享 fragment 的编辑 | 确认作用范围后编辑；或要求先复制 fragment | S1 确认范围；`defn` 只读 |
| D-A4 | 恢复草稿存储 | 仅浏览器；或服务端草稿 | S1 仅浏览器，明确不跨设备 |
| D-A5 | Canvas／Office 渲染复用 | 现在证明；或推迟 | 推迟到 DOM 网格的性能或功能不足时 |
| D-A6 | Template Engine 模块提取 | 现在提取；或等 Template Engine 上线后 | 等 Template Engine 维护者确认 |

## 7. 原型能做与需要未来契约的事项

下表描述 `/authoring` 录制原型；“需要的契约”包含已经定义、实现但尚未接入该原型的
G-A4／G-A14 切片。独立 `/template-preview` 页可预览未录制的根模板原文及其示例标量输入。
真实 schema 展示属性 owner／补丁已另有 [API 合同](schema-properties-contract.md)，但两页面尚未接入其
可视化控件／缓冲协调；没有正式源历史提交或多文档保存。以下原型状态不随该 API 自动升级。

| 事项 | 原型 | 需要的契约 |
| --- | --- | --- |
| 标签、class、公式编辑的即时反馈 | 录制状态内可用；未录制显示无引擎预览 | G-A1、G-A3、G-A4 |
| 无效草稿与诊断范围 | 录制真实 422 | G-A15 |
| 业务 finding 保留数值 | 录制真实响应 | — |
| 迟到响应丢弃、IME 守卫、错误后修复 | 前端状态机与测试 | G-A4 的序号与修订字段 |
| 源修订冲突 | 模拟 409；外部修改后的状态为录制 | G-A7、G-A13 |
| 保存 | 模拟，明确未写入文件 | G-A7 |
| 恢复草稿 | localStorage | G-A12 |
| 示例输入的候选 Paper | 录制真实 `preview-paper` 响应 | 已实现 |
| 构建与发布 | 只显示录制的导出报告；按钮不返回成功 | G-A10、G-A11 |

## 8. 高级作者能力的实施次序

原 P0–P7 与 S1 验收继续保留。[Invoice Builder 对照的 A0–A4](invoice-builder-gaps.md#6-首批实施顺序与验收)
是在其上的后续路线：先定义属性目录和同源编辑，再暴露已有呈现选项，之后扩展结构／规则、
pattern／多案例验收，最后接入输出能力与交付。优先复用既有 DSL，不新增自由画布或第二份可写配置。

第一批根版式 14 项已实现，覆盖 locale／language、显示精度／百分比精度、负数／零值、grouping、
hide-zero／show-inactive／expand-members／row-numbers、signed 与 title／subtitle；后续已加入根 layout
有限 class 定义，详见 [样式实施记录](style-classes-progress.md)。定义与应用分开，不扩展为 schema tag
分配、任意 selector 编辑、重命名、重排或完整引用分析。
手写源及两类 Apply 的撤销／重做已由 [有界内存历史](source-history-progress.md) 衔接；它不代替
服务端 G-A6、正式提交 G-A7 或恢复 G-A12，重载／新源快照后不保留事务。
精确计算 round、输入约束和校验放在单独的 schema 属性组，
不能通过“格式设置”改变计算口径。完整清单见 [高级设置](invoice-builder-gaps.md#4-高级设置清单)，
交互见 [设计规范 §3.14](design-spec.md#314-高级设置与模板构建后续切片2026-10-24)，
边界状态见 [状态矩阵 §8](state-matrix.md#8-高级设置的补充状态后续切片)。
