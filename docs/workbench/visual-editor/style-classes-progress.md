# 本地样式 class：实现与验证

> 2026-10-25，继续 [Draft PR #15](https://github.com/6234456/mantra/pull/15)。
> 契约 `3ad222f`，后端 `e653356`，HTTP／客户端 `b0cc5a0`，界面 `06d8962`。
> 本文为 Codex 实施记录，不替代 Claude 的设计与系统输入法复核。

## 当前实现

承接 [Invoice Builder 对照](invoice-builder-gaps.md) 和 [首批根设置](layout-settings-progress.md)，
真实 `/template-preview` 新增 **Style classes**。这是 G-A16／G-A17／G-A18 的本地定义切片：

- Inspect／Refresh 从公开 LayoutReader 读取选中 preset 的真实规则，并合并本地定义、当前布局的
  字面量 selector 名称目录。前端不复制预设表，也不计算最终样式。
- 创建或修改 `(style-class :name {...})` 的有限 `weight`／`tone`／`fill`；null 表示移除本地属性，
  显式 normal／default／none 各自保留。全 null 是空定义 `{}`，不等同于删除定义。
- preset 定义只读，可创建同名本地覆盖；删除本地覆盖恢复 preset 贡献。新定义追加到根 layout，
  已有规则次序不改。创建时若名字已有本地定义，要求选择原定义编辑，避免清空旧属性。
- 目录区分 Local／Inherited／Class effective；后者只是具名声明贡献，后续规则仍可覆盖，
  最终结果以当前真实候选 Paper 为准。
- 引用列表仅统计提交的根 layout 中 `:use` 和 `:class` selector 的 token 范围，明确排除 schema、
  include 和未提交 schema。无 preset 回退的硬 `:use` 依赖阻止删除；selector 本身不是硬依赖。
- 合法 selector 的名字范围比本地定义更广；例如 `FutureTag` 和 `:future/tag` 仍显示引用，
  标为只读并说明原因，不把有效布局误报为错误。

启动与完整操作见 [用户说明](../../site/style-classes.md)。该表单与十四项根布局设置共用同一份
内存 DSL；Apply 只接纳源补丁，源变化后仍明确 Preview。任一表单的未应用字段都会阻止预览，
并提供 Apply／Cancel 说明。另一表单或手动编辑改变源后，旧检查标过期但保留字段，Refresh 后再 Apply。

## 一致性与边界

新增 `POST …/template-style-classes`，详见 [契约 §7.1](../contract.md#71-操作)。
请求绑定完整 revision map、根 layout handle、原文、草稿序号；仅 inspect／upsert／remove，
每次最多十六个不同类名。响应带顺序 UTF-16 补丁与确切 removedText，逆序可恢复原文。
修改只触及定义 token，保留其他声明、注释、空白、CRLF／LF；删除定义时也保留其 token 之间的注释。
文件、历史及发布状态不变。

前端在接纳补丁时原子预留当前根源文本。两个面板若在同一 React 批次同时收到旧原文的响应，
只接纳第一个匹配补丁；另一方保留字段并要求刷新，不发生最后响应覆盖前一修改。
请求后又输入的新字段也不会被旧确认清空。409 保留源草稿；技术错误保留原文和字段。
服务只解析保存基准用于修订核对，不能把成功的设置响应当作未提交 schema 的有效计算。

本切片没有 schema class 分配、改名或引用重写、结构重排、共享 include 编辑、完整影响分析，
也没有通用 owner、源历史、正式文件 Save、构建或发布。G-A16／G-A17／G-A18 仍为部分完成，
G-A19–G-A21 与 D-A1 保持原计划。没有修改计算原语、内核、依赖或 Template Engine 仓库。

## 已完成的专项检查

| 检查 | 结果 |
| --- | --- |
| Workbench 源服务 | 14 项新测试通过；含真实 preset、空／中性声明、引用范围、删除回退、UTF-16 正反补丁、CRLF／注释、预算与修订冲突 |
| 真实 HTTP | 8 项新测试通过；严格请求形状、令牌／方法／查询／包宿主、完整修订、有效 schema、源文件字节保持；实际 Run 精确值仍为 20 |
| 界面与客户端 | 53 项专项通过：面板 13、预览页 26、客户端 14；包括双响应竞争、迟到确认、后续字段保留、创建冲突、readonly selector 和合成 IME |
| 静态检查 | 生成类型、专项 ESLint、TypeScript、受限 Kotlin 格式通过；ABI 仅新增三个 DTO 与一个方法，没有删除旧 API |
| 前端完整门禁 | format／lint／生成类型／TypeScript 通过；49 文件、465 项测试通过 |
| 构建与录制 | fixture 与新 live 构建通过；8 个 live 产物及完整依赖图排除原型／录制／golden；9 项隔离反例、32 个原录制状态摘要通过 |
| 文档 | 26 项测试、默认站点生成与复查通过：37 个 HTML、2071 个本地链接；6 份设计／用户文档的 94 个相对链接及锚点通过 |
| 完整 Gradle 门禁 | `check` 成功，8 分 20 秒／110 个任务；218 份 JVM 测试结果、990 项，失败／错误／跳过均为 0 |
| 真实 Chromium | 28 个流程通过：31 个预览响应、10 个根设置响应、10 个 class 响应，27 次源文件字节检查，0 失败 |

物理系统拼音输入法需要人工操作；合成事件不作为系统输入法通过证据。

## 浏览器证据

沿用前 21 个真实流程，增加真实 preset／局部贡献与最终 Paper 的区别、字段缓冲与 Cancel、
本地 class 创建与硬引用删除保护、null 与显式中性值、空闲定义删除和 preset 回退、迟到 class 补丁、
跨面板 Refresh／Apply。逐项验证实际 Paper、DOM 的 weight／tone／fill、候选精确值 20 与独立保存基准 20。
新 class 组合输入检查使用合成事件，并等待 React 更新后核对按钮状态，未替代物理输入法验证。

浏览器验证了 `:use` 阻止删除按钮和真实 preset 回退移除；删除失败 422、class 端点修订 409 则由
新 HTTP／Workbench 测试覆盖。浏览器原有 409 流程仍包含根设置与模板预览的两次明确外部修改。
预览过程本身未写工作区源文件。

`build/ui-qa/template-preview/report.json` 保存实际请求、响应、补丁与文件指纹，并由现有 CI artifact 收集。
浏览器、服务进程组与临时目录均清理，报告标记 `taskProcessGroupsStopped` 和
`temporaryDirectoryRemoved`。取消请求分别为预览 2、根设置 2、class 1，不把迟到回复视为当前结果。
[实测完整页面](screenshots/style-classes/applied.png) 为 1425×1778，报告 `completePage: true`。
本轮文档站检查为默认静态快照，没有执行带全部 KDoc／应用制品的严格站点发布。

重跑：

```sh
./gradlew --no-daemon :mantra-cli:installDist
MANTRA_TEST_CHROME=/path/to/installed/chrome \
  npm --prefix workbench-ui run test:template-preview:e2e
```
