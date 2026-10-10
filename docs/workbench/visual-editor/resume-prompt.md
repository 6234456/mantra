# Claude Code continuation prompt

使用说明：恢复本地 Claude Code 会话后，可复制下方正文。`d091674` 是已核对的进度检查点；先核对最新远端与本地状态，不把历史基线当作当前状态。本文补充原[设计交接](../claude-code-design-prompt.md)，不将设计决定或原型写成已实现产品能力。

---

继续 Mantra 可视化 DSL 编辑器及其与 Template Engine 并行使用的 UI/UX 设计任务。上次因额度耗尽停在阶段 0，请从已有记录恢复，完成交付。

## 从已有检查点继续

- Mantra：`/Users/qiouyang/Documents/Claude/Codes/mantra`；任务分支 `codex/mantra-design-handoff-20261010`；已推送的进度提交为 `d091674`。先检查是否有更新，用正常 fast-forward 同步干净分支；保留所有已有修改，不 reset、clean、强推。
- Template Engine：`/Users/qiouyang/Documents/Claude/Codes/template_engine`；上次基线为 `6443c688`，主工作区有另一会话的 markdown-editor 改动。重新核对并保留这些文件、暂存内容、分支和 HEAD。
- 阅读 Mantra `CLAUDE.md`、`docs/workbench/claude-code-design-prompt.md`、`docs/workbench/visual-editor/README.md` 及 Template Engine 的 `.agentdocs/index.md`。只补读变动或当前阶段需要的代码，不重复整轮勘察。上次 CLI 构建、边界检查、158 项前端测试通过是历史基线；新变更运行相应检查。

保留已确定架构：Mantra 独立编辑器和源码视图编辑同一份原始 DSL；共享作者、编译、预览与构建流程，之后才分叉为 Mantra 原生和派生 Excel 两种交付。Template Engine 历史纯 Excel 作者路径独立。保持 DSL 公式、精确 Decimal、源 owner/修订、业务 finding 与技术失败、只读 package 和显式 writable fork 的边界。

## 首版默认交互

继续使用 capped-allocation 的 `controls` scalar panel；它的现有布局只包含 preset/class，`style-class`／`:use` 的来源链另用 actuals-vs-baseline 展示。继续用 Mantra 自己的 HTML table，先设计、再实现原型，不引入整套 Office Canvas 或 Session。示例差异属于 fixture，通用组件不得按这些 schema/node id 加入业务分支。

键盘以现有 Mantra `ui-spec.md` 为设计默认，不把 Excel 键位差异作为停工条件：浏览态 Enter 进入编辑，F2 也可进入编辑；单行编辑态 Enter 提交，成功后向下移动，失败保留草稿和焦点；Shift+Enter 成功后向上移动。多行公式/文本 Enter 换行，Ctrl/Cmd+Enter 提交；补全菜单先消费 Enter；IME 组合期间不提交或导航。浏览态 Tab 按现有规范离开表格。记录新作者模式与现有 PaperTable 实际行为的差异，勿全局改变现有工作台；Excel 导航方案可列作后续选项。

## 按阶段交付并保存

1. 在有实际提交的 Mantra 任务分支上，先创建或沿用 Draft PR，清楚标注当前仅基线/设计工作已完成、原型仍待实现。随后完成并提交 `docs/workbench/visual-editor/design-spec.md`，包含页面流程、双视图、属性/公式面板、源差异、目标构建反馈、首次使用、窄屏、键盘和可访问性。
2. 完成并提交同目录 `state-matrix.md`、`implementation-plan.md`，覆盖状态/权限、恢复草稿与正式源保存、冲突、上次有效预览、只读、finding/诊断/人工核准区分；列出实际复用依赖、设计 token、接口缺口与分步实施。
3. 实现 `workbench-ui/src/authoring/` 的可运行 fixture 原型及生成脚本、必要定向测试、启动说明、操作脚本和截图。沿用现有前端组织，清楚标注原型入口与模拟能力。每完成可检查的一小段就落盘、更新进度、commit、push 并更新 Draft PR，不等所有交付都完成才保存。
4. 完成 Template Engine `.agentdocs/frontend/mantra-derived-template-parallel-use.md` 与索引条目，描述并行导航、来源/版本/运行时、下游实例独立历史、兼容报告以及组件提取建议。在其独立任务分支有文档提交后创建 Draft PR，只提交本任务文件。

## 原型和复用的真实性

录制数据必须来自真实 Mantra 引擎。生成脚本在临时副本修改 schema/layout/示例输入，保存每个脚本状态的原始源文档、摘要、引擎/契约版本、请求及真实响应。保留精确值和诊断范围，检查响应与源快照对应；422 诊断本身不代表通用 owner/source-patch/draft-Paper API 已实现。

录制响应可以驱动已录制状态；未录制的编辑显示“无引擎预览”，不伪造数值或 Paper。界面说明这是录制数据原型，区分模拟 owner、冲突和保存状态；浏览器恢复草稿不能冒充已写回源文件。旧有效预览须显示过期，运行时失败保留失败身份，导出/发布不得假成功。检查共享修订下的过期响应、IME、错误后修复、冲突保留草稿等真实状态转换。

复用清单区分已验证接入、建议提取、暂不适用。无依赖源码不等于已有可发布共享包；复制模块须保留许可/出处并标明维护方式，提取模块须定义入口和版本/依赖边界。不要把 JSX/TS 源入口或 React 18 workspace 包当成可直接安装的 React 19 SDK。

Template Engine 不创建/切换 worktree、不触碰另一会话工作区。若文档提交采用临时 index + `commit-tree`：从明确基线的 Git tree 构建，仅加入任务内容；共享 `.agentdocs/index.md` 从基线 blob 生成任务条目，不能把脏工作副本整体加入。只更新任务分支 ref，核对提交 diff 和提交树中的文档链接；操作前后检查用户原工作区、真实 index、HEAD 均未变化。隔离 index 并不隔离验证环境，不能用脏工作副本的检查结果代替目标提交树的检查。不要因安全隔离要求丢掉其他会话的索引条目；后续合并时正常保留双方条目并处理冲突。

如网络/API 阻断 PR 创建，保留已推送分支和可用 PR 描述，记录具体错误、真实创建入口及下一步，继续设计；不得把链接或计划写成已创建 PR。接近额度上限时提前推送当前可恢复进度，记录下一项具体任务、文件和未完成检查。

完成时提供四项交付位置、实际启动/操作步骤、验证结果、两仓库真实 PR 链接或明确阻断、剩余接口缺口。不要将四项尚未完成的交付再汇总成一份空的进度报告。
