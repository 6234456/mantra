# Invoice Template Builder 对照：功能缺口与高级设置

> 2026-10-24 初始规划；2026-10-25 更新：**根版式 14 项设置已实现，G-A16／G-A18 部分完成**。
> 依据 Mantra `62f004d` 与 [Invoice Parser `1cbf0efa`](https://github.com/6234456/invoice-parser/tree/1cbf0efa78556d2aa4b1db23552176ce91b13b99)。
> 2026-10-24 对参考仓库只读核对源码和测试断言，没有运行 Invoice Parser，历史截图和验证记录不作为本次实测。
> 本文补充 [实施计划](implementation-plan.md)、[设计规范](design-spec.md) 与 [状态矩阵](state-matrix.md)，
> 不改变已接受的 S1 切片或 [原型复核结论](prototype-review.md)。

首批实现检查点为 `67149fc`，详见 [根版式设置实施记录](layout-settings-progress.md)。
独立 `/template-preview` 已提供根 layout 的类型目录、inspect／set／unset、显式／继承／根有效值，
并将源补丁接回当前草稿。Apply 后仍须真实预览；操作绑定完整源修订与当前文本，409 保留草稿。
它不包含通用单元格／表级／样式目录，也不提供正式文件 Save、源历史或发布。

## 1. 需要借鉴的能力

Invoice Builder 与 Mantra 都以 DSL 为权威源，但解决的问题不同。前者编排发票打印页面，后者编排
受约束的计算表格。应借鉴源编辑、引用、设置与验证的机制，保留 Mantra 的行列、层级与有限样式模型。

| Invoice Builder 的实际实现 | Mantra 现状 | 本次补充 |
| --- | --- | --- |
| 属性、局部 DSL、完整源码、拖动等入口最终写同一份原文；局部片段绑定规范 source span | 录制 `/authoring` 有模拟 owner／补丁；独立 `/template-preview` 接受根文档原文 | 正式 owner／最小补丁继续用 G-A1／G-A3；所有新增设置共用 G-A18 的写入协调，不另建可写表格配置 |
| 表单保留未完成数字；局部与全局样式分别有 Apply／Cancel；迟到编辑核对原文后才能接纳 | 已有未提交缓冲、迟到预览丢弃和源历史模型 | 扩展到类型化高级属性、多个编辑入口和字段级待确认状态 |
| 模板级样式、局部覆盖、具名样式／格式、引用范围精确编辑 | 引擎已有 preset、style-class、style、use；原型只分配 class、显示最终样式 | 增加定义编辑与影响范围；格式先映射 Mantra 已有选项，不直接加入另一套 DSL |
| 可搜索字段目录：类型、参考值、空值过滤、复制表达式；零与 false 保留 | Structure、Explain、CLI catalog 已有；正式 schema 语言服务仍为 G-A5 | 按真实作用域提供输入、参数、节点、维度成员、函数、class 目录，不能用发票字段或集合首项规则代替 |
| 持久化 draft 与 active source 分离；用户 version 与 CAS revision 分离 | 浏览器恢复／JSON 备份已有；模板文件提交、构建／发布仍有缺口 | 保留恢复、正式保存、构建与发布的不同语义；继续 G-A6／G-A7／G-A10–G-A12，不创建平行的生命周期合同 |
| 预设库、参考单据、预览语言、模板视图与实际单据视图 | 四个 pattern；真实模板预览只覆盖根 schema/layout 与标量示例输入 | 增加可参数化实例化、验收样例集与预览上下文；继续保持真实引擎计算 |
| 根据选择规则／默认值选择模板并试算 | case 已有显式 layout／parameters 绑定及 setBindings；真实模板预览不允许改绑定／依赖闭包 | 优先复用显式绑定与试算；自动选择策略留在宿主／应用，不增加发票专用分支 |

### 参考源码

以下链接固定到本次检出的提交，避免后续 main 变化改变证据：

- [局部源码绑定](https://github.com/6234456/invoice-parser/blob/1cbf0efa78556d2aa4b1db23552176ce91b13b99/frontend/src/features/print-layouts/blockSource.ts#L14)、[共享样式 span](https://github.com/6234456/invoice-parser/blob/1cbf0efa78556d2aa4b1db23552176ce91b13b99/frontend/src/features/print-layouts/templateStyles.ts#L9)。
- [源编辑协调](https://github.com/6234456/invoice-parser/blob/1cbf0efa78556d2aa4b1db23552176ce91b13b99/frontend/src/features/print-layouts/usePrintLayoutDesigner.ts#L258)、[表单缓冲](https://github.com/6234456/invoice-parser/blob/1cbf0efa78556d2aa4b1db23552176ce91b13b99/frontend/src/features/print-layouts/BlockPropertiesPanel.tsx#L48)。
- [全局样式与覆盖](https://github.com/6234456/invoice-parser/blob/1cbf0efa78556d2aa4b1db23552176ce91b13b99/frontend/src/features/print-layouts/GlobalStylesPanel.tsx#L33)、[字段参考目录](https://github.com/6234456/invoice-parser/blob/1cbf0efa78556d2aa4b1db23552176ce91b13b99/frontend/src/features/print-layouts/PrintLayoutReferencePanel.tsx#L20)。
- [Save draft／Activate](https://github.com/6234456/invoice-parser/blob/1cbf0efa78556d2aa4b1db23552176ce91b13b99/frontend/src/features/print-layouts/PrintLayoutDesigner.tsx#L56)、[后端保存校验](https://github.com/6234456/invoice-parser/blob/1cbf0efa78556d2aa4b1db23552176ce91b13b99/backend/src/main/kotlin/com/invoiceparser/printlayout/PrintLayoutController.kt#L155)。
- [版本／修订协调](https://github.com/6234456/invoice-parser/blob/1cbf0efa78556d2aa4b1db23552176ce91b13b99/frontend/src/features/print-layouts/usePrintLayoutDesigner.ts#L353)、[格式 locale](https://github.com/6234456/invoice-parser/blob/1cbf0efa78556d2aa4b1db23552176ce91b13b99/frontend/src/features/print-layouts/TemplateLanguageField.tsx#L21)、[预览上下文](https://github.com/6234456/invoice-parser/blob/1cbf0efa78556d2aa4b1db23552176ce91b13b99/frontend/src/features/print-layouts/referencePreferences.ts#L16)。

## 2. 能力分层与边界

### 已有能力应补作者入口

Mantra 已有输入约束、条件、显式舍入、维度、聚合、校验、数值格式、列配置与样式规则，见
[DSL 参考](../../dsl-reference.md) 和 [复用指南](../../reusable-patterns.md)。
当前原型的有限布局属性只有 title／precision／hide-zero，见
[Inspector](../../../workbench-ui/src/authoring/AuthoringInspector.tsx) 与
[模拟 owner](../../../workbench-ui/src/authoring/simulated/sourceOwners.ts)。其他选项缺作者入口，不等于引擎缺功能。
独立真实预览页现已开放根版式 14 项设置；录制原型不因此变成正式作者服务，设置 Apply 也不代表文件保存。

### 不默认扩展为自由打印设计器

绝对定位、任意字体／颜色、图片库、旋转、水印、纸张拖动、发票抬头、PDF/A 与 ZUGFeRD 不列为本次必补缺口。
Mantra 继续使用 table／tiered／matrix、有限列配置和 weight／tone／fill。新呈现能力若确有需求，
应按 [原语完成定义](../../roadmap.md#23-原语完成定义) 单独评审，说明各输出目标的语义。

Invoice 的高级表单本身是硬编码 `BlockForm`／JSX，并没有现成通用属性 schema 服务可直接接入。
Mantra 的 G-A16 仍需覆盖更广的声明、作用域与目标；已实现的根版式目录不是参考界面的通用 SDK。

两个仓库同样写 `normeinVersion=0.3.0`，也不代表功能完全一致。Invoice 的
[源码锁](https://github.com/6234456/invoice-parser/blob/1cbf0efa78556d2aa4b1db23552176ce91b13b99/backend/normein-build.lock)
是 `91d69dfe9448e75a7cd2fe13970d5adcc8d58ec8`，Mantra 默认消费 Maven 制品。
本轮不变更 Mantra 的内核或依赖，不复制参考仓库实现；能力判断应包含实际制品／引擎指纹。

## 3. 新增功能缺口

以下 G-A16／G-A18 已实现根版式有界切片，其余范围和 G-A17／G-A19–G-A21 仍为**待实现**。
现有 `template-layout-settings` 合同、JSON Schema 与生成类型见 [工作台契约 §7.1](../contract.md#71-操作)；
后续扩展仍须先定义相应版本、请求／响应与错误。
G-A1–G-A15 的编号、未完成部分及 D-A1 的多文档保存决定继续保留。

| 编号 | 功能缺口 | 交付内容 | 依赖与验收重点 |
| --- | --- | --- | --- |
| G-A16 | 类型化高级属性与能力目录 | 按声明种类、作用域、源修订及输出目标给出属性类型、枚举／范围、是否显式声明、继承值、最终值、来源、可编辑原因、支持状态和引用候选 | 【部分实现】根版式 14 项的类型、编辑范围、显式／继承／根有效值及只读原因；更广属性／目标目录仍依赖 G-A1／G-A5／G-A9，不代表保存可用 |
| G-A17 | 结构化声明编辑 | 创建、删除、重排输入／维度／计算项／check／reconcile／表格列；编辑本地 style-class 和规则；检查 ID、依赖、类型及引用影响 | G-A1／G-A3／G-A15；不能用 DOM 行号定义语义位置；删除被引用定义先报告影响，不能静默级联 |
| G-A18 | 高级设置语义操作与编辑协调 | set／移除显式覆盖；保持 false、0、空字符串与省略不同。属性、公式、class、局部／完整源码共用源事务、序号、撤销和冲突处理 | 【部分实现】根版式 inspect／set／unset、顺序源补丁、原文／完整修订绑定及迟到保护；Apply 后经真实预览确认，更广操作／源历史／正式保存仍依赖 G-A3／G-A6／G-A7 |
| G-A19 | pattern 实例化与共享影响 | 参数契约、命名冲突预检、ID 映射和显式引用重写、依赖清单；显示共享 fragment／defn／样式的使用范围；明确编辑共享定义与创建独立副本 | G-A2／G-A8／G-A9；现有 include 不是参数化宏。共享 fragment 在真实草稿预览中的覆盖仍需扩展合同 |
| G-A20 | 多案例验收集 | 保存验收案例引用、基准修订、必要的预期值／finding／格式检查；同一候选模板分别预览多个案例，报告差异与失败 | G-A4／G-A10／G-A14；表／维度输入与绑定更改超出现有标量候选合同，须先定义。独立核对预期值，不能由当前输出自动生成通过基准 |
| G-A21 | 输出目标能力与兼容档案 | 按 Text／HTML／PDF／XLSX／Template Engine 及版本标明值、格式、样式和可重算支持；关联构建证据、fallback 与阻断原因 | G-A10／G-A11；未知／未验证区别于不支持。Template Engine 的完整兼容仍为 false，不得以开关隐藏真实失败 |

G-A16 的描述至少覆盖：属性身份、owner／文档、声明种类、类型、单位／比例语义、合法值、
适用条件、来源修订、显式值、继承值、最终值、是否允许移除覆盖、编辑权限与原因、
所需引擎能力、各目标支持状态。用于高级设置的数值输入保留十进制原文，由服务端转换；
不要照搬 Invoice 的前端 Number 转换，也不要从另一个项目复制数值范围或默认值。
现有 LayoutLanguageCatalog 只列语言形式，不等于这套属性模型。

引用目录优先使用已有 Structure／SchemaMap／catalog：搜索标签、ID、类型和说明，展示当前
案例中的精确值及来源；字段复制失败提供可选择文本。复制表达式必须合法于当前 owner 的作用域，
表格、成员映射和标量有不同形状，不能默认取集合第一项或把 `all.node` 变成标量。

## 4. 高级设置清单

所有设置先按源所属分组，再按选中对象过滤。首批已开放根 layout 的 title／subtitle、locale／language、
precision／percent-precision、negative／zero／grouping、hide-zero／show-inactive／expand-members、signed／row-numbers。
其余设置仍为后续规格；下面的源键均为 Mantra **已有 DSL**，不能据此宣称完整高级作者界面已实现。

| 设置组 | 设置与控件意图 | 写入位置 | 已有能力与作者缺口 |
| --- | --- | --- | --- |
| 输入定义 | type、default、optional／required、条件必填；原文／表达式输入 | schema 的 input／field，或其所属 fragment | 引擎已支持；目录、定义编辑和作用域检查待补 |
| 输入约束 | min／max、min-rows、keyword options、列类型／nullable、references | schema 的输入／表列声明 | 不把业务 finding 变为技术错误；类型／外键失败维持技术门禁 |
| 输入组织 | label／help／unit／group、group-titles | schema 的输入声明／元数据 | 值不在此处填写；案例数据在 Inputs／Sources 通道 |
| 维度 | per、members、from／key／title、parent／parent-key | schema 的 dimension／声明 | 检查成员键、父子关系与引用；保持维度声明顺序，不在前端分配数值 |
| 计算 | when、spread、aggregate sum／none／ratio | schema 的计算项／section | 表达式与引用从当前作用域目录取得；不让用户配置引擎求值顺序 |
| 计算舍入 | round 的位数与 half-up／half-even／half-down／floor／ceiling／down／up | schema 的 line／choice | 会改变精确结果与 trace；显示精度不是该操作 |
| 校验 | check 条件、reconcile 两侧／tolerance、severity | schema 的 check／reconcile | 原型操作数只读；需正式 owner、表达式检查和诊断映射 |
| 标签与可见性 | sign-labels、hidden、section 的 display | schema／fragment 的对应声明 | hidden 不等于不计算；隐藏定义仍从 Outline／Problems 定位 |
| 语言与数字区域 | locale、language | layout 元数据 | locale 为数字格式区域；现有 language 文本包为 de／en；不能照搬 Invoice 的多语言标题替换 |
| 数字显示 | format amount／percent／number／integer、precision、percent-precision | layout 全局及适用计算项的呈现属性 | 仅影响显示；有效值和适用范围由引擎目录给出，保留十进制值类型 |
| 数字符号 | negative minus／parentheses、zero、grouping、signed、operators | layout 元数据／operators | 负数样式与贡献符号分开；空 zero 文本与移除覆盖不同 |
| 行与成员 | hide-zero、show-inactive、expand-members、row-numbers global／table | layout 全局／适用 table | 不活跃和零值为不同状态；零值转换解释行仍按引擎规则保留 |
| 表结构 | tiered／matrix／transpose、columns、members／member、列 header／width／align、table／schedule／inline／hide | layout 的结构声明 | 扩展 G-A17，保持有限列与表模型；不是自由画布或任意单元格公式模型 |
| 报告信息 | title／subtitle、header、explain appendix／none | layout | header 选择已有字段；案例中的 subject／period 等事实仍写 case |
| 样式与复用 | style-preset、class 标签、本地 style-class、style selector 与 use | 标签在 schema／fragment；样式定义在 layout | 管理定义与应用分开，显示真实来源；weight／tone／fill 之外的自由 CSS 不开放 |
| 参数和数据绑定 | parameters、layout、params；sources 路径／映射／顺序 | case／parameters／外部输入文件 | 复用现有案例编辑与 Sources；不写进布局或编辑器私有状态 |
| 预览上下文 | 示例案例、面板、坐标、临时标量输入、includeZero、对比基准 | 临时请求／可恢复的编辑上下文 | 当前候选只允许根 schema/layout 与标量输入；预览更改不暗中修改案例绑定或 layout 的 hide-zero |
| 验收与输出 | 验收集、目标／版本、兼容状态、构建／产物修订 | 规划中的工作区验收记录／构建记录 | 需 G-A20／G-A21 与正式合同；不得作为额外计算模型或直接打开发布 |

### 覆盖、继承与恢复

每项属性显示来源与当前范围，例如“layout 全局／本 table／schema 呈现提示”。不假定所有属性
共用同一条优先级链，最终值由对应 reader／WorkingPaper 提供。

- 改值产生显式覆盖；false、0 与合法空字符串都可以是显式值。
- “恢复继承”移除当前范围的覆盖，先预览新的最终值；不把继承值复制回源文。
- “取消编辑”只清除尚未提交的本地缓冲；“撤销”回退已接受的源事务。
- 切换 preset 不展开成一份覆盖所有字段的快照；对既有显式覆盖给出差异与作用范围。
- 不适用、只读、不支持、能力未知分别说明原因；未知源选项保留原字节，不被表单重建删除。

class 的先后顺序不能当作 CSS specificity：Mantra 按 layout 规则声明顺序逐属性合并；
use 左到右合并后由显式属性覆盖，本地类和预设类的合并遵守
[现有规则](../../dsl-reference.md#34-reusable-style-classes)。具名日期／货币格式、defformat、
任意 Intl 选项不在当前 Mantra DSL 中，首批设置不承诺加入；若后续需要，另提带多输出语义的 RFC。

### 百分比与格式迁移

Mantra percent 显示使用比例：0.19 显示为 19 %，见
[NumberFormatter](../../../mantra-render/src/main/kotlin/com/xqiou/mantra/render/paper/NumberFormatter.kt)。
Invoice 的[手册](https://github.com/6234456/invoice-parser/blob/1cbf0efa78556d2aa4b1db23552176ce91b13b99/frontend/src/manual/pages/invoice-layouts/styles.ts#L63)
区分百分数类别与显式 percent style：前者使用百分点 19，后者使用比例 0.19。
本轮未运行其格式引擎，引用的是仓库约定。属性目录须标明单位／比例语义，迁移需要用
0.19、19、零、负数和舍入边界做双方实测，不能因关键字同名就缩放数据。

## 5. 预览、草稿与版本

Invoice 的[模板视图投影](https://github.com/6234456/invoice-parser/blob/1cbf0efa78556d2aa4b1db23552176ce91b13b99/backend/src/main/kotlin/com/invoiceparser/printlayout/PrintLayoutTemplateView.kt#L10)
会移除 def／defn、把条件改为 true、表格改为占位行，并把表达式替换为标签；它是排版结构视图。
Mantra 的 Paper／Run／Explain 必须继续来自同一真实候选：不强制条件成立、不填造成员／数字，
没有样例或没有当前候选时明确显示缺少证据。隐藏、零值和不活跃定义用 Outline／源码导航定位。
将来若增加结构示意，必须是独立视图，不能显示为已计算 Paper 或兼容证明。

预览选择、格式 locale、界面主题与持久化源设置分别处理。换示例案例或面板不得隐式保存案例，
也不得丢失模板未提交缓冲；响应至少匹配完整基准图、文档集、草稿序号及当前预览上下文。
共享 fragment、参数文件、表／维度输入或绑定变更超出现有候选能力时，保留草稿并说明原因，
不能退回已保存模板计算后冒称当前结果。

Invoice 允许技术无效的服务端 draft，但激活需重新编译。Mantra 当前规则保持：
本地恢复／JSON 备份可保存无效草稿，不代表模板已保存；正式模板 Save 的技术校验仍阻断，
业务 finding 不阻断，运行时失败可以保存源、不能成功构建。未来若提供服务端草稿须另定 G-A12／D-A4，
不借增加高级设置绕过 G-A7 或擅自决定 D-A1。

版本区分 schema 的语义版本、文档／图 CAS 修订、候选序号、引擎指纹和构建／发布版本。
用户更改版本文字不等于 CAS 成功，也不重置并发保护。共享源影响范围和当前预览证据必须可核对。

## 6. 首批实施顺序与验收

| 阶段 | 工作 | 可评审的完成条件 |
| --- | --- | --- |
| A0 目录与合同 | G-A1／G-A3／G-A16／G-A18；明确原文补丁、能力目录、错误及数据版本 | 每项设置有真实归属、类型、覆盖／继承语义和不支持原因；正式写入依旧等待 G-A7／D-A1 |
| A1 高级呈现设置 | 优先已有 locale／language、precision／percent-precision、negative／zero／grouping、hide-zero／show-inactive／row-numbers，以及 class 定义入口 | 同一源事务可预览、撤销；改变呈现不改变 Run 精确值；false／0／空字符串与移除覆盖分别验证；只实现已有引擎选项 |
| A2 结构与规则 | G-A17；输入约束、维度、when／round／aggregate、check／reconcile、表列编辑；G-A5 引用目录 | 至少两个不同领域的现有样例无需前端特例；技术错误与业务 finding 分开；引用与源范围准确 |
| A3 复用与验收 | G-A19／G-A20；参数化 pattern、共享影响、多案例／边界输入回归 | ID 冲突可解释，独立副本不影响共享源；期望值来自独立来源；每个候选与全部源修订对应 |
| A4 目标与交付 | G-A21 接入 G-A10／G-A11 | 目标支持状态有证据；fallback、精度／舍入差异、未知项不被隐藏；兼容未通过时不宣称发布可用 |

新增设置必须补以下有区分度的检查，而非只验证表单和实现产生相同 JSON：

1. 两处编辑竞争、输入法组合、未完成数字、返回确认后继续键入、撤销／重做、恢复与 409 保留原文。
2. 改显示精度后 Run 值不变；改计算 round 后 Run／Paper／Explain／XLSX 对同一显式舍入一致。
3. preset／本地 class／use／显式声明的真实来源与优先级；恢复继承后重新计算最终样式。
4. 必填／范围／对账 finding 允许保存；类型／引用技术错误阻断；runtime failure 不变成构建成功。
5. 未知属性、只读 package、共享 include、能力版本变化时不丢源文、不冒称可编辑；迟到响应不成为当前证据。
6. 表／维度、空集合、0／false／nil、比例／百分点以及 locale 边界；临时预览不修改生产绑定。
7. Text／HTML／PDF／XLSX 与 Template Engine 的验证范围分别记录，不能只凭浏览器外观判定可重算兼容。

2026-10-24 的初始交付为缺口、设置规格与实施次序，仅验证文档链接、固定源码证据及能力／编号一致性。
2026-10-25 的根版式表单、真实服务与浏览器验证另见 [实施记录](layout-settings-progress.md)；
没有据此验收 Invoice 运行、跨项目格式迁移或发布，其余实现仍按上述阶段推进。

## 7. 本轮另行落实的模板预览增强

用户补充模板预览需求后，已在独立真实 `/template-preview` 页实现面板／零值上下文、独立保存基准、
精确引擎差异、报告标题与空候选反馈。实现、13 个真实 Chrome 流程与截图见
[预览增强记录](template-preview-enhancements.md)，启动与操作见 [用户说明](../../site/template-draft-preview.md)。
这是现有 G-A4／G-A14 合同上的可用增强；后续根版式设置另见 [实施记录](layout-settings-progress.md)。
自动键入预览、完整高级属性编辑和 G-A20 多案例验收仍未实现。
