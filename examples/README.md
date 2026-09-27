# 验收样例（不属于引擎库）

这些目录是**领域方案**，用来验证 Mantra 引擎的通用能力是否足以覆盖不同领域。它们只通过公开 API
（`Mantra.loadSchema / loadCase / calculate`、`Render.*`）使用引擎，与将来的独立领域应用完全相同，
计划在 monorepo 中迁为独立应用。

| 目录 | 内容 | 核对依据 |
| --- | --- | --- |
| `de-est-2025/` | 德国所得税 2025：R 2 EStR 主线、七类收入、Sonderausgaben、§ 33、§ 31 Günstigerprüfung、Splitting、Soli、KiSt、结算 | § 32a EStG 2025 官方公式；`verify_expected.py` 独立重算 |
| `ifrs-ias36-corporate-assets/` | IAS 36 总部资产：分摊、CGU 测试、损失回分、最小 CGU 组测试 | IAS 36 IE Example 8（IE69–IE79） |
| `sap-co-product-cost/` | SAP CO 风格成本流：初级/次级成本要素归集、分配到工单、产品加权实际/标准成本与差异 | 虚构数据，独立金额断言与成本流对账；[案例说明](sap-co-product-cost/README.md) |

每个目录包含 `schema.mantra`（方案）、`case-*.mantra`（案例）、`layout.mantra`（版式）。
测试位于 `src/test/kotlin`，渲染结果写入 `examples/build/out/`。
