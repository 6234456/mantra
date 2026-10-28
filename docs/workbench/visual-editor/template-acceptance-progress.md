# 多案例模板验收 CLI：实施与验证

> 2026-10-25；G-A20 首个只读切片完成。合同检查点 `4d75deb`，核心实现 `e0d2148`。
> [合同](template-acceptance-contract.md) · [用户说明](../../site/template-acceptance.md) ·
> [案例套件](../../examples/template-acceptance/suite.json) · [Draft PR #15](https://github.com/6234456/mantra/pull/15)

后续已增加独立 v2 的 [Paper 展示验收](template-paper-acceptance-progress.md)；本文保留首批 v1 的
执行记录和边界，不将后续测试计入本轮结果。

## 已完成

- `template-capture` 取得真实完整源图与根 schema/layout 原文；expect 留 null，不能自动生成通过基准。
- `template-verify` 对同一候选模板运行多个案例／原始标量输入，用每个案例的新快照重新取得句柄。
- 手工独立预期支持标量存在性、精确类型与 Decimal、display、active、业务判定和整个诊断多重集。
  Decimal 不经 Double，null、缺失、0 与 false 保持区别；有维度目标明确报不支持。
- 每个候选复用真实 `WorkspaceCatalog.templatePreview`；实际值、诊断和 Difference 均来自引擎。
  运行失败负例可明确验收 succeeded=false；语法错误、输入类型错误或冲突不能据此通过。
- 初始匹配全部源修订与根 descriptor；执行末尾重读全部已捕获基准，早运行案例的依赖变化同样作废。
  单个失败不掩盖其他独立案例；状态及退出码区分预期失败、技术错误和冲突。
- 严格独立 JSON 格式、解码后重复键／UTF-8／预算检查、实际版本与 suite 字节摘要写入报告。
- 输出保护套件和实际源图，包括硬链接、符号链接及父目录别名；使用同目录临时文件原子替换。
  无法完整捕获／末尾检查失败时，只接受 stdout 或工作区外新文件，失败清理临时文件。
- 四场景的固定源套件与真实 schema 验证加入 CLI 测试，未来修改源字节会要求明确更新基准。

## 最终检查

最终 `./gradlew --no-daemon --max-workers=2 spotlessApply check :mantra-cli:installDist` 成功，
用时 1m 4s，114 个任务（22 执行、92 up-to-date）。实际 XML 汇总 **224 suites／1028 JVM tests**，
失败、错误、跳过均为 0；含 30 项新增验收测试。边界、源码大小、格式、诊断、ABI 与前端 check 通过。
首次全仓库 check 为 223 suites／1027 tests；随后新增固定示例／实际 schema 测试，再次检查得到上述最终数。
文档的 26 项测试通过，默认静态站点生成及 check-only 均通过（39 HTML／2116 本地链接）；
新用户说明和示例源码包含在站点中。本轮未执行要求全部 KDoc／发布制品的严格站点发布。

测试覆盖：两个实际案例、德语 1,25、超 binary64 Decimal、业务 finding、nil／false／缺失、维度目标拒绝、
完整源图缺键／根 descriptor 不符、无效输入后继续其他案例、未填写预期、运行失败负例、422 与负例区分、
末尾 include 改动／源图变得无效、硬链接／符号链接输出保护及源字节不变。
解析测试另覆盖严格 JSON／UTF-8、日期、深度／文本／总字节、未知字段／身份、超过 128 键的完整修订图。

前端源码、HTTP v4／生成类型、DSL、Normein 依赖版本与预录 32 状态均未改；本次没有重跑浏览器流程、
前端完整测试或 Template Engine。前端 check 随最终全仓库检查执行，不能将上一轮浏览器／536 项前端
测试计为本轮新执行。公共 JVM 库 ABI 无变化；CLI 增加现有 Jackson 的显式编译依赖和 schema 测试依赖。

## 安装版独立复测

使用实际 installDist 程序、临时复制的示例工作区，执行 **4 条流程／5 次独立命令**：

| 流程 | 实际结果 |
| --- | --- |
| 固定候选四场景 | exit 0，4 passed、42 个断言；30、-6、3.75、27021597764222979.0000000003 均为真实实际值 |
| 将独立预期改为 999 | exit 3；actual 仍为 30，其余三场景 passed |
| 外部明确追加 schema 注释 | exit 2；四场景全部 conflict，没有伪造候选结果 |
| 重新 capture，再 verify 空预期 | capture exit 0；verify exit 2，两案例 error |

各命令前后对全部临时工作区文件比较 SHA-256，字节不变；注释的施加／恢复由测试脚本明确操作，
不归因于应用写入。原始示例没有改变，临时工作区已删除。Python Draft 2020-12 独立验证了
3 份实际 suite 和 4 份实际 report；JVM 测试另验证仓库固定套件与其实际报告。

归档 [证据摘要](acceptance-evidence/summary.json)、[真实通过报告](acceptance-evidence/good-report.json)、
[预期不匹配报告](acceptance-evidence/mismatch-report.json)、[修订冲突报告](acceptance-evidence/conflict-report.json)
和 [空预期报告](acceptance-evidence/unfilled-report.json)。
摘要记录每次退出码、源摘要、实际引擎版本、断言与报告摘要；根代理核对通过报告和固定套件的 SHA-256。
引擎为 `mantra 1.0.0-rc.1 / normein 0.3.0`。原始报告、变体 suite、执行脚本与日志保存在该云工作区的
`/workspace/.cloud-setup/template-acceptance-smoke/`，完整 Gradle 日志为
`/workspace/.cloud-setup/template-acceptance-final-check.log`。

早期执行中的测试 fixture 使用了与内核 callable 重名的 amount，改为 base-amount；一个测试名超过格式
长度限制后缩短。公开示例的德语高精度原始输入使用逗号小数，精确结果仍为标准编码点号。
未修改内核、放松检查或从实际输出重新生成独立预期。

## 保留边界

G-A20 仅部分完成：此处是 CLI、单个共享根模板、标量覆盖及标量断言，不是多案例视觉 UI／服务端验收记录。
display 来自真实 Run；还没有 Paper 单元格／维度／表格断言或输入扩展。外部写入未被锁定，末尾是逐个修订
检查，不提供跨进程原子快照。正式 Save、源历史、服务端草稿、D-A1、构建／发布仍未实现。
Template Engine 的完整重算兼容仍为 false。未改 Invoice Parser、Template Engine 或旧 Mantra 工作区。
本记录是工程执行证据，不代替 Claude 的设计签收或真实系统中文输入法验证；远端 CI 仍需看实际运行结果。
