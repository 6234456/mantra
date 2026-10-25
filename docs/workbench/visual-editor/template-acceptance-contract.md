# 多案例模板验收：CLI 首批合同

> 2026-10-25；G-A20 的只读 CLI 切片。先固定本合同，再实现。

## 命令与边界

```text
mantra template-capture <case.mantra> [more cases...] --workspace <dir> [--out <suite.json>]
mantra template-verify <suite.json> --workspace <dir> [--out <report.json>]
```

capture 读取真实 `templateSources`，生成完整源修订及可编辑根文档的候选原文；每个案例的 expect 为 null。
它不从当前结果生成预期值。用户明确填写独立预期后才能 verify；含 null 的套件不能通过。
verify 复用公开 `WorkspaceCatalog.templatePreview`，只在内存中替换根 schema/layout、叠加原始标量输入。
两个命令不保存模板、不接通发布、不改变 HTTP v4、DSL 或计算内核；`--out` 只写显式指定的 JSON。

## 套件 `mantra.template-acceptance/1`

顶层仅 `{format,documents,cases}`。documents 为 1–2 项共享候选根文档，每项仅
`{document,role,sha256,text}`，role 为 schema/layout；必须恰有一份 schema，最多一份 layout。
document/role/sha256 与各案例的新快照逐项匹配，且覆盖全部可编辑根；text 为候选原文。
所有案例必须使用同一组根文档身份及基准摘要；include、参数、链接案例保持各自完整闭包。
句柄不进入套件，执行时按每个案例的新快照重新映射，禁止跨案例复用。

cases 为 1–32 项，仅允许：

```json
{
  "id": "independent-positive-example",
  "case": "case.mantra",
  "baseRevision": "<64 lowercase hex>",
  "baseRevisions": {"<source document>": "<64 lowercase hex>"},
  "inputs": [{"node": "base-amount", "text": "1,25"}],
  "panel": "main",
  "includeZero": false,
  "expect": {
    "succeeded": true,
    "validationPassed": true,
    "values": [{"node": "answer", "present": true, "value": {"n": "3.75"}, "display": "3,75"}],
    "diagnostics": []
  }
}
```

id 为唯一非空名称（最多 120 字符）；同一个 case 可用不同 id 和输入重复出现。
baseRevisions 为完整、非空源映射；不只核对 schema。inputs 至多 100 个不同 node，保留 raw text。
panel 可省略且非空，includeZero 可省略（false）；其余 case 字段必填。
expect 为 null（待填写）或对象，必含 succeeded、validationPassed、values；diagnostics 可省略。
values 至多 256 个不同 node 的标量结果断言，不接受维度坐标／表单元格；首批输入同样只支持标量。

值断言仅允许 `{node,present,value?,display?,active?,validationPassed?}`：

- present=false 时不得再带 value/display/active/validationPassed；测试目标在 Run 中没有标量条目。
- present=true 时必须显式给 value（可为 null），其他三个字段可省略。null 与条目不存在不同。
- value 使用现有 Workbench 精确值编码：Decimal 为 `{n:"..."}`，另有 bool/text/kw/date/vector/ordered-map。
  Decimal 用 BigDecimal 数值比较，不经 Double；1.00 与 1 相等，display 则严格比较字符串。
  不同类型不等，向量及有序 map 递归比较并保留顺序。日期须合法；不接受 JSON 数字代替 Decimal。
- validationPassed 为 boolean 或 null；null 表示该值没有有效业务判定，不能和 false 合并。
- diagnostics 如提供，为 `{code,severity,count}` 数组，与候选诊断的整个 code/severity 多重集相等；
  severity 为 error/warning/info，count 为 1–10000，至多 2048 组，同一组不可重复。空数组表示不得有诊断。
- expect.succeeded=false 可显式验收运行时失败负例；技术无效、修订冲突或未执行不能以此冒充通过。
  业务 finding 可用 validationPassed=false 及实际 check 值／诊断断言明确验收。

JSON 限 2 MiB UTF-8，深度 16，单个文本至多 65536 个 UTF-16 code unit；拒绝无效 UTF-8、重复键
（含解码后同名）、尾随 JSON、未知字段、非法值与重复身份。每套 values 总数至多 2048。
文件读取按预算截断检测，不先无限 readAllBytes。源图键数不以文档根数量限制。
解析预算不替代现有真实预览的 1 MiB 请求与运行预算；超限如实报错，不截断或改写。

## 执行与证据

执行前取得各独立 case 的完整快照，匹配其 baseRevision/baseRevisions 和共享根 descriptor。
捕获错误的案例记录 error/conflict，不能跳过后当作全部通过；其他独立案例继续。
每次预览都有自己的草稿序号、当前句柄及全部根候选，使用原始输入和 panel/includeZero。
预览完成后对已捕获的全部 case 再检查完整基准，先运行案例的依赖随后变化也必须作废。
这是逐个重新读取的修订检查，不声称提供跨进程工作区原子快照或锁住外部写入。

报告独立版本 `mantra.template-acceptance-report/1`，至少包含：format、engine（实际 RuntimeVersions）、
suiteSha256（原始套件字节）、passed、cases。每项包含 id、case、status（passed/failed/error/conflict）、
baseRevision、baseRevisions、proposedRevision（未运行为 null）、succeeded/validationPassed（未运行为 null）、
diagnostics、assertions（path、expected、actual、passed）及 error（无错误为 null）。
实际值、诊断与 Difference 全部来自本次真实候选；每项 difference 保留引擎原始对比，未运行为 null。
断言 expected 来自套件，绝不自动改成 actual。失败仍输出报告，整体只有全部 passed 才 passed=true。
退出码：0 通过／capture 成功；1 命令用法；2 套件/工作区/IO/技术错误或修订冲突；3 预期不匹配。
同时有 2 与 3 时返回 2。空 expect 是套件错误 2。

`--out` 不得覆盖套件、任何参与源，或经符号链接／硬链接指向这些文件；capture 同样保护其参与源。
写报告前再次检查输出目标，避免输出操作自己改变被验收的源。报告可含案例原始值，按用户指定位置保管。
若任何实际源图未能完整读取，则输出只能走 stdout 或写入工作区外的新文件；不能依赖失配套件里
不完整的旧源清单来批准覆盖已有文件。写文件采用同目录临时文件与原子替换，失败清理临时文件。

## 未包含

多案例视觉界面、独立验收记录服务、表／维度输入、绑定改动、结构编辑、自动期望生成、正式保存、
构建与发布、Template Engine 重算兼容。G-A20 只标记部分完成；完整兼容状态仍为 false。
