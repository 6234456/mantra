# 可视化 DSL 编辑器设计交付：进度与恢复记录

> 状态：**进行中（阶段 0 完成）**。本文只记录可复现基线、已确认的设计决定和未完成项，
> 不声明可视化模板编辑器、owner 句柄、模板源 draft Paper 或发布能力已经实现。已有案例操作的
> 候选 Paper 与公式编辑交互补充见 [逻辑实施记录](interaction-progress.md)。任务来源见
> [Claude Code 设计交接](../claude-code-design-prompt.md)。

## 1. 可复现基线（2026-10-10）

| 仓库 | 状态 |
| --- | --- |
| Mantra `6234456/mantra` | 本地 `main` = `origin/main` = `6cfe83c`，工作区干净。任务分支 `codex/mantra-design-handoff-20261010` 自 `be0ca66` 起（比 `main` 多 `fd05711`、`be0ca66` 两个提交），已在主检出中跟踪远端。开始时没有相关 PR（仅 dependabot #1–#14）。`~/.codex/worktrees` 下其他分支未触碰。 |
| Template Engine `6234456/paramita-v2` | 本地 `main` = `origin/main` = `6443c688`。主检出有其他会话的未提交改动（`.agentdocs/index.md`、`workflow/261010-markdown-editor-support.md` 修改，`frontend/markdown-editor-design-spec.md` 与 `workflow/evidence/261010-markdown-editor-design/` 未跟踪），保持原样。仓库没有 PR。其 `.agentdocs/index.md` 规定不创建或切换 worktree，因此计划用 plumbing 提交（临时 index + `commit-tree`）建立任务分支，不改动主检出。 |

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
- **键位**：编辑网格采用 Template Engine `resolveSheetKeyboardShortcut` 的 Excel 语义
  （Enter 下移、F2 或直接输入开始编辑、IME 组合期间不触发快捷键）。与工作台 ui-spec §6
  “Enter 编辑”的差异需要在设计稿中记录并请维护者确认。
- **复用依赖闭包**（以 `6443c688` 源码核对）：`sheet-keyboard-shortcut.ts`、
  `keyboard-input-session.ts`、`focus-boundary.ts` 和 `theme-tokens` 没有依赖；
  `ApplicationStatusPill`／`OfficeSidePaneShell` 经 `OfficeActionIcons` 依赖 `@fluentui/react-icons`
  与 `--te-*` 主题变量；剪贴板 transport 依赖 `formula-engine` 和 `office-model-v2`；
  `CellEditorOverlay` 内置 Excel 补全，宿主补全不能替代它；所有包都声明 React 18 peer、
  `workspace:*` 依赖且入口为 TS 源码。首版建议使用 Mantra 自己的 DOM `PaperTable`，不引入 Canvas。

## 3. 未完成项

1. `design-spec.md`：模板入口、双视图、属性与公式面板、输入／Explain、源差异、保存与构建反馈、
   首次使用、键盘／IME／剪贴板／撤销／焦点返回／窄屏，以及与 Template Engine 并行使用的约定。
2. `state-matrix.md`：生命周期状态、七类通用状态 × 区域、权限矩阵，以及诊断／finding／人工核准的区分。
3. `implementation-plan.md`：组件复用清单、设计 token、接口缺口（owner 句柄、源补丁、draft Paper、
   样式来源、构建记录、发布）和分步实施计划。
4. 原型：`workbench-ui/src/authoring/`、生成脚本、测试、启动步骤、操作脚本和截图；
   导出／发布按钮不得假成功。
5. Template Engine 侧文档 `.agentdocs/frontend/mantra-derived-template-parallel-use.md` 及索引条目
   （用 plumbing 提交，不触碰他人未提交的改动）。
6. 两个仓库各自的 draft PR（尚未创建）。
