# Claude Code continuation prompt

使用说明：设计交付与初次复核已完成，Codex 已按 F1–F18／S1 修正原型。
本轮请对 Mantra PR #15 最新已推送 HEAD 再次复查，重点验证你在 `69ab8ff` 上发现的问题；
不要重做设计基线，也不要将 Codex 的自动检查视作 Claude 的人工签收。
修正映射见 [prototype-fixes.md](prototype-fixes.md)，启动与检查证据见 [实施记录](implementation-progress.md)。

---

继续 Mantra 可视化 DSL 编辑器与 Template Engine 并行使用任务。按约定，你负责设计文档与复核，
Codex 负责编码。你已在 `92664ae` 推送对 `69ab8ff` 的初次复核；现在按同一 Draft PR 的最新提交复查修正。
修正代码检查点是已推送的 `dc01609`；其后文档提交不改变源码，仍应记录并读取 PR 最新已推送 HEAD。

## 恢复与保护已有工作

- Mantra：`/Users/qiouyang/Documents/Claude/Codes/mantra`，任务分支
  `codex/mantra-design-handoff-20261010`，Draft PR：https://github.com/6234456/mantra/pull/15。
  先读取状态、远端和最新提交，在干净分支正常 fast-forward；保留已有文件、暂存内容与分支，不 reset、clean 或强推。
  记录并按最新已推送 HEAD 复查，不按工作区或历史 `69ab8ff` 复查。
- 云端编码使用独立检出 `/workspace/.cloud-setup/mantra-checkpoint-repo`，旧 `/workspace/mantra` 工作区保持原样。
- Template Engine：`/Users/qiouyang/Documents/Claude/Codes/template_engine`，Draft PR：
  https://github.com/6234456/paramita-v2/pull/1，任务分支 `codex/mantra-parallel-use-20261010`。
  本轮未修改该仓库；另一会话的工作区与真实 index 保持原样，遵守不创建或切换 worktree 的规则。

先读 `CLAUDE.md`、`docs/workbench/visual-editor/prototype-review.md`、`prototype-fixes.md`、
`implementation-progress.md` 和 `prototype.md`。按需补读 `design-spec.md`、`state-matrix.md`。
本轮不扩展 G-A1–G-A15，也不替正式多文档文件事务选择 D-A1 策略。

## 复查实现与交互

1. `npm --prefix workbench-ui ci`，`npm --prefix workbench-ui run dev`，打开
   `http://localhost:5173/authoring`。这是 fixture 入口；live 构建不包含原型，原工作台仍为 `/`。
2. 在 1440×900 检查 F1–F3：首屏即见网格行，公式栏固定且聚焦展开；Split 的两窗格独立滚动。
   保持网格焦点选择 owner，源码有可见的主／次高亮，不能把整栏滚走或抢焦点。
3. 按 `prototype.md §9` 再走标签、class、无效公式、finding、迟到预览、冲突、运行时失败与恢复。
   未录制文字的源操作被接受后应完成导航并说明无预览；Source changes 的多个 hunk 不能把未变声明列为新增／删除。
   冲突显示外部 hunk，旧预览明确基于旧修订；重定基准后才接受新修订的当前预览。
4. 检查 F5：Style 使用源顺序 class 芯片。移除 subtotal，Add class → result，显式 Apply。
   可选词汇来自 layout 声明的 preset／本地 style-class；utilities 的 normal／subtle／highlight 与
   working-paper 的 variance 都存在且注明出处。最终 Weight／Tone／Fill 直接取 Paper，不从芯片推算。
   本原型的 capped-allocation 没有本地 style-class；局部声明与未知标签的行为另有测试，必要时用未录制源草稿抽查。
5. 检查 F7–F9、F14–F17：运行时失败计入 Problems／Errors，标记说明显示当前结果；Example input 与只读
   owner 的通道标识变化。恢复时间本地化；恢复出的运行时失败草稿首个匹配技术有效预览后也能模拟保存。
   Build 报告注明 recorded base，三个禁用按钮分别关联原因；不能显示新构建／发布／下游兼容成功。
6. 在 390 px 检查 F10：首屏网格、Outline 可关闭覆盖层、Grid／Source 分段、全屏 Inspector；关闭后返回原单元格。
   桌面 Split 变窄后退为 Grid，扩大后恢复；窄屏 F2 公式编辑只有一个 CodeMirror，Escape 取消并正确返回。
   检查 13 张最新实测截图，新增图为 split-independent.png 与 narrow-inspector.png。
7. 检查 F12–F13：合成 keyCode 229 已覆盖同步移交焦点，单格浏览态 Escape 不关闭 Drawer。
   **真实中文拼音输入法仍需人工操作**：首个组合输入能进入字段，候选 Enter 只确认，第二次 Enter 提交。
   若你无法操作真实输入法，明确标为待用户人工验证，不能把合成事件写成真实系统 IME 通过。
8. 示例输入 `9`／`nine` 前使用 `Reset prototype to recorded base`。先前模拟保存或外部修改后，仅撤销不能恢复初始保存基准。

## 检查与真实性

运行 check、test、fixture 与 live 构建、边界检查及录制摘要漂移检查：

```sh
npm --prefix workbench-ui run check
npm --prefix workbench-ui test
npm --prefix workbench-ui run build
VITE_WORKBENCH_MODE=live npm --prefix workbench-ui run build
python3 scripts/check-boundaries.py
npm --prefix workbench-ui run authoring:record:check
```

本轮代码检查点实测 40 个文件／347 项测试通过，作者浏览器 15 个、原工作台 13 个流程通过，共 28 个；
fixture／live 构建、边界与 32 状态漂移检查均通过。live 产物不能包含原型或录制数据；
原有 204 项测试保持通过，复查数量以你当前运行输出为准。
`App.tsx` 保持不变，现有入口行为应不变。已有安装 Chrome 时可运行作者和原工作台浏览器脚本：

```sh
MANTRA_TEST_CHROME=/path/to/installed/chrome npm --prefix workbench-ui run test:authoring:e2e
MANTRA_TEST_CHROME=/path/to/installed/chrome npm --prefix workbench-ui run test:e2e
```

使用专属临时 profile，检查退出清理；不安装浏览器 bundle。浏览器脚本结果和完整门禁见实施记录。

你在初次复核中独立重录 32 状态，确认 277 个完整响应 blob、307 个响应除执行间 eventId 外一致。
本轮 recording.json、pattern 和 JVM 引擎没有修改；检查提交差异与摘要漂移即可，布局修正不要求重复生成录制文件。
如确有数据变更，才重新核对真实服务响应。任何数值、Paper、诊断、Explain 均须来自真实录制，未录制草稿不合成结果。

## 复核边界与输出

owner、源补丁、fork、保存与冲突都是模拟，保存不写模板文件。Build／Publish／Open in Template Engine 仍禁用；
报告属于原始录制 base，不能证明 Template Engine 或 Excel 重算兼容。正式接口与发布、D-A1 文件原子性仍未决定。
S2 的 Inspector 文字编辑作为原型接受项保留，S3 多行 class 分配延后。

保留 shared DSL → 编译／预览／构建 → Mantra 原生与派生 Excel 分叉架构，以及 Template Engine 独立纯 Excel 作者路径。
本轮仍为 Mantra HTML table，没有引入 Office Canvas、React 18 workspace UI 包或新 npm 依赖。

输出独立的再次复核记录：写明提交 SHA、门禁结果，按 F1–F18／S1 列出通过或具体剩余问题及操作步骤／源位置。
保留 prototype-review.md 的初次评估；不要把编码方修正说明改成你的签收。真实 IME 另列实测或未测。
确认通过后才更新 prototype.md §11／README 的人工评审状态；仅加入本任务文档，及时提交、正常推送同一 Draft PR。
