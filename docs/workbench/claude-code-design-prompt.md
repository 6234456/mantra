# Claude Code UI/UX design handoff

使用说明：在本地 Claude Code 中打开 Mantra workspace，将下方正文作为任务提示词。允许读取另一个 Template Engine workspace；先核对本地状态，再按实际代码开展设计。已有 PR 地址从 Git 和 GitHub 查询，不要编造。

---

你负责 Mantra 独立可视化 DSL 编辑器，以及它与 Template Engine 并行使用时的 UI/UX 设计和可运行原型。请完成可评审的设计交付，不停留在建议清单，也不要擅自实现整套业务后端或发布服务。

## 工作区和现状

- Mantra：`/Users/qiouyang/Documents/Claude/Codes/mantra`；仓库 `https://github.com/6234456/mantra`。
- Template Engine：`/Users/qiouyang/Documents/Claude/Codes/template_engine`；仓库 `https://github.com/6234456/paramita-v2`。

Mantra 本次云端成果保存在远端任务分支 `codex/mantra-design-handoff-20261010`，尚未合入 `main`。若本地缺少以下文档或新增 DSL/pattern/style 能力，先 fetch、核对差异并安全整合所需提交，保留已有本地改动。

先检查两仓库的分支、HEAD、remote、未提交修改和已有 draft PR，记录可复现基线。不要用旧记录覆盖本地最新成果。阅读各自 `AGENTS.md`、`CLAUDE.md`；Mantra 重点读 `docs/template-directions.md`、`docs/workbench/visual-template-authoring.md`、`docs/workbench/contract.md`、`docs/reusable-patterns.md`；Template Engine 按 `.agentdocs/index.md` 阅读最新架构、UI 和 `.agentdocs/frontend/standalone-sheet-application-toolkit-design.md`。查看真实组件及已有界面，区分已实现、设计中和原型模拟能力。

## 已确定的产品架构

Mantra 自己提供独立编辑器，不需要运行 Template Engine 应用、后端或 OfficeSession。可视化网格和源码视图编辑同一份原始 DSL，不创建新的 UI DSL。共同作者流程为：编辑源定义 → Mantra 编译校验 → 使用示例数据预览 → 构建；随后才分叉为 Mantra 原生运行与派生 Excel 模板的 Office/Excel 运行。两个目标共用的是作者阶段，不是跨项目编辑器产品。

Template Engine 只在下游接收派生 Excel；它已有的纯 Excel 公式模板继续独立，不要求逆向转换成 DSL。下游实例有自己的输入和历史；直接修改生成公式属于派生版本的分叉，不能自动回写 DSL 或被下一次生成静默覆盖。为并行打开两项目设计一致的导航、快捷键、面板、来源标识及交付跳转，明确当前编辑对象、源版本和运行时，不能让用户误以为两边自动同步。

可按实际代码选择复用 Template Engine 中性的输入桥、选择交互、主题、停靠面板、状态胶囊及布局渲染模块；不要整体搬运应用、公式引擎、Session 或税务业务。现有完整 Canvas 宿主位于 app 层，React 18/source workspace 包与 Mantra React 19 的兼容尚未验证。列出最小依赖、适配与提取范围；模块复用是可选实现选择，不是 Mantra 编辑器的前置条件。

## 编辑和预览约束

公式栏、补全、引用和诊断使用 Mantra DSL，不使用 Excel A1 公式解释源码。每个可编辑属性须通过服务端签发的 owner 句柄关联源文档、声明和修订；显示坐标或格式化文本只能帮助导航，不能决定写目标。视觉操作形成最小源补丁，保留注释、空白和无关内容；源码编辑与网格撤销/重做共享源历史。包括文件、重复维度行和共享声明要显示实际归属；被零值抑制或不活跃的定义仍可定位；不可取得的 owner 只读。

正式提交时重新核对精确源文档与全部参与 graph 的修订，拒绝过期句柄；多文档修改先定义原子性策略。捕获 package 的模板定义保持只读，开发须显式创建授权可写的 workspace fork 并保留 include、映射及依赖身份；已有授权 case 输入编辑仍按其独立权限处理。

示例输入、参数和模板定义必须明确区分，不能依据颜色猜测可编辑格。所有值、Explain 和业务 findings 来自 Mantra；Decimal 用精确字符串传递，舍入显式。若复用 Office 展示模型，它只是可丢弃的只读缓存，不能伪造投影 runtime，也不能用 JavaScript number 重新计算金融值或选区汇总。

设计本地恢复草稿、正式源保存、校验、构建和发布的不同状态：未保存、保存中、已保存、预览中、无效草稿、上一有效预览、版本冲突、只读及构建失败。恢复草稿可以保留未完成输入，但不得冒充已校验源提交或可发布模板。语法/类型/引用/循环依赖错误拒绝正式提交；运行时部分结果保留失败状态，不能显示成功构建。业务 findings 保留计算结果，并不自动阻止保存。发布前按目标能力验证，明确不支持语义和兼容报告。

预览去抖，携带草稿序号及源/依赖修订，忽略过期响应；取消请求不足以防止旧预览覆盖新输入。当前公式预览返回 Run/差异，并没有完整 draft Paper，原型必须标注这个接口缺口。现有 ExcelExport 已保留/翻译公式及报告 fallback；复用现有 compile → seed case calculation → ExcelExport 路径。不能称可复用模板发布器、Template Engine 导入重算兼容或 Excel 等价性已经实现/验证，也不要求新增公共编译 IR。

## 首版范围和交付

首版覆盖一个真实 scalar panel：标题、标签、注释、公式、class 分配和有限布局；提供现有 pattern 作为起点。使用已有 `:class`、`style-class`、`:style-preset`、`:use`，展示生效来源；保持受控排版，样式不改变值或校验。插删行列、重排、动态维度与转置另列后续切片。屏幕所见不等于导出分页、字体和像素完全一致。

请交付：

1. 设计规范与页面流程：模板入口、双视图切换/并排、属性与公式面板、输入/Explain、源差异、保存及目标构建反馈；明确首次使用和键盘操作，涵盖选区、Enter/Tab、Escape、IME、复制粘贴、撤销/重做、焦点返回和窄屏。
2. 状态和权限矩阵：正常、等待、空、错误、冲突、离线恢复、只读；视觉诊断与真实源定位一致，不混淆人工核准和引擎 finding。
3. 可运行 fixture 原型：优先在 Mantra 现有前端组织中实现，使用真实 DSL/Paper fixture；模拟接口单独标识。展示一次标签/公式/class 编辑、即时反馈、无效草稿和源修订冲突，附启动步骤、操作脚本与截图。导出/发布按钮不得假成功。
4. 组件复用清单、设计 token、接口缺口与分步实施计划；对照两项目实际代码，区分 UI 原型可做事项和未来 owner/source patch/draft Paper/publishing 契约。通用引擎不加入业务规则，先定义合同再修改公共 API。

先完成设计规范和流程，再落实原型及适当的定向检查。将交付文档写入各仓库约定的位置；Mantra 设计合同可用中文，面向用户的公开说明遵循其语言规则。每个阶段保留可恢复成果与明确未完成项。

## 持续保存和 PR

云端不稳定，及时写入文件；每完成一个可检查的小阶段就记录进度、执行相应检查、小 commit 并 push，更新 draft PR 的最终范围与验证情况。不要承诺每次按键都 commit。两仓库分别使用任务分支和 PR，只在需要修改的仓库提交；保留用户已有修改，不 reset、clean、强推或混入无关变更。沿用相关 draft PR，找不到则创建，并报告真实链接；不要自动合并、部署或发布。若网络/凭据阻断 push，先保存本地 commit 和恢复说明，明确尚未远端保存，并继续不受影响的设计工作。
