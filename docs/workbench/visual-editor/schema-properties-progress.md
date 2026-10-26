# Schema 展示属性：真实 owner 与可逆源编辑

> 2026-10-26；合同 `cc25806`，核心代码 `7af4078`。G-A1／G-A3／G-A16／G-A18 的有界切片完成。
> [合同](schema-properties-contract.md) · [使用说明](../../site/schema-properties.md) ·
> [示例](../../examples/schema-properties/schema.mantra) · [PR #15](https://github.com/6234456/mantra/pull/15)

## 已完成

新增文件工作区 `POST …/template-schema-properties` 及公开 catalog 方法，提供 14 种展示声明的
label／note text／classes owner。定位基于公开 SchemaReader 模型及 Document AST；不扫描 Paper
可见行或把公式／字符串中的伪声明当作 owner。隐藏、零值、不活跃声明仍能定位。独立 input/param/
dimension、根标题、choice option 和案例扩展不在本目录内。

owner 绑定完整基准、document handle、精确初始草稿、声明路径与属性。批操作先绑定初始目录，
再逐步按模型路径重定位，最终重发句柄。captured include 只读；resolver 只使用捕获内容及规范路径
映射，支持嵌套／符号链接别名，不授予额外读取权限。无效绑定公式的语法有效草稿也能编辑展示属性，
但成功不证明计算有效；Apply 后仍须真实 Preview。

顺序 UTF-16 补丁和 removedText 可正向应用及逆向还原，保留 CRLF、注释、未知合法选项、公式及区间外
字节。同值无补丁，缺省 class、显式 [] 与 unset 分开。新文本须经正式 reader 无损读回；孤立 surrogate
在 owner 摘要前拒绝，防止替代编码碰撞。合法 emoji 保留。操作不写模板、推进历史或保存状态。

前端交付独立 `LiveSchemaPropertiesClient`，含不可变请求快照、完整响应／owner 校验、正逆补丁检查。
UI 接纳前仍须核对当前草稿；未新增属性面板，录制原型未接入本 API。

## 最终验证

完整 `./gradlew --no-daemon --max-workers=2 :mantra-cli:installDist check` 成功：8m 20s，
112 个任务（46 执行、66 up-to-date）。XML 实际汇总 **230 suites／1086 JVM tests**，失败／错误／
跳过均为 0；新增 **33 项**，editor 13、service 11、真实 HTTP 9。格式、边界、源大小、诊断、
测试数量、六库 ABI 与前端 check 均通过。

前端完整 check 和 **548 tests／54 files** 通过，新增 12 项协议测试。fixture／fresh live 构建、
8 文件／94 modules 完整 live 图隔离、9 个隔离反例、原 32 状态摘要均通过。最终 live 构建包含新增
诊断说明，不含录制原型／recording／golden。首次发现诊断缺少翻译目录项后已补齐英德说明，完整重跑
通过，没有放宽检查。

覆盖：14 种声明与伪声明排除、批量标签/class/note、Unicode／CRLF 正逆补丁、class noop／[]／unset、
只读／过期／错误 owner、无效绑定草稿、重复 metadata、类型／预算／请求约束、基准损坏／删除／末尾
外部修改、嵌套及符号链接 include、合法与孤立 surrogate、真实候选值及样式。客户端另覆盖请求对象
晚修改、取消、错误详情、范围／补丁拒绝与大于 64 个既有 class 的目录。

公共 JVM ABI 只新增 2 个 DTO（TemplateSchemaPropertiesRequest、TemplateSchemaPropertyOperation）
和 WorkspaceCatalog.templateSchemaProperties。baseline 新增 40 行，旧基线每行／描述符仍按原序保留；
实际 dump 经审查更新，仅 workbench baseline 改变。旧端点／schema 字节不变，新增独立
[schema](../schema/template-schema-properties.schema.json) 与生成类型，不改变 HTTP v4、DSL、内核
制品／版本、计算语义或既有 DTO 构造器。

## 独立安装版 HTTP

真实 installDist 程序对示例隔离副本执行 **11 组流程／21 次 HTTP**：

| 检查 | 结果 |
| --- | --- |
| 旧 source／目录 | source data 恰为 document/baseRevisions/documents；14 owner、4 readonly；zero/inactive 可定位 |
| 同批改标签/class/note | 3 个可逆 UTF-16 补丁；所有 owner 重发；公式／注释不变 |
| 实际预览 | Run answer `{n:"20"}` 且全部 values 与基准相同；Difference changes=[]；中文标签／备注、20,00 与 bold/accent/subtle |
| Noop／空 class／unset | noop 原文／句柄不变、patches=[]；缺省与显式 [] 不同 |
| 错误绑定 | inspect 200，真实 preview 422 |
| owner／协议拒绝 | 过期／错误属性／只读／重复 JSON／非法 UTF-8 为 400 |
| Unicode／权限 | 孤立 source／label surrogate 422；无令牌 403、方法 405、查询 400 |
| 外部 include 改动 | 409 |

状态序列为 `[200,200,200,200,200,200,200,200,200,422,400,400,400,400,400,422,422,403,405,400,409]`。
公开说明的 Python 代码块也实际执行，仅 base URL 改为任务端口，退出 0，第一行输出 `{'n': '20'}`。
全部请求前后工作区 SHA 相同；外部改动／恢复明确单列，原始示例未改，任务目录和服务进程组已清理。
6 个目录、2 个 preview、1 个旧 source 响应通过独立 Python Draft 2020-12 验证，JVM 另验证正式 schema。
实际版本为 Mantra `1.0.0-rc.1`／Normein `0.3.0`。

归档 [摘要](schema-properties-evidence/summary.json)、[目录](schema-properties-evidence/02-inspect.response.json)、
[补丁](schema-properties-evidence/04-edit-label-class-note.response.json)、
[真实预览](schema-properties-evidence/05-edited-preview.response.json)、
[公开脚本输出](schema-properties-evidence/public-script.txt) 及同目录其余实际响应。
根代理核对全部 21 个 response SHA、stdout SHA 及示例源 SHA。正文没有令牌、header 或 index HTML。
原始请求／脚本／日志：`/workspace/.cloud-setup/schema-properties-smoke/`；全量日志：
`/workspace/.cloud-setup/schema-properties-full-check.log`。

文档 26 项测试和默认站点／check-only 通过（41 HTML／2163 本地链接）；
未执行要求全部 KDoc／发布制品的严格站点发布。
本轮没有浏览器或真实系统中文输入法测试，API／脚本证据不代替 UI 设计签收。

## 保留范围

G-A1／G-A3／G-A16／G-A18 仍为部分完成。完整 Outline、公式语言服务、结构／共享编辑、可视化 UI
接入、源历史／服务端草稿、D-A1／正式 Save、构建及发布仍待后续。Template Engine 完整兼容仍为 false。
其他仓库、录制 32 状态和 pattern 源未改；远端 CI 以实际运行结果为准。
