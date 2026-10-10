# 录制引擎原型：实施与复核记录

> 2026-10-10：Codex 已实现 fixture 作者原型；Claude 已复核 `69ab8ff`，见 [复核记录](prototype-review.md)。
> **F1–F18 与 S1 已修正，Claude 第二轮复核见 [复核记录 §7](prototype-review.md#7-第二轮复核)**；逐项说明见 [修正记录](prototype-fixes.md)。
> 任务分支 `codex/mantra-design-handoff-20261010`，沿用
> [Mantra Draft PR #15](https://github.com/6234456/mantra/pull/15)。
> 设计要求与人工复核脚本见 [prototype.md](prototype.md)，历史状态见 [README](README.md)。

代码检查点：`9155663`（录制器、源服务、状态与恢复），`aa5e14a`（作者界面、组件测试与截图）。
首轮完整实现为 `69ab8ff`，Claude 复核文档为 `92664ae`。这些是历史定位信息；后续复核请读取 PR 最新已推送 HEAD。
F1–F18／S1 修正代码检查点 **`dc01609` 已提交并正常推送**；本轮以下门禁与浏览器验证对应该代码。
本说明随后作为文档提交交付，Claude 再次复查仍以 PR 最新已推送 HEAD 为准。

## 1. 启动与入口

在仓库根目录运行：

```sh
npm --prefix workbench-ui ci
npm --prefix workbench-ui run dev -- --host 0.0.0.0
```

打开开发服务器显示地址的 `/authoring`，默认本机为 `http://localhost:5173/authoring`。
云端访问使用环境提供的端口转发／预览地址，同样加上 `/authoring`。已有录制数据可直接运行，不需启动 JVM 服务。
`/` 仍为原有工作台。入口只存在于 fixture 模式，`VITE_WORKBENCH_MODE=live` 构建不带作者原型。

进入 Capped allocation 的 `Create editable copy`，确认模拟依赖闭包，即可执行
[prototype.md §9](prototype.md#9-操作脚本claude-已复核见复核记录)。只有该 pattern 录制可用；其他入口显示原因。
原型控制提供延迟下一预览、模拟外部 layout 修改及恢复初始录制基准。正式模板文件不会被此界面写入。

## 2. 实现内容与边界

下表只记录 `/authoring` 录制原型。后续已实现的文件工作区真实模板草稿／标量示例输入预览与
候选 Explain 位于独立 `/template-preview` 页，见 [契约 §7.1](../contract.md#71-操作) 和
[工程扩展记录](extension-progress.md)；录制原型尚未接入这些 API，正式属性 owner 和文件 Save 仍未实现。

| 内容 | 已实现行为 | 实际边界 |
| --- | --- | --- |
| 入口与作者界面 | 模式身份、模拟 fork、Outline、Grid／Source／Split、Property／Style／Explain／Example input、Problems／Changes／Build | fixture 原型，不是正式可写模板工作区 |
| 修正后的布局 | 公式栏固定且聚焦展开、Grid 与 Source 独立滚动、失焦 owner 高亮、按面板分组的 Outline、窄屏覆盖层 | 不声明已完成人工设计签收；窄屏 Split 暂退为 Grid |
| class 选择 | 从 layout 的 preset／本地声明读取词汇，带出处的完整预设、源顺序芯片、Paper 的 Weight／Tone／Fill | 不在前端求值样式规则；多行分配延后，来源链仍需正式接口 |
| 编辑与源历史 | 数据驱动 owner 扫描，最小源补丁与逆补丁，精确字节撤销／重做，schema／layout 源视图 | owner、补丁与语言服务均为模拟；只覆盖原型声明形式 |
| 源编辑缓冲 | 500 ms 空闲合并事务；提交后清除缓冲，避免覆盖后续语义编辑 | 未提交源码期间同文档语义操作需先完成当前编辑 |
| 预览 | SHA-256 文档集摘要查找完整录制响应，序号与全量基准修订校验，上一有效预览 | 未录制的草稿明确无预览；不计算或拼造 Paper |
| 诊断与 Explain | 真实 422 源范围、同 owner 诊断、业务 finding、运行时失败与录制步骤 | 本录制原型未接入真实候选 Explain；正式属性 owner API 仍未实现；过期范围不当成当前定位 |
| 保存与冲突 | 模拟 CAS，外部 layout 事件，保留草稿与历史，重新预览／丢弃，迟到保存回执保护 | 仅更新内存的已保存文档集；不写文件、不证明 D-A1 的文件事务策略 |
| 浏览器恢复 | 保存实际模拟基准与草稿、源历史和未提交输入；校验摘要／修订／补丁；匹配的 valid／runtimeFailure 首次预览解除恢复未校验标记 | 不恢复可信预览／校验结果，不跨设备；旧异步写入不能复活已丢弃记录 |
| 示例输入 | 初始录制基准的 `9` 候选 Paper 与 `nine` 的真实 422；迟到响应不覆盖变更后的模板或输入 | 只在无草稿且保存基准为原始 `base` 时启用；真实组合预览 API 已实现，本录制原型尚未接入 |
| 构建与下游 | 展示原始案例的录制 ExcelExport 报告，明确禁用构建／发布／Template Engine 跳转 | 没有新产物，没有登记发布，也未证明 Template Engine 运行兼容性 |

模拟句柄在每个草稿上重新解析范围，不接受客户端提供偏移。无法可靠定位的声明只读。
单行文字拒绝空标签／换行，note 支持 CodeMirror 多行编辑与转义；class 与有限 layout 值按类型检查。
浏览态 Tab 离开网格，单行编辑态 Tab 成功提交后寻找右侧可编辑位置，多行公式／note 保留 Enter 换行。
框架保持 React 19、CodeMirror 6 与现有依赖；没有引入 Office Canvas、React 18 workspace UI 包或新增 npm 依赖。

## 3. 录制来源与复现

`workbench-ui/scripts/record-authoring-prototype.mjs` 为每个源状态复制临时 pattern 集，启动真实
`mantra serve`，录制请求与完整响应。32 状态由 label × class × 四种公式状态 × external 组成。
当前 `recording.json` 为 **1,619,324 字节、277 个按响应原文 SHA-256 去重的完整 blob**。
录制元数据记录源提交 `b27e2f5`、干净 pattern、Mantra `1.0.0-rc.1`、Normein `0.3.0` 与
`mantra.workbench/4`。该元数据指录制时的源，而不是 PR 当前 HEAD；后续前端提交不改变录制源。

Claude 在 `69ab8ff` 上独立重录 32 个状态：307 个响应中 163 个逐字节相同，其余 144 个仅含契约允许的
执行间内核 `eventId` 差异，详见 [真实性复核](prototype-review.md#2-录制数据真实性)。本轮没有修改录制数据、
pattern 或 JVM 引擎；重新进行源摘要漂移检查，独立重录结论保留为历史证据。

脚本检查契约／同状态修订一致性、公式原文切片、422 诊断范围、finding／runtime 身份及示例输入结果，
全部通过后原子写录制文件，逐状态清理服务与临时目录。生成过程没有改动原始 `docs/patterns`。

漂移检查无需 JVM：

```sh
npm --prefix workbench-ui run authoring:record:check
```

重新录制需已构建的 CLI 与 UI：

```sh
./gradlew --no-daemon :mantra-cli:installDist
npm --prefix workbench-ui run build
npm --prefix workbench-ui run authoring:record
```

脚本支持 `--cli`、`--ui`、`--output` 与 `--help`。Vitest 用当前四个 `.mantra?raw` 原文验证录制摘要，
在无 JVM 的前端检查中也可发现源已变而录制未更新。

<a id="validation"></a>

## 4. 验证与待复核项

以下为 `dc01609` 修正代码的本轮实测检查；不沿用 `69ab8ff` 的旧检查代替本轮结果。

| 检查 | 当前记录 |
| --- | --- |
| `npm --prefix workbench-ui run check` | 格式、ESLint 与 TypeScript 通过 |
| `npm --prefix workbench-ui test` | 40 个文件、347 项通过；保留原有 204 项，新增 143 项模型／服务／恢复／录制／组件回归 |
| fixture 与 live 构建 | 均通过；fixture 97 modules，live 77 modules／5 个产物文件；断言无 AuthoringPrototype chunk、录制标识或 fixtures 目录 |
| `python3 scripts/check-boundaries.py` | 通过，无 schema／node id 业务分支 |
| 录制 `--check` | 32 个状态与当前 pattern 源摘要一致 |
| 作者浏览器脚本 | 15 个真实 Chrome CDP 流程通过，13 张截图；无未捕获浏览器异常 |
| 原工作台浏览器脚本 | 13 个既有流程通过 |
| 浏览器资源清理 | 两套浏览器检查均确认任务进程退出、临时 profile 删除 |
| Claude 设计与操作复核 | 首轮 `69ab8ff`；第二轮 `da8138d`（最新 HEAD `3c281c3` 门禁复跑）：F1–F17 与 S1 通过，F18 部分达成，见 [复核记录 §7](prototype-review.md#7-第二轮复核) |
| 真实中文拼音 IME | 待人工操作；自动脚本仅验证合成 composition／keyCode 229 守卫 |

首轮新增 101 项，修正后累计新增 143 项。回归包括同文档源码缓冲与语义操作、有限布局输入类型、
迟到保存回执、示例输入与模板草稿竞态、多行 note 与编辑态 Tab，以及恢复运行时预览、冲突旧修订说明、
独立源码定位、class 词汇／芯片、源差异来源、窄屏覆盖层和 StrictMode 对话框焦点。
此轮未改 JVM 引擎或公共服务器契约，不将历史 JVM 检查冒充本轮重跑。

浏览器脚本使用已安装 Chrome，无新浏览器 bundle：

```sh
MANTRA_TEST_CHROME=/path/to/installed/chrome npm --prefix workbench-ui run test:authoring:e2e
```

本会话内置 Browser 不可用，因此通过 Chrome CDP 操作真实 DOM、输入、CodeMirror 与键盘。
作者脚本覆盖入口／模拟 fork、F2／Enter／Tab 与 IME 守卫、label、class、不合法公式与精确范围、finding、
Undo／Redo、迟到预览、runtime／冲突／重新预览／模拟保存、恢复、示例输入、禁用发布与窄屏／深色主题。
本轮扩展检查包括首屏网格与固定公式栏、独立 Split 和失焦 owner 高亮、分离差异、未录制文字导航、
恢复运行时草稿、窄屏 Outline／Inspector 及单实例公式编辑。
最终作者脚本 15 个、原工作台 13 个流程均通过，共 28 个。
真实输入法的候选确认、系统剪贴板与完整人工交互仍按 [复核清单](prototype.md#11-claude-复核清单) 检查。

## 5. 实测截图

桌面为 1440×900，窄屏为 390×844；全部来自上述脚本的真实截图。

| 状态 | 截图 |
| --- | --- |
| 入口 | [entry.png](screenshots/entry.png) |
| 标签编辑 | [label-edit.png](screenshots/label-edit.png) |
| class 样式 | [class-style.png](screenshots/class-style.png) |
| 独立 Split 与失焦 owner 高亮 | [split-independent.png](screenshots/split-independent.png) |
| 无效草稿 | [invalid-draft.png](screenshots/invalid-draft.png) |
| 业务 finding | [business-finding.png](screenshots/business-finding.png) |
| 运行时失败 | [runtime-failure.png](screenshots/runtime-failure.png) |
| 冲突 | [conflict.png](screenshots/conflict.png) |
| 恢复草稿 | [recovery.png](screenshots/recovery.png) |
| 构建报告 | [build-panel.png](screenshots/build-panel.png) |
| 390 px 窄屏 | [narrow-390.png](screenshots/narrow-390.png) |
| 窄屏全屏 Inspector | [narrow-inspector.png](screenshots/narrow-inspector.png) |
| 深色主题 | [dark.png](screenshots/dark.png) |

## 6. 后续合同与交付

[实施计划](implementation-plan.md) 的 G-A1–G-A15 仍是正式后端与语言服务缺口；前端模拟实现没有扩展
`mantra.workbench/4` 公共合同。D-A1 的多文档文件提交原子性尚未决定，不影响本轮仅内存保存的原型。
首版继续采用 ui-spec 键位、浏览器恢复、只读共享 defn 与 Mantra DOM 网格；Template Engine 模块提取等待其维护者确认。

Claude 已按 [F1–F18／S1 对应表](prototype-fixes.md) 完成第二轮复核，结果与后续事项见 [复核记录 §7](prototype-review.md#7-第二轮复核)。
Template Engine
[Draft PR #1](https://github.com/6234456/paramita-v2/pull/1) 仍为并行使用设计文档，本轮没有追加其源码变更。
两个 Draft PR 均不因原型通过而自动合并。
