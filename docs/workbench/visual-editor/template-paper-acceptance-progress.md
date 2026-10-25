# Paper 展示验收 v2：实施与证据

> 2026-10-25；合同 `e28922d`，核心实现 `1de3df8`，G-A20 的展示层切片完成。
> [合同](template-paper-acceptance-contract.md) · [用户说明](../../site/template-paper-acceptance.md) ·
> [固定套件](../../examples/template-paper-acceptance/suite.json) · [Draft PR #15](https://github.com/6234456/mantra/pull/15)

## 交付

v2 在同次真实候选的 Paper 上检查标题、表存在性／标题／样式、完整有序列、单元格显示文本、
editable 与最终 weight/tone/fill。表／列 ID 必须唯一；单元格以 row 与结构化 address 的交集匹配，
多匹配明确报错，不取首项。缺失父表／列不能满足“目标行不存在”的断言。
row 的省略／显式 null 区别和 address 中完整维度顺序保留，不依赖 Run 的未转义坐标拼接键。
矩阵／转置、斜线成员和最终样式来自实际 Paper，空成员及外部 case 的匹配边界另有协议测试。

`template-capture --format 2` 明确生成 `mantra.template-acceptance/2`；每案例 expect 仍为 null。
verify 自动读取版本；报告 `mantra.template-acceptance-report/2` 每 case 必含完整实际 paper 或未计算的 null。
报告中的值／诊断／Difference／Paper 来自同一候选；断言错误及末尾源冲突不会用保存基准伪造候选。
末尾冲突保留先前候选证据并将状态作废。源图检查、案例继续、退出码和输出文件保护沿用首批实现。

JSON Schema 独立于 v1：
[suite v2](../schema/template-acceptance-v2.schema.json)、
[report v2](../schema/template-acceptance-report-v2.schema.json)。
旧 v1 suite/report schema 相对 `546500f` **字节不变**；默认 capture 与显式 --format 1 实测输出字节相同。
v1 严格解析仍拒绝 paper；v1 报告不增加字段。HTTP v4、生成类型、公共 JVM 库 ABI 和 DSL 均未改动。

v2 合并预算为每案例最多 256 个 value/table/cell/title 目标、每套 2048 个，列组另限 64。
其他 strict JSON／UTF-8／源图／运行预算保持。逻辑断言路径保存规范选择器，经 JSON Pointer 段转义；
它是报告目标身份，不声称可直接在原始 Paper 上寻址。columns 的 expected/actual 用有序 `[id,header]` 对。

## 最终自动检查

`./gradlew --no-daemon --max-workers=2 spotlessApply check :mantra-cli:installDist` 成功，
用时 1m 2s，114 个任务（20 执行、94 up-to-date）。实际 XML 汇总 **227 suites／1053 JVM tests**，
失败／错误／跳过均为 0；本轮新增 **25 项**，含固定四场景示例及其真实报告的 schema 验证。
格式、边界、源码大小、诊断、ABI、测试数量和前端 check 全部通过。

测试包括：v1 默认／显式版本／严格拒绝及报告结构；v2 字段／类型／预算／规范选择器；
仅改 title／precision／class 的真实 Paper 变化而 Run 值不变；零值隐藏与 includeZero；
矩阵精确二维地址、斜线成员和转置；预期错误、父列拼错、重复父对象、重复行／外部 case 的拒绝；
初始技术无效时 paper=null，末尾冲突保留旧 Paper；多个独立案例继续；实际源字节不变。
展示预期来自明确 DSL 选项和独立算式，不以当前输出重新生成预期。

全量检查前格式器曾报告重排后行长问题；使用缓存中同一固定版本 ktlint 1.7.1 规范化测试源，再由
未修改的 Spotless／完整门禁确认通过，没有禁用规则或改变预期来绕过失败。

没有修改前端源码、预录 32 状态、内核、依赖版本或其他仓库；本轮没有重跑浏览器、536 项前端测试或
Template Engine。前端 check 随全仓库任务执行。公共接口及 v1 schema 的不变性另按差异核对。

## 独立安装版 CLI 复测

使用真实 installDist 程序，复制固定示例到隔离临时工作区：**7 组流程／10 次命令**。

| 流程 | 实测 |
| --- | --- |
| 固定四场景 | exit 0；4 passed／59 断言；标题 Candidate paper、Item/Amount 列序、30,000 的 bold/accent/subtle、德语 3,750、二维 7,000 及零值可见性通过 |
| 预期 weight 改为 normal | exit 3；实际仍为 bold，报告保留完整候选 Paper |
| 反转预期列顺序 | exit 3；实际列序仍为 Item/Amount |
| 父列拼错 | exit 2；即使 present=false 也不能通过，实际 Paper 保留 |
| matrix 用非唯一 node 选择行 | exit 2；A/B 两行歧义，实际 Paper 保留 |
| 外部明确追加 schema 注释 | exit 2；四场景均 conflict，未计算 Paper 为 null |
| default／显式 1／显式 2 capture，再 verify 空 v2 预期 | 三次 capture 均 0；default 与 1 字节相同，2 只改格式；空预期 verify 为 2 |

完整退出码序列：`[0,3,3,2,2,2,0,0,0,2]`。
10 次命令各自前后比较全部工作区文件 SHA，字节相同；外部 schema 注释的施加／恢复明确单列，
不归因于应用写入。原始示例未改变，临时工作区已删除。8 份实际 suite／7 份实际 report 独立通过
Python Draft 2020-12 验证；固定 suite 与实际 Paper 另经 JVM schema 测试。

真实证据已归档：[摘要](paper-acceptance-evidence/summary.json)、
[四场景通过](paper-acceptance-evidence/good-four-scenarios-report.json)、
[样式错误](paper-acceptance-evidence/style-mismatch-report.json)、
[列序错误](paper-acceptance-evidence/column-order-mismatch-report.json)、
[父列错误](paper-acceptance-evidence/missing-parent-column-report.json)、
[歧义行](paper-acceptance-evidence/ambiguous-matrix-row-report.json)、
[源冲突](paper-acceptance-evidence/external-source-conflict-report.json)、
[空预期](paper-acceptance-evidence/unfilled-v2-expectations-report.json)。根代理逐份核对报告摘要。

固定 suite SHA-256：`4bc16525d66a9eabd2671fc424a1dcee830025e0323a8c50e467f9410b19942b`。
通过报告 SHA-256：`bd34bdf3a7e2360b1dccdee941e06ecb8f81f0754dc20fdf697943ce3da3c06c`。
实际版本：Mantra `1.0.0-rc.1`／Normein `0.3.0`。原始变体／脚本／日志位于
`/workspace/.cloud-setup/paper-acceptance-smoke/`；最终全量日志
`/workspace/.cloud-setup/paper-acceptance-full-check.log`。

文档 26 项测试通过，默认静态生成与 check-only 均为 40 HTML／2139 本地链接通过；
未进行要求全部 KDoc／发布制品的严格站点发布。

## 保留边界

G-A20 仍是部分完成：输入与 Run 断言仅标量，Paper 的结构化坐标检查不等于扩展了输入或 Run wire。
多案例视觉 UI、服务端验收记录、PDF/XLSX 视觉／重算验证、正式 Save、服务端源历史／草稿、D-A1、
构建／发布仍待后续。Template Engine 完整兼容仍为 false。本记录不代替 Claude 的设计签收、
真实中文系统输入法验证或远端 CI 的实际状态。
