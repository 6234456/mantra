# 可视化 DSL 编辑器：复用、设计 token、接口缺口与实施计划

> 状态：**设计规范（PLANNED）**。本文对照 Mantra `682bd2c` 与 Template Engine `6443c688` 的实际代码，
> 区分已实现能力、建议的契约与原型模拟。界面与键位见 [设计规范](design-spec.md)，状态与权限见
> [状态矩阵](state-matrix.md)。新增公共 API 前先在 [工作台契约](../contract.md) 定义合同；
> 通用引擎、工作台与 UI 不加入业务规则（CLAUDE.md、契约 W2）。

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
| 模板 owner 句柄、源投影与 Outline | 【设计】 | G-A1、G-A2 |
| 模板语义操作与最小源补丁 | 【设计】 | G-A3 |
| 模板草稿预览（draft Paper、Explain、诊断） | 【设计】 | G-A4、G-A14、G-A15 |
| schema 公式语言服务 | 【设计】 | G-A5 |
| 源历史、提交与多文档原子性 | 【设计】 | G-A6、G-A7、D-A1 |
| workspace fork | 【设计】 | G-A8 |
| 样式来源 | 【设计】 | G-A9 |
| 构建记录、兼容报告、发布 | 【设计】／计划 | G-A10、G-A11 |
| 本地恢复草稿 | 【设计】 | G-A12 |

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

依赖闭包按 `6443c688` 源码逐个 import 核对。所有包都是 `private` monorepo 包：`main`／`types` 指向
`src/index.ts`，依赖为 `workspace:*`，React 组件声明 `react`／`react-dom` `^18` peer。它们不是可直接安装的
React 19 SDK。

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

| # | 缺口 | 最小合同 | 原型中的处理 |
| --- | --- | --- | --- |
| G-A1 | owner 句柄 | 每个可编辑属性返回不透明 `handle`、`kind`（label／title／note／formula／classes／layoutOption）、文档、显示范围、显示路径、`editable` 与原因、共享范围（include 它的 schema）、在 Paper 中的投影（面板、行 anchor、单元格）。句柄绑定精确文档修订与 graph 修订；删除或歧义使其失效 | 【原型】前端模拟扫描器生成句柄，并用测试与录制的 Structure 位置核对 |
| G-A2 | 模板身份与 Outline | 模板 = schema id／version + layout id + 参与文档；Outline 包括隐藏、零值隐藏与不活跃声明及原因 | 【原型】由模拟扫描器与录制的 Paper flags 组合 |
| G-A3 | 语义操作与最小补丁 | `{baseRevisions, draftSequence, operations:[{op, handle, value}]}` → `{patches:[{document, startOffset, endOffset, text, inverse}], diagnostics}`；不变式同契约 §7.2：读回语义等价、编辑区间外字节不变 | 【原型】前端模拟补丁 |
| G-A4 | 模板草稿预览 | 语义与 `preview-paper` 一致：`draftSequence`、全部参与修订、候选修订、`succeeded`、`validationPassed`、`diagnostics`、`run`、`difference`、`paper`；修订不一致 409；技术无效 422 | 【原型】按文档集摘要查找录制的真实响应；未录制显示无引擎预览 |
| G-A5 | schema 公式语言服务 | 以句柄为目标的 complete／hover／check，作用域为该声明可见的节点、`defn` 与函数目录 | 【原型】补全来自录制的 Structure 与 CLI catalog |
| G-A6 | 源历史 | 服务端返回逆补丁；客户端保存事务；应用撤销时服务端核对基准修订 | 【原型】客户端历史，逆补丁在前端计算 |
| G-A7 | 提交 | 重新核对全部参与修订与句柄；409 返回当前修订与变化文档；422 返回技术诊断；成功返回新修订与写入的文档 | 【原型】模拟 409 与“已保存（未写入文件）” |
| G-A8 | workspace fork | 指定 package 与模板，复制依赖闭包到可写目录，保留 include、映射与依赖身份并返回摘要清单 | 未覆盖 |
| G-A9 | 样式来源 | 每个单元格按声明顺序列出贡献的预设规则、`style-class`、`style` 与 `:use` 合并及其源位置 | 【原型】只显示最终样式与可用 class |
| G-A10 | 构建记录 | 目标、源修订、引擎指纹、案例／参数／版式身份、状态、`ExcelReport`、已验证与未验证的兼容项、产物摘要、映射边车摘要 | 【原型】只显示录制的导出报告，不执行构建 |
| G-A11 | 发布与目录 | 版本化产物与 Template Engine 目录登记合同（计划） | 按钮禁用 |
| G-A12 | 恢复草稿 | 浏览器本地保存基准修订集、源事务与未提交输入；只作恢复，不作校验结果 | 【原型】localStorage |
| G-A13 | 模板级外部修改 | SSE 增加受影响的模板／case graph 修订，或由客户端按路径刷新参与修订 | 【原型】模拟外部修改按钮 |
| G-A14 | 草稿 Explain | 草稿预览的 trace 与步骤 | 【原型】录制状态的 Explain |
| G-A15 | 无效草稿诊断的 owner | 诊断附 owner 句柄；现有诊断只有位置与地址 | 【原型】录制诊断的源范围映射到模拟句柄 |

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
