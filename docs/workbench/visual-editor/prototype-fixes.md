# 原型复核修正：F1–F18 与 S1

> 2026-10-10：**Codex 修正完成；Claude 第二轮复核见 [复核记录 §7](prototype-review.md#7-第二轮复核)**。
> 本轮以 Claude 文档提交 `92664ae` 为起点，修正 [初次复核](prototype-review.md) 对 `69ab8ff` 的发现。
> 修正代码检查点 **`dc01609` 已正常推送**；本文随后以文档提交交付，复查仍按 PR 最新已推送 HEAD。
> 下表是编码方的实现与检查记录，不表示 Claude 已签收。启动与完整操作脚本见
> [实施记录](implementation-progress.md)、[prototype.md](prototype.md)；复查提示词见 [resume-prompt.md](resume-prompt.md)。

## 1. 修正对应表

源码路径均位于 `workbench-ui/src/authoring/`。自动化覆盖真实 DOM、布局、引擎录制结果和状态转换；
F12 的系统输入法候选行为仍需人工操作。

| 发现 | 实现与当前行为 | 源码／检查依据 |
| --- | --- | --- |
| F1 Split 共用滚动区域 | 中栏工作区与两个窗格限定高度；网格和源码各有内层滚动。源码 owner 定位只移动 CodeMirror 的滚动容器，保留网格位置和焦点。 | `authoring.css`、`AuthoringCodeEditor.tsx`；CodeMirror 祖先滚动回归；浏览器断言分栏并排、源码内层有滚动、网格位置与中栏 scrollTop 不变；[split-independent.png](screenshots/split-independent.png) |
| F2 失焦源码无高亮 | 独立 decoration 标出主 owner 与同声明次要 owner，不依赖编辑器选区或焦点。源码缓冲尚未提交时清除不可确认的范围。 | `AuthoringCodeEditor.tsx`、`AuthoringPanels.tsx`；主次范围更新／清除测试；浏览器在网格保持焦点时检查源码高亮可见 |
| F3 网格不在首屏 | 公式栏位于固定的中栏顶部，默认紧凑、聚焦后展开；网格使用剩余高度并在内部滚动。 | `AuthoringPrototype.tsx`、`authoring.css`；浏览器检查 1440×900 首行位于视口内、滚动网格不移动公式栏 |
| F4 未录制文字提交无说明 | 接受文字属性的源操作后立即按 Enter／Shift+Enter／Tab 规则返回网格并移动。未录制草稿保留源编辑；字段旁与预览状态说明没有引擎预览，保存保持禁用。公式仍在匹配的技术有效预览后完成导航。 | `AuthoringPrototype.tsx`、`AuthoringInspector.tsx`；未录制标签与导航组件测试、浏览器未录制文字步骤 |
| F5 Style 面板 | 去掉自由 class 文本框。读取当前 layout 的 `:style-preset` 与本地 `style-class`，使用带出处的预设表提供完整选择；包括 `normal`、`subtle`、`highlight`、`variance`。按源顺序显示芯片，删除／添加后显式 Apply；最终 Weight／Tone／Fill 直接取所选 Paper 单元格。 | `AuthoringStylePanel.tsx`、`styleVocabulary.ts`、`stylePresets.json`；完整预设、局部声明、未知标签、只读／多选测试；[class-style.png](screenshots/class-style.png) |
| F6 未变行进入差异 | 真实逐行匹配生成分离 hunk，保留 CRLF 与文件末尾换行差异；按可达源历史给每个 hunk 标注来源操作。已保存或外部重定基准前的历史不混入当前来源。 | `sourceDiff.ts`、`AuthoringPanels.tsx`；分离编辑、重复行、换行、已保存／重定基准／撤销来源测试；浏览器检查未改声明不出现在新增／删除中 |
| F7 运行时失败漏计数 | Problems 与 Errors 包含技术错误和运行时错误；另列 Runtime failures，业务 Findings 继续独立统计。 | `AuthoringPrototype.tsx`、`AuthoringDrawer.tsx`；真实录制运行时诊断组件测试与浏览器断言 |
| F8 错误标记说明不符 | 当前运行时失败标记朗读当前预览；冲突保留旧版运行时结果时明确属于 previous source revisions；无效草稿等保留有效结果时朗读上一有效预览。显示文本继续来自录制 Paper。 | `AuthoringGrid.tsx`；当前／旧修订运行时可访问名称测试与浏览器检查 |
| F9 Inspector 通道固定 | Example input 标签显示独立通道；不可编辑 owner 显示 Read-only 与具体原因；可编辑模板属性显示模拟模板定义通道。 | `AuthoringInspector.tsx`；示例输入／只读 owner 组件测试与浏览器检查 |
| F10 窄屏堆叠 | 小屏 Outline 改为可关闭覆盖层，Grid／Source 为分段视图，Inspector 为全屏覆盖层。关闭 Inspector 返回原单元格；窄屏 Split 退为 Grid，扩大视口后恢复 Split；公式编辑在 Inspector 内只有一个 CodeMirror 实例。 | `AuthoringPrototype.tsx`、`authoring.css`；窄屏覆盖层与公式取消测试；浏览器检查 390 px 首屏网格、无横向溢出、关闭后焦点；[narrow-390.png](screenshots/narrow-390.png)、[narrow-inspector.png](screenshots/narrow-inspector.png) |
| F11 Outline 扁平 | 按面板分组，包括面板标题 owner；共享定义与模板资源分开。隐藏、零值解释、条件不活跃原因来自录制 Structure／Paper／Run，并保留其他面板标识。 | `AuthoringOutline.tsx`；浏览器检查 Allocation conservation 及其 section owner 归属；不根据节点 id 计算可见性 |
| F12 浏览态输入法无法启动 | 首个 keyCode 229 同步打开对应编辑器并移交焦点，不 preventDefault、不提交字符、不导航；组合期继续由输入框接收。只读 owner 朗读原因。 | `AuthoringGrid.tsx`、`AuthoringPrototype.tsx`；网格／标签／公式合成事件及同步焦点测试；浏览器合成启动检查。**真实拼音输入法待人工验证** |
| F13 Escape 关闭 Drawer | 单个网格单元格的 Escape 不动作；矩形多选仍缩为单个单元格，不再调用 Drawer 关闭行为。 | `AuthoringGrid.tsx`、`AuthoringPrototype.tsx`；网格与原型组件测试；浏览器检查 Drawer 内容保持不变 |
| F14 构建报告基准不清 | 标题改为 `ExcelExport report for the recorded base state`，说明报告属于原始录制 base，并非当前模拟保存修订。 | `AuthoringPanels.tsx` `BuildPanel`；报告文案组件测试；[build-panel.png](screenshots/build-panel.png) |
| F15 冲突全文与当前预览 | 冲突对话框显示外部源的实际 hunk；冲突期间保留真实旧预览，并在网格／状态栏注明基于旧源修订。匹配旧基准的迟到结果仍不能标为 current；重定基准后等新修订的匹配预览。 | `AuthoringPanels.tsx`、`AuthoringPrototype.tsx`、`model.ts`；外部差异与冲突预览模型测试；[conflict.png](screenshots/conflict.png) |
| F16 禁用原因未关联 | 每个 Build／Publish／Open in Template Engine 按钮通过 `aria-describedby` 关联自己的不可用原因；没有新增成功路径。 | `AuthoringPanels.tsx`；按钮与对应原因组件测试及浏览器检查 |
| F17 原始 ISO 时间 | 恢复横幅使用浏览器 locale 的中等日期与短时间；无效时间显示 earlier session。 | `AuthoringPrototype.tsx` `recoveryTime`；浏览器确认恢复横幅无原始 ISO 时间；[recovery.png](screenshots/recovery.png) |
| F18 文件接近上限 | Inspector、Drawer、Outline 分成独立组件，保留外壳的状态与交互协调；样式选择与逐行差异也各自分离。 | `AuthoringInspector.tsx`、`AuthoringDrawer.tsx`、`AuthoringOutline.tsx`；ESLint 的 1200 非空行门禁检查 |
| S1 恢复运行时失败无法保存 | 第一次与当前草稿序号及完整基准修订集匹配的 `valid` 或 `runtimeFailure` 响应解除 restored。invalid／unrecorded／迟到或不同修订的响应不能解除；运行时失败不替换上一有效预览证据。 | `model.ts`；24 项模型测试包含恢复有效／运行时、错误修订、迟到无效响应；浏览器刷新并恢复运行时失败草稿后检查 Save 可用 |

样式预设表来源为 [dsl-reference §3.4](../../dsl-reference.md#34-reusable-style-classes)，界面注明出处与 layout 源位置。
词汇解析只读取声明，不在前端求值样式选择器；规则来源链仍等待正式接口，不能从 class 芯片推断校验通过。
S2 的 Inspector 文字编辑继续作为原型接受项，S3 的多行 class 分配继续延后，未宣称已实现。

## 2. 本轮验证

`npm run check` 已通过，完整前端测试 **40 个文件、347 项通过**。
真实 Chrome 检查为作者原型 **15 个流程**、原工作台 **13 个流程**，共 **28 个**，全部通过且完成任务资源清理。
fixture／live 构建、源摘要漂移与边界检查均通过：fixture 97 modules，live 77 modules／5 个产物文件，
live 无 AuthoringPrototype chunk、`mantra.authoring-recording/1` 标识或 fixtures 目录；32 状态摘要与当前 pattern 一致。
完整结果以 [实施记录的验证部分](implementation-progress.md#validation) 为准，避免把历史 `69ab8ff` 检查当作本轮重跑。
本轮截图清单为 13 张，包含更新后的 11 种原有状态、独立 Split 与窄屏 Inspector。

录制 `recording.json`、pattern 与 JVM 引擎未修改。Claude 在初次复核中独立重录 32 状态并证明响应真实性，
这一结论继续作为历史证据；本轮重新执行源摘要漂移检查，不将未再次重录写成已重录。

## 3. Claude 再次复查

按 [继续提示词](resume-prompt.md) 同步 PR #15 最新已推送 HEAD，在该提交上重跑门禁并逐项复现。
重点检查两窗格独立滚动、失焦 owner 高亮、首屏网格与固定公式栏、完整 class 选择、多个 diff hunk、
当前运行时错误说明、恢复运行时草稿可保存，以及窄屏覆盖层和返回焦点。
保存、fork、owner 与源修订冲突仍是模拟；正式 G-A1–G-A15、D-A1 文件事务策略、发布与 Template Engine
兼容性验证均未由本轮替代。两个 Draft PR 保持待评审。
