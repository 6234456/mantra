# 高级布局设置：首批实现与验证

> 2026-10-25。延续 [Draft PR #15](https://github.com/6234456/mantra/pull/15)。
> 契约 `4e643e9`，服务 `35f6d44`，界面 `80c7443`，真实浏览器脚本 `866ecbd`，
> 显式默认值操作 `67149fc`。本文是 Codex 实施记录，不替代 Claude 的设计复核。

## 已实现范围

承接 [Invoice Builder 对照](invoice-builder-gaps.md)，在真实 `/template-preview` 页面加入
**Advanced layout settings**。这推进 G-A16／G-A18 的根 layout 切片；通用 owner 属性目录、
任意节点最小补丁及正式文件提交仍未完成。

十四项设置均沿用现有 DSL：

- 报告：`title`、`subtitle`、`language`。
- 数字：`locale`、`precision`、`percent-precision`、`negative`、`zero`、`grouping`、`signed`。
- 可见性：`hide-zero`、`show-inactive`、`expand-members`、`row-numbers`。

先 Inspect，从服务取得当前声明、类型、选项、显式值、继承值与根有效值，再编辑缓冲字段。
**Use explicit value** 支持将与继承值相同的内容刻意声明为覆盖，包括空字符串、false 与 0。
**Reset to inherited** 删除覆盖声明；Cancel 只丢弃尚未 Apply 的字段。

Apply 将服务返回的补丁接纳到同一份内存 DSL。未 Apply 的字段会阻止 Preview，并给出 Apply／Cancel
说明。Apply 后必须显式 Preview，才获得对应候选的计算、Paper 与 Explain；独立保存基准保持原始输入。
这些操作不保存模板文件、不推进源历史，也不构建或发布模板。

完整操作和启动步骤见 [用户说明](../../site/layout-settings.md)；预览、保存基准对照和空结果行为见
[预览增强记录](template-preview-enhancements.md)。

## 契约与源一致性

[契约 §7.1](../contract.md#71-操作) 新增源级
`POST /api/v1/cases/{case}/template-layout-settings`，绑定完整源 revision map、根 layout handle、
当前原文与草稿序号。空 operations 只检查属性；set／unset 返回按顺序应用的 UTF-16 补丁，
每条包含确切 removedText，逆序反转可恢复输入原文。

编辑器通过公开语法树定位源范围，保留未知选项、其他声明、注释与行结束符；拒绝重复／歧义 metadata。
未知类型、非字面量和超出结构化编辑范围的声明保留只读原因，可明确 Reset 时才移除。
文本须通过固定公开 literal reader 的往返检查；无法原样表达的控制字符拒绝，未修改内核或重解释旧源。
0–18 是该表单的精度编辑范围，不是新加的 DSL 限制；显示精度不改变计算值和显式舍入。

接口解析保存基准以核对修订，不计算提交的草稿候选。设置成功不证明未保存 schema 的计算有效。
请求前后核对源图；外部修改返回 409，保留草稿。前端还核对 case、快照、handle 和完整当前原文，
迟到响应不能替换手动改过的源；请求中的字段若又编辑，新缓冲不会被旧确认清空。

## 实际验证

| 检查 | 结果 |
| --- | --- |
| 根目录 `./gradlew --no-daemon --max-workers=2 check` | 成功；216 份 JVM 测试结果、968 项测试，0 失败／错误／跳过；含 ABI、格式、边界、诊断及应用检查 |
| 新服务专项 | Workbench 18 项、HTTP 7 项通过；含类型／预算／歧义、完整修订绑定、UTF-16 正反补丁与文件字节保持 |
| 前端完整门禁 | format／lint／生成类型／TypeScript 通过；47 文件、433 项测试通过 |
| 构建和隔离 | fixture 构建成功；新 live 构建 8 个文件及完整 Vite 依赖图排除原型、录制、golden；9 项隔离反例通过 |
| 录制回归 | 原 32 状态及 pattern 源摘要检查通过，未修改录制数据或示例源 |
| 真实服务＋已安装 Chromium | 21 个流程、26 个预览响应、8 个设置响应、20 次源字节检查，0 失败 |
| 文档 | 26 项测试通过；默认静态站点生成及复查通过，36 个 HTML 页面、2048 个本地链接 |

新增浏览器流程覆盖：十四项真实属性与 preset、未提交字段及合成 IME、显式空字符串／false／0、
继承重置、非支持声明只读、延迟检查与延迟补丁、外部源冲突。原有十三个预览流程一起重跑通过。
脚本明确施加两次外部 layout 变更来触发冲突；这些不属于服务写入。服务与浏览器进程组、临时目录
均已清理，报告确认 `taskProcessGroupsStopped` 和 `temporaryDirectoryRemoved`。

实测证据在 `build/ui-qa/template-preview/report.json`，包括实际请求、响应、补丁、原始文件摘要
与取消的请求；现有 CI artifact 会收集。可重跑：

```sh
./gradlew --no-daemon :mantra-cli:installDist
MANTRA_TEST_CHROME=/path/to/installed/chrome \
  npm --prefix workbench-ui run test:template-preview:e2e
```

[高级设置实测截图](screenshots/layout-settings/applied-excerpt.png) 为 1425×4000 的截取，
报告标为 `completePage: false`，不代表完整页面。这里没有重跑带全部应用／KDoc 制品的严格站点发布。
物理系统拼音输入法仍需人工测试，合成事件不作为真实输入法通过证据。

## 后续复核与剩余工作

1. 核对 Explicit／Inherited／Root effective 的语义和字段操作，尤其 null 与空字符串、false、0 的区别。
2. 手动改源码后 Refresh 保留缓冲，再明确 Apply；迟到响应和冲突不能丢掉当前编辑。
3. 设置只改变呈现。用真实 Preview 比较精确值、格式与可见行，不能把表单成功当作有效计算。
4. G-A16 的节点／输入／参数／函数／class 目录，G-A17 的定义与影响范围，G-A18 的多 owner 协调，
   以及 G-A19–G-A21 仍继续按实施计划推进。D-A1 正式多文档原子提交仍待设计选择。

这批代码没有接管 `/authoring` 的录制服务，也没有引入新的 npm 依赖、计算语言语义、自由打印设计器
或 Template Engine 写入路径。模板派生与两个项目的分叉架构保持既定设计。
