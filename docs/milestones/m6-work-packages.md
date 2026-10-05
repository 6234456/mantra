# M6 工作包：v1.0 稳定验收

> 状态（2026-10-04）：联合 `1.0.0-rc.1` 候选的实现与功能验收已完成；390 样本、实际 10k 和 JFR 已验收。
> 测量源码 `ae9af84bde3f82cc86787e908aab7be76ddecee9` 的远端 CI 已通过。
> 最终源码发行修订及 CI 见 `v1.0.0-rc.1` tag 与[发行候选记录](../release-candidate.md)的 release receipt。
> 稳定 `1.0.0` 和 Mantra Maven Central 尚未发布。
> 更新（2026-10-05）：默认构建与 CI 已使用公开 `normein-dsl:0.3.0`；标准准备器
> 在 `a1e6e65` 上的实际 TEST-key 签名、144 项 bundle、十次隔离消费者及清理通过。
> 维护者已暂缓 Mantra 生产 secrets 与正式发布；上述历史测量身份不改。

M4/M5 联合候选验收与发布边界见 [M4](m4-work-packages.md) 与 [M5](m5-work-packages.md)。
上述 2026-10-04 测量使用 Normein 0.3.0、`0a3ae1de844c92635fbbc03406a13cb0e8920c03` 的源码构建，
未改依赖源码。2026-10-05 的公共制品运行身份单独记录，不将其反标为历史锁定提交。
本阶段没有提出新的计算原语；两个中性应用只组合既有能力，独立 Fraction/Decimal 预期先行。

## 1. 工作包和已记录证据

| 工作包 | 候选已实现及实际验收 | 剩余收尾或证据边界 |
| --- | --- | --- |
| S1 规范 | [语言规范 v1](../language-specification-v1.md) S1–S7、目录/诊断/Nil 对应关系；文档规则和当前全量检查通过 | 说明更新后严格站点已重新生成/核对；最终发行身份见 tag/receipt |
| S2 一致性 | 独立 24 个完整程序、两个参数文档、冻结 SHA inventory、typed values/诊断效力；runner 24/24 及候选 CI 通过 | 有限 corpus 不是全程序或生命周期证明 |
| S3 兼容 | 六库全量 ABI、真实二进制反例、五个隔离 POM-only consumer、六份本地 POM/sources/KDoc 制品实际通过 | 公开 Normein 前提已解除；Mantra Central 生产配置与上传已暂缓 |
| S4 通用性 | energy-budget/project-portfolio 无新原语；十应用 package/三格式/独立逐值与当前完整检查通过 | 展示应用不构成专业建议或生产软件 |
| S5 安全 | 当前导入 16 项、LSP 21 项、HTTP 33 项含 36 stalled bodies、package policy/bounds/CAS、导出/read 与清理实际通过；见安全记录 | source review/入口回归，不宣称独立第三方渗透或完整 CVE 审计 |
| S6 性能 | 冻结源码 39 组/390 样本、全预算与独立 CSV 复算通过；实际 10k/1,120,000 精确比对；Combined export JFR 隐私/范围验收通过 | 单次 profile 不归因历史 M2 长尾；批量不是多轮分布 |
| S7 文档/开发体验 | 教程 300.00、六库 KDoc、26 site tests/严格站点；103 UI、13 browser flows、实际 package live/PDF/LSP 与清理通过 | 文档更新后重新生成摘要；原生 IDE/Excel GUI 未认证 |
| S8 发布 | [候选 CI 37220953941](https://github.com/6234456/mantra/actions/runs/37220953941) green；check/ABI/consumer/conformance/stdio/应用等门实际通过 | 历史源码发行修订与 CI 见 tag/release receipt；实际 TEST 准备单独通过，Mantra 生产配置与上传已暂缓 |

计数来自各自实际日志；当前 XML 可能被后续 targeted run 覆盖，不能相加为一次全量运行。
此前失败日志是问题发现记录，不能删除后称从未失败；修复需有新的实际执行证据。
XLSX 导入曾暴露两维事实/动态成员次序问题；修复后的导入与完整检查已实际通过，
历史失败仍留作问题发现记录。

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
- `STRICT_HANDLES` 是目录加载默认，不能默默降级；显式 `TRUSTED_LOCAL` 是合作式本地目录能力，
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

当前候选入口与限额/取消/清理回归已通过，详见[安全验收记录](../security-review-v1.md)。
后续新增修复仍须保留失败重现并实际复验，不能沿用旧证据宣布成功。

## 4. T2 性能冻结和验收口径

[RC 性能报告](../performance-v1.md)记录当前实际执行，[M3](../performance-m3.md)与
[M4 批量报告](../performance-m4.md)保留历史证据；全部原预算保持不变。
当前测量在固定设备、JDK 21、相同 JVM 堆/GC 和锁定内核下串行执行，
源码为 `ae9af84bde3f82cc86787e908aab7be76ddecee9`。测量前后全部清单一致，
25 个运行时 JAR 不变；测量期间没有并行构建、测试或修改实现。
原始 CSV 每组 5 次 warmup、10 次保留样本，30+9 组共 390 样本。
39 是 scenario/operation 的组合数，不是 39 个独立事实场景；所有尾样本都保留。
Python 独立重算 median、nearest-rank p95、max、数值/status 和预算，不修改原始行或换行字节。

独立复核已确认全部 39 组、390 样本及冻结预算通过，验证脚本没有运行引擎或重生成预期。
当前实际 10k 为 22,777 ms、1,120,000 次精确比对、333,767,296 字节 heap-pool peak 总和；
22 初始计划/会话、560,000 次实际求值、零运行期计划编译/evidence，22 次关闭全部成功。
历史 M4 的 20,875 ms 保留，不替换原始记录，也不把这一次批量写成多轮 median。
Heap-pool peak 总和不是 RSS、同时存活堆或累计分配量。

Combined XLSX 用实际 JFR recording 识别当前热点/分配/GC，记录范围必须明确：
现有 `--scenarios combined` 会跑全部六个操作，不能误称 XLSX-only。
此次隔离 export consumer 的 setup/独立值核对/warmup 位于 recording 外，
实际 workbook 构建/重算/序列化/关闭实录 41.029 s；CPU 样本 2,812、allocation 样本 1,774、GC 5 次。
六类敏感 metadata event 计数均为零，identity/privacy verification 为 PASS。
profiler 源码 SHA-256 为 `44bce2ed8da563fa632fbee39954e12e6f3b23746c49ae8c0f2f3bcea3ea608a`；
这是未插桩测量后的独立配置修订，生产 Kotlin 与计时 harness 未变，不能反标为 `ae9af84` 时已存在。
录制期间 workbook 序列化到 bytes，stop 后才 dump JFR，实际 FileWrite event 为零。
profile 不混入 390 个未插桩样本；未复现的 M2 长尾不作无证据归因。

## 5. 最终出口清单

当前 RC.1 的以下工程验收已完成；源码发行身份以 tag/release receipt 为准，稳定版发布另行决定：

1. 冻结实现全 `check`、十应用三格式/独立逐值和 conformance 24/24 通过。
2. 六库 ABI、二进制破坏反例和五个隔离 POM-only consumer 实际通过。
3. 当前安全/导入/权限/迁移/协议/socket/schema 回归及资源清理有实际记录。
4. 原预算内的 390 样本/独立 CSV 复核、实际批量和 JFR 范围/隐私验收全部通过。
5. 教程、六库 KDoc、严格十应用站点/目录/下载/链接已通过；最终文档后的清单已重建/核对（2,615 HTML / 66,473 本地链接）。
6. UI check/test/build、端到端、实际 package live 和 PDF 既定 QA 范围通过，清理已核对。
7. 测量候选 `ae9af84` 的远端 CI green；含最终文档/profiler 记录的发行修订及 CI 见 `v1.0.0-rc.1` tag 与[发行候选记录](../release-candidate.md)的 release receipt。

源码发行、离线文档、IDE 插件 ZIP、本地 Maven staging 与公开 Maven 上传分别记录。
最终源码发行修订和 CI 以 `v1.0.0-rc.1` tag 及[发行候选记录](../release-candidate.md)的 release receipt 为准。
Mantra Maven 上传尚未执行。公开 `normein-dsl:0.3.0` 的依赖前提已于 2026-10-05 解除。
标准准备器在 `a1e6e65a01aa39c792681a138beda31ab66af172` 上完成真实 TEST-key 全流程：
144 项 bundle 通过全部 GPGv 验签，五个 fresh POM 和五个 fresh Gradle 消费者通过；
临时 agent、私有 key home 及外层 TEST 密钥/bundle 均已清理。98 项 Python 测试通过，
其中包括七个准备器边界回归。手动 Central 客户端已有 26 项 mocked HTTP 测试，尚无实际 HTTP。
生产 namespace/signer/token 验证和上传仍由维护者明确暂缓；本地 TEST 通过不代表生产身份或发布。
详见[发布准备证据](../central-publication.md)。历史测量与 tag/CI 收据保留原身份，
公共依赖修订的 CI 与 390 样本/实际批量已有[独立复测报告](../performance-public-kernel.md)，
保持各自实际来源。候选验收不等于稳定版或公开 Mantra 制品已发布。
