# M5 工作包：开发者体验

> 状态（2026-10-04）：联合 `1.0.0-rc.1` 候选的实现与功能验收已完成。
> 测量源码 `ae9af84bde3f82cc86787e908aab7be76ddecee9` 的远端 CI 与冻结性能独立复核通过。
> 最终源码发行修订及 CI 见 `v1.0.0-rc.1` tag 与[发行候选记录](../release-candidate.md)的 release receipt。
> 不宣称稳定 `1.0.0`、IDE 市场或 Maven Central 已发布。
> 依据：[路线图](../roadmap.md) M5、R1/R5/R8、T4，
> [工作台契约](../workbench/contract.md) D2 与 [M4 工作包](m4-work-packages.md)。

## 1. 范围与约束

语言工具只进行静态读取和作者服务，复用公开语义及目录，不运行业务公式来产生诊断。
IDE 客户端是 stdio 薄适配，不包含引擎、不自行猜测名字替换、不从项目自动运行不可信命令。
参考工作台继续通用，数字、判定、来源和地址来自公开结果；界面不按应用 ID 分支。
公开 README、教程、DSL/API 与诊断说明使用英文；界面和代码说明支持英语、德语。
PDF 只呈现已经计算的底稿，不引入领域算法或调用浏览器生成 PDF。
Normein 继续锁定 0.3.0 原提交，没有以插件实现为由修改内核。

## 2. 交付与实际验收

| 包 | 交付 | 候选实际验收 | 支持或发布边界 |
| --- | --- | --- | --- |
| D1 语言服务 | 静态 core 服务、布局桥接、LSP/CLI stdio；诊断、补全、悬停、定义、引用、版本化 rename | 21 项 LSP 协议/UTF-8/来源边界测试；两种实际安装入口完成 initialize/navigation/hover/references/rename/shutdown 和清理；候选 CI 通过 | 只提供已声明的 LSP 能力，不运行公式做静态检查 |
| D2 薄 IDE 客户端 | VS Code language client；IntelliJ classic action client | VS Code 编译/5 项 Node 测试；IntelliJ 全平台编译/ZIP；13 项协议测试含实际安装进程、取消与清理；候选 CI 通过 | 原生 IDE 交互及 Community/plugin verifier 不在现有证据内；未上传插件市场 |
| D3 英文教程 | 四个 invoice 文档、CLI/public Kotlin、HTML/XLSX/PDF | 当前完整检查实际编译/运行，独立应缴 300.00；PDF 2 页视觉检查 | 发行文档和离线下载保留实际源文件 |
| D4 文档站点 | 离线生成器、函数/诊断目录、公共声明、六库 Dokka、摘要与链接检查 | 26 项生成器测试；最终 strict manifest 无 artifact issue，检查 2,615 HTML 文档和 66,473 本地链接 | 文档更新后严格站点/摘要已重建并核对；未 hosted 部署 |
| D5 展示库 | 十应用各页、README、DSL/case/manifest、独立来源和三格式 | 十应用包/独立数值/fixtures 及 13 条浏览器流程通过，候选 CI 通过 | 展示范围和简化有声明，不发布为税务或财务生产软件 |
| D6 PDF/打印 | 有界 PDF、分页/表头/页码/Unicode/审计，CLI/服务导出 | 自动边界测试与 long 5 页/lease 23 页；教程 2 页视觉检查；实际 package smoke 10 份 binary PDF 与 26 处日期来源 | 视觉 QA 范围如实记录，不等于所有输出逐页人工认证 |
| D7 诊断本地化 | 静态代码 EN/DE、未知 Normein fallback、可见原英文细节 | 最新 UI 20 文件 103 项测试及 check/build 通过；浏览器流程通过 | 稳定 code/原文不被翻译说明替换 |
| D8 参考工作台 | 通用校验/矩阵/prev/links、package/迁移、主题与地址导航 | package live 四只读页；输入 14→16→14、参数 14→28→14、迁移 14↔21；stale 拒绝、refresh 重预览、注释保留、进程/profile/文件清理通过 | 不按应用 ID 特化；目录默认 `STRICT_HANDLES`，`TRUSTED_LOCAL` 必须显式选择 |

表中测试数属于相应真实运行，不能把后续专项 XML 相加为完整测试清单。
当前修复已由全量检查和[候选 CI 37220953941](https://github.com/6234456/mantra/actions/runs/37220953941)
复验；历史失败和成功日志继续保留。390 个冻结样本与实际 10k 批量的独立核对见
[RC 性能报告](../performance-v1.md)，不以 UI 或站点成功替代数值证据。
[客户端说明](../../editors/README.md)明确当前支持矩阵：VS Code 正常语言功能，
IntelliJ 的 classic action 入口与诊断标记；未运行的原生 IDE 操作不写成已验收。

## 3. 目录、消息与地址契约

公开函数目录来自实际 Mantra 注册，内部 lowering helper 不公开。
诊断目录由代码核对；英语、德语说明按稳定 code 映射，未知内核 code 显式 fallback。
`Diagnostic.message` 原文仍是可见细节，不能被本地化说明替代。
语言服务使用 UTF-16 位置，只发布真实作者范围；缺失 source mapping 明确不可用，不造坐标。
Rename 拒绝不精确版本、关闭/过期 buffer、重叠编辑、resource operation 及根外来源。
客户端 restart、folder disposal 和 shutdown 必须释放自己创建的进程、流和 worker。

`mantra.workbench/4` 与 `mantra.packages/1` 各自版本化；后者包裹真实 workbench/4 文档。
工作台 schema 不能因为 UI 需要方便而重写计算值、来源身份或错误效力。
来源树按服务端的 references/parts 真地址继续，跨案例证据校验当前 source revision。
BUSINESS ERROR 仍允许合法输入保存；技术失败不能以旧成功底稿或金额补齐当前请求。

## 4. 站点和教程复验入口

协调完成构建后执行下列真实入口；本站点生成器不代替构建或金融核对：

```sh
./gradlew --no-daemon :mantra-cli:installDist :mantra-lsp:installDist :mantra-cli:verifyDocumentationExamples
./gradlew --no-daemon :mantra-core:dokkaHtml :mantra-render:dokkaHtml :mantra-excel:dokkaHtml :mantra-workbench:dokkaHtml :mantra-server:dokkaHtml :mantra-packages:dokkaHtml
mantra-cli/build/install/mantra/bin/mantra catalog > build/mantra-catalog.txt
python3 -m unittest discover -s docs/site/tests -p 'test_*.py'
python3 scripts/generate-docs-site.py --require-artifacts --catalog build/mantra-catalog.txt
```

应用测试及独立来源核对先生成全部展示输出；严格模式要求真实六库 KDoc 与全部配置下载。
生成清单记录文件字节 SHA-256，生成器验证本地 HTML/anchor，外部链接不冒称已访问。
严格离线站点已在最终文档更新后重新生成并通过摘要/链接检查。
Hosted 部署是另一个明确动作，离线 `build/docs-site` 不等于已经托管或公开发布。

## 5. 退出条件

D1–D8 在最终源码修订的全 `check`、UI 与实际 CI 通过；教程从文件开始实际计算并导出；
严格站点覆盖十个应用、真实 KDoc 与所有下载，目录/来源摘要/内部链接一致；
新能力在端到端测试及 live package 流程可用，PDF 的全页 QA 范围有记录；
测试与人工探针关闭全部自有进程、临时 profile、文件和 worker。

发行记录必须区分源代码、IDE 可安装 ZIP、离线站点、本地 Maven staging 与实际公开制品。
当前候选的功能、性能、安全与兼容检查已实际通过；最终源码发行修订及 CI 以 `v1.0.0-rc.1`
tag 和[发行候选记录](../release-candidate.md)的 release receipt 为准。
Mantra Maven 上传尚未执行。2026-10-05 公开 Normein 0.3.0 的依赖前提已解除，
默认构建与 CI 直接消费公共 DSL。标准准备器的实际 TEST-key 签名、144 项 bundle 验签、
十次隔离消费者运行及清理已通过，不能由此推导出生产身份或正式发布。
维护者已暂缓 Mantra namespace/signing/token 配置与上传；历史候选和站点证据保持原身份。
详见[发布准备证据](../central-publication.md)。
