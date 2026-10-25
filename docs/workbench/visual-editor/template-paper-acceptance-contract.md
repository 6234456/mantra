# Paper 展示验收：独立套件 v2

> 2026-10-25；G-A20 的展示层切片。沿用真实预览和源修订守卫，先定义合同再实现。

## 兼容与命令

新增独立 `mantra.template-acceptance/2` 与 `mantra.template-acceptance-report/2`。
现有 `/1` 套件及报告 schema／默认 capture 输出保持原形；v1 严格 reader 仍拒绝 paper 字段。
`template-capture ... --format 2` 显式捕获 v2，省略 --format 或指定 1 均输出 v1。
verify 自动按套件版本读取；不接受 --format，不隐式升级旧套件，不改 HTTP v4、DSL 或内核。

v2 仍使用相同的根 documents、完整 case revision、原始标量 inputs 和独立 expect。
既有 values 断言仍限标量；不能通过 Run 的未转义拼接键读取包含歧义的维度坐标。
新增可选 expect.paper，只读取同次真实候选返回的 Paper，不再次渲染或计算。

## Paper 断言

paper 仅允许 `{title?,tables?,cells?}`，至少一个字段；tables/cells 可以是空数组。

- title：严格文本匹配 Paper.title。
- tables：每项 `{id,present,title?,style?,columns?}`，id 非空且唯一，至多 64 项。
  present=false 只允许 id/present。true 可选检查表标题、tiered/matrix/transpose 与 columns。
  columns 若提供，是最多 64 个唯一 id 的 `{id,header}` 数组，必须与引擎整组列按顺序完全一致。
- cells：至多 256 个选择器，每项仅允许
  `{table,column,row?,address?,present,text?,editable?,style?}`。
  table/column 必须非空；至少有 row/address 之一；两者同时给出时取交集。
  row 为 `{node,kind?,optionKey?}`，node 必须是非空文本或显式 null。
  kind 是现有引擎 RowKind 小写；optionKey 是非空文本或 null，省略表示不筛选该字段。
  address 必须且仅含 `{node,coord}`，node 非空、coord 为最多 8 个文本成员的完整有序数组。
  它精确匹配所选单元格的结构化 address，case 必须为空，不能退回 Run 拼接键。
  Paper address 中的空文本／斜线成员保留原样，不做编码、拆分或数值转换。
  row 选择器用于无 address 的标签等单元格；数字位置／anchor 不能作为稳定 owner，首批不提供。
  present=false 禁止 text/editable/style；true 至少明确检查其中一项。
  text 是引擎最终显示文本，editable 是引擎布尔值。
  style 是非空的部分最终样式：weight normal/bold，tone default/muted/accent，fill none/subtle/accent；
  只比较已提供字段，不能用 preset/class 的声明贡献冒充最终 Paper 样式。

每个目标 table ID 和 column ID 必须在实际父集合中唯一；无论 present 真假，父 table/column
缺失或重复均报技术 error，不能把拼错列名当作隐藏行已通过。cells 只对目标行/单元格的存在性断言。
选中单元格多于一个均 error，不取首项；零个才按 present 比较。
重复选择器按 table/column/row/address 结构身份拒绝，JSON 键顺序不影响身份。
不同但重叠的选择器均可执行，任何歧义各自报错。

v2 每案例 values + tables + cells +（如有 title 则 1）最多 256，整套最多 2048。
嵌套列数另限 64；其他原有 byte/depth/text/源图/输入预算和技术错误退出码保持。

## 报告与失败

v2 报告沿用 v1 字段，逐 case 另必含 paper：实际完整候选 Paper，未计算则 null。
paper 来自与 Run／Difference 相同的候选修订；末尾冲突仍保留旧证据并将状态标为 conflict。
v1 报告不增加此字段。版本和字段均有独立 schema，不能用旧严格 schema 验证 v2。

assertions 仍为 `{path,expected,actual,passed}`；Paper 字段以精确字符串／布尔／样式枚举比较。
列组比较在 expected/actual 中用 `[[id,header],...]` 保存，保持精确值编码的结构约束。
cell path 使用规范 JSON 序列化选择器经 JSON Pointer 段转义组成逻辑路径，保留完整选择器，
不能以匹配到的行号作为失败目标身份。报告 path 是逻辑目标路径，不承诺均可在原始 Paper 上解引用。
匹配过程中无法确定唯一对象时 error（退出 2）；确定对象但内容不符时 failed（退出 3）。
多个独立案例仍继续，最终源图重检、输出保护与未填写预期的行为和 v1 相同。
Capture 不添加 paper 预期，不把真实 Paper 自动转成通过断言；由用户独立指定。

## 保留边界

本切片没有多案例 UI、table／维度输入覆盖、Run 维度断言、PDF／XLSX 视觉或重算认证、
正式 Save、服务端验收记录、构建／发布。Paper 验收只说明本次引擎表格模型符合所给断言。
Template Engine 完整兼容仍为 false；G-A20 仍标记部分完成。
