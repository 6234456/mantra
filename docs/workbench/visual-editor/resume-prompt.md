# Claude Code continuation prompt

使用说明：恢复本地 Claude Code 会话后，可复制下方正文。设计文档已完成，Codex 已实现录制引擎原型；
本轮任务是按远端提交复核，不再从阶段 0 重写设计。历史基线与检查点见 [README](README.md)，
当前启动、证据与限制见 [实施记录](implementation-progress.md)。

---

继续 Mantra 可视化 DSL 编辑器与 Template Engine 并行使用任务。按约定，你负责设计与实现复核，
Codex 负责编码。先确认并同步 Mantra Draft PR #15 的最新提交，再按 `prototype.md §11` 复核原型。

## 恢复与保护已有工作

- Mantra：`/Users/qiouyang/Documents/Claude/Codes/mantra`，任务分支
  `codex/mantra-design-handoff-20261010`，Draft PR：https://github.com/6234456/mantra/pull/15。
  先读取状态、远端和最新提交，在干净分支正常 fast-forward；保留已有文件、暂存内容与分支，不 reset、clean 或强推。
  `b27e2f5` 是你的设计交付提交，`9155663` 是 Codex 第一段已推送的代码检查点，均是历史定位信息，
  不把它们当作 PR 当前 HEAD。
- 云端编码使用独立检出 `/workspace/.cloud-setup/mantra-checkpoint-repo`，没有清理旧 `/workspace/mantra`
  工作区；本地复核以远端提交为准。
- Template Engine：`/Users/qiouyang/Documents/Claude/Codes/template_engine`，Draft PR：
  https://github.com/6234456/paramita-v2/pull/1，任务分支 `codex/mantra-parallel-use-20261010`。
  此轮 Codex 未修改该仓库。其主工作区可能仍有另一会话的改动；重新核对并保持原样，遵守不创建或切换 worktree 的规则。

先读 Mantra `CLAUDE.md`、`docs/workbench/visual-editor/implementation-progress.md`、`prototype.md`，
再按需要补读 `design-spec.md`、`state-matrix.md` 与 `implementation-plan.md`。Template Engine 的设计文档已交付，
只在复核并行使用约定时读取 `.agentdocs/index.md` 与 `.agentdocs/frontend/mantra-derived-template-parallel-use.md`。

## 验证实现与真实性

1. `npm --prefix workbench-ui ci`，`npm --prefix workbench-ui run dev`，打开
   `http://localhost:5173/authoring`。这是 fixture 入口；live 构建不包含原型。现有工作台入口仍为 `/`。
2. 按 `prototype.md §9` 逐步操作，并检查 `screenshots/` 的 11 张实测图。
   在模拟保存与外部修改后，测试 Example input 的 `9`／`nine` 前必须使用
   `Reset prototype to recorded base`；仅撤销不能恢复初始保存基准。
3. 运行 `npm --prefix workbench-ui run check`、`npm --prefix workbench-ui test`、
   `npm --prefix workbench-ui run build`、`python3 scripts/check-boundaries.py`，以及
   `npm --prefix workbench-ui run authoring:record:check`。
   查验全部现有测试保留，新增测试覆盖行为与竞态，不能只断言渲染成功。
4. 有已安装 Chrome 时运行：

   ```sh
   MANTRA_TEST_CHROME=/path/to/installed/chrome npm --prefix workbench-ui run test:authoring:e2e
   ```

   脚本使用专属临时 profile 并在退出时清理；云端已有 12 个作者 CDP 场景及 13 个原工作台浏览器流程通过。
   合成 composition/keyCode 守卫不能替代真实中文输入法：用拼音 IME 人工复核候选 Enter、
   第二次 Enter 提交与焦点，不安装浏览器 bundle。
5. 抽查 32 个录制状态、277 个完整响应 blob、源摘要与引擎／契约版本。数值、Paper、诊断与 Explain
   必须来自真实录制响应；未录制草稿显示无引擎预览。检查未知符号精确范围、业务 finding 可保存、
   运行时失败身份、过期响应与过期示例预览不得覆盖新草稿。
6. 检查源码缓冲、500 ms 空闲事务、逐字节 Undo／Redo、模拟 CAS 与外部冲突、重新预览、恢复记录正确的
   已保存基准，以及丢弃后迟到写入不能复活草稿。重新预览与保存成功后不得错误保留旧 Conflict 状态。

## 复核边界与输出

原型的 owner、源补丁、fork、保存与冲突均为模拟；保存不写源文件。Build、Publish 与
`Open in Template Engine` 禁用，只展示真实录制的现有 ExcelExport 报告。示例输入仅在原始录制保存基准且无模板草稿时启用。
不要把这些结果写成正式模板草稿 API、多文档事务、发布能力或已验证 Template Engine 兼容性。

G-A1–G-A15 仍需契约与后端实施；D-A1 的正式多文档原子性尚未决定，本轮模拟源集合更新没有替它选定文件保存策略。
保留 shared DSL → 编译／预览／构建 → Mantra 原生与派生 Excel 分叉的架构，以及 Template Engine 独立的纯 Excel 作者路径。
本轮继续用 Mantra HTML table；不因本地 TS 源码无依赖就宣称 Template Engine 的 React 18 workspace UI 是可安装 React 19 SDK。

提交复核结果：列出已复现行为、真实 IME 结果、设计与实现的具体差异及问题的操作步骤／源位置。
确认通过后才将 `prototype.md §11` 改为实际复核结果；不要将 Codex 自动检查当作你的人工签收。
修复或文档调整只加入本任务文件，及时提交、正常推送同一 Draft PR，保留另一会话工作区。
