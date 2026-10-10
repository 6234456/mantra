# 可视化 DSL 编辑器设计交付：进度与恢复记录

> 状态：**设计交付完成，录制引擎原型已实现，待 Claude 按提交复核（2026-10-10）**。
> 原型入口为 fixture 模式的 `/authoring`；保存、fork、owner 与源修订冲突均为模拟，未写入模板文件。
> 当前实现、启动步骤与证据见 [实施记录](implementation-progress.md)，复核任务见
> [继续提示词](resume-prompt.md)。正式模板 API 与发布能力仍属于接口缺口。
> 已有案例操作的候选 Paper 与公式编辑交互补充见 [逻辑实施记录](interaction-progress.md)。
> 任务来源见 [Claude Code 设计交接](../claude-code-design-prompt.md)。

## 1. 可复现基线（2026-10-10）

| 仓库 | 状态 |
| --- | --- |
| Mantra `6234456/mantra` | 本地 `main` = `origin/main` = `6cfe83c`，工作区干净。任务分支 `codex/mantra-design-handoff-20261010` 自 `be0ca66` 起（比 `main` 多 `fd05711`、`be0ca66` 两个提交），已在主检出中跟踪远端。开始时没有相关 PR（仅 dependabot #1–#14）。`~/.codex/worktrees` 下其他分支未触碰。 |
| Template Engine `6234456/paramita-v2` | 本地 `main` = `origin/main` = `6443c688`。主检出有其他会话的未提交改动（`.agentdocs/index.md`、`workflow/261010-markdown-editor-support.md` 修改，`frontend/markdown-editor-design-spec.md` 与 `workflow/evidence/261010-markdown-editor-design/` 未跟踪），保持原样。仓库没有 PR。其 `.agentdocs/index.md` 规定不创建或切换 worktree，因此计划用 plumbing 提交（临时 index + `commit-tree`）建立任务分支，不改动主检出。 |

**恢复时状态（2026-10-10）**：任务分支按 fast-forward 同步到 `682bd2c`，`origin/main` 也已指向
`682bd2c`，即 `main` 已包含此前全部任务提交；后续 Draft PR 只含恢复后的新提交。远端新增的
`preview-paper` 能力与公式草稿修复见 [逻辑实施记录](interaction-progress.md)。Template Engine 仍为
`main` = `origin/main` = `6443c688`，主检出另一会话的未提交改动已扩大到应用与包源码，继续保持原样，
真实 index 无暂存内容。

**Codex 编码恢复（2026-10-10）**：在云端独立检出
`/workspace/.cloud-setup/mantra-checkpoint-repo` 中，将任务分支正常 fast-forward 到 Claude 文档提交
`b27e2f5`，保留旧 `/workspace/mantra` 的检出与未提交文件。原型第一段检查点 `9155663`
已提交并推送到同一任务分支，包含真实录制器、录制响应、模拟源服务、状态模型与恢复逻辑。
界面、组件测试与 11 张截图已保存于 `aa5e14a`；本记录沿用
[Mantra Draft PR #15](https://github.com/6234456/mantra/pull/15)，
当前集成检查结果见 [实施记录的验证部分](implementation-progress.md#validation)。
本次未修改 Template Engine 源码或其另一会话工作区。

本机工具链：Zulu JDK 21.0.12、Node 25.2.1、npm 11.6.2。
`./gradlew --no-daemon --offline :mantra-cli:installDist` 在任务分支上构建成功。
基线检查：`workbench-ui` 的 `npm run check` 通过；`npm test` 28 个文件、158 项通过；
`python3 scripts/check-boundaries.py` 通过。

## 2. 已确认的设计决定

- **首版 scalar panel**：`docs/patterns/capped-allocation` 的 `controls`（Allocation conservation）。
  它有 section 标题、6 个带 `:class` 的计算项、note、公式、`:style-preset [:utilities :working-paper]`、
  tiered 表，以及引擎标记 `explains-zero` 的零值行，可演示被零值抑制的定义仍可定位。
  该版式没有 `style-class`／`:use`；设计稿用 `actuals-vs-baseline` 版式说明这两者的生效来源链。
- **原型数据来自真实引擎**：生成脚本对 `docs/patterns` 的临时副本施加脚本化补丁（标签、class、
  公式拼写错误、引起业务 finding 的公式、外部 layout 修改），每个组合启动一次
  `mantra serve`，录制真实 `structure`／`run`／`paper`／`diagnostics`／`explain` 响应。
  已验证：无效草稿时 live 接口返回 422 和带 `startOffset`／`endOffset` 的结构化诊断。
  前端模拟服务按文档集摘要查找录制结果；未录制的草稿显示“无引擎预览（接口缺口）”，不伪造 Paper。
- **键位（维护者 2026-10-10 更正）**：以 Mantra [ui-spec §6](../ui-spec.md) 为默认：浏览态 Enter
  或 F2 进入编辑；单行编辑态 Enter 提交，成功后下移，失败保留草稿与焦点；Shift+Enter 成功后上移；
  多行公式／文本 Enter 换行、Ctrl/Cmd+Enter 提交；补全菜单先消费 Enter；IME 组合期间不提交、不导航；
  浏览态 Tab 离开表格。Template Engine 的 Excel 导航只作为后续可选方案记录，不改变现有工作台。
- **复用依赖闭包**（以 `6443c688` 源码核对）：`sheet-keyboard-shortcut.ts`、
  `keyboard-input-session.ts`、`focus-boundary.ts` 和 `theme-tokens` 没有依赖；
  `ApplicationStatusPill`／`OfficeSidePaneShell` 经 `OfficeActionIcons` 依赖 `@fluentui/react-icons`
  与 `--te-*` 主题变量；剪贴板 transport 依赖 `formula-engine` 和 `office-model-v2`；
  `CellEditorOverlay` 内置 Excel 补全，宿主补全不能替代它；两个 UI 包声明 React 18 peer；
  各包入口都是 TS 源码，多数依赖 `workspace:*`，均未发布到 registry。首版建议使用 Mantra 自己的 DOM `PaperTable`，不引入 Canvas。

## 3. 交付进度

- [x] [design-spec.md](design-spec.md)：页面流程、双视图、属性／公式／样式面板、Explain 与示例输入、源差异、
  保存与构建反馈、首次使用、窄屏、键盘／IME／剪贴板／撤销／焦点返回、可访问性，以及与 Template Engine
  并行使用的约定。
- [x] [state-matrix.md](state-matrix.md)：状态维度、生命周期与转换、七类通用状态 × 区域、权限矩阵，以及诊断／finding／人工核准的区分。
- [x] [implementation-plan.md](implementation-plan.md)：能力现状、组件复用清单与前置条件、设计 token、接口缺口
  G-A1–G-A15、分步实施与待决策项。
- [x] [prototype.md](prototype.md)：录制数据原型的实施说明（录制脚本、模拟服务、状态模型、键盘、测试）、
  操作脚本、截图清单与复核清单。维护者 2026-10-10 决定由 Codex 编码、Claude 复核。
- [x] 原型代码：`workbench-ui/src/authoring/`、32 状态录制脚本、状态／服务／恢复／组件测试、Chrome CDP
  操作脚本及 11 张截图。入口、能力边界与实际结果见 [implementation-progress.md](implementation-progress.md)。
- [x] Claude 复核 `69ab8ff`：[prototype-review.md](prototype-review.md)。门禁与录制真实性通过；交互布局与样式面板的
  F1–F18 待 Codex 修正后再次复核；真实中文拼音输入法仍需人工操作。
- [x] Template Engine 侧文档 `.agentdocs/frontend/mantra-derived-template-parallel-use.md` 与索引条目：
  任务分支 `codex/mantra-parallel-use-20261010` 的 `97ba1cc5` 与更正包事实的 `16f49196`，均以临时 index 与
  `commit-tree` 从基线 `6443c688` 的树生成，相对基线只含这两个文件；操作前后主检出的 HEAD、真实 index、工作区状态、其他分支与 stash 不变。
  Draft PR：[6234456/paramita-v2#1](https://github.com/6234456/paramita-v2/pull/1)。
- [x] Mantra Draft PR：[6234456/mantra#15](https://github.com/6234456/mantra/pull/15)。
