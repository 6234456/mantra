# 真实展示声明目录与源码定位

> 2026-10-26；接续 `e7390fc`，使用既有 `template-schema-properties` owner 目录。
> [属性面板](schema-properties-ui-progress.md) · [用户说明](../../site/schema-properties.md)

## 本轮实现

真实 `/template-preview` 的 Schema presentation properties 新增 Presentation outline。
按 owner.document 分组，同一声明的 label／text／classes 合并展示，按正式 reader 的数值 path
排序并连接同文档中实际存在的父声明。缺少父声明时保持根项，不补造 DSL 声明或跨文件父级。
同一源 fragment 的不同展开路径保留为独立声明；文档、行与 path 一起标识导航项。

Find a declaration 可搜索文档、form、node、原始文本或 class 值；匹配子项保留祖先上下文。
这是 presentation-items 目录，包含隐藏／零值／不活跃声明，但不自行推断当前可见状态或原因。
只读 include 可选择检查；目录不会给它新增写权限。保留原属性下拉选择器作为紧凑入口。
目录用原生列表和按钮，Tab／Enter 可用，没有未实现键盘模式的 ARIA tree。

Show property in source 选择已捕获的参与文档，将焦点移到公共源码框并选中 owner.range；
未声明 classes 没有 token，改选 declarationRange。切换文档不修改源、不增加历史、不触发请求或计算。
根 owner 仍核对 case、完整源图、根 handle 和精确 inspected 源；只读文档必须是捕获时原文，
范围必须落在实际捕获的文本内。HTML textarea 规范化 CRLF／裸 CR，定位先把原始 UTF-16
偏移映射到规范化后的 DOM 偏移，中文／emoji 不转成 Unicode code-point 索引。

未 Apply 字段、加载与过期目录阻止切换属性／源码定位；过期保留搜索及旧目录说明，需显式 Refresh。
恢复仍先重新 Inspect，不恢复旧 owner 或导航权限。定位意图在执行后消费，不在后续文档切换时抢焦点。
已有 Undo／Redo、预览、备份格式、HTTP／DSL、JVM API 与录制原型保持原范围。

## 验证

- 前端 check（格式／lint／类型）通过；完整前端 **56 文件／569 项测试**通过，
  新增 8 项覆盖数值路径排序／去重、搜索祖先上下文、只读选择、旧目录与缓冲门禁、
  空目录、CRLF／裸 CR／emoji、页面定位不入历史及未声明 class 的范围回退。
  最新面板与导航的 **21 项**定向测试再次通过。
- fixture 构建与 fresh live 隔离构建通过；live **8 文件／100 modules** 不含录制原型、
  recording 或 golden；隔离反例通过。原 32 状态摘要与边界检查通过。
- 真实安装版 CLI＋Chromium **44 场景**全部通过，新增 2 场景验证目录筛选／父级上下文、
  零值声明、无 class token 的范围回退、只读 include 的 CRLF 中文／emoji 定位及过期禁用。
  导航前后请求数量、源内容与历史保持不变；42 次工作区源字节检查通过。
  3 次 schema-property HTTP 均 200；所有任务进程组停止，临时工作区／浏览器 profile 清理通过。
- 文档站点生成／check-only 通过（31 HTML／1029 本地链接）。没有 JVM 代码／ABI／契约变更，
  使用前轮已构建安装版 CLI，本轮未将旧 JVM 测试数量计作本轮结果。

归档 [验证摘要与旧 CI 失败定位](schema-navigation-evidence/summary.json)、
[真实目录／源响应](schema-navigation-evidence/schema-properties-responses.json)、
[桌面目录定位](schema-navigation-evidence/schema-presentation-outline.png) 与
[390 px 只读 include 定位](schema-navigation-evidence/schema-outline-narrow.png)。
完整浏览器报告仍在 `build/ui-qa/template-preview/report.json`；日志位于 `/tmp/mantra-navigation-*.log`。

### 同步修复上次远端 CI 的浏览器重载竞争

检查 [e7390fc 的 run 40297604768](https://github.com/6234456/mantra/actions/runs/40297604768)
确认前置质量／JVM／文档／前端／其他浏览器门禁成功，但真实模板浏览器步骤在源码重载后
`selected source schema.mantra` 超时。旧脚本发起 Reload 后立即查询旧下拉项，一次性选择后
只等编辑器，快照更新可能替换菜单／handle 并使选择失效。

本轮复用 freshSourceCapture，在 Reload 后先核对实际新 HTTP 源快照与恢复身份，再操作当前
选项；source 选择器在同一轮读取菜单并发出选择，直到对应文档的编辑器已提交。
没有增加超时、固定 sleep 或放松源码／历史断言。修正后完整 44 场景重跑通过。
远端新提交的 CI 结果须另查，不能将这次本地通过写成远端成功。

## 边界

这是 G-A2 的真实展示声明导航切片，不是完整 Outline 或 Paper owner 投影。
独立 input／param／dimension／defn、根标题与 choice option 不在目录里；未新增结构编辑、
公式语言服务、计算条件／隐藏原因分析、网格到源或源光标到网格的双向投影。
正式 Save／D-A1、服务端历史／草稿、构建发布、多案例 UI、真实系统 IME 和独立设计签收仍待完成。
Template Engine 全工作簿兼容仍为 false。
