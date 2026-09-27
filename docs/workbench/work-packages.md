# 工作台工作包（交给 agent 的任务单）

> 状态：实施中 · 2026-09-27
> 配合[工作台契约](contract.md)与[界面规格](ui-spec.md)使用。每个工作包交给一个独立的 agent 会话，产出一份可以审阅的变更。

## 当前进度

| 工作包 | 状态 |
| --- | --- |
| WP0、WP2、WP3、WP5、WP13 | 已实现、审阅并合入主分支；三个方案的 golden 与 ESt 参数比较 golden 已验证 |
| WP8 | 只读界面已合入；Schema 生成的 TypeScript 类型、三个 golden 流程的浏览器端到端测试已通过（`1f6a324`） |
| WP7 | 工作区、结构、结果、纸面、诊断、参数和 Compare POST 已合入（`3c60c53`）；Explain、编辑、SSE 和导出端点待接入 |
| WP1 | 新 Normein 接入已在独立分支 `codex/wp1-normein-adoption` 完成并审阅，锁定已发布的 `0a3ae1de`（language 25 / stdlib 33）；全套测试通过，待合入主分支 |
| WP4 | Explain 已安排从 WP1 分支另起隔离分支实施 |
| WP6、WP9–WP11 | 尚未合入；依赖关系见 §2 |
| WP12 | 导出视图在独立工作树实施中 |

四个验收案例的版式现已写入案例文本，fixture 与 live 服务依同一 `:layout` 绑定选择版式。

## 1. 交付方式

1. **先纳入版本控制（WP0）。** 每个工作包在自己的分支或工作树中完成，审阅后再合并。否则多个 agent 会在同一目录里互相覆盖，也无法审阅和回退。
2. **固定的必读顺序。**
   1. `CLAUDE.md`
   2. `docs/architecture.md`
   3. `docs/engine-application-boundary.md`
   4. `docs/workbench/contract.md`
   5. 前端工作包另读 `docs/workbench/ui-spec.md` 和对应画板
   6. 本文中该工作包的条目
3. **契约变更先行。** 实现中发现契约不足时，先修改 `contract.md` 并写入变更记录，确认后再改代码和 golden 文件。代码与契约不能不一致。
4. **独立审阅。** 每个工作包完成后，由另一个会话按 §5 的清单审阅，只提意见、不改代码，然后再合并。
5. **通用完成定义**（适用于所有工作包）：
   - `./gradlew test` 全部通过；涉及前端时，前端单元测试和端到端测试也要通过。
   - 新原语、新契约字段都有测试；数值断言来自独立来源（CLAUDE.md）。
   - 不修改 Normein（`.deps/normein`、`~/IdeaProjects/xrechnung`）；内核需求写入 `docs/rfc/`。
   - 引擎、工作台、服务和前端都不引入领域名词。
   - 同步更新受影响的文档（契约、界面规格、架构）。
   - 最终报告列出改动的文件、测试命令与结果、与契约或画板的偏差，以及遗留问题。

## 2. 依赖与阶段

```text
阶段 A  WP0 版本控制 ──► WP2 只读结果视图 ──► WP3 契约 JSON 与 golden
        WP1 采用新内核（已锁定提交，待合入）

阶段 B  WP4 Explain（WP1、WP3）        WP5 Compare 与参数分层（WP3）
        WP6 案例回写（WP1、WP3）        WP13 呈现提示（WP2）
        WP8 前端外壳·只读（WP3 的 golden；无需等待服务端）

阶段 C  WP7 mantra serve（只读端点在 WP3 之后即可开始；编辑端点需要 WP6）
        WP10 前端编辑（WP5–WP8）    WP9 公式编辑（WP1、WP6–WP8）
        WP12 导出视图（WP7、WP8）   WP11 数据接入（D1、WP6、WP7、WP10）
```

Normein 提交 `0a3ae1de` 已发布，WP1 已通过干净锁定检出的全套测试。WP4 Explain 与 WP6 案例回写可接续 WP1；WP7 的导出接口、WP10 的参数与诊断界面，以及 WP12 导出视图可并行推进。WP9 需等 WP6 的编辑接口；WP11 需等 WP6、WP7、WP10 的本地工作完成。

## 3. 工作包

### WP0 版本控制与基线（由你决定和执行）

- 在仓库根目录执行 `git init`。`.gitignore` 包括 `build/`、`.gradle/`、`.deps/`、`examples/build/`。
- 建立基线提交。可选：一个运行 `./gradlew test` 的 CI。
- 完成标志：基线提交存在，工作树干净。

### WP1 采用新 Normein 内核

- **依赖**：Normein 候选变更（语言 25 / 标准库 33）已提交。
- **范围**：
  - 更新 `normein-build.lock` 和 `.deps/normein`。
  - 按 RFC 0001 的迁移说明更新 `NormeinRfcContractTest`（C、D、F、I 等项翻转），更新 RFC 0001 的分类（G 改为“内核已修复”）。
  - 启用 `hostPosition`，删除 Mantra 的二次坐标重锚。
  - 若以下三项缺陷尚未修复，一并修复：
    1. 字面量分类改用 `DslFormLiterals`，与内核一致；
    2. 值上限异常转为诊断，不再抛出；
    3. `Qualified.rewrite` 只改写符号，不改写字符串和关键字。
  - 评估多形式读取（`readForms`）后 `include` 是否仍然需要。
- **完成定义**：全部测试通过；架构文档 §4.1、§9 与 RFC 0001 已更新。

### WP2 只读结果视图

- **依据**：契约 §5，缺口 G1。
- **范围**：
  - 在 mantra-core 中定义 `CalculationView`，覆盖结构、节点元数据、成员、值、trace 和诊断。
  - 把 mantra-render、mantra-excel 和 `StructureJson` 迁移到这个视图上。
- **不做**：JSON 格式的改动（归 WP3）。
- **完成定义**：
  - 有测试确认这三个模块不再导入 `engine` 包中的顶点类型；
  - 现有测试不修改也全部通过。

### WP3 契约 JSON v1 与 golden 文件

- **依据**：契约 §6.1–§6.4、§6.6，缺口 G2、G4。
- **范围**：
  - 新增 `mantra-workbench` 模块，写出 Structure、Run、Paper、Diagnostics，采用 §6.1 的值编码和 §6 的外层。
  - JSON Schema 放在 `docs/workbench/schema/`。
  - 三个方案的 golden 文件放在 `mantra-workbench/src/test/resources/golden/`。
  - 新增 CLI 命令 `mantra fixtures <case> --out <dir>`，为前端生成测试数据。
  - 诊断带地址，输入诊断指向案例中的值。
  - 按板块取表。
- **完成定义**：
  - golden 文件通过 Schema 校验；
  - `StructureTest` 的断言迁移到新格式；
  - 前端能直接用这些 fixture。

### WP4 Explain

- **依据**：契约 §6.5，缺口 G8。依赖 WP1、WP3。
- **范围**：
  - 对单个节点做 FULL trace 求值，输出 `steps`、`branches`、带来源的 `references`、`parts`，以及带差额的 `options`。
  - 遵守预算，超出时截断。
  - 新增 CLI 命令 `mantra explain`。
- **测试**：
  - ESt `ermaessigung-35a`：740 = min(15.676; 240 + 500)；
  - ESt zumutbare Belastung（分段计算）；
  - ESt `kinder-pruefung`（择优）；
  - IAS 36 分摊。
  - 所有数值都用独立来源核对。

### WP5 Compare 与参数分层

- **依据**：契约 §6.7、§6.8，缺口 G5。依赖 WP3。
- **范围**：
  - 两次计算的差异，差值由引擎计算，按主线和板块分组；
  - 参数各层的值；
  - 新增 CLI 命令 `mantra diff`。
- **测试**：ESt 2025 对 `params-2026` 的 golden，例如 zvE 从 83.217,90 变为 83.061,90。扩展 `verify_expected.py`，用 2026 参数独立复算。

### WP6 案例编辑与回写

- **依据**：契约 §7。依赖 WP1（需要子形式的起止偏移）和 WP3。
- **范围**：
  - §7.1 的全部操作；
  - §7.2 的最小改动回写；
  - 原子批处理、预演、撤销栈。
- **测试**：
  - 往返性质测试：用随机生成的操作序列，检查语义等价，以及编辑区间以外的文本逐字节不变；
  - 注释与格式保持不变；
  - 非法输入返回 422，且不写入文件；
  - 三个方案的案例都要覆盖。

### WP7 `mantra serve`

- **依据**：契约 §9。依赖 WP3–WP6（只读端点在 WP3 之后即可开始）。
- **范围**：
  - 新增 `mantra-server` 模块（建议 Ktor），CLI 子命令为 `mantra serve <工作区> [--port]`。
  - 端点按顺序实现：只读端点 → 编辑端点 → SSE。
  - 实现 §9.5 的安全措施和 §9.4 的资源上限。
  - 托管前端构建产物。
- **测试**：状态码、409、路径越界、`Host` 检查、令牌、资源上限。

### WP8 前端外壳（只读）

- **依据**：界面规格 §2–§4.4、§4.11、§4.12，画板 `Main`、`Panel`、`Pruefpfad`、`Guenstiger`、`IAS36`、`Navigation`。依赖 WP3 的 fixture。
- **范围**：
  - 项目初始化（技术栈见 D3）；设计令牌；外壳。
  - 总览；板块表（Staffel 与矩阵）；罗盘；面包屑。
  - 检查器：WP4 完成前，先显示 Paper 中的审计条目。
  - 来源树；择优视图。
  - 支持两种数据模式：fixture（golden 文件）和 live（`mantra serve`）。
- **测试**：
  - 组件测试只用 fixture；
  - 三个方案的端到端测试；
  - 通用性检查：前端源码中不出现方案标识符。

### WP9 公式编辑

- **依据**：契约 §8.2，界面规格 §4.10，画板 `Anpassungen` 与 `IAS36`。依赖 WP1、WP6、WP7、WP8。
- **范围**：
  - 服务端：补全、悬停、检查，以及预览。
  - 前端：行编辑器（CodeMirror）和公式槽卡片。

### WP10 前端编辑：录入、参数、校验

- **依据**：界面规格 §4.5、§4.6、§4.9，画板 `Eingaben`、`Parameter`、`Pruefungen`。依赖 WP5–WP8。
- **范围**：
  - 录入：表单、表格输入、影响路径；
  - 参数：分层表和比较；
  - 校验：诊断列表和详情。

### WP11 数据接入

- **依据**：契约 §4.4、§8.1，界面规格 §4.7，画板 `Datenquellen`，缺口 G3、G7。依赖 WP6、WP7、WP10。
- **范围**：
  - 数据来源声明；
  - CSV 宽表模式；
  - 记录每个值来自哪个数据来源；
  - 导入流程与映射模板。

### WP12 导出视图

- **依据**：界面规格 §4.8，画板 `Export`，缺口 G6。依赖 WP7、WP8。
- **范围**：
  - 工作簿结构描述；
  - 工作表列表、预览、公式保真度报告和导出选项。

### WP13 呈现提示

- **依据**：契约 §10。依赖 WP2。
- **范围**：
  - `:sign-labels`：用例为 ESt 的 `abrechnungsergebnis` 和 SAP CO 的标准成本差异；
  - `:headline`；
  - `:group`。
  - 纸面、XLSX 和结构 JSON 同步支持。
- **完成定义**：mantra-core 或 mantra-render 中有测试，两个领域都有用例。

## 4. 任务单模板

交给 agent 时复制下面的文字，并填入尖括号中的内容：

```text
你负责 Mantra 工作台的工作包 <WPx 标题>，条目见 docs/workbench/work-packages.md。

先读：CLAUDE.md、docs/architecture.md、docs/engine-application-boundary.md、
docs/workbench/contract.md<；前端再读 docs/workbench/ui-spec.md 和画板 docs/workbench/design/<文件>>。

目标：<从工作包条目复制>
范围内：<…>
范围外：<…>。发现需要做时记为后续事项，不要顺手实现。
完成定义：工作包条目中的完成定义，加上 work-packages.md §1 的通用完成定义。

约束：
- 不修改 Normein（.deps/normein、~/IdeaProjects/xrechnung）；内核需求写入 docs/rfc/。
- 引擎、工作台、服务和前端不含业务逻辑，不引入领域名词。
- 契约不足时，先修改 docs/workbench/contract.md 并说明理由。
- 在分支 <wp-x-…> 上工作；未经明确要求，不合并、不推送。

结束时报告：改动的文件、测试命令与结果、与契约或画板的偏差、遗留问题。
```

## 5. 审阅清单

- **契约**：字段、值编码（十进制字符串）、错误代码和状态码都与契约一致。
- **W1**：编辑只落到文档；除撤销栈外，服务端没有私有状态。
- **W2**：前端显示的每个数字都来自引擎；没有按方案或节点 id 分支；没有领域词。
- **测试**：golden 文件通过 Schema 校验；数值断言有独立来源；有往返性质测试；有安全测试。
- **画板**：布局、令牌、各种状态和无障碍要求都已实现；与画板的偏差已记录在界面规格 §8。
