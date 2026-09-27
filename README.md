# Mantra

财务与税务计算方案（Berechnungsschema）引擎。它把形式化、流程化的计算——德国所得税主线、IAS 36 总部资产分摊与减值测试、SAP CO 风格工单与产品成本归集——拆解为少量**内置的计算原语**与**内置的表格原语**，由用户用 [Normein DSL](https://github.com/6234456/normein) 组合表达，并生成 Staffel、矩阵或审计底稿式的计算表格与审计轨迹。

```text
schema.mantra  ─┐
case.mantra    ─┼─▶  mantra-core（依赖图 + Normein 精确求值）─▶ mantra-render（版式 DSL）─▶ HTML / Text
layout.mantra  ─┘
```

## 边界

* **引擎库**（`mantra-core`、`mantra-render`、`mantra-excel`、`mantra-cli`）只提供通用能力：计算组件（section/line/field/total/choice/slot/formula-slot/dimension，分摊、关系式汇总、分段、折现、横向合计）、表格组件（table/columns/col 与列内容函数）以及常用预设。**不包含任何具体业务逻辑。**
* **领域方案**（ESt 2025、IAS 36、SAP CO 风格成本归集等）由用户或领域应用定义；本仓库 `examples/` 中的三个方案只是验收样例，将来迁往 monorepo 作为独立应用。
* **Normein DSL 内核**以固定 commit 引用、不做修改；对内核的扩展需求写在 [RFC 0001](docs/rfc/0001-normein-dsl-kernel-extensions.md)。

## 快速开始

要求：JDK 21（Normein 以 17 为目标），Git。

```bash
scripts/bootstrap-normein.sh
```

在本机也可以直接从本地 Normein 仓库克隆（commit 见 `normein-build.lock`）：

```bash
NORMEIN_SOURCE=~/IdeaProjects/xrechnung scripts/bootstrap-normein.sh
```

构建并运行全部测试：

```bash
./gradlew test
```

安装命令行工具：

```bash
./gradlew :mantra-cli:installDist
```

计算 IAS 36 示例并输出 HTML 底稿：

```bash
mantra-cli/build/install/mantra/bin/mantra run examples/ifrs-ias36-corporate-assets/schema.mantra --case examples/ifrs-ias36-corporate-assets/case-ie8.mantra --layout examples/ifrs-ias36-corporate-assets/layout.mantra --format html --out out/ias36.html
```

计算所得税示例（终端文本，含审计轨迹）：

```bash
mantra-cli/build/install/mantra/bin/mantra run examples/de-est-2025/schema.mantra --case examples/de-est-2025/case-mustermann.mantra --layout examples/de-est-2025/layout.mantra --audit
```

计算工单归集与产品加权成本示例：

```bash
mantra-cli/build/install/mantra/bin/mantra run examples/sap-co-product-cost/schema.mantra --case examples/sap-co-product-cost/case-demo.mantra --layout examples/sap-co-product-cost/layout.mantra --audit
```

查看内置原语目录：

```bash
mantra-cli/build/install/mantra/bin/mantra catalog
```

启动只读工作台服务（仅监听本机回环地址）：

```bash
mantra-cli/build/install/mantra/bin/mantra serve examples --port 8080
```

服务提供 `/api/v1/workspace` 以及案例的 Structure、Run、Paper、Diagnostics JSON。
若已有 live 前端构建产物，可加 `--ui workbench-ui/dist` 托管页面；
Explain、编辑与 SSE 等接口将在相应工作包完成后接入，当前返回 501。

## 一个最小方案

```clojure
(schema demo/lohn {:title "Einkünfte aus nichtselbständiger Arbeit"}
  (param an-pauschbetrag 1230 {:reference "§ 9a Satz 1 Nr. 1a EStG"})
  (dimension person {:members [{:key :A :label "Person A"} {:key :B :label "Person B"}]})
  (section nsa "Nichtselbständige Arbeit" {:per person}
    (field lohn "Bruttoarbeitslohn" {:kz "110"})
    (field wk-ist "Werbungskosten lt. Nachweis" {:op :info})
    (line wk "Werbungskosten, mindestens Pauschbetrag" (max wk-ist an-pauschbetrag) {:op :minus})
    (total einkuenfte "Einkünfte")))
```

## 项目结构

| 路径 | 内容 |
| --- | --- |
| `mantra-core` | 方案/案例读取、模型、依赖图、Normein 集成与 `mantra.calc` 函数库、求值与追溯 |
| `mantra-render` | 版式 DSL、预设、WorkingPaper 网格模型、HTML/Text 渲染、公式解释器 |
| `mantra-workbench` | 工作区扫描、修订与只读契约文档 |
| `mantra-server` | 回环地址 HTTP 服务、安全检查与 live 前端静态文件 |
| `mantra-cli` | `run`、`check`、`catalog`、`fixtures`、`serve` |
| `workbench-ui` | 工作台 React 前端 |
| `examples/` | 验收样例（不属于引擎库）：`de-est-2025`、`ifrs-ias36-corporate-assets`、`sap-co-product-cost` |
| `docs/` | [架构设计](docs/architecture.md)、[引擎与应用职责契约](docs/engine-application-boundary.md)、[DSL 参考](docs/dsl-reference.md)、[RFC](docs/rfc/) |

## 验收样例的核对

* **IAS 36 IE Example 8**：分摊 19/56/75、分摊后账面 119/206/275、减值 0/42/4、集团测试 650 → 604 vs 720、总减值 46 全部复现；B 单元损失回分按最大余数法为 11/31（准则示例印为 12/30，但 42 × 56/206 = 11,42，没有单一舍入规则能同时得到示例的 12/30 与 C 的 1/3），见测试注释。
  资产权重是应用开放的 `formula-slot`；[自定义案例](examples/ifrs-ias36-corporate-assets/case-custom-weight.mantra)演示用户以 `(bind weighting …)` 提供加权公式。
* **ESt 2025**：§ 32a 2025 年税率公式（与 BMF 官方 EStH 2025 一致）、合并申报 Splitting、Günstigerprüfung、Soli 免征额、教会税、结算；预期值见 `examples/src/test/.../EinkommensteuerTest.kt`。
* **SAP CO 风格成本案例**：初级和次级成本分别归集并分配，工单实际与标准成本对比，按完工数量计算产品加权单位成本；来源、工单和产品三层金额对账。采用虚构数据，口径和边界见[案例说明](examples/sap-co-product-cost/README.md)。
