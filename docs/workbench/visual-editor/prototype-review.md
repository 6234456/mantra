# 录制数据原型：Claude 复核记录

> 复核对象：任务分支 `codex/mantra-design-handoff-20261010` 已提交的 HEAD `69ab8ff`
> （Codex 提交 `9155663`、`aa5e14a`、`69ab8ff`）。复核日期 2026-10-10。
> 依据：[prototype.md](prototype.md) 的实施要求与 §11 复核清单、[设计规范](design-spec.md)、[状态矩阵](state-matrix.md)。
> 结论：**数据真实性、状态模型和门禁通过；交互布局与样式面板有需修正项，见 §4。** 修正由 Codex 实施，Claude 复核。

## 1. 门禁复跑

在本机 `69ab8ff` 干净检出上重新运行，结果与 Codex 记录一致：

| 检查 | 结果 |
| --- | --- |
| `npm --prefix workbench-ui run check` | 通过 |
| `npm --prefix workbench-ui test` | 36 个文件、305 项通过 |
| fixture 构建 | 通过；作者原型为单独的懒加载 chunk（约 1.69 MB，含录制数据） |
| live 构建（`VITE_WORKBENCH_MODE=live`） | 通过；产物中没有原型代码或录制数据 |
| `npm --prefix workbench-ui run authoring:record:check` | 32 个状态与当前 pattern 原文一致 |
| `python3 scripts/check-boundaries.py` | 通过 |
| `App.tsx` 与现有页面 | 未修改；只在 `main.tsx` 增加 fixture 模式的 `/authoring` 入口，`PaperTable.tsx` 仅导出 `cellAppearance` |

PR CI（`verify`）在复核时仍为 pending，未计入本结论。

## 2. 录制数据真实性

在本机用安装版 CLI 独立重新录制全部 32 个状态（`--output` 写到临时文件），再与已提交的 `recording.json` 逐项比较：

- 状态 id、编辑、源文档摘要与请求完全一致；共 277 个去重响应 blob，文件大小同为 1,619,324 字节。
- 307 个响应体中，163 个逐字节相同；其余 144 个只有内核 `eventId` 不同。`eventId` 是每次执行的不透明标识，
  契约 v4 规定它在不同运行间可以不同；除此之外没有差异。
- 结论：已提交的数据是未经修改、未删减字段的真实引擎响应。

原型源码中没有数值合成：唯一的 `Number(...)` 用于网格坐标和 layout `:precision` 选项；显示值全部来自引擎文本。

## 3. 浏览器操作复核

使用内置浏览器，fixture 开发服务器，视口 1440×900、390×844 与深色主题，逐步执行 [prototype.md §9](prototype.md#9-操作脚本claude-已复核见复核记录)：

| 步骤 | 实际结果 | 判定 |
| --- | --- | --- |
| 2 入口与模拟 fork | 四个 pattern，仅 Capped allocation 可用；对话框列出 4 个参与文档、可编辑性与 SHA-256 | 通过 |
| 3 标签 | 键入可打印字符开始编辑（焦点移到 Inspector 字段）；提交后预览为当前、焦点下移到 `Capacity not consumed`；Source changes 显示一行差异 | 通过；见 F4 |
| 4 class | `subtotal`→`result` 后行样式变为 accent／accent fill，数值不变 | 通过；样式面板见 F5 |
| 5 无效公式 | Cmd+Enter 后编辑器保留焦点；`Invalid draft`；`Previous valid preview (draft #4)`；`allocated-totl` 与整式两条诊断标出；Save 原因 `Fix the technical errors before saving`；Problems (2) 不抢焦点 | 通过 |
| 6 finding | `unallocated` 为 `(60.00)`，`conserved` 为 `✗`；Findings 1、Errors 0；Save 可用；焦点回到原单元格 | 通过 |
| 7 撤销／重做 | 先回到无效草稿并显示上一有效预览，再回到步骤 4；重做两次回到步骤 6；状态栏朗读 `Undid …`／`Redid …` | 通过 |
| 8 迟到响应 | 延迟后撤销再重做：`Ignored late preview #11`，网格保持当前草稿 | 通过 |
| 9 冲突 | `Conflict` 胶囊、草稿保留；Cmd+S 打开冲突对话框；重新预览后 Paper 标题为新标题；保存显示 `Saved in this prototype session — no file was written` | 通过；见 F15 |
| 10 运行时失败 | `Draft #15 calculated with a runtime failure`；`unallocated` 显示引擎文本 `undefined` 与标记；Save 可用 | 通过；见 F7、F8 |
| 11 恢复 | 刷新后出现恢复横幅；Restore 后为 `Restored · not validated` 且历史恢复 | 通过；见 S1 |
| 12 拼音输入法 | 自动化无法驱动真实输入法；代码与测试只覆盖合成 composition／keyCode 229 | **待人工** |
| 13 示例输入 | 重置后输入 `9`：候选 Paper 为 `90.00／90.00／50.00` 并标注录制、未保存；`nine`：字段下显示 `Input text was rejected: Invalid decimal text`，原文保留 | 通过；见 F9 |
| 14 构建 | 录制报告：公式单元格 105、输入单元格 15、命名区域 44、无 fallback 与求值错误；Build／Publish／Open in Template Engine 禁用 | 通过；见 F14、F16 |
| 15 窄屏与深色 | 390 px 无横向滚动，深色主题可读 | 通过；布局见 F10 |

模拟能力在身份栏、Outline、Inspector、补全说明、冲突对话框、保存胶囊与原型控制中都有标注；导出、构建、发布没有成功路径。

## 4. 需修正项（交给 Codex）

按影响排序。行号指 `69ab8ff` 的 `workbench-ui/src/authoring/` 文件。

| # | 严重度 | 问题 | 位置 | 修正要求 |
| --- | --- | --- | --- | --- |
| F1 | 中 | Split 不能并排使用：网格与源码共用中栏的一个滚动容器，源码编辑器高度不受限；选中网格单元格时源码选择 `scrollIntoView` 把整个中栏滚走，左侧网格变成空白（实测分栏容器高 1381 px，网格卡片位于 y=−529） | `AuthoringPrototype.tsx:874`、`AuthoringCodeEditor.tsx:194`、`authoring.css` `.author-split` | 两个窗格各自限定高度并独立滚动；源码定位只滚动编辑器自身 |
| F2 | 中 | 网格到源码的 owner 高亮不可见：owner 范围作为编辑器 selection 传入，编辑器失焦时不绘制（实测选区层为 0） | `AuthoringPanels.tsx:122`、`AuthoringCodeEditor.tsx` | 用与焦点无关的 decoration 标出主 owner 与次要 owner（设计规范 §3.4） |
| F3 | 中 | 1440×900 下打开编辑器时看不到网格行：公式卡片与网格同在中栏滚动区，滚到网格又看不到公式栏 | `AuthoringPrototype.tsx:836–877`、`authoring.css` | 公式栏固定在网格上方（默认紧凑，聚焦时展开），网格占据剩余高度 |
| F4 | 中 | 单行文字提交后若草稿未录制，编辑器停留且没有说明（实测状态栏只显示 `No engine preview…`） | `AuthoringPrototype.tsx:358` | 文字属性在操作被接受后即移动；若决定停留，须在字段旁说明“编辑已保留，无引擎预览” |
| F5 | 中 | Style 面板偏离设计 §3.8：自由文本输入；class 列表写死在 `config.json` 且不完整，缺 `:utilities` 的 `normal`、`subtle`、`highlight` 与 `:working-paper` 的 `variance`；最终样式以原始 JSON 显示 | `config.json:26`、`AuthoringPrototype.tsx:979–1017` | 由 layout 的 `:style-preset` 推导可选 class（使用引擎目录或带出处的 dsl-reference §3.4 预设表数据），并列出本 layout 的 `style-class`；芯片式选择；最终样式以 weight／tone／fill 文字显示 |
| F6 | 中低 | Source changes 每个文档只算一个前后缀块，不相邻的编辑会把中间未变的行显示为删除再新增，与 `Unrelated bytes unchanged` 矛盾 | `AuthoringPanels.tsx:262` | 按补丁或真实行差异生成多个 hunk，并逐 hunk 标注来源操作 |
| F7 | 中低 | 运行时失败诊断不计数：Problems 角标与状态栏 Errors 只统计 parsing／structural | `AuthoringPrototype.tsx:318、1070、1081` | 增加运行时失败计数，Problems 角标包含它 |
| F8 | 低 | 错误标记的可访问名称在运行时失败状态下仍说“数值来自上一有效预览”，实际显示的是当前预览 | `AuthoringGrid.tsx:260` | 按预览状态给出不同说明 |
| F9 | 低 | Inspector 通道徽标固定为 `ƒ Template definition · simulated owner`，Example input 标签与只读 owner 下也不变 | `AuthoringPrototype.tsx:887` | 按当前标签与 owner 显示 `Example input`、`Read-only · <reason>` 等（设计规范 §2.2） |
| F10 | 低 | 窄屏把 Outline、公式、网格、Inspector、Drawer 依次堆叠，网格远在首屏以下 | `authoring.css` `@media (max-width: 760px)` | 按设计规范 §3.13：Outline 抽屉、Grid／Source 分段、全屏 Inspector |
| F11 | 低 | Outline 是跨面板的扁平列表，含 case／layout 声明 | `AuthoringPrototype.tsx:807–834` | 按面板分组，并显示隐藏、零值、不活跃原因 |
| F12 | 低 | 浏览态收到 keyCode 229 时直接返回，用输入法直接键入无法开始编辑 | `AuthoringGrid.tsx:110` | 在 keydown 中打开编辑器并同步移交焦点，使组合落入输入框；需人工输入法验证 |
| F13 | 低 | 网格无选区时 Escape 会关闭底部 Drawer，设计要求不动作 | `AuthoringPrototype.tsx:679` | 移除该行为，或改为只关闭由网格打开的覆盖层 |
| F14 | 低 | Build 面板写 `for the saved example case`，模拟保存改变已保存基准后仍显示初始 base 的报告 | `AuthoringPanels.tsx:330` | 标注为 `recorded base state`，或在已保存基准不是 base 时说明不可用 |
| F15 | 低 | 冲突对话框显示外部文档全文而非差异；冲突期间预览仍标为当前 | `AuthoringPrototype.tsx:1121` | 显示外部差异；冲突期间预览标注基于旧源修订 |
| F16 | 低 | 禁用的 Build／Publish／Open in Template Engine 没有关联原因 | `AuthoringPanels.tsx` `BuildPanel` | 用 `aria-describedby` 或提示把原因关联到各按钮 |
| F17 | 低 | 恢复横幅显示原始 ISO 时间 | `AuthoringPrototype.tsx:773` | 本地化时间 |
| F18 | 低 | `AuthoringPrototype.tsx` 已 1151 行，接近 1200 非空行上限 | 整个文件 | 修正时把 Inspector 与 Drawer 拆为独立组件 |

## 5. 设计说明需澄清（Claude 文档）

| # | 问题 | 决定 |
| --- | --- | --- |
| S1 | 恢复草稿只在 `valid` 预览后清除未校验标记，因此恢复出的运行时失败草稿在编辑前一直不能保存；未恢复的运行时失败草稿却可以保存 | 运行时失败说明源码在技术上有效。恢复标记在首个与当前草稿匹配、技术有效（`valid` 或 `runtimeFailure`）的预览后清除；状态矩阵与 prototype.md 按此更正 |
| S2 | 原型在 Inspector 字段而不是单元格内编辑文字 | 原型接受 Inspector 编辑，提交后焦点按规则回到网格；正式实现仍按设计规范评估单元格内编辑 |
| S3 | 多行 class 分配在原型中提示“planned” | 原型保持延后；设计规范 S1 中的多行分配列为正式实现项 |

## 6. 待完成

- F1–F18 由 Codex 修正，S1 的模型行为随之调整；完成后 Claude 按本文件复核。
- 真实中文拼音输入法（步骤 12）需要人工操作。
- PR CI 结果待出。
