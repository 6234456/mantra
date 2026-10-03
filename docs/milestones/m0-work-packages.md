# M0 工作包

> 开工：2026-10-03 · 分支：`codex/m0-monorepo-foundation`
> 依据：[路线图 M0](../roadmap.md) · 基线：`9539c6d`

M0 不改变 DSL 语义。Monorepo、公开 API、质量门、职责拆分和展示验收已完成本地验证；
正在记录性能基线并运行远端 CI，全部退出条件通过后才标为 M0 完成。

## 必读顺序

`CLAUDE.md` → [架构](../architecture.md) → [职责契约](../engine-application-boundary.md) →
[工作台契约](../workbench/contract.md) → [路线图](../roadmap.md)。

## 任务与验收

| 工作包 | 交付与验收 | 状态 |
| --- | --- | --- |
| M0-1 远端与 CI | `origin` 指向 `6234456/mantra`；Java 21/Node 22；锁定 Normein；JVM、前端、浏览器和独立金额核对；测试产物归档 | 专用只读凭据已配置；[远端 CI 通过](https://github.com/6234456/mantra/actions/runs/37157900961) |
| M0-2 Monorepo | `:apps:de-est`、`:apps:ifrs-impairment`、`:apps:cost-accounting`；各自拥有方案、案例、版式、测试及 README；中性成本方案 ID；fixture 从 `apps/` 生成 | 本地验收通过 |
| M0-3 公开 API 与边界 | 结果/公式接口在 `core.api`；坐标、成员及 trace 在 `core.view`；planner 与 vertex 为 internal；`Mantra.inspect` 与 `FunctionCatalog`；自动扫描领域标识符、内部导入与项目依赖 | 本地验收与独立审阅通过 |
| M0-4 展示验证与文档 | 每个案例及参数变体的 HTML/Text golden、XLSX 逐值重算；独立金额核对脚本；通用工作台打开全部案例；导入样本/模板；英文 README、DSL 参考及社区文件；目录一致性检查 | 本地验收通过 |
| M0-5 质量门 | Spotless/ktlint、Prettier/ESLint、文件与行宽门禁；拆分三个大文件；选择器/预设/行号测试；10 个模块测试下限 | 完整本地 check 通过 |
| M0-6 性能与公开发布 | 五轴合成方案；plan/calculate/explain/paper/xlsx 耗时及内存；IFRS 案例改为独立虚构事实；[内核发布计划](../normein-publication.md) | 实现与来源核查完成；性能记录中 |

M0-1 的 CI 对私有 Normein 只需要专用只读 deploy key，在 Mantra 的 Actions secret
`NORMEIN_DEPLOY_KEY` 中保存。不要使用个人账号的通用 SSH 私钥或将密钥提交到仓库。
Dependabot 触发的 PR 需要同名 Dependabot secret。外部 fork 的 PR 没有私有库凭据，完整 JVM
验收须由维护者在受信分支运行；不使用 `pull_request_target` 执行 PR 代码。

Mantra 是公开仓库，维护者已于 2026-10-03 明确确认继续；路线图 R10 的公开时点已同步。
Normein 当前私有，Actions 与 Dependabot 的同名 secret 已配置为专用只读 deploy key。
本轮公开源码；首个 Maven 制品仍按路线图在 M1 后与 Normein 协同发布。

## 验证命令

```sh
npm --prefix workbench-ui ci
./gradlew --no-daemon check :mantra-cli:installDist
python3 -m unittest discover -s scripts/tests
scripts/smoke-cli.sh
cd workbench-ui
npm test
npm run build
npm run test:e2e
```

所有独立金额核对命令在 [CI workflow](../../.github/workflows/ci.yml) 中。故意更新底稿或 fixture
时才设置 `MANTRA_UPDATE_GOLDEN=1`，先检查差异，再以未设置此变量的 `check` 重跑。
浏览器测试使用已安装的 Chrome，任务临时 profile/cache/logs 随测试删除，不下载浏览器。

## 本轮验证结果（2026-10-04）

- `./gradlew --no-daemon check`：173 项测试，0 失败；golden 更新变量未设置。
- Python 边界检查回归：18 项通过；目录一致性测试通过。
- 所有九个案例/参数变体的独立金额核对通过；XLSX 包括输入表格单元格逐值核对。
- `scripts/smoke-cli.sh`：七个命令全部通过，临时服务与目录已清理。
- `npm ci`、前端 30 项测试、生产构建及三个浏览器流程通过；临时 profile/cache/logs 已删除。
- 独立审阅发现的快照别名、依赖检查语法绕过和 POI 错误值误判均已修复并复审。
- 新边界案例发现并修复通用 XLSX 空维度/不适用布尔值问题；Normein 检出未修改。
- 所有 Kotlin/TypeScript 质量门、10 模块测试下限与新增 6 个渲染行为测试通过。
- 新增 6 个大表 SUM/公式限制回归和 1 个工作台导出拒绝回归；1,000 行公式零回退，修改输入后可重算，未知辅助值不会猜成空值。
- IAS 36 展示使用独立虚构案例，默认/自定义/零损失总额分别为 121/133/0；来源核查与内核制品发布方式已记录。
- 首次远端 CI 已通过；大表 SUM 修复后的全量检查已通过；修复后的远端 CI 与最终性能基线正在收尾。

## 审阅与后续

- 公开 API 与引擎求值、底稿、工作簿和工作台结果保持一致；不以同一实现的结果取代独立核对。
- 边界扫描只豁免有明确公共含义的 `order`、`orders`、`person`、`rounding`、`summary`；方案 ID
  没有豁免。应用 ID 不得加入此集合以绕过检查。测试及 golden 允许领域数据。
- 结果快照必须与调用者的可变输入集合分离；导出不得静默回退为固定值。
- 路线图中 M0 退出条件仍全部有效，未完成项保留在 M0-5/M0-6。
