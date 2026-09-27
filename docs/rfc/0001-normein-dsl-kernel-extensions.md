# RFC 0001 – Normein DSL kernel extensions requested by Mantra

| | |
| --- | --- |
| Status | Draft for review, revised with per-item acceptance contracts |
| Date | 2026-09-26 |
| Consumer | Mantra calculation-schema engine (`mantra-core`, `mantra-render`, `mantra-excel`) |
| Kernel baseline | `normein-dsl` 0.3.0 at `be7648b57c019a8d0efe6ae8b2a8a5695078c475`: language semantics 14, stdlib 21, reader 3, parser 3, type system 3, canonicalization 2 |
| Candidate assessed | Unreleased Normein working tree after `be7648b5` (language semantics 24, stdlib 32, parser 6, type system 6, canonicalization 3), assessed on 2026-09-26 with `-PnormeinCandidate=true` |
| Executable contract | [`NormeinRfcContractTest`](../../mantra-core/src/test/kotlin/com/xqiou/mantra/core/NormeinRfcContractTest.kt) pins the current behaviour of every item |
| Scope | Requests only. Mantra does not modify Normein; every item has a working Mantra-side workaround today. |

## 摘要（中文）

Mantra 以“宿主形式 + 嵌入 Normein 表达式”的方式复用 Normein DSL。本 RFC 先说明各项需求，再在[逐项验收契约](#acceptance-contracts)中给出每项的输入与错误示例、版本及指纹影响、资源上限和旧版 Mantra 的迁移方式。契约里“现状”一栏由 `NormeinRfcContractTest` 针对锁定的内核逐条验证。2026-09-26 还用 `-PnormeinCandidate=true` 对未发布的候选内核（language 24 / stdlib 32）跑了同一套契约，据此把各项分为三类：

- **真正的内核缺口**：B（嵌入表达式的宿主绝对位置）、D2（`decimal/round` 等函数返回可空类型）、I（新增：公开的字面量分类 API）。另有三项只剩部分缺口：A（多根文档；实测最大示例只用了 18% 的 token 上限，降为低优先级）、C（结构错误缺少字段路径，整数取值策略未写明）、F（缺少从节点路径到源码位置的公开索引，非标量值也不渲染）。
- **已修复事项**：D1（`apply` 的结果类型）在候选内核中已修复，Mantra 下次锁定内核时采纳。其余各项在候选内核中行为不变。Mantra 既有测试在候选内核上无需任何修改即全部通过，只有契约中的版本锁定和 D1 两条按预期提示变化。
- **应由领域层实现**：E（OPTIONAL 根缺省时不按 nil 处理；Mantra 显式传 nil 本就是正确契约，内核只需补文档）、G（有界迭代可以用 `reduce` + `range`，或写一个通过 `invokeCallable` 调用回调的领域库函数，都受内核预算约束）、H（`DslAuthoringService` 已经接受宿主提供的分析作用域）、J（`mantra/` 限定名继续由 Mantra 做等长改写），以及资源上限带来的领域层义务。

核对中还发现 Mantra 自身有三个待修缺陷，都属于领域层：数据字面量的规则与内核不一致（I）；超出内核值上限时抛出未捕获异常，没有转成诊断（见资源上限）；限定名改写会误改字符串和关键字里的 `mantra/`（J）。

## Context

Mantra models tax and accounting calculation schemas (German income tax per R 2 EStR, IAS 36 impairment tests, SAP CO style cost roll-ups, working papers) as documents made of host forms such as `(section …)`, `(line id "Label" <expr> {opts})` and `(total …)`. Host forms are read with the public `DslFormReader`; every embedded expression is compiled with `DslSemanticCompiler` against a Mantra-built `DslAnalysisScope` (one scope per dimension context) and evaluated with `DslEvaluationEngine`. Domain functions (`alloc/pro-rata`, `calc/stepwise`, `dim/sum`, `fin/npv`, …) are an ordinary `DslLibraryDescriptor` composed next to the standard libraries.

This integration works without kernel changes. The items below remove workarounds, improve diagnostics and enable the next Mantra iterations. The [acceptance contracts](#acceptance-contracts) separate what the kernel must change from what Mantra must build on existing kernel capabilities.

---

## A. Multi-form host documents with document-level bounds (priority: low)

**Problem.** `DslFormReader.readDocument` accepts exactly one root form (`DSL-PARSE-TRAILING-TOKEN` otherwise) and caps a document at 65,536 UTF-16 units, 8,192 tokens and nesting 128. Consumers may lower these limits but not raise them. A schema document is naturally a sequence of top-level declarations.

**Measured headroom.** A typical `(line …)` form costs 16 tokens, so one document holds 511 line forms. The largest Mantra example (`de-est-2025/schema.mantra`) uses 1,491 tokens (18 %). The one-root rule is therefore an ergonomic issue, not a capacity problem, and the priority is lowered from high to low.

**Workaround in Mantra.** Every document is wrapped in one root form (`(schema …)`, `(fragment …)`, `(case …)`, `(layout …)`, `(parameters …)`), and large schemas are split with `(include "fragment.mantra")` (`mantra-core/.../read/SchemaReader.kt`).

**Proposal.** Add `DslFormReader.readForms(source, sourceName, limits)` returning an ordered list of root forms with shared comments and document-coordinate spans. Limits are enforced **per root form**, with a separate, larger document ceiling. `readDocument` keeps its one-root contract.

**Compatibility.** The public form projection declares `document=exactly-one-root` in its canonical surface, so the new entry point changes the surface fingerprint. It therefore needs `normein-clj-form-reader` version 2, or a second projection id. The expression grammar (language reader version 3) and all expression identities stay unchanged.

## B. Compile an embedded sub-form with absolute source positions (priority: high)

**Problem.** A host reads the document once, then compiles each embedded expression from its source slice. Kernel diagnostics then report positions relative to the slice (`line 2, column 6` of the expression, not of the document).

**Workaround in Mantra.** Every kernel diagnostic span is re-anchored by adding the formula's start line and column (`Planner.report`, `Evaluator.evaluate`).

**Proposal.** An optional `DslCompileRequest.hostPosition: DslSourcePosition?` (and the same field on `DslNamedDefinition`), reusing the existing `DslSourcePosition(line, column, startOffset, endOffset)`. When present, all diagnostic spans are reported in host-document coordinates. The name `sourceOrigin` from the first draft is taken: it is the existing `DslSourceOrigin` field (`AUTHORED`, `GENERATED`, `MIGRATED`).

Identities must not depend on the position. Today this already holds for `logicalLocation`, and the same must hold for `hostPosition` (contract B).

## C. Construct record values for closed object types (priority: medium)

**Problem.** Dimension members and table rows are records (`cgu.carrying-amount`, `person.label`). A keyword-keyed `DslValues.map` is rejected for an object-typed root (`DSL-INPUT-ROOT-TYPE`). The path that works is to register a `DslTypeDefinition`, declare the root as `DslTypes.ref(id)` and call `DslValues.importStructuredHost(hostMap, ref, schema)`. Integer fields must then be passed as `IntegerValue`: an integral `DecimalValue` fails with `DSL-VALUE-STRUCTURAL-TYPE`, and the violation path is always `$`, so the offending field is not named.

**Workaround in Mantra.** A `PlanTypes` registry of record types per dimension and table, plus a type-aware host-record converter (`Evaluator.hostRecord`).

**Proposal.** Document `importStructuredHost` (or a dedicated `DslValues.record(typeId, fields, schema)`) as the supported record constructor. Structural failures should report the field path (`$.n`, `$[17].n`). The kernel should also state whether an integral `DecimalValue` may satisfy an `Integer` field (numeric equality already treats `1` and `1M` as equal).

## D. Static result types for `apply` and `decimal/*` (priority: medium)

**D1.** `(apply min (vals m))` is typed `Any`, so `(decimal/divide x (apply min (vals m)) 4)` fails with `DSL-TYPE-CALL-ARGUMENT`. *Workaround:* Mantra added `dim/min` and `dim/max`. **Fixed in the assessed candidate:** the expression is typed `Decimal` there.

**D2.** `decimal/round`, `decimal/floor`, `decimal/ceil` and `decimal/truncate` are typed `number?` for all inputs, so a host cannot compile a rounded formula against a non-null expected type (`DSL-TYPE-EXPECTED`). *Workaround:* Mantra compiles numeric lines against nullable expected types and maps `nil` to 0. *Proposal:* follow the Clojure-parity rule already applied to `-`, `/`, `min` and `max`: a non-null result for non-null numeric inputs, and typed diagnostics for invalid scales. `decimal/divide` stays nullable, because division by zero returns a documented `nil`. Still open in the candidate.

## E. Semantics of OPTIONAL roots that are absent (documentation only)

**Observation.** A root declared `OPTIONAL` may be omitted from the input, but reading it then fails at runtime with `DSL-RUNTIME-MISSING-ROOT`. An absent `REQUIRED` root is rejected earlier with `DSL-INPUT-MISSING-ROOT`. Absence is never read as `nil`.

**Position of Mantra.** Keeping "absent" and "nil" distinct keeps input manifests and receipts unambiguous, and Mantra already supplies every referenced root, using `DslValue.Nil` when the host value is absent. The request is reduced to documenting this contract. No semantic change is requested.

## F. Value trace for explanations (priority: high for audit use)

**Problem.** Working papers need a *Rechenweg*: the formula, the values that entered it and, for `if`/`cond`, the branch taken. Mantra currently re-renders the formula with root values substituted.

**Existing capability.** With `DslTracePolicy.FULL` the kernel already emits AST-node events keyed by canonical node path (`0`, `0.1.1`, …), and each carries the rendered scalar result. A branch that is not taken is absent, and named-definition bodies appear below the callable node. What is missing:
1. A public mapping from node path to author source. It exists only inside `DslWhyNotExplainer`, and definition bodies lie outside the expression's own AST.
2. Rendering of keywords, text and small collections. These are `null` today; collections carry only `itemCount`.

**Proposal.** A documented source index on the compiled expression (node path → origin and span, covering referenced named definitions), plus bounded rendering of non-scalar results under the existing trace limits and redaction policy. Mantra would render the index inline (`min(0,2 × 1.200 = 240; 4.000)`).

## G. Bounded iteration (withdrawn: domain layer)

Circular schemas (interest-barrier carry-forwards, bonuses on profit after bonus, tax-on-tax gross-ups, constrained re-allocation under IAS 36.105) need bounded iteration. The pinned kernel already expresses it: `(reduce (fn [g _] …) init (range n))` runs a fixed number of steps within the evaluation budget. Domain functions can also invoke callbacks through `DslFunctionRuntime.invokeCallable` under the same budget accounting. Convergence helpers therefore belong in Mantra's domain library (see contract G). `reduced` (early exit) would be a convenience, but it is not requested.

## H. Authoring services for embedded expressions (withdrawn: domain layer, depends on B)

`DslAuthoringService(environment, analysisScope)` already accepts Mantra's scope: completion, hover and signature help work for an expression slice. Mapping offsets back to the document is the mechanism of item B. Documentation of Mantra's own forms (`section`, `line`, `total`) is Mantra's responsibility (`mantra catalog`).

## I. Public classification of literal atoms (new, priority: medium)

**Problem.** The public form projection has three atom kinds: `SYMBOL`, `STRING` and `REGEX`. Numbers, keywords, booleans and `nil` are `SYMBOL` atoms whose meaning only the compiler defines: `toBigDecimalOrNull` plus a numeric-shape check, keywords with optional namespaces, and value limits. A host that reads data positions (parameters, case inputs, options) must reimplement these rules and diverges. Mantra accepts `1.5M` and rejects `.5`, while the kernel does the opposite. A parameter of scale 1,001 escapes Mantra as an uncaught `DslValueConstructionException`, whereas the kernel reports `DSL-VALUE-NUMERIC-SCALE-LIMIT`.

**Proposal.** `DslFormLiterals.classify(atom, limits)` returning `Nil`, `Boolean`, `Number(BigDecimal)`, `Keyword(namespace, name)`, `Symbol`, or a failure with the compiler's diagnostic code. It uses exactly the compiler's rules, versioned with the parser.

---

## Acceptance contracts

Each contract states:

1. **Classification.** One of: *kernel gap* (needs a Normein change); *fixed upstream* (present in the assessed candidate, adopted with the next pin); *domain layer* (Mantra builds it on existing kernel capabilities); or *documentation only*.
2. **Current behaviour.** Inputs and the diagnostic codes observed at the pin, pinned by the named test in `NormeinRfcContractTest`. When the behaviour is identical in the candidate, the contract says "(pin = candidate)".
3. **Acceptance.** The inputs, results and error examples a kernel change must satisfy.
4. **Versions and fingerprints.** Which version components and identities change when the item is accepted.
5. **Limits.** Resource bounds that apply to the item.
6. **Mantra migration.** What changes for existing Mantra code and documents.

### Classification overview

| Item | Kernel gap | Fixed upstream | Domain layer | Documentation |
| --- | --- | --- | --- | --- |
| A multi-form reader | `readForms`, projection v2 | | wrappers, `include`, data sources for bulk data | |
| B host positions | `hostPosition` | | remove re-anchoring on adoption | |
| C records | field paths, numeric policy | | record-type registry (`PlanTypes`) | constructor contract |
| D1 `apply` typing | | ✓ (candidate) | keep `dim/min`, `dim/max` | |
| D2 `decimal/*` typing | non-null signatures | | nullable expectations until then | |
| E absent OPTIONAL roots | | | explicit `nil` (kept) | ✓ |
| F value trace | source index, non-scalar rendering | | Rechenweg renderer | |
| G bounded iteration | | | `reduce` pattern or callback function | |
| H authoring | (via B) | | editor integration, host-form docs | |
| I literal classification | `DslFormLiterals` | | align reader now (open defect) | |
| J qualified node references | | | form-aware rewrite (open defect) | |

### Identity model used below

Observed at the pin (contract B):

- **Reader grammar:** `DslFormReader.GRAMMAR_IDENTITY` = `normein-clj-form-reader@1`. Its `surfaceFingerprintSha256` hashes a canonical declaration that includes `document=exactly-one-root` and the limit kinds. The language reader version (3) is a separate axis.
- **Language versions:** `DslLanguageVersions` (language semantics, reader, parser, evaluator, standard library, normalized AST API, canonicalization, type system, artifact schema).
- **Per compiled expression:**
  - `sourceFingerprint`: source format version, the exact author text, and the named definitions the expression references. An unreferenced definition changes nothing; a changed referenced definition changes all three fingerprints.
  - `canonicalAstHash`: bound canonical AST; layout-insensitive.
  - `executionFingerprint`: canonical AST, slot, expected type, environment and scope. Insensitive to layout and to `logicalLocation`.
  - `environmentFingerprint` and `analysisScopeFingerprint`.
- **Per evaluation:** `DslEvaluationReceipt`. It holds the language versions, the execution artifacts (including stdlib coordinates such as `application-code:normein.stdlib.clojure:21`), the fingerprints above, and the input manifest and fingerprint, evaluation key and outcome fingerprint.
- **Mantra persists none of these.** Each run compiles afresh; the only identity Mantra supplies is the input snapshot identity, derived from the case document name and the schema id. A kernel bump therefore needs no stored-data migration. Applications that cache results must key them by Mantra version plus the `normein-build.lock` commit.

### A. Multi-form host documents

- **Classification:** kernel gap, low priority.
- **Current (pin = candidate)**, `A - a document has exactly one root form…` and `A - the token limit admits 511…`:
  - `readDocument("(schema s)\n(case c)")` → failure `DSL-PARSE-TRAILING-TOKEN` at 2:1.
  - `DslFormReaderLimits(maxTokens = 8_193)`, `maxSourceLength = 65_537` and `maxNesting = 129` each throw `IllegalArgumentException`.
  - 511 typical line forms read successfully; 512 fail with `DSL-PARSE-TOKEN-LIMIT`.
- **Acceptance:**
  1. `readForms("(schema s)\n(case c)", "doc")` → two root forms. The second is at 2:1; spans and comments are in document coordinates and document order.
  2. `readForms("")` → success with no forms. `readDocument("")` keeps failing with `DSL-PARSE-UNEXPECTED-END`.
  3. Error example: `readForms("(schema s)\n(case c")` → `DSL-PARSE-MISSING-CLOSE` with span 2:1 and an attribute `rootIndex = 1`.
  4. Each root form is subject to today's limits (65,536 / 8,192 / 128). A document ceiling (proposal: 16 × the per-form values) fails with a new `DSL-PARSE-DOCUMENT-LIMIT`. Consumers can lower both, never raise them.
  5. `readDocument` is unchanged.
- **Versions and fingerprints:**
  - Public form projection `normein-clj-form-reader` 1 → 2 (or a second projection id), with a new surface fingerprint.
  - The language reader version (3), parser, expression grammar and every compiled-expression, environment and receipt fingerprint are unchanged.
- **Limits:** as in acceptance 4. Measured Mantra usage:
  - 16 tokens per typical line form.
  - Largest example 1,491 tokens (18 %).
  - An inline two-column case table costs 6 tokens per row, so a case document holds about 1,360 rows.
- **Mantra migration:**
  - Wrapped documents stay valid forever: `readForms` of a wrapped document yields one form.
  - Mantra accepts both layouts once the projection v2 identity is present, and keeps writing wrapped documents until older Mantra versions (which reject a second root with `MANTRA-READ-SYNTAX`) are retired.
  - `include` remains for modularity.
  - Independently of A, bulk data never goes into documents; it goes through data sources (`JsonSource`, `CsvSource`, `XlsxSource`), which bypass the reader.

### B. Absolute positions for embedded expressions

- **Classification:** kernel gap, high priority.
- **Current (pin = candidate)**, `B - diagnostics of an embedded expression…` and `B - identities ignore…`:
  - `compile("(+ a\n     zzz)", logicalLocation = "line.zve")` → `DSL-REF-UNKNOWN-SYMBOL` at 2:6, relative to the expression, with `logicalLocation = "line.zve"` echoed.
  - Changing `logicalLocation` changes no fingerprint. Changing only whitespace changes `sourceFingerprint` but not `canonicalAstHash` or `executionFingerprint`.
- **Acceptance:**
  1. The same source with `hostPosition = DslSourcePosition(line = 41, column = 22, startOffset = 1830, endOffset = 1830 + source.length)` → `DSL-REF-UNKNOWN-SYMBOL` at 42:6, offsets shifted by 1830. A diagnostic on the expression's first line reports column `22 + column − 1`.
  2. `DslNamedDefinition.hostPosition` behaves the same for errors inside `defn` bodies.
  3. Error example: `endOffset − startOffset ≠ source.length`, `line < 1` or `column < 1` → compile failure `DSL-COMPILE-HOST-POSITION`, with no partial result.
  4. Runtime diagnostics (`DslEvaluationOutcome.Failure`) of an expression compiled with `hostPosition` use document coordinates too.
  5. `sourceFingerprint`, `canonicalAstHash` and `executionFingerprint` are byte-identical with and without `hostPosition`, as they already are for `logicalLocation`.
- **Versions and fingerprints:** additive compiler API. No language or stdlib bump, and no fingerprint changes (hard requirement). Receipts differ only in diagnostic spans.
- **Limits:** none new. Positions are validated and must fit in `Int`.
- **Mantra migration:**
  - Pass `hostPosition` and, **in the same change**, delete the re-anchoring in `Planner.report` and `Evaluator.evaluate`; otherwise locations shift twice.
  - The contract test B (relative span) flips.
  - Mantra's reported locations stay the same, so no test expectation of Mantra itself changes. Documents are unaffected.

### C. Record values

- **Classification:** partly present at the pin. Kernel gap for field paths and the numeric policy; the record-type registry is domain layer.
- **Current (pin = candidate)**, `C - records need a declared type…` and `C - value limits count every nested item`:
  - A keyword-keyed map for a `TypeRef` root → `DSL-INPUT-ROOT-TYPE`.
  - `importStructuredHost({key :A, n 1 (DecimalValue)}, ref(row))` → `DSL-VALUE-STRUCTURAL-TYPE` at path `$`.
  - The same input with an `IntegerValue` succeeds, and `row.n` evaluates.
  - Limits: 1,428 rows of 3 fields import, 1,429 fail with `DSL-VALUE-ITEM-LIMIT`. A 5,001-element vector throws `DslValueConstructionException` (`DSL-VALUE-ITEM-LIMIT`).
- **Acceptance:**
  1. One documented constructor for typed records and vectors of records.
  2. Error examples:
     - The integral-decimal input above → `DSL-VALUE-STRUCTURAL-TYPE` at `$.n`, with attributes `expected = Integer` and `actual = Decimal`.
     - The same inside a table → `$[17].n`.
     - A missing required field → `$.key`, with a code that distinguishes absence from a type mismatch.
     - An undeclared field → `$.extra`.
  3. Numeric policy stated: either an integral `DecimalValue` (`1`, `1.0`, `1M`) satisfies `Integer` and is canonicalised to `IntegerValue`, or it is rejected with the diagnostic in 2. Mantra prefers acceptance, since it loses no information.
- **Versions and fingerprints:**
  - Items 1 and 2 change diagnostics only; no version bump.
  - Item 3, if coercion is accepted, is a type-system change (type-system version bump). Coerced values must canonicalise to `IntegerValue`, so that input fingerprints equal today's explicit-integer inputs.
- **Limits:** `DslValueLimits`:
  - 10,000 items per value, where a record costs `1 + 2 × fields`: 1,428 rows of 3 fields, 2,000 rows of 2 fields.
  - 5,000 entries per collection; depth 32; numeric precision and scale 1,000.
  - Violations must carry the path of the first offending element.
- **Mantra migration:**
  - `PlanTypes` stays in Mantra.
  - `Evaluator.hostRecord` and `Evaluator.structured` call the documented constructor, and `MANTRA-RECORD` quotes the field path.
  - `Values.toIntegerDsl` can go if coercion is accepted.
  - Documents are unaffected.

### D. Static result types

- **Classification:** D1 fixed upstream; D2 kernel gap.
- **Current**, `D - apply is typed Any…`:
  - **D1:** `(apply min (vals m))` with `m: Map<Keyword, Decimal>` → `Any` at the pin, and `(decimal/divide 1 (apply min (vals m)) 4)` → `DSL-TYPE-CALL-ARGUMENT`. The candidate infers `Decimal` and compiles the division.
  - **D2 (pin = candidate):** `(decimal/round x 2)` → `Null | Integer | Long | Decimal`. With expected type `Decimal` → `DSL-TYPE-EXPECTED`. `(+ (decimal/round x 2) 1)` compiles; arithmetic accepts the nullable input.
- **Acceptance:**
  - **D1:** as in the candidate. `(apply min (vals {}))` remains a runtime failure, never a silent `nil`.
  - **D2:**
    - `(decimal/round x 2)` with non-null `x: Decimal` → `Decimal`, and compiles against expected type `Decimal`.
    - With nullable `x` → nullable result.
    - A literal invalid scale (`(decimal/round x 2.5)`) → a compile-time diagnostic. A dynamic invalid scale → a runtime failure, not `nil`.
    - `decimal/divide` keeps `number?`.
- **Versions and fingerprints:**
  - Static-type changes need a stdlib semantics and type-system bump; the candidate already moves 21 → 32 and 3 → 6.
  - `inferredType` changes for affected expressions, and `canonicalAstHash` / `executionFingerprint` change where inserted non-null assertions disappear.
  - Any stdlib bump also changes `environmentFingerprint` and the receipts' execution artifacts.
- **Limits:** none.
- **Mantra migration:**
  - Keep `dim/min` and `dim/max`: they define empty-map behaviour and are domain functions. Schemas are unaffected.
  - After D2, numeric lines whose formulas are statically non-null may be compiled against non-null expected types. The `nil` → 0 policy remains for `decimal/divide`.
  - The D contract test flips per sub-item.

### E. Absent OPTIONAL roots

- **Classification:** documentation only. Mantra's explicit `nil` is the intended contract.
- **Current (pin = candidate)**, `E - an absent optional root fails when read…`:
  - An `OPTIONAL` root omitted from the input and read by `(if (nil? x) 0 x)` → `DSL-RUNTIME-MISSING-ROOT` at 1:11.
  - An explicit `nil` evaluates to 0.
  - An omitted `REQUIRED` root → `DSL-INPUT-MISSING-ROOT` before evaluation.
- **Acceptance:** the `DslFieldPresence` documentation and the language reference state that `OPTIONAL` means "may be omitted when not read", that absence is never read as `nil`, and that both codes are stable. "Absent reads as nil" is explicitly **not** requested: it would change evaluation semantics (language semantics bump) and blur absent versus `nil` in receipts.
- **Versions and fingerprints:** none.
- **Limits:** none.
- **Mantra migration:** none. Mantra keeps passing every referenced root, with `nil` when the host value is absent.

### F. Value trace

- **Classification:** partly present at the pin. Kernel gap for the source index and non-scalar rendering; the Rechenweg renderer is domain layer.
- **Current (pin = candidate)**, `F - a full trace renders scalar node results…`, for `(if (> x 10) (min (* 0.2 handwerker) 4000) 0)` with x = 1200 and handwerker = 1200:
  - Node `0` renders `240.0`, `0.0` renders `true`, `0.1.1` renders `240.0` and `0.1.1.2` renders `1200`. Node `0.2`, the branch not taken, is absent.
  - `(if … :high {:a 1 :b 2})` renders `null` with `itemCount = 2`.
  - In `(cap (* 0.2 handwerker))` the body of `defn cap` is traced as `0.0.0` (renders `4000`).
- **Acceptance:**
  1. A source index on `DslCompiledExpression` maps every canonical node path of the expression and of each referenced named definition to `(origin, span)`, where origin is `EXPRESSION` or `DEFINITION(name)`. Example: `0` → offsets 0–45; `0.1.1` → the span of `(* 0.2 handwerker)`; `0.0.0` in the `cap` example → the body of `cap` in its own source.
  2. Keywords (`:high`) and text render canonically. Collections render up to `maxCollectionEntries` (64); larger values are marked `truncated`.
  3. Error example: tracing `(decimal/divide 1 (- x x) 2)` still succeeds with `nil` at the division node, and a runtime failure yields `partialTrace` nodes with status `FAILED` whose paths resolve through the same index.
  4. Redaction: `REDACT_SENSITIVE` renders `INTERNAL` values as today; `REDACT_ALL` renders none.
- **Versions and fingerprints:** additive. Traces enter no fingerprint; receipts record only the trace status. No version bump.
- **Limits:** `DslTraceLimits`:
  - 10,000 events and 20,000 attribute entries; attribute depth 8.
  - 64 collection entries and 1,000,000 text characters.
  - 1,000 children per node.
  - Truncation must be visible (`TRUNCATED`). Mantra requests `FULL` only for audit output.
- **Mantra migration:**
  - The formula explainer switches from root-value substitution to the index behind `--audit`.
  - Rendered Rechenweg text in HTML, text and Excel audit output changes, so golden outputs are updated. JSON audit output gains fields (additive).
  - Schemas are unaffected.

### G. Bounded iteration

- **Classification:** domain layer (kernel request withdrawn).
- **Current (pin = candidate)**, `G - bounded iteration is expressible…`:
  - `loop`/`recur`, `iterate` and `reduced` → `DSL-FUNCTION-UNKNOWN`.
  - `(reduce (fn [g _] (decimal/round (+ net (* rate g)) 2)) net (range 60))` with net 1,000 and rate 0.25 → `1333.33`, a tax-on-tax gross-up.
  - `(reduce + 0 (range 30000))` → 449,985,000. `(reduce + 0 (range 100001))` → `DSL-LIMIT-NUMERIC-OPERATIONS`.
- **Acceptance (Mantra's implementation):**
  - A domain function, for example `calc/converge [f init max-iterations tolerance]`, calls `f` through `DslFunctionRuntime.invokeCallable` under kernel budget accounting.
  - The gross-up converges to `1333.33` within 60 iterations.
  - A non-convergent `(fn [x] (+ x 1))` with `max-iterations = 50` fails with `MANTRA-CALC-NOT-CONVERGED`, never a value. `max-iterations` above 1,000 is rejected.
- **Versions and fingerprints:** a `mantra.calc` library semantics bump (1 → 2), which changes Mantra's environment fingerprint. No kernel change.
- **Limits:** kernel per-evaluation budgets (100,000 per counter) plus Mantra's iteration cap.
- **Mantra migration:** additive. Schemas using the new function do not run on older Mantra (`DSL-FUNCTION-UNKNOWN`), which is inherent to new functions. `alloc/capped` stays.

### H. Authoring

- **Classification:** domain layer (kernel request withdrawn); needs B for position mapping.
- **Current (pin = candidate)**, `H - the authoring service works with a host analysis scope`:
  - `DslAuthoringService(environment, scope)` completes `(- brutto` to `bruttolohn`.
  - Hover on `alloc/pro-rata` returns its signature and documentation.
- **Acceptance (Mantra):**
  - Completion inside a `(line …)` of a `{:per person}` section offers that section's scope: node ids, `person` fields, domain functions. Ranges are in document coordinates.
  - Host forms are documented by Mantra's catalog.
- **Versions and fingerprints:** none; authoring enters no identity.
- **Limits:** `DslCompletionRequest.limit` (default 20).
- **Mantra migration:** none.

### I. Literal classification

- **Classification:** kernel gap (small, additive), plus an open domain-layer defect.
- **Current (pin = candidate)**, `I - numeric literal rules of the kernel`:
  - The kernel reads `.5`, `1.`, `1e3`, `+7` and `-0.0` as `Decimal`, and rejects `1.5M`, `1N`, `0x10` and `1/2`.
  - A literal of scale 1,001 → `DSL-VALUE-NUMERIC-SCALE-LIMIT`, and `DslValues.decimal(1E-1001)` throws with the same code.
  - Mantra (probe of 2026-09-26, not pinned) accepts `1.5M` as 1.5 and rejects `.5` and `1.` with `MANTRA-READ-LITERAL`. A parameter literal of scale 1,001 crashes the run with `DslValueConstructionException`.
- **Acceptance:**
  - `DslFormLiterals.classify` returns:
    - `1.5M` → `Symbol`, and `.5` → `Number(0.5)`.
    - `:de.est/zve` → `Keyword("de.est", "zve")`.
    - A scale-1,001 literal → failure `DSL-VALUE-NUMERIC-SCALE-LIMIT`.
  - A differential test shows that the classifier and `DslSemanticCompiler` agree on every atom in the reader corpus.
- **Versions and fingerprints:** tied to the parser version (literal rules live in the parser: 3 at the pin, 6 in the candidate). Additive; no fingerprint change.
- **Limits:** numeric precision and scale 1,000 (`DslValueLimits`).
- **Mantra migration:**
  - Now, as domain-layer work:
    - Align `Forms.number` with the kernel rule, and validate data literals against `DslValueLimits` at read time.
    - Convert `DslValueConstructionException` into diagnostics at the kernel boundary.
    - Accept `M` and `N` suffixes for one release with a deprecation warning, then report `MANTRA-READ-LITERAL`. No example uses them.
  - After the kernel change, `Forms.number` and `Forms.keyword` delegate to `DslFormLiterals`.

### J. Qualified node references `mantra/<id>` (not requested)

- **Classification:** domain layer.
- **Current**, `J - roots resolve by position…`:
  - A root may share a function's name and is resolved by position: `(+ amount 1)` reads the root, `(amount "12,50")` calls the function.
  - Root names cannot contain `/`. `(+ mantra/amount 1)` fails with `DSL-NAME-INVALID` at the pin. In the candidate it fails with `DSL-REF-UNKNOWN-SYMBOL`, because a `/` symbol in value position resolves as a namespaced callable.
- **Consequence:**
  - Mantra keeps its equal-length rewrite `mantra/<id>` → root `mantra_<id>` before compilation, and never registers functions under `mantra/`.
  - Open defect: `Qualified.rewrite` is textual and also rewrites strings and keywords (`"mantra/x"` → `"mantra_x"`, `:mantra/y` → `:mantra_y`). It must rewrite symbol atoms only, using the public form spans.
- **Versions and fingerprints:** `sourceFingerprint` covers the rewritten text. A form-aware rewrite changes fingerprints only of formulas that contain `mantra/` inside strings or keywords.

### Cross-cutting resource limits

| Kernel limit (pin = candidate) | Value | Consequence for Mantra | Owner and status |
| --- | --- | --- | --- |
| Reader | 65,536 chars, 8,192 tokens, nesting 128; lower only | about 511 line forms per document; about 1,360 inline two-column case rows | Domain: `include`, data sources for bulk data |
| Items per value | 10,000; a record costs `1 + 2 × fields` | a table referenced by a formula holds ≤ 1,428 rows of 3 columns (≤ 2,000 of 2); Mantra reports `MANTRA-RECORD` | Domain: pre-aggregate large tables |
| Entries per collection | 5,000 | member maps (`all.<id>`, member-map roots) hold ≤ 5,000 members; at 5,001 members Mantra currently throws `DslValueConstructionException` | Domain, **open defect**: report a diagnostic |
| Depth | 32 | one level per extra dimension of a member map | none |
| Numeric precision / scale | 1,000 / 1,000 | data literals must be validated (I) | Domain, **open defect** |
| Evaluation budget | 100,000 per counter per evaluation (numeric operations, function calls, evaluated nodes, …), 16 MiB byte counters, 128 open cursors | applies per formula and member tuple; the kernel has no run-level budget | Domain: Mantra needs a run-level cap (member tuples × lines) |
| Library charging | handlers charge what they declare | Mantra's handlers charge `arguments.size` numeric operations per call, regardless of map size or iterations (`alloc/capped`) | Domain: charge in proportion to entries × iterations |
| Trace | 10,000 events, 64 collection entries, 1,000,000 text characters | `FULL` trace for audit output only | Domain |
| Names | 256 bytes | node ids are far shorter; Mantra truncates `logicalLocation` to 200 | none |

### Adopting a new kernel

1. **Assess the candidate** without touching the lock:
   ```bash
   ./gradlew test -PnormeinBuildPath=<candidate checkout> -PnormeinCandidate=true
   ```
   `NormeinRfcContractTest` fails for each item whose behaviour changed. The version pin test always fails on a version bump, as intended.
2. **Adopt each changed item** by following its migration above, then update the expectation in the contract test and the "Current" line of the contract.
3. **Pin the commit:** update `normein-build.lock`, run `scripts/bootstrap-normein.sh` and the full suite, and update the kernel baseline in this header.
4. **Fingerprints:** every language or stdlib bump changes `environmentFingerprint`, `executionFingerprint` and the receipts' execution artifacts; canonicalization bumps may change `canonicalAstHash`. Mantra stores none of them, so no data migrates. Mantra versions pinned to an older commit keep their behaviour exactly.

Assessment of 2026-09-26 (candidate: language 24 / stdlib 32): Mantra compiles unchanged, and every Mantra test outside the contract passes, including the ESt 2025/2026, IAS 36 IE8 and SAP CO figures and the Excel formula verification. The contract reports exactly two changes: the version pin and D1.

---

## Summary

| Item | Area | Classification | Priority | Mantra today |
| --- | --- | --- | --- | --- |
| A | Reader: multiple root forms, per-form limits | Kernel gap | Low (18 % headroom) | Wrapper roots, `include`, data sources |
| B | Compiler: host positions for embedded expressions | Kernel gap | High | Span re-anchoring |
| C | Values: record constructor | Partly present; gap: field paths, numeric policy | Medium | Type registry + `importStructuredHost` |
| D1 | Types: `apply` | Fixed upstream (unreleased) | – | `dim/min`, `dim/max` |
| D2 | Types: `decimal/*` nullability | Kernel gap | Medium | Nullable expected types |
| E | Runtime: absent OPTIONAL roots | Documentation only | Low | Explicit `nil` |
| F | Trace: source index, non-scalar rendering | Partly present; gap: index + rendering | High (audit) | Root-value substitution |
| G | Bounded iteration | Domain layer (withdrawn) | – | Kotlin library functions |
| H | Authoring for embedded expressions | Domain layer (withdrawn; needs B) | – | None yet |
| I | Reader: literal classification | Kernel gap + open Mantra defect | Medium | Own number regex (diverges) |
| J | Qualified node references | Domain layer (not requested) | – | Equal-length rewrite (open defect) |

Mantra keeps consuming the kernel at a pinned commit and adopts accepted items behind `NormeinRfcContractTest`. `NormeinContractTest` covers the basic kernel APIs Mantra relies on.
