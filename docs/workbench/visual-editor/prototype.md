# 录制数据原型：Codex 实施说明、操作脚本与复核清单

> 状态：**录制引擎原型已实现并经 Claude 两轮复核：F1–F17 与 S1 通过，F18 部分达成（2026-10-10）**。
> 首轮评估对象为 `69ab8ff`，见 [复核记录](prototype-review.md)；本轮修正见 [prototype-fixes.md](prototype-fixes.md)。
> 维护者决定：Claude 负责文档与意图，
> Codex 编码，Claude 复核。启动、实际验证与截图见 [实施记录](implementation-progress.md)。
> 本文同时保留实施要求和复核脚本；实现的是 [设计规范](design-spec.md) 的 S1 交互，状态与权限遵循
> [状态矩阵](state-matrix.md)，缺口编号见 [实施计划 §4](implementation-plan.md#4-接口缺口)。
> 下文的引擎行为都已在 `682bd2c` 的安装版 CLI 上实测；如果实施时结果不同，以实测为准并更新本文。

## 1. 目标与边界

在 `workbench-ui` 中提供可运行的 fixture 原型，演示 capped-allocation `controls` 面板上的标签、class、公式编辑，
即时反馈、无效草稿与上一有效预览、业务 finding、运行时失败、源修订冲突、浏览器恢复草稿，以及 §4 的键盘规则。

- 所有数值、诊断、Explain 与 Paper 都来自录制的真实 `mantra serve` 响应。未录制的草稿显示
  `No engine preview for this draft (not recorded in this prototype)`，绝不合成数值或 Paper。
- owner 句柄、源补丁、保存、冲突与 fork 是前端模拟，界面与代码注释都必须标明 `simulated`。
- 模拟源集合按修订进行比较后整体更新，不是文件系统事务。D-A1 的正式多文档原子性策略仍未确定。
- 录制的 422 诊断范围证明现有引擎能给出精确范围；不代表正式模板 owner 或源补丁接口已存在。
  后续已实现的模板 draft Paper／Explain 属于独立 `/template-preview` 页的真实候选切片，本录制原型
  尚未接入；范围见 [契约 §7.1](../contract.md#71-操作) 与 [工程扩展记录](extension-progress.md)。
- 导出、构建、发布与 Template Engine 跳转按钮永不显示成功。

## 2. 约束

- 不新增 npm 依赖；沿用 React 19、CodeMirror 6、Vite、Vitest。
- `npm --prefix workbench-ui run check`、`npm --prefix workbench-ui test`、`npm --prefix workbench-ui run build`
  与 `python3 scripts/check-boundaries.py` 必须通过，现有 204 项前端测试不变。
- 边界检查会扫描 `workbench-ui/src` 中非测试 `.ts/.tsx/.js/.jsx/.mjs` 的全部标记（包括字符串）。节点 id、
  文档路径与脚本数据只放在 JSON 中，生产 TS 代码按数据驱动，不按 schema 或节点 id 分支（契约 W2）。
- ESLint：单文件最多 1200 非空行、120 列；Prettier 配置见 `workbench-ui/.prettierrc.json`。
- `src/ui/App.tsx` 已接近行数上限，不修改。入口在 `src/main.tsx` 按路径 `/authoring` 懒加载原型；
  只在 fixture 模式（`VITE_WORKBENCH_MODE !== 'live'`）编译此入口，live 构建不包含原型。
- CodeMirror 沿用 `meta[name="mantra-style-nonce"]` 的 CSP nonce 配置（见 `FormulaEditor.tsx`）。
- 界面文字先只提供英文；浏览器存储读写全部包在 try/catch 中，存储不可用时原型仍可运行。

## 3. 文件布局

| 路径 | 内容 |
| --- | --- |
| `workbench-ui/scripts/record-authoring-prototype.mjs` | 录制脚本（§4），Node 内置模块，无新依赖 |
| `workbench-ui/src/authoring/recording/recording.json` | 录制结果，提交到仓库 |
| `workbench-ui/src/authoring/AuthoringPrototype.tsx` | 外壳：身份栏、Outline、网格／源码、Inspector、Drawer、状态栏、原型控制 |
| `workbench-ui/src/authoring/AuthoringInspector.tsx`、`AuthoringDrawer.tsx`、`AuthoringOutline.tsx` | 从外壳分离的 Inspector、Drawer 与按面板分组的 Outline |
| `workbench-ui/src/authoring/AuthoringStylePanel.tsx`、`styleVocabulary.ts`、`stylePresets.json` | class 芯片、layout 声明词汇与带出处的完整预设表；最终样式读取 Paper |
| `workbench-ui/src/authoring/sourceDiff.ts` | 保留源行结束符的多 hunk 差异及可达历史的操作来源 |
| `workbench-ui/src/authoring/model.ts` | 纯 reducer：草稿序号、源历史、预览与保存状态（§6） |
| `workbench-ui/src/authoring/recovery.ts` | 校验浏览器恢复记录、基准文档摘要、修订与可重放历史；不恢复预览证据 |
| `workbench-ui/src/authoring/service.ts` | `AuthoringService` 接口，按未来契约形状定义 |
| `workbench-ui/src/authoring/simulated/recordedService.ts` | 由录制数据驱动的实现，模拟句柄、补丁、保存与冲突（§5） |
| `workbench-ui/src/authoring/simulated/sourceOwners.ts` | 模拟 owner 扫描器（§5.2） |
| 其余组件 | 网格、公式字段、Inspector、源码视图、Problems／Changes／Build Drawer，按需拆分以满足行数限制 |
| `workbench-ui/src/raw-text.d.ts` | 增加 `*.mantra?raw` 声明，供漂移测试读取 `docs/patterns` 原文 |
| `workbench-ui/src/ui/PaperTable.tsx` | 只导出现有 `cellAppearance`，供作者网格复用；行为不变 |
| `workbench-ui/README.md` | 英文 `Authoring prototype` 小节：启动、录制、限制 |
| `docs/workbench/visual-editor/screenshots/` | §9 的截图 |
| `workbench-ui/scripts/authoring-browser-e2e.mjs` | 已安装 Chrome 的 CDP 操作脚本、截图与任务进程清理 |

## 4. 录制脚本

### 4.1 前置与启动

1. 仓库根目录 `./gradlew --no-daemon :mantra-cli:installDist`；`npm --prefix workbench-ui run build` 生成
   `workbench-ui/dist`。
2. 对每个状态：复制整个 `docs/patterns` 到新的临时目录，施加编辑，执行
   `mantra serve <临时目录> --port 0 --ui <仓库>/workbench-ui/dist --directory-policy trusted-local`，
   从输出 `at http://127.0.0.1:<port>/` 解析端口。
3. 没有 `--ui` 时 `GET /` 返回 404，拿不到 `mantra-session-token`，POST 会被拒绝。
4. 每个状态结束后停止进程（SIGTERM，10 秒后 SIGKILL）并删除临时目录。

### 4.2 参与文档与编辑

参与文档：`capped-allocation/case-demo.mantra`、`capped-allocation/layout.mantra`、
`capped-allocation/schema.mantra`、`common/formulas.mantra`。每个编辑都是在单个文档中只出现一次的精确替换；
不匹配或多次匹配时脚本失败。原型的模拟补丁必须产生逐字节相同的文本。

| 编辑 | 文档 | 查找 | 替换为 | 实测结果 |
| --- | --- | --- | --- | --- |
| `label` | schema | `(info unallocated "Request not allocated"` | `(info unallocated "Unallocated request"` | 仅标签变化 |
| `class` | schema | `(dim/sum all.unused-capacity) {:class :subtotal}` | `(dim/sum all.unused-capacity) {:class :result}` | 行样式变为 `.result`，数值不变 |
| `formula:typo` | schema | `(- request allocated-total) {:class :result}` | `(- request allocated-totl) {:class :result}` | 422：`MANTRA-FORMULA` `DSL-REF-UNKNOWN-SYMBOL`，范围恰为 `allocated-totl`；同 owner 另有 `DSL-TYPE-CALL-ARGUMENT` |
| `formula:finding` | schema | 同上 | `(- request total-capacity) {:class :result}` | 200：`unallocated` 显示 `(60.00)`；`conserved` `✗`；`MANTRA-RECONCILE-FAILED` |
| `formula:runtime` | schema | 同上 | `(/ request 0) {:class :result}` | 200 且 `succeeded: false`：`MANTRA-EVALUATION` `DSL-RUNTIME-DIVIDE-BY-ZERO`，`unallocated` 显示 `undefined`；`conserved` 另有运行时诊断 |
| `external` | layout | `:title "Capacity and conservation"` | `:title "Capacity, allocation and residue"` | Paper 标题变化 |

`(decimal/divide request 0 2)` 的结果是零，不会产生运行时失败，不能用来演示该状态。
`mantra fixtures` 在无效草稿上抛异常，`mantra check` 的文本输出没有结束偏移；因此统一使用 live 服务录制。

### 4.3 状态组合

`label` × `class` × 公式（无、`typo`、`finding`、`runtime`）× `external`，共 32 个状态。
状态 id 为按上表顺序以 `+` 连接的编辑名，无编辑时为 `base`。

### 4.4 每个状态的请求

全部记录请求方法、路径、请求体与响应状态码及完整响应体：

1. `GET /api/v1/workspace`
2. `GET /api/v1/cases/{case}/structure`、`/run`、`/paper`、`/diagnostics`（无效状态返回 422 并记录错误体）
3. Structure 有效时，对 `controls` 面板的每个节点 `GET …/explain?address=<node>`
4. 仅 `base`：`GET …/export-preview`；以及两次 `POST …/preview-paper`，请求体
   `{"baseRevision": <run revision>, "draftSequence": n, "panel": "controls", "operations":
   [{"op": "setInput", "address": {"node": "requested-units"}, "text": t}]}`，其中 `(n, t)` 为 `(1, "9")` 与
   `(2, "nine")`。实测：`"9"` 返回 200，`requested-value` 与 `allocated-total` 为 `90.00`，
   `unused-total-capacity` 为 `50.00`，`proposedRevision` 不等于基准修订；`"nine"` 返回 422
   `MANTRA-WORKBENCH-EDIT`（`Invalid decimal text`，诊断没有地址）。

### 4.5 存储格式

```json
{
  "format": "mantra.authoring-recording/1",
  "notice": "Real Mantra engine responses for scripted source states. …",
  "recordedAt": "<ISO 时间>",
  "source": {"repository": "https://github.com/6234456/mantra", "commit": "<HEAD>", "patternsDirty": false},
  "contract": "mantra.workbench/4",
  "engine": {"mantra": "…", "normein": "…"},
  "workspace": "docs/patterns", "case": "capped-allocation/case-demo.mantra", "panel": "controls",
  "documents": ["…4 个参与路径…"],
  "base": {"<路径>": {"sha256": "…", "text": "…"}},
  "edits": {"<编辑名>": {"description": "…", "document": "…", "find": "…", "replace": "…"}},
  "states": [{
    "id": "label+class", "edits": ["label", "class"], "digest": "…",
    "documents": {"<路径>": {"sha256": "…", "text": "…仅当不同于 base 时…"}},
    "exchanges": [{"request": {"method": "GET", "path": "…"}, "response": {"status": 200, "body": {"$blob": "<sha256>"}}}]
  }],
  "blobs": {"<原始响应文本的 sha256>": {"…": "解析后的完整响应体"}}
}
```

- `digest` = 对按路径排序的 `[[path, sha256], …]` 取 `JSON.stringify` 后的 UTF-8 SHA-256（十六进制）。
  前端用 WebCrypto 以同一方式计算。
- 响应体完整保存，只按原始文本摘要去重，不删减字段。生成后报告文件大小；超过 3 MB 时先删减状态组合并在本文记录，
  不能删减响应字段。

当前提交数据包含 **32 个状态、277 个完整响应 blob，1,619,324 字节**，未删减状态或响应字段。
录制元数据的源提交为 `b27e2f5`（pattern 干净），引擎为 Mantra `1.0.0-rc.1`／Normein `0.3.0`。

### 4.6 录制时的一致性检查（任一失败则不写文件）

1. 所有 200 响应的 `contract` 为 `mantra.workbench/4`。
2. 同一状态内 structure、run、paper、diagnostics、explain 的外层 `revision` 相同；内容不同的状态修订不同。
3. Structure 中每个 `formula.location` 在该状态源文本中的切片等于 `formula.text`。
4. 422 状态的诊断范围位于文档内，未知符号诊断的切片等于 `allocated-totl`。
5. `label` 状态中 `unallocated` 行的标签为 `Unallocated request`；`class` 状态中 `unused-total-capacity` 行的
   `classes` 含 `result`；`external` 状态的 Paper 标题为新标题。
6. `finding` 状态 `succeeded: true`、`validationPassed: false`；`runtime` 状态 `succeeded: false`。

### 4.7 漂移检查

- 仓库根目录 `node workbench-ui/scripts/record-authoring-prototype.mjs --check`：确认 `base` 的摘要等于当前 `docs/patterns` 原文，
  并且对当前原文施加各状态的编辑后摘要不变。
- Vitest 测试用 `?raw` 读取四个参与文档，以 WebCrypto 计算摘要并与 `recording.json` 的 `base` 比较，
  让 `npm test` 在没有 JVM 的情况下发现 pattern 已改而录制未更新。

## 5. 模拟服务

### 5.1 接口

`AuthoringService` 按未来契约的形状定义，界面只通过它交互，从不发送源偏移：

| 方法 | 输入 | 输出 |
| --- | --- | --- |
| `open()` | — | 模板身份、参与文档与基准修订、owner 表、Outline、基准预览 |
| `apply(operation)` | `{handle, op: setText｜setFormula｜setClasses｜setLayoutOption, value}` 与当前草稿 | `{patches: [{document, start, end, text, inverse}]}` 或带原因的拒绝 |
| `preview(request)` | `{draftSequence, baseRevisions, documents}` | `{draftSequence, baseRevisions, kind: valid｜invalid｜runtimeFailure｜unrecorded, recorded responses}` |
| `commit(request)` | `{baseRevisions, documents}` | 成功（模拟）或 409 `{changed, currentRevisions}` |
| `previewExampleInput(text)` | 示例输入原文 | 录制的 `preview-paper` 响应或 `unrecorded` |
| `simulateExternalChange()` | — | 对已保存基准施加 `external` 编辑，并发出等同 SSE 的变化事件 |

### 5.2 模拟 owner 扫描器

- 在当前草稿文本上做识别字符串与注释的 S-表达式扫描，只覆盖 S1 需要的形式：`(section id "标题" …)`、
  `(info|line|total|check|reconcile id "标签" …)`、`(note "文本" {…})`、项选项映射中的 `:class` 值，以及 layout 选项
  `:title`、`:precision`、`:hide-zero`。
- 句柄是不透明 id，映射到 `{document, declaration, property}`；范围在每次草稿变化后重新计算。
  显示路径形如 `capped-allocation/schema.mantra › (info unallocated …) › label`。
- 只读 owner：`reconcile` 两侧、`check` 条件、fragment 中的 `defn`、表的 `:style` 与列。原因按设计规范 §3.6 的文案。
- 测试：对每个有效录制状态，扫描得到的公式范围等于录制 Structure 的 `formula.location` 偏移，
  标签范围内的文本等于录制的标签。

### 5.3 补丁与历史

note 的多行文本会转义换行、回车与 tab，并保留逐字节逆补丁；单行标签仍拒绝换行。

- 文字：按 Normein 字符串规则转义 `\` 与 `"`；单行属性含换行时拒绝；空标签拒绝。
- class：单个时写 `:x`，多个时写 `[:a :b]`；名称必须匹配 `[a-z][a-z0-9-]*`。
- 公式：原文写入，不翻译。layout 选项：`:precision` 为非负整数，`:hide-zero` 为布尔，`:title` 为字符串。
- 每个源事务保存逆补丁；撤销恢复逐字节相同的文本（测试比较字节）。

### 5.4 预览与延迟

- 草稿文档的摘要命中录制状态时返回该状态的响应；422 映射为 `invalid`，`succeeded: false` 映射为
  `runtimeFailure`，否则 `valid`。未命中返回 `unrecorded`。
- 外层 `draftSequence` 与 `baseRevisions` 是模拟包装；录制的响应体原样使用。
- 默认延迟为确定序列（例如 300 ms、600 ms 交替），并提供测试钩子与原型控制 `Delay next preview by 2 s`，
  用来演示迟到响应被丢弃。

### 5.5 保存、冲突与恢复

- 只有当前预览为 `valid` 或 `runtimeFailure`，且对应最新草稿序号时才能保存；`invalid` 与 `unrecorded` 禁用并写原因。
- 若已模拟外部修改且草稿基准早于它，`commit` 返回 409：变化文档 `capped-allocation/layout.mantra`。
  冲突对话框提供 `Re-preview on the new base` 与 `Discard my draft`。重新预览时，只触及未变化文档的源事务
  通过句柄重新解析后重放；触及变化文档的事务标为冲突（脚本不覆盖此情形）。
- 成功保存显示 `Saved in this prototype session — no file was written`。
- 恢复草稿存于 `localStorage` 键 `mantra.authoring.prototype.<case>.<base digest>`，内容为基准摘要、源事务与未提交输入。
  再次打开时显示设计规范的恢复横幅；恢复后状态为 `Restored · not validated`，直到收到与当前草稿匹配、技术有效
  （`valid` 或 `runtimeFailure`）的预览。
- 恢复记录保存实际的模拟已保存文档集与修订，不固定假设初始 `base`；读取时校验四个参与文档、摘要、
  补丁与逆补丁，以及当前可撤销／重做的历史。冲突阻断的旧历史保留证据但不允许越过该边界重放。
  丢弃或更新恢复记录后，迟到的异步写入不能复活旧草稿。

### 5.6 示例输入与构建面板

- Example input 标签只提供 `requested-units`。仅当模板草稿干净且已保存基准仍为录制的 `base` 时启用；
  否则说明组合预览尚未接入本录制原型。G-A4／G-A14 的真实组合预览已在独立 `/template-preview` 页
  实现，见 [契约 §7.1](../contract.md#71-操作)；这不改变本原型的录制状态范围或模拟保存边界。
  `"9"` 显示录制的候选 Run、差异与 Paper；`"nine"` 在字段下显示录制的 422 消息并保留原文；其他输入显示未录制。
- Build 标签只显示 `base` 录制的 `export-preview` 报告（公式单元格、输入单元格、命名区域、fallback、求值错误），
  标题 `ExcelExport report for the recorded base state`，明确不属于当前模拟保存修订。
  Build、Publish 与 `Open in Template Engine` 均禁用，并用 `aria-describedby` 分别关联不可用原因。

## 6. 状态模型要求

`model.ts` 是纯 reducer，实现 [状态矩阵 §3](state-matrix.md#3-关键转换) 的转换；
保存胶囊按状态矩阵 §1 的优先级只显示一项。至少覆盖：

- 序号低于最新或修订不同的响应被丢弃，且不改变预览、诊断与保存按钮。
- 无效草稿保留上一有效预览并标为过期；修复后新的有效响应恢复为当前。
- 撤销与重做恢复逐字节文本，并产生新的草稿序号。
- 冲突保留完整草稿与历史；丢弃后回到新基准。
- 冲突期间真实旧预览标为 previous，状态栏明确基于旧源修订；匹配旧基准的响应也不能标为当前。
- 恢复的草稿在首个技术有效（`valid` 或 `runtimeFailure`）的当前预览前标为未校验。

## 7. 界面与键盘

- 区域、文案与窄屏行为按设计规范 §2.3、§3.3–§3.13；token 按实施计划 §3.2，深色主题沿用 `data-theme`。
- 作者网格为 HTML `table`，`role="grid"`，roving tabindex，所有渲染单元格可选中，单元格外观复用 `cellAppearance`。
- 公式栏固定在中栏顶部，默认紧凑、聚焦展开；Grid／Source 分别限定高度并独立滚动。
  源码的主／次 owner 使用独立 decoration，网格保持焦点时仍可看见；定位只滚动源码编辑器。
- Style 按当前 layout 的 `:style-preset` 与本地 `style-class` 提供带出处的完整词汇；芯片保留源顺序，
  删除／添加后显式 Apply，最终 Weight／Tone／Fill 只读取 Paper，不在前端求值样式选择器。
- 键盘按设计规范 §4.2–§4.4：浏览态 Enter／F2 进入编辑；单行 Enter 提交，成功下移、失败保留焦点；
  Shift+Enter 成功上移；多行 Enter 换行、Ctrl/Cmd+Enter 提交；补全菜单先消费 Enter；IME 组合期间
  （`isComposing` 或 `keyCode === 229`）不提交、不导航、不发预览；浏览态 Tab 离开网格；Escape 分层取消。
- 原型可在 keydown 时把焦点移入单元格编辑框（设计规范 §4.3 的原型说明）。
- 浏览态首个 keyCode 229 同步打开并聚焦编辑器，让浏览器将后续组合输入交给字段；不提交或导航。
  真实系统输入法候选确认仍需人工验证。
- 公式字段为多行 CodeMirror。补全来源：录制 Structure 的节点 id、标签与类型，以及 `common/formulas.mantra` 中的
  `defn` 名称；界面注明 `Completions from recorded structure (simulated language service)`。
- 源码视图：schema 与 layout 可编辑，空闲 500 ms 合并为一个源事务；fragment 与 case 只读。
  此 500 ms 为原型的源事务合并时间；收到事务后才安排预览，不把原文编辑缓冲当成已提交语义操作。
- Problems 用 `explainDiagnostic` 显示说明与原始消息，按 owner 聚合，F8／Shift+F8 导航。
- Source changes 显示相对已保存基准的逐行差异，每个 hunk 标注来源操作。
- 身份栏前固定显示 `Prototype · recorded engine data`；原型控制面板集中放置模拟控件。
- 窄屏 Outline 为可关闭覆盖层，Inspector 为全屏覆盖层，Grid／Source 分段切换；关闭 Inspector 返回原单元格。
  窄屏 Split 暂退为 Grid，扩大视口后恢复原选择；公式编辑在窄屏 Inspector 中保持单个实例。

## 8. 测试要求

| 范围 | 测试 |
| --- | --- |
| 状态模型 | §6 全部转换 |
| 键盘 | Enter／F2 进入编辑；单行提交成功与失败；Shift+Enter；多行换行与 Ctrl/Cmd+Enter；补全消费 Enter；IME 组合期间无提交无导航；Tab 离开网格；Escape 分层 |
| 模拟服务 | 摘要命中与未命中；补丁结果与录制编辑逐字节相同；owner 范围与录制位置一致；API 不接受偏移 |
| 录制漂移 | `?raw` 原文摘要与 `recording.json` 一致 |
| 组件 | 标签编辑后网格显示录制状态的新标签；无效草稿显示上一有效预览；导出与发布不出现成功文案 |

## 9. 操作脚本（Claude 已复核，见复核记录）

以下脚本已按 F1–F18／S1 修正更新；Claude 已在 `da8138d` 上逐步复核，结果见 [复核记录 §7](prototype-review.md#7-第二轮复核)。

1. `npm --prefix workbench-ui ci`，然后 `npm --prefix workbench-ui run dev`，打开 `http://localhost:5173/authoring`。
2. 入口页 `Start from a pattern` 列出四个 pattern，只有 Capped allocation 已录制。`Create editable copy` 打开模拟 fork
   对话框，列出依赖闭包与 SHA-256，确认后进入 `Allocation conservation` 面板。
3. 方向键选中 `Request not allocated`，Enter，输入 `Unallocated request`，Enter：焦点下移；状态从
   `Calculating draft #1…` 变为当前；Source changes 显示 schema 中一行差异。
4. 选中 `Capacity not consumed`，Style 标签点击 `Remove class subtotal`，再 `Add class` → `Add class result` →
   `Apply classes`：行样式变化，数值不变。最终 Weight／Tone／Fill 来自所选 Paper 单元格；词汇按 layout preset 分组，注明出处。
5. 选中 `Unallocated request` 的金额单元格，F2，把公式改为 `(- request allocated-totl)`，Ctrl/Cmd+Enter：编辑器保持打开，
   Problems 显示两条同 owner 诊断，`allocated-totl` 被标出；网格显示 `Previous valid preview (draft #2)`；Save 禁用并写原因。
6. 改为 `(- request total-capacity)`，Ctrl/Cmd+Enter：`unallocated` 为 `(60.00)`，`conserved` 为 `✗`，Findings 计数 1，Save 可用。
7. Ctrl/Cmd+Z 两次回到步骤 4 的状态，Ctrl/Cmd+Shift+Z 两次回到步骤 6 的状态；每一步都显示录制结果。
8. 打开原型控制 `Delay next preview by 2 s`，再做一次编辑并立即撤销：迟到的响应被丢弃，状态栏显示被忽略的序号。
9. 原型控制 `Simulate external edit to layout.mantra`：出现冲突横幅，草稿保留；Save 打开冲突对话框，
   显示外部标题 hunk 而非全文；网格与状态栏注明预览基于旧源修订。
   `Re-preview on the new base` 后 Paper 标题为 `Capacity, allocation and residue`；
   再 Save 显示 `Saved in this prototype session — no file was written`。
10. 把公式改为 `(/ request 0)`：显示 `Calculated with a runtime failure`，`unallocated` 显示引擎文本 `undefined` 与失败标记；
    Problems／Errors 包含运行时诊断，Runtime failures 单独计数；标记说明数值属于当前预览。Build 不显示成功。
11. 保持步骤 10 的未保存草稿刷新页面：显示本地化日期时间的恢复横幅；Restore 后为 `Restored · not validated`。
    首个匹配的 valid 或 runtimeFailure 预览到达后标记解除，恢复出的运行时失败草稿也可模拟保存。
12. 用中文拼音输入法编辑标签：组合期间 Enter 只确认候选，第二次 Enter 才提交。
13. 使用原型控制 **Reset prototype to recorded base**，再打开 Example input。步骤 9 的模拟保存已改变保存基准，
    仅撤销草稿不能重新启用仅针对初始 `base` 录制的示例预览。输入 `9` 并应用：显示录制的候选 Paper
    （`requested-value` 为 `90.00`）；
    输入 `nine`：字段下显示错误并保留原文。
14. Build 标签显示录制的导出报告；Build、Publish、`Open in Template Engine` 均为禁用并写明原因。
15. 浏览器宽度 390 px：网格在首屏，无横向滚动；Outline 默认关闭，通过 `Open outline` 打开再关闭。
    `Open inspector` 使用全屏覆盖层，关闭返回原单元格。窄屏选择 Grid／Source；Split 从桌面切入时退为 Grid，
    恢复宽屏后回到 Split。金额单元格 F2 在全屏 Inspector 中打开唯一的公式编辑器，Escape 取消并返回网格；深色主题可读。
16. 在 1440×900 选择 Split，点选不同网格 owner：两窗格保持并排，网格不被滚走；源码主／次高亮可见且不抢焦点。
    源码与网格可分别滚动，固定公式栏始终可访问，聚焦时展开。
17. 检查 Style 的 Add class：`:utilities` 包含 normal／subtle／highlight，`:working-paper` 包含 variance；
    若 layout 有本地 `style-class`，应显示源位置；未声明规则的已用标签有无视觉规则说明。多行分配仍标为后续正式实现。
18. 重置后提交未录制标签（如 `Draft label`）：文字操作与导航完成，源草稿保留，并说明没有引擎预览、Save 禁用。
    再在不同声明上编辑文字，Source changes 分成多个 hunk，中间未变声明不列为删除／新增；每个 hunk 有操作来源。
    选中单个网格单元格按 Escape，Drawer 保持原状。

## 10. 截图清单

已保存在 `docs/workbench/visual-editor/screenshots/`。本会话内置 Browser 不可用，改用已安装 Chrome 与 CDP，
专属临时 profile，1440×900 与 390×844；脚本退出时验证浏览器、Vite 与 profile 已清理：
`entry.png`、`label-edit.png`、`class-style.png`、`invalid-draft.png`、`business-finding.png`、
`runtime-failure.png`、`conflict.png`、`recovery.png`、`build-panel.png`、`narrow-390.png`、`dark.png`，
以及本轮新增的 `split-independent.png`、`narrow-inspector.png`，共 13 张。

## 11. Claude 复核清单

**Claude 已于 2026-10-10 按 `69ab8ff` 复核，结果、需修正项 F1–F18 与设计澄清 S1–S3 见
[复核记录](prototype-review.md)。Codex 修正完成，逐项映射见 [修正记录](prototype-fixes.md)；
第二轮复核已完成，见 [复核记录 §7](prototype-review.md#7-第二轮复核)；真实拼音输入法仍待人工操作。**

1. 以已提交的 HEAD 为准复核，不以工作区为准。
2. 在仓库根目录重新运行 `node workbench-ui/scripts/record-authoring-prototype.mjs --check`；确认本轮 `recording.json`、
   pattern 和 JVM 引擎未改。初次独立重录的真实性结论见复核记录；未变化的数据不必为本轮布局修正重新生成。
3. 搜索原型源码中的数值字面量与格式化逻辑，确认没有合成金融值、汇总或 Paper。
4. 模拟能力在界面与代码注释中都有标注；导出、发布、构建没有成功路径。
5. §2 的四项检查全部通过，现有测试数不减少；`App.tsx` 与现有页面行为不变。
6. §8 的测试真实断言状态转换，不只断言渲染不报错。
7. 按 §9 在浏览器逐步操作，截图与描述一致后再把本节改为实际结果。
8. 按 [F1–F18／S1 修正对应表](prototype-fixes.md#1-修正对应表) 逐项填写复现结果，明确区分自动检查、
   人工设计复查与真实输入法验证；保留初次复核结论作为历史记录。
