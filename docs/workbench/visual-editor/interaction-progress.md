# 编辑逻辑实施记录

2026-10-10：Claude 额度恢复前，按维护者要求暂停视觉设计，完成已有工作台的明确交互逻辑。
原 [设计交付](README.md) 的四项交付和 Template Engine 文档仍待 Claude 继续。

## 已实现的逻辑

- 包案例的来源输入保护覆盖整个映射及祖先坐标的 set/clear，阻止通过上层替换绕过 leaf 保护；未链接的 sibling 和祖先仍按既有权限编辑。
- 公式检查、预览、补全、hover 绑定草稿及编辑上下文；迟到响应、旧修订和 IME 期间的操作不会应用到新草稿。
- 保存检查与实际提交使用同一快照。检查期间继续输入会停止旧提交；HTTP 写请求发出后锁定对应公式，新 EditorView 继承写锁，成功通知当前同 owner 的回调。
- 页面使用稳定的 case/slot identity，同目标的外部 binding 更新保留未提交草稿并清理旧反馈；未编辑内容跟随更新，切换案例或 slot 重置。
- `POST /api/v1/cases/{case}/preview-paper` 显式预演已有案例操作，返回同一次真实计算的 Run、差异和 Paper，附基准修订、`draftSequence`、候选修订与成功/业务验证状态。
- 预演使用捕获的文档和数据及独立计算会话，保留正式源、undo/redo 和正式计算缓存；返回前重新核对完整参与修订。技术错误拒绝，业务 finding 保留当前结果，运行时失败保留失败及部分结果身份。
- `LiveData.previewPaper` 是可选能力，传递 AbortSignal 并核对响应序号/修订；PackageData 和 FixtureData 不提供它。写请求错误保留 HTTP 状态和冲突修订，包括非 JSON 的代理失败。

## 接入方式与边界

请求、严格响应 schema 和错误语义见 [contract §7.1](../contract.md#71-操作) 与
[preview-paper.schema.json](../schema/preview-paper.schema.json)。前端先检查 `data.previewPaper`；调用方仍需守卫当前目标和草稿序号，取消请求不替代响应身份检查。
提交继续使用正式 `/edits` CAS 接口，不能把预演响应冒充已保存内容。
新引入依赖首次读取后计入候选修订，但预演不锁定它们；提交重新读取并计算，不能承诺稍后提交与旧候选完全相同。

这个端点只接受既有 case 操作，不以任意路径或 offset 选择模板写目标，也不接收 schema/layout 原文或模板 owner。
标签、class、样式来源、结构插入及 layout 作者预演仍需要未来契约；原型录制这些状态时必须标注模拟能力。
此轮没有实现可视化作者页面、自动恢复持久化、多文档事务、派生模板发布或 Template Engine UI。

## 验证与保存

包保护先复现四个失败场景，再通过新旧目标 13 项测试；两个逻辑修复检查点已推送到
`codex/mantra-design-handoff-20261010`：`e5b2a54`（包保护）、`2e95dd8`（公式异步状态）。
后续已推送 `b2ae64b`（页面草稿保留）、`e1e65f8`（隔离候选 Paper、传输与契约）。

整体验证通过：Gradle `check :mantra-cli:installDist`，918 项 JVM 测试、129 项 Python 测试、
24 项 conformance、公共 ABI、格式/边界/诊断/源码尺寸检查；前端 check、204 项测试、生产构建及
13 个浏览器流程通过。容器 PID 1 不回收孤儿进程，进程清理测试使用已有的云端 subreaper 启动器；
浏览器/Vite 退出与临时 profile 删除均已验证，无产品逻辑或测试断言豁免。

安装后的 CLI 又在 `docs/patterns` 临时副本启动真实服务，选择 `capped-allocation/case-demo.mantra`
的 `controls`，预演 `requested-units` 从 8 变为 9：候选请求值是精确的 `90.00`，Paper 与候选 Run
一致，`includeZero: true` 使 `unallocated` 零行可定位。正式 Run 仍为 `80.00`，基准修订不变，
副本全部 16 个文件的哈希不变；测试服务已关闭。

接入该面板的请求示例（令牌沿用当前服务 session metadata，修订从当前 Run 获取）：

```json
{
  "baseRevision": "<current-run-revision>",
  "draftSequence": 23,
  "panel": "controls",
  "includeZero": true,
  "operations": [
    {"op": "setInput", "address": {"node": "requested-units"}, "value": {"n": "9"}}
  ]
}
```

该请求发往 `/api/v1/cases/capped-allocation%2Fcase-demo.mantra/preview-paper`，只适用于文件工作区。
编译后新增的 public 方法是 `WorkspaceCatalog.previewPaper`；库构造器与旧 wire 响应保持兼容。

GitHub API 仍受当前云端网络规则阻断，尚未创建 Draft PR；Git HTTPS 推送可用。
恢复后先正常同步远端，再按 [continuation prompt](resume-prompt.md) 完成视觉交付与两个仓库的真实 PR。
