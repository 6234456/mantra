# Schema 展示属性 owner 与最小补丁

> 2026-10-26；G-A1／G-A3／G-A16／G-A18 的首个 schema 展示属性切片，先定义再实现。

## 接口与范围

新增文件工作区 `POST /api/v1/cases/{case}/template-schema-properties`，会话令牌、v4 envelope、
1 MiB body、无查询、其他方法 405、包宿主不提供，沿用现有源快照及基准校验。
请求必须且仅含 `{baseRevision,baseRevisions,draftSequence,handle,text,operations}`。
handle 是 template-sources 中可编辑根 schema 的 document handle，text 为其当前完整草稿。
完整参与源修订必须逐项相同；schema id/version、include 路径与捕获依赖闭包保持不变。
源最多 65536 UTF-16 units，序号为 0–9007199254740991；operations 至多 32 项。

operations 可为空（inspect），否则每项仅为以下之一：

```json
{"op":"setText","owner":"<opaque owner handle>","text":"New label"}
{"op":"setClasses","owner":"<opaque owner handle>","classes":["subtotal","custom"]}
{"op":"unset","owner":"<opaque owner handle>"}
```

每 owner 每批最多一项。同一声明的文本与 classes 属于不同 owner，可同批修改。
文本原样保留，包括合法空字符串／中文；必须经当前公开 reader 无损读回，不能注入 DSL。
classes 至多 64 个唯一简单名 `[a-z][a-z0-9-]*`，不带冒号；未知但合法的 tag 允许存在。
setClasses 规范写为 keyword vector，[] 是显式空；unset 只移除 :class 键和值，保留空 metadata／注释。
现有声明的逻辑 class 列表相同且已 declared 时不改源，保留原 keyword/vector 拼写及重复原文；
缺省 class 与显式 [] 不同。文本同值无补丁。unset 仅允许 classes，必需文本不能删除。

## 权威来源与 owner

正式 `SchemaReader` 读取完整候选 schema 与捕获的 include（不编译／执行公式），再按其模型 location
与公开 Document AST 精确匹配声明。禁止用正则或 Paper 可见行猜测定义，也不遍历公式／引用数据中的
形似声明的表达式。include resolver 仅从捕获的根案例文档读取，保留基准 resolver 的规范路径映射；
不能新增文件读取权限或静默回退到目录搜索。

首批 coverage 固定 `presentation-items`：section/slot、line/subtract/info/formula-slot、field、total、
choice/choose-min/choose-max、check/reconcile 与 note。每个带标签声明返回 label owner，note 返回 text
owner；这些声明各有 classes owner。Section 的 label 是原始标签，不冒充 :title 或最终 Paper 标题。
不列 schema 根标题、独立 input/param/dimension/defn、choice option、案例扩展，亦不修改其内容。
模型决定声明及嵌套路径，隐藏／零值／不活跃声明仍存在；不在此接口推测可见性、值或最终样式。

每项 owner 必须且仅含：
`{handle,document,node,form,path,property,type,declared,value,editable,reason,canUnset,range,declarationRange}`。
node 是 id 或 note 的 null；form 是真实声明头；path 为模型声明序号路径（整数数组，根 items 从 0 开始）。
property 为 label/text/classes，type 为 text/classes。value 使用现有精确编码：文本为 string；
classes declared 时为正式模型的去重有序 keyword vector，未声明为 null。declared 与显式 [] 分开。
range 是属性 token 的 `{line,column,startOffset,endOffset}`，class 未声明则 null；declarationRange 为
完整声明范围。偏移为 UTF-16，位置属于 owner.document，不能当写入权限或客户端补丁输入。
根 schema owner 可编辑；捕获 include 的 owner 全部只读并说明原因。canUnset 只对已声明可编辑 classes 为 true。

owner handle 为 64 位小写 SHA-256，绑定协议 domain、document handle、完整基准、精确初始根草稿 SHA、
声明源文档／序号路径／属性。它是不透明身份，不是路径；任意源文本改变会重发句柄，旧句柄不能覆盖新稿。
同一源不变时句柄稳定；range 变化不能被猜测后复用。所有批操作先对初始目录解出 owner，再以稳定声明
路径逐步重定位；前一步标签／metadata 长度变化后不能拿旧偏移继续写。最终目录绑定最终 source.text。

## 响应、验证与冲突

data 为 `{document,draftSequence,baseRevisions,scope:"schema-properties",coverage:"presentation-items",
schemaId,source:{handle,document,text},owners,patches}`，外层 revision 为捕获的完整基准。
patches 沿用 `{startOffset,endOffset,text,removedText}`，按数组顺序相对前一步源文本；逆序可精确还原。
每步与最终文本都在预算内；补丁外字节、注释、CRLF、未知合法 metadata 和公式保持不变。
源语法／声明语义经正式 SchemaReader 读回，重复声明 metadata 键或无法唯一定位范围则 422，
带稳定 `MANTRA-TEMPLATE-SCHEMA-PROPERTY` 诊断及精确位置；原 reader 诊断也原样返回。

未知／过期 owner、只读 include、错误 owner 类型／操作、非法字段／重复操作／名／JSON 键为 400。
超预算 413。完整基准图变化为 409，包括基准已损坏或删除；返回前再次重查全部参与源。
saved baseline 仍须技术有效；业务 finding／运行时失败不阻止目录。候选公式绑定错误不阻止编辑其合法
展示属性，因为 source-only 成功不是候选计算验证。操作不会计算候选、写文件或推进历史／保存状态。
Apply 之后仍须真实 template-preview；业务值、最终 class 样式及可见性只由其结果证明。

客户端须匹配 case、document handle、完整修订、序号及请求原文；严格检查顺序 patch removedText 与结果，
并对逆补丁读回。迟到目录／补丁不能覆盖当前文本；未提交字段、Undo／Redo 与恢复的 UI 协调仍另行接入。
新增响应有独立 schema 和生成类型；旧 v4 端点／schema 保持原形，公共 JVM API 仅做可审查的增量添加。

本切片不关闭完整 Outline、通用 owner、公式语言服务、结构增删重排、共享编辑、正式 Save／源历史／
服务端草稿、D-A1、构建和发布。录制作者原型与其模拟 owner 保持独立，UI／UX 仍由 Claude 设计复核。
