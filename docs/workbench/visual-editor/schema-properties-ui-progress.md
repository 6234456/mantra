# Schema 展示属性面板与客户端草稿协调

> 2026-10-26；接续 `e76d06e` 的真实 owner API 切片。
> [用户说明](../../site/schema-properties.md) · [API 合同](schema-properties-contract.md)

## 实现范围

独立真实 `/template-preview` 页新增 Schema presentation properties，显式 inspect／refresh 后
按 document、行、form、node 与 property 选择声明。目录直接来自真实 API，包括隐藏／零值／
不活跃声明与只读 include；不是完整 Outline 或 Paper 单元格 owner 投影。

label／note 原文与 classes 可逐属性编辑。classes 允许未知合法名，最多 64 个唯一简单名；
空文本 Apply 得到显式 []，Remove class declaration 是 unset；UI 不把 class 贡献当最终样式。
Apply 只接纳服务返回的根 schema 原文，保持正式引擎预览与文件保存分开。

根 schema 属性、手写源、根 layout 设置与本地 style 定义共用精确源 Undo／Redo。
未 Apply 字段阻止 Preview／Undo／Redo；Apply 后先前预览过期，仍须真实候选响应。
完整基准、case、handle、初始原文、请求 generation 与当前字段都参与接纳检查；
迟到／取消／409／其他源编辑不能覆盖当前草稿。服务端 owner 是编辑权威，不使用模拟扫描器。

保留字段只存逻辑 document／path／form／node／property 与精确 declaration 原文，
不存 owner handle 或权限。刷新后只有同一声明仍存在且原文一致，才可重新显式 Apply；
声明改变或缺失时保留字段供查看／Cancel，不猜测绑定其他声明。

浏览器副本、JSON 备份／恢复包含新增未应用字段；恢复清空结果与历史并要求重新 inspect。
新增独立 [恢复 v2 schema](../schema/template-recovery-v2.schema.json)，仅存在 schema 未应用字段时
导出 `mantra.template-preview-recovery/2`。没有此字段继续导出旧 v1 原形；新客户端读取 v1／v2，
旧客户端拒绝 v2。旧 schema 与 HTTP v4 不变。

## 验证

- 前端 check（格式／lint／类型）通过；新增面板及恢复集成 **13 项**定向测试通过。
  最终完整前端 **55 文件／561 项测试**全部通过（原 548＋新增 13）。
- fixture／live 构建通过；fresh live **8 文件／97 modules** 排除录制原型、recording 与 golden，
  隔离反例通过；原 32 状态摘要与边界检查通过。
- 真实 JDK 21 `:mantra-cli:installDist` 成功；未修改 JVM 实现、ABI 或旧 HTTP schema，
  本轮未重跑完整 JVM 测试，不复用上一轮 1086 项结果作为本轮通过证据。
- 实际安装版 CLI＋Chromium **42 场景**全部通过，含新增真实 schema owner 标签编辑、
  中文／emoji、精确 Undo／Redo、自动副本、真实 Paper 文本与值 `20` 不变、390 px 无横向溢出。
  共 41 次源字节检查，2 个新 schema-property HTTP 200 响应；任务进程组和临时资源清理通过。
- 13 项定向测试另覆盖 note 的 null node、class []／unset／非法重复、只读 include、
  迟到源／字段确认、409、声明变化拒绝重绑定、恢复后重新 inspect、合成 composition。
  合成输入不等同真实系统 IME。
- 独立 Python Draft 2020-12 校验恢复 v2：接受 note 字段、拒绝 forged owner。
  文档站点生成／check-only 通过（31 HTML／1029 本地链接；最终数量随链接更新）。

归档 [浏览器摘要](schema-properties-ui-evidence/summary.json)、
[两个真实 schema 属性响应](schema-properties-ui-evidence/schema-properties-responses.json)、
[桌面](schema-properties-ui-evidence/schema-property-label.png) 与
[390 px](schema-properties-ui-evidence/schema-property-narrow.png)。
完整报告在 `build/ui-qa/template-preview/report.json`，日志在 `/tmp/mantra-*.log`。

环境只有 JRE，测试前在工作区工具目录安装 JDK 21，并配置已有代理和系统 CA 信任。
首次浏览器执行所有场景均通过但容器孤儿进程清理断言失败；换用持续 waitpid 的环境 subreaper
包装器完整重跑后清理通过，未放宽断言。浏览器使用隔离 profile，不安装 browser bundle。
环境工具不属于产品源码。

## 仍未实现

正式文件 Save／D-A1、服务端源历史／草稿、完整 Outline／Paper owner、结构增删重排、语言服务、
构建发布和多案例 UI 均保持待办。录制 `/authoring` 的模拟 owner 与数据不变。
真实系统中文拼音 IME 与独立设计人工签收不在自动化结果内；Template Engine 全工作簿兼容仍为 false。
