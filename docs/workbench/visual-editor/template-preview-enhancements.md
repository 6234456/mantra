# 真实模板预览增强：实现与验证

> 2026-10-24。沿用 [Draft PR #15](https://github.com/6234456/mantra/pull/15)；代码检查点
> `5d20a23`，Chrome 回归检查点 `5f7c219`，响应校验检查点 `bfc58a1`。
> 报告标题显示与真实 DOM 检查补充在 `4cfce72`，随后重新通过全部 13 个 Chrome 流程。
> 本文为 Codex 的实现记录，不表示 Claude 已完成设计复核。高级设置的规划另见
> [Invoice Builder 对照](invoice-builder-gaps.md)。

## 当前可用行为

- `/template-preview` 增加 **Preview panel**，从真实 Structure 列出面板，并可切回全部面板。
  切换面板会丢弃迟到预览和之前的 Explain 地址；Show zero rows 与面板共同构成预览上下文。
- **Compare with saved template** 使用捕获的原始 schema/layout 和已保存案例输入单独计算基准。
  不发送草稿覆盖值，也不把它们叠加到基准上；普通草稿编辑不污染基准。关闭比较不产生额外基准请求。
- 比较区显示引擎 Difference 的原值、候选值、delta、成员坐标、参数来源以及新增／移除标识，
  数值保留服务端显示文本或精确值编码；浏览器不重算差额、舍入或校验。
- Saved template 的 Paper 只读，不提供会误改当前 Explain 的按钮；展示引擎返回的运行时失败及校验状态。
- 面板、零值选项、案例或源快照改变时，重新取得对应基准。基准失败可独立重试；新面板只存在于
  草稿时，已保存模板的 404 不覆盖有效候选，也不伪造空基准。任一基准 409 都使当前源快照冲突。
- 当前候选为空时仍显示空 Paper 的说明，不回退为保存基准或上一张有行的表。无效草稿保留上一
  引擎结果，并明确标为 Previous；比较也标为 Previous draft comparison。
- 只改标题或格式可能没有值差异；界面按真实 Difference 显示，不把“没有值变化”误说成“没有源码变化”。

使用步骤与启动命令见 [用户说明](../../site/template-draft-preview.md)。页面仍需显式点击 Preview draft，
没有增加自动键入预览、正式模板 Save 或发布，也没有把录制 `/authoring` 切换成真实服务。
API 形状保持 [契约 §7.1](../contract.md#71-操作)；仍只接受根 schema/layout 与标量示例覆盖，
不支持新增依赖、改变绑定、修改共享 include，或表／维度输入覆盖。

## 实际验证

| 检查 | 结果 |
| --- | --- |
| 前端 format／lint／生成类型／TypeScript | 完整 check 通过 |
| 前端测试 | 45 文件、412 项通过；其中真实预览页／传输／比较专项 34 项 |
| 实际 CLI 构建 | 固定 Maven 内核、JDK 21，installDist 20 个任务成功；未修改内核或 JVM 业务代码 |
| 真实服务＋安装的 Chromium | 13 个流程通过，22 个实际 HTTP 响应，12 次文件字节／目录检查 |
| 测试进程清理 | 浏览器、服务与临时目录清理完成，report 标记 taskProcessGroupsStopped／temporaryDirectoryRemoved |
| 构建与隔离 | fixture 构建通过；真实 live 构建通过，8 个产物文件的完整依赖图不含原型、录制与 golden；9 项隔离反例通过 |
| 录制、边界与文档 | 原 32 状态源摘要通过；边界／源码大小通过；26 项文档测试通过，183 个相对链接／锚点与 14 个固定参考源码位置可解析 |

Chrome 流程覆盖：初始真实 Paper、候选公式与 locale／原始输入、独立保存基准、候选输入改变、
同候选 Explain、真实延迟请求的取消、面板与零值上下文、运行时失败、空 Paper、422、409、
显式重新捕获与仅源码标题变化。模板源、case、include 原字节保持不变；唯一外部 layout 改动由脚本
明确施加用于 409，不归因于预览写入。原录制数据与 pattern 未修改。

自动报告位于 `build/ui-qa/template-preview/report.json`，保留实际请求、响应与文件指纹；CI 现有
verification-results 会收集该目录。新增评审图：[保存基准与候选](screenshots/template-preview/saved-versus-draft.png)、
[空候选仍保持为空](screenshots/template-preview/empty-draft-with-saved-template.png)。

本轮没有运行 Invoice Parser、跨项目格式迁移、Template Engine 重算、JVM 全量测试或正式发布。
这些不属于本次前端增强的通过结论；高级设置仍按 G-A16–G-A21 实施，多个案例的验收集合仍为 G-A20。

## 设计复查重点

1. 候选和保存基准的输入、source revision、面板与 includeZero 各自可核对，不能让一个结果冒充另一个。
2. source／input／Explain／panel 变化后的旧证据保持标旧；基准请求失败不清除候选草稿。
3. 原始零、false、null、Absent 与超出 binary64 的 Decimal 均不被格式化或前端差额计算改写。
4. 比较区在开启前不发请求，开启后的额外计算有独立状态／错误／重试。
5. 本页仍是 source-first 工程入口；没有据此签收最终视觉作者编辑器的布局、所见即所得或系统输入法。
