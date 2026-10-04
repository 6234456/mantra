# M6 工作包：v1.0 稳定验收

> 状态（2026-10-04）：S1–S7 已实现或接入实际专项验收，最终联合退出验收进行中。
> 未完成最终源码冻结、全量检查、性能、严格站点与远端 CI 前，不宣布 M6/v1.0 完成。
> 公开 Maven 发布仍有独立前置；本地 staging、GitHub 源码与公开制品分开记录。

M4/M5 的未完成退出项继续实施，见 [M4](m4-work-packages.md) 与 [M5](m5-work-packages.md)。
Normein 固定为 0.3.0、`0a3ae1de844c92635fbbc03406a13cb0e8920c03`，未改依赖源码。
本阶段没有提出新的计算原语；两个中性应用只组合既有能力，独立 Fraction/Decimal 预期先行。

## 1. 工作包和已记录证据

| 工作包 | 已实现及实际记录 | 未完成出口 |
| --- | --- | --- |
| S1 规范 | [语言规范 v1](../language-specification-v1.md)的 S1–S7、DSL/calc/内核/诊断目录对应关系；公开错误与 Nil 语义 | 最终文档/目录一致性和全量回归 |
| S2 一致性 | 与实现分离的 24 个完整程序、两个参数文档、冻结 SHA inventory、typed values/诊断效力与外部适配器协议；实际 runner 24/24 通过 | 最终源码重跑；不能把有限 corpus 当作 graph/输出/生命周期证明 |
| S3 兼容 | [兼容策略](../compatibility.md)、六库全量 ABI、二进制反例、五个 Java/Kotlin 实际 consumer；本地 POM/sources/KDoc staging 已运行 | 最终 ABI 与隔离 consumer 复验；公开 Maven 前置另列 |
| S4 通用性 | energy-budget、project-portfolio，无新原语；两应用有 package、英文 README、边界事实和独立算法；后续应用/金本运行已通过 | 十应用当前源码的全部格式、逐值和独立脚本联合检查 |
| S5 安全 | 包路径/摘要/容量、捕获 bytes、strict/trusted policy、链接权限、迁移字节/图 CAS、导出/read 预算、LSP 帧/实际来源读取和回环/CSRF 已实现并有专项测试 | 最新导入 round-trip/type/error/行预算修复、socket/schema 与完整资源清理复验；不能由较早安全专项绿推断全部通过 |
| S6 性能 | M3 的 39 scenario/operation groups、390 原始样本已归档且独立 CSV 复核；M4 10k 不同案例、1,120,000 exact comparisons、真实物理统计已测 | 最终冻结源码重跑全部 390 样本及批量、独立摘要复算、Combined XLSX JFR 实录与清理 |
| S7 文档/开发体验 | 英文 CLI/Kotlin 教程实跑，六库真实 KDoc、26 site tests；PDF 输出及页边界检查；91 UI 测试运行与 13 browser flows 记录；LSP/薄客户端/本地化专项通过 | 最新全量 UI、严格站点、最终 PDF/浏览器范围与 live package 路径验收 |
| S8 发布 | `check`、ABI/clean-consumer、conformance、stdio LSP、客户端、应用复算及浏览器步骤已登记 CI；本地 staged artifacts 可由隔离 consumer 使用 | 最终远端 CI、源码发行及清理；Maven namespace/signing/repository 与真实 Normein 制品未完成公开上传 |

计数来自各自实际日志；当前 XML 可能被后续 targeted run 覆盖，不能相加为一次全量运行。
此前失败日志是问题发现记录，不能删除后称从未失败；修复需有新的实际执行证据。
最新 XLSX 导入专项曾暴露两维事实/动态成员次序问题，其最终修复和复验是 S5 出口的一部分。

## 2. 一致性与独立来源

[`conformance/`](../../conformance/README.md)包含 24 个 schema/case 程序及两个参数文件，
外部 runner 不导入 Mantra、不读取已有 engine 金本、不提供 expected recording 模式。
预期从规范和手算/Fraction/Decimal 导出，库存摘要为
`e4f7b4c41891ed45b8cc2949b4caec0971c0f4ac99a6f96096eda9c1c01586ce`。
真正的适配器只用公开 Mantra/WorkbenchJson API，打印类型值、成功/校验状态与稳定诊断效力。

有限 corpus 覆盖 S1–S7 的数值和错误合同；case graph、package/参数期效/迁移、异步取消、
owner-thread 清理和 HTML/PDF/XLSX 生命周期由另行集成测试证明。不得从引擎输出回填独立预期。
Audit eventId 是真实运行事件身份，跨运行可能不同；测试只可保留关联地规范化 ID，
不能删除步骤/分支关联或向生产 trace 注入稳定假 ID。

两个中性应用验证连续期间、存量/流量、分摊、参数、条件、校验和矩阵的组合能力。
事实是虚构输入，独立脚本是算术核对来源；这些展示不构成工程、投资或人员配置建议。
十个应用均保持领域内容在 `apps/`，不发布为库制品。

## 3. T5 安全和诚实边界

最终安全验收必须在实际入口检查，不以检查源码或单个 schema validator 替代执行：

- 包清单、资源及参与来源受字节/行/容量限制，解析、摘要及来源图使用同一捕获 bytes。
  XLSX 错误单元格不可默默变成 Nil/default zero；CSV/JSON 字符解码与记录行控制不得旁路。
- strict directory policy 不能默默降级；显式 `TRUSTED_LOCAL` 是合作式本地目录能力，
  before/after identity/hash 检查不等于对恶意 concurrent rename 的隔离保证。
- 包/目录读取权限不自动授予 case 写入、跨包/跨文件 links 或数据访问权限。
  CLI 明确根文件能力不扩大 link/data 的授权范围；classpath/JAR 不可写，也不等于任意 ZIP 导入。
- 迁移 preview/apply 维持字节及来源图修订 CAS；token 有界，apply 不偷偷接纳变化后的新图。
  合作式文件锁和原子 replacement 的能力边界如实记录，不声称阻止不合作写入者的所有竞态。
- 纸面/export 共享独立 read epoch，不能每格刷新；返回制品不持有 reader/RunControl。
  导出预算覆盖 package 和 legacy 路径；LIMIT 映射明确，取消/deadline 不能被解析适配器吞掉。
- LSP 检查实际读取的来源字节、UTF-8、JSON framing、重复/trailing 数据及版本化编辑；
  只访问已授权工作区。HTTP 保持回环、来源/CSRF 防护和结构化地址/schema 验收。
- 所有 owner-thread sessions、executor、协议流、测试服务器和 browser profile 按真实生命周期关闭。
  错线程失败不能先修改 closed 状态而泄漏资源；当前失败不能换成旧成功金额/底稿。

任一新增修复先保留失败重现，再记录正常与限额/取消/清理回归；未通过前 S5 不关闭。

## 4. T2 性能冻结和验收口径

[M3](../performance-m3.md)与[M4 批量报告](../performance-m4.md)记录已执行证据及未扩大的预算。
最终运行在固定设备、JDK 21、相同 JVM 堆/GC、明确源码和 kernel identity 下串行执行，
测量前后源码/制品摘要一致；测量期间不并行构建、测试或改变实现。
原始 CSV 每组 5 次 warmup、10 次保留样本，30+9 组共 390 样本。
39 是 scenario/operation 的组合数，不是 39 个独立事实场景；所有尾样本都保留。
Python 独立重算 median、nearest-rank p95、max、数值/status 和预算，不修改原始行或换行字节。

批量已有 20,875 ms 的一轮实际 10k 记录，22 初始计划/会话、560,000 次实际求值、
运行期计划编译/evidence 为零、22 成功关闭；最终源码重复这项验收，不造多轮 median。
Heap-pool peak 总和不是 RSS，也不是同时存活堆或累计分配量。

Combined XLSX 用实际 JFR recording 识别当前热点/分配/GC，记录范围必须明确：
现有 `--scenarios combined` 会跑全部六个操作，不能误称 XLSX-only。
若采用隔离 export consumer，其 setup/独立值核对/warmup 位于 recording 外，
只录实际 workbook 构建/计算/序列化/关闭；profile 样本不混入 390 个未插桩验收样本。
未复现的 M2 长尾不作无证据归因，优化不能通过放宽既有预算宣布成功。

## 5. 最终出口清单

下面全部完成后，才能将 M6/v1.0 状态更新为完成：

1. 最终源码冻结；全量 `check`、应用三格式与独立逐值检查、conformance 24/24 通过。
2. 六库 ABI baseline 审阅/检查、二进制破坏反例及隔离 POM-only consumer 真正重跑。
3. 最新安全/导入/权限/迁移/协议/socket/schema 回归通过，清理检查有记录。
4. 全部冻结预算、390 样本、独立 CSV 复核、批量和实际 JFR 证据完整；限制和未归因部分可见。
5. 英文教程及六库 KDoc、十应用严格站点、目录/下载/本地链接/source digest 一致。
6. 最新 UI check/test/build、端到端和 live package 流程、PDF 全页 QA 的实际范围有记录。
7. 同一候选修订的远端 CI 真正通过，发行状态和资源清理记录准确。

源码发行、离线文档、IDE 插件 ZIP、本地 Maven staging 与公开 Maven 上传分别记录。
Public Maven 上传目前仍待真实 namespace/signing/repository 与对应 Normein 制品；
需要维护者配置时，先给出可审阅制品和具体缺项，再提出最终发布设定问题。
语言规范、兼容策略和包清单的存在不等于稳定版验收完成，也不以未执行的发布冒充交付。
