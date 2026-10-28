# 第二轮残留修正：R2-1–R2-3

> 2026-10-10：以 Claude 的第二轮复核提交 `55c2c98` 为基准，正常 fast-forward 后修正。
> 本文是 Codex 的实现与验证记录；[Claude 复核 §7](prototype-review.md#7-第二轮复核) 保留原结论，
> 不将编码方的自动检查写成 Claude 的签收。复查以 [Draft PR #15](https://github.com/6234456/mantra/pull/15)
> 最新已推送 HEAD 为准；继续任务见 [resume-prompt.md](resume-prompt.md)。

交互代码检查点为 `47d4559`；本轮文档提交与 CI 测试修复仍须包含在复查的最新 HEAD 中。

## 修正对应

| 发现 | 当前行为 | 依据 |
| --- | --- | --- |
| R2-1 源码 owner 在底边裁切 | owner 导航在 CodeMirror 内部滚动区垂直居中；不改变网格焦点、源码光标或外层滚动。 | `AuthoringCodeEditor.tsx`；边缘目标居中回归；真实 Chrome 检查全部高亮片段完整可见、接近中心及网格焦点保持 |
| R2-2 展开控制区后表格高度为零 | 展开的控制区限定高度并自行滚动；390 px 下最多 88 px，桌面最多 180 px，均受视口高度限制。summary 在内部滚动时保持可见，深处的 JSON 备份操作仍可到达。 | `authoring.css`；390×844 的只读 owner／长公式说明场景检查表格高度、完整可见行、内部滚动、状态栏和备份按钮／收起入口 |
| R2-3 输入法直接起笔追加原文 | keyCode 229 移交焦点时同步选中全文。普通属性用输入框选区，公式与 note 用 CodeMirror 选区；保留原文直到浏览器开始组合输入。不取消原生事件，组合期间不提交、不导航，不随后续输入重复全选。 | `AuthoringPrototype.tsx`；标签、已存在的公式／note 编辑器替换与组合守卫测试；Chrome 合成事件检查同步全选及延后保持。**真实系统拼音输入法仍待人工验证** |

原型数值、Paper、诊断和 Explain 仍来自原始录制；本轮没有改动录制数据、pattern 或 JVM 引擎。
保存、fork、owner 与冲突仍为模拟，Build／Publish／Open in Template Engine 没有新增成功路径。
F18 继续保留第二轮“部分达成”的结论，本轮不扩展界面外壳的功能。

## CI 文档步骤

公开的 [PR run 的 verify job](https://github.com/6234456/mantra/actions/runs/38067015283/job/114256661200)
显示 `55c2c98` 已运行并以 Failure 结束，失败步骤为 `Exact documentation commands and complete offline site`，
exit 1，后续前端与浏览器步骤跳过。push run 同样失败于该步骤；不是仍在排队。
公开页面要求登录才能读取完整日志，因此进一步使用冻结提交中的实际命令本地复现。

前七条 invoice 命令成功，第八条文档测试失败：`test_site.py` 仍期待 13 个公共函数，实际已包含
`table/count-where`、`table/sum-where`，共 15 个。现改为核对明确的公共函数集，并检查新增函数
确实出现在生成的 HTML。未改生成器或绕过门禁。

修正后原 CI 步骤全部十条命令通过：invoice check、text／HTML／XLSX／PDF、Explain、catalog，
26 项文档测试，以及严格站点构建和链接检查。站点包括 2647 份 HTML、67257 个本地链接。
云端为此单独生成六个模块的真实 Dokka 输出；CI 原有前置步骤会提供这些产物。
上述结果不代表尚未结束的新 GitHub run 已通过。

## 草稿预览状态说明

[实施计划](implementation-plan.md) 的 G-A4／G-A14、[原型说明](prototype.md) 和
[实施记录](implementation-progress.md) 已同步为：文件工作区的只读真实候选切片已经实现，
入口是独立 `/template-preview`；录制 `/authoring` 尚未接入。
原型 Example input 的不可用原因也改为尚未接入组合预览，而不是合同不存在。
正式属性 owner、源补丁、文件 Save、多文档事务和发布仍有接口缺口。
新功能本身的设计复核继续作为 Claude 的独立任务，不因这些事实更新而视为已接受。

## 验证与后续复查

完整前端 `check` 与 44 个文件的 **394 项测试**通过；录制摘要检查覆盖 32 个状态。
fixture 构建、真实 live 构建隔离检查及九个隔离反例通过；边界与源码大小检查通过。
真实 Chrome 的 **17 个作者流程**通过，任务浏览器、Vite、专属 profile 完成清理。
主 owner 中心与源码滚动区中心相差约 0.28 px，全部高亮片段完整可见。
390×844 在只读公式说明显示、Prototype controls 与备份区展开时，表格滚动区仍有 82 px，
可见一个完整行；状态栏、深处备份按钮与保持可见的收起入口均可访问，外层页面不滚动。
新增滚轮检查另用四个 Chrome 流程验证：真实 wheel 改变表格内部 scrollTop，网格位置和页面滚动保持不变，
完成后恢复原滚动位置。该检查已并入作者浏览器脚本。
原有工作台另跑 13 个真实 Chrome 流程通过，完成进程与 profile 清理。
本轮没有重跑 JVM 全量测试；引擎源码未变化，前次 943 项结果作为历史证据。
截图新增为 [源码居中](screenshots/r2-source-centered.png)、[窄屏控制区展开](screenshots/r2-controls-open.png)，
未覆盖第二轮复核用的原有截图。

真实输入法人工操作：从标签、公式与 note 网格单元格用拼音直接起笔，确认原文被替换；
候选 Enter 只确认组合，完成组合后再显式提交。合成 composition 与 keyCode 229 检查不能代替该项。
