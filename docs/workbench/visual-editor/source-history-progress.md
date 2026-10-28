# 真实预览的统一内存源历史

> 2026-10-25，继续 [Draft PR #15](https://github.com/6234456/mantra/pull/15)。
> 契约检查点 `7e2a209`，实现 `f0dd8fa`。这是 G-A18 的客户端源编辑协调，不是正式模板文件保存或服务端源历史。

## 目标与行为

手写 schema／layout、[根布局设置](layout-settings-progress.md) 和
[本地样式 class](style-classes-progress.md) 共用一份 DSL，也共用 Undo source／Redo source。
每个接受的源修改记录 `{handle,before,after,label}`，最多五十条事务；保留精确原文，不格式化或重算。
撤销后新编辑截断 redo，无变化和被拒绝／迟到的响应不入历史。

撤销要求当前文档与事务 after 相同，重做要求与 before 相同；不能把历史覆写到新的、不匹配的源。
历史推进和源文本接纳共用原子协调，保留前一批两个属性面板竞争时只能接纳一个旧原文补丁的保护。
任何历史源修改都会推进草稿序号、取消旧候选请求、使预览与比较标过期，随后由明确 Preview 计算。
当前标量示例输入、捕获的保存基准与完整源修订保持独立。

有未 Apply 的设置／class 字段时，先 Apply 或 Cancel；历史按钮给出关联原因。加载或源修订冲突时
同样禁用；切换案例或重新捕获源后清空。源变化使属性目录过期，保留原有 Refresh 规则。
采用显式按钮，未新增全局快捷键，不截获 textarea、输入框或系统输入法的撤销／组合事件。

这是页面内存功能，不写模板文件，不调用案例 undo／redo，不持久化历史，也不把恢复草稿、G-A6／G-A7、
G-A12 或 D-A1 的缺口标为已完成。没有从 `src/authoring` 引入录制原型依赖，live 构建保持隔离。

使用方式见 [真实模板预览](../../site/template-draft-preview.md#undo-and-redo-source-edits)，
约束见 [契约 §7.1](../contract.md#71-操作)。

## 实现与专项验证

独立的 [sourceHistory.ts](../../../workbench-ui/src/templatePreview/sourceHistory.ts) 仅保存文本事务，
页面统一接纳手写源、根设置与 class Apply。两个面板同时成功时只接受一个匹配原文的补丁；
某个响应先确认 409 时，也立即预留 conflict，拒绝同一 React 批次后返回的旧基准成功补丁及历史记录。
不能等待下一次 render 才核对冲突。

专项 61 项通过：纯历史 4、真实预览页 34、class 面板 13、根设置面板 10；格式、ESLint、生成类型与
TypeScript 检查通过。新增覆盖精确 CRLF／Unicode／空文本、多文档撤销重做、五十条上限、分支截断、
原文不匹配拒绝、无变化不入历史、迟到预览、保留原始示例输入和独立基准、重捕获／切换案例清空、
未应用字段与冲突守卫、原生 IME／快捷键不被拦截，以及 409／成功响应批处理竞争。

原生 textarea 对交互输入的换行行为保持浏览器原样；纯历史模块对接纳的原文逐字符还原，
没有加入 CRLF 到 LF 的生产转换。物理系统拼音输入法仍需人工验证。

## 完整门禁

| 检查 | 实际结果 |
| --- | --- |
| Gradle `check` | 成功，55 秒／110 个任务；218 份 JVM 结果、990 项测试，失败／错误／跳过均为 0 |
| 前端 | 完整 format／lint／生成类型／TypeScript 通过；50 文件、477 项测试通过 |
| 构建与隔离 | fixture 构建成功；新 live 构建 8 个文件及完整依赖图排除原型／录制／golden，9 项隔离反例通过 |
| 录制与文档 | 原 32 状态及 pattern 源摘要通过；26 项文档测试通过，默认静态站点 37 个 HTML／2073 个本地链接通过 |
| 真实浏览器 | 33 个流程，37 个预览响应、13 个根设置响应、13 个样式响应，32 次源文件字节检查，0 失败 |

## 最终真实浏览器复核

前一批 28 个流程全部保留并通过，再增加五个源历史流程：四步跨入口精确撤销／重做；两个面板的
未提交字段阻止历史操作并关联原因；真实迟到预览在 Undo 时取消；新编辑替换 redo 分支；显式重新
捕获清空历史和示例覆盖。候选原始输入 `1,25`、独立保存基准 20 与实际候选精确值 3.75 均由真实服务验证，
浏览器未自行计算候选或差异。

报告 `build/ui-qa/template-preview/report.json` 标记 VERIFIED；取消请求为预览 3、根设置 2、class 1，
明确外部修改仍为原有两个 409 场景。32 次源文件字节检查保持一致；浏览器／服务进程组及临时目录
均清理，`taskProcessGroupsStopped` 与 `temporaryDirectoryRemoved` 为 true。
[源分支实测完整页面](screenshots/source-history/branch.png) 为 1425×1952，`completePage: true`。
脚本按 React 更新后核对字段保护按钮，保留全部断言；验证中的时序修正未修改生产代码。

历史与样式流程共用现有真实服务测试入口：

```sh
./gradlew --no-daemon :mantra-cli:installDist
MANTRA_TEST_CHROME=/path/to/installed/chrome \
  npm --prefix workbench-ui run test:template-preview:e2e
```

设计／用户文档的相对链接和锚点已检查。物理系统拼音输入法、正式文件保存、服务端历史与发布
继续按各自范围复核；这里未运行带完整 KDoc／应用制品的严格文档发布。
