# SAP CO 风格的工单与产品成本案例

这是使用虚构数据的领域方案。它演示从费用科目和初级成本要素出发，将直接成本归属工单，将待分摊的初级成本及次级成本要素按分配基数分配到工单，再按产品汇总并计算产量加权的实际与标准单位成本。引擎只提供表格输入、维度、公式、精确分摊、汇总、审计轨迹和版式；归集路径与加权口径写在 [schema.mantra](schema.mantra) 中，由领域应用细化。

## 数据与口径

- `primary-postings`：费用科目/初级成本要素、直接归属或待分摊类别、工单及金额。`:references {:order-id order}` 由引擎检查非空工单编号是否有效。示例中费用科目和初级成本要素使用相同编号，符合 SAP S/4HANA 将成本要素作为总账科目管理的模型。
- `secondary-postings`：内部作业或间接费用形成的次级成本要素及来源成本中心。本例只接受已确定的次级金额，不演算上游成本中心的作业量和费率。
- `orders`：工单所属产品、完工数量、分配基数和标准单位成本。工单实际成本 = 直接初级成本 + 分配的初级成本池 + 分配的次级成本池；标准工单成本 = 完工数量 × 标准单位成本。
- `products`：按产品汇总工单。产品实际加权单位成本 = 工单实际成本合计 ÷ 完工数量合计；产品标准加权单位成本 = 工单标准成本合计 ÷ 完工数量合计。差异 = 实际成本 − 标准成本。

两类成本池分别用 `alloc/pro-rata` 按工单的 `allocation-base` 分配，按 2 位货币精度使分配额精确回总。引擎将单位成本行的 `:aggregate false` 识别为不可加总的度量规则，底稿和 XLSX 都不把不同工单或产品的单位成本直接相加。产品加权单位成本由总成本除以总完工数量得到，保留 4 位。XLSX 中的费用流水单元格可编辑，相关归集和对账公式会重算。

`order` 维度通过 `:parent product :parent-key :product-id` 声明工单→产品关系；引擎会拒绝指向不存在产品的工单。产品数量、实际成本与标准成本使用通用 `dim/rollup` 按该关系汇总；应用仍决定哪些成本进入这些指标。

产品差异行在方案中声明 `:class :variance`；[layout.mantra](layout.mantra) 用统一的 `(style {选择器} {样式声明})` 形式设置默认、差异行和产品差异金额单元格的样式。类标签与条件样式不参与成本计算，HTML 与 XLSX 都呈现同一受限样式规则。

## 演示结果

| 环节 | 结果 |
| --- | ---: |
| 直接初级成本 / 待分摊初级成本 / 次级成本 | 12,400 / 900 / 1,800 |
| 工单 O100 / O101 / O200 实际成本 | 5,450 / 4,350 / 5,300 |
| 产品 A：数量、实际成本、标准成本 | 150 / 9,800 / 9,000 |
| 产品 A：加权实际、加权标准单位成本 | 65.3333 / 60.0000 |
| 产品 B：数量、实际成本、标准成本 | 80 / 5,300 / 4,960 |
| 产品 B：加权实际、加权标准单位成本 | 66.2500 / 62.0000 |
| 实际总成本 / 标准总成本 / 差异 | 15,100 / 13,960 / 1,140 |
| 来源→工单、工单→产品的对账差额 | 0 / 0 |

从仓库根目录执行：

```bash
./gradlew :mantra-cli:run --args='run examples/sap-co-product-cost/schema.mantra --case examples/sap-co-product-cost/case-demo.mantra --layout examples/sap-co-product-cost/layout.mantra --audit'
./gradlew :mantra-examples:test --tests 'com.xqiou.mantra.examples.SapCoProductCostTest'
```

这是一份成本分析底稿，不执行 SAP 过账、工单结算、在制品核算或 Material Ledger 实际成本结算；标准成本也作为工单输入，没有实现标准成本估算流程。SAP 对[初级与次级成本要素](https://help.sap.com/docs/PRODUCT_ID/5e23dc8fe9be4fd496f8ab556667ea05/82c2b70ff84c4eebb14fe45c12d5aed4.html)、[工单成本借记与结算](https://help.sap.com/docs/SAP_S4HANA_ON-PREMISE/5e23dc8fe9be4fd496f8ab556667ea05/5c35d85275c8d142e10000000a4450e5.html)及[实际成本的期间单位价格](https://help.sap.com/docs/SAP_S4HANA_ON-PREMISE/5e23dc8fe9be4fd496f8ab556667ea05/6ff1d353ca9f4408e10000000a174cb4.html)有各自的业务定义；本例的产品加权单位成本只按已完工工单的数量与成本计算，不等同于完整 SAP 实际成本核算。

本例假定输入中的直接费用与待分摊成本池互不重叠，且次级成本要素的上游来源金额没有再次作为本例的初级成本重复计入。HTML/Text 底稿支持完整计算和审计轨迹；XLSX 中的费用金额和工单→产品关系均可重算。表格外键校验发生在引擎计算时；当前 XLSX 编辑器不提供工单编号下拉校验。
