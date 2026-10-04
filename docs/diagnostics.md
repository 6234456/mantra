# Diagnostic reference

Diagnostics carry a stable `code`, `severity`, `category`, optional source `location` and optional
calculation address. Workbench contract `mantra.workbench/4` also carries zero-based `rowIndex` and
`column` for table-cell findings. Linked findings also retain typed `caseKey` / `caseRevision`; their wire address selects the originating case. A cell address uses that row index within the current revision;
source locations point at the supplied cell, its row when the field is omitted, or the declaration
when an external source has no DSL source span.

`CalculationResult.succeeded` excludes business findings and is false only for a nonbusiness error.
`validationPassed` is false for a business error; business warnings preserve both flags. A failed
business decision never stops other calculations or prevents saving a well-typed case. It remains
visible in diagnostics, paper status columns and the workbook's recalculating check formulas.

## Categories and severity

- `parsing`: DSL syntax or literal-construction failures. Correct the document before calculation.
- `structural`: declarations, compilation, input types, references and document/request contracts.
  These failures can reject a calculation or edit. Reader options-map errors are structural even
  though their code starts with `MANTRA-READ-`.
- `evaluation`: formula execution, incompatible results, value limits and incomplete audit capture.
  Errors fail execution; the audit-budget warning keeps numeric results available and marks the
  explanation incomplete.
- `business`: author-defined checks and data-completeness/range rules. Errors fail validation without
  failing execution. Check/reconcile severity is authored; input completeness/range failures are
  errors, while an undefined ratio aggregate is a warning.

The category belongs to the individual diagnostic, rather than being inferred from its code prefix.
In particular, `MANTRA-INPUT-MIN-ROWS` is structural for an invalid declaration and business when a
valid table has fewer records than required.

## M1 business decisions

A failed `check` uses `MANTRA-CHECK-FAILED`. A reconciliation uses `MANTRA-RECONCILE-FAILED` when
`abs(left - right) > tolerance`; equality at the tolerance boundary passes. Its result retains the
exact left/right amounts, difference, tolerance and pass state. Checks and reconciliations do not
contribute to ordinary totals and cannot be read as numeric dependency roots.

`MANTRA-INPUT-REQUIRED` covers unconditional and conditional requirements. Omission, nil and blank
text are missing; default or implicit zero does not count as an explicitly supplied fact. A supplied
numeric zero is valid. Conditional table-column requirements preserve every record and its original
row index. `MANTRA-INPUT-RANGE` reports declared minimum/maximum violations.

`MANTRA-AGGREGATE-ZERO-DENOMINATOR` returns an undefined aggregate and one warning for that aggregate
context, preserving a valid run. `MANTRA-AGGREGATE-DIVISION` is an execution error when strict exact
division needs a rounding declaration. A member's ordinary formula still follows the schema's
explicit zero-denominator guard.

## Code families

Schema/case/include/parameter/layout codes explain document declarations. Dimension, input, all-member,
formula and total codes explain binding and dependency structure. Data codes explain source loading
and mappings. Value/evaluation codes explain execution. Workbench codes explain local request,
revision, editor, token and resource-limit contracts; they retain structural category by default.

`DSL-MANTRA-*` names below are cause codes of the locally supplied `mantra.calc` function library.
The adapter normally exposes them inside a `MANTRA-EVALUATION` message. Other `DSL-*` causes can come
from the pinned Normein kernel and are outside this repository's static-code inventory. Preserve the
cause text and inspect the matching locked kernel documentation when troubleshooting it.

## Import failures

`MANTRA-DATA-UTF8`, `MANTRA-DATA-XLSX-CELL`, `MANTRA-DATA-XLSX-CONTAINER`,
`MANTRA-DATA-XLSX-LIMIT` and `MANTRA-DATA-XLSX-NAME` are structural errors. They reject the
import before its values can become calculation inputs. Malformed UTF-8 is decoded with `REPORT`;
Excel error cells remain technical failures, preserving the concrete error in the original message.
Neither failure is converted into a successful missing, zero or replacement-character fact.

XLSX preflight bounds the immutable capture to 10 MiB compressed, 1,000 entries, 16 MiB expanded per
entry and 64 MiB expanded per archive view before POI opens a workbook. Local headers and the central
directory must select the same entry names, expanded lengths and SHA-256 content digests. It does not
change POI's global ZIP settings. Upload inspection projects the limit failure as `TOO_LARGE`.
Host cancellation, deadline and run-budget failures retain their original structured failure and
evaluation category; they are not rewritten as import errors.

Named member cells must identify every declared axis in canonical order. Unknown, ambiguous,
partial or invalid range names are errors; valid two-dimensional names become nested case maps.
Explicit numeric zero and Boolean false remain supplied values. Cell failures retain their input
address and, for table cells, their zero-based row index and column. `MANTRA-DATA-XLSX` remains a
structural warning when the source supplies no recognized named input cells.

## Complete static inventory

This table covers every complete static diagnostic/cause string in `mantra-*/src/main/**/*.kt`.
It excludes test fixtures, application code, dependency checkouts and incomplete matching prefixes.
`python3 scripts/check-diagnostics.py` rejects missing, stale or duplicate rows; adding a code requires
an intentional description here. This is an inventory of stable names, not a promise that every
listed code is a top-level workbench diagnostic.

| Code | Category | Meaning |
| --- | --- | --- |
| `DSL-MANTRA-ALLOC-ZERO-BASIS` | evaluation cause | Allocation weights sum to zero, so the requested allocation is undefined. |
| `DSL-MANTRA-BAND-ROW` | evaluation cause | A lookup band is not a two-item threshold/value row. |
| `DSL-MANTRA-INTEGER-REQUIRED` | evaluation cause | A library argument requiring an integer is fractional or invalid. |
| `DSL-MANTRA-MAP-REQUIRED` | evaluation cause | A dimension or allocation argument is not a member map. |
| `DSL-MANTRA-NUMBER-REQUIRED` | evaluation cause | A numeric library argument is not a number. |
| `DSL-MANTRA-PMT-PERIODS` | evaluation cause | Payment calculation has a nonpositive number of periods. |
| `DSL-MANTRA-ROLLUP-KEY` | evaluation cause | A source member has no parent relation for rollup. |
| `DSL-MANTRA-STEPWISE-BAND` | evaluation cause | A progressive band is not an upper-limit/rate pair. |
| `MANTRA-AGGREGATE` | structural | An aggregate declaration is malformed or has invalid ratio references. |
| `MANTRA-AGGREGATE-DIVISION` | evaluation | An aggregate quotient needs explicit rounding because its decimal expansion is nonterminating. |
| `MANTRA-AGGREGATE-REFERENCE` | structural | A ratio numerator or denominator is missing, nonnumeric or has different dimensions. |
| `MANTRA-AGGREGATE-TYPE` | structural | Ratio aggregation is attached to a nonnumeric measure or lacks valid ratio metadata. |
| `MANTRA-AGGREGATE-ZERO-DENOMINATOR` | business | The aggregate denominator is zero; the ratio remains undefined rather than becoming zero. |
| `MANTRA-ALL-DYNAMIC` | structural | An all-member reference cannot resolve to a static node name. |
| `MANTRA-ALL-UNKNOWN` | structural | An all-member reference names an unknown calculated node. |
| `MANTRA-AUDIT-TRUNCATED` | evaluation | Audit capture reached its per-coordinate or run budget; missing steps are explicitly marked. |
| `MANTRA-CASE-BIND` | structural | A case formula binding is malformed. |
| `MANTRA-CASE-BIND-DUPLICATE` | structural | A case binds the same formula slot more than once. |
| `MANTRA-CASE-BIND-UNKNOWN` | structural | A case binds a formula slot not declared by the schema. |
| `MANTRA-CASE-DUPLICATE` | structural | The same case input or parameter key is supplied more than once. |
| `MANTRA-CASE-EXTEND` | structural | A case extension is malformed. |
| `MANTRA-CASE-FORM` | structural | The case contains an unsupported declaration form. |
| `MANTRA-CASE-ID` | structural | The case identifier is missing. |
| `MANTRA-CASE-INPUT-UNKNOWN` | structural | The case supplies an input not declared by its schema. |
| `MANTRA-CASE-PARAM-UNKNOWN` | structural | The case overrides a parameter not declared by its schema. |
| `MANTRA-CASE-ROOT` | structural | The document is not a case document. |
| `MANTRA-CASE-SCHEMA-MISMATCH` | structural | The case declares a different schema from the selected one. |
| `MANTRA-CASE-SLOT-UNKNOWN` | structural | A case extends an undeclared extension slot. |
| `MANTRA-CASE-SOURCE` | structural | A source declaration lacks valid source options or form. |
| `MANTRA-CHECK-ARITY` | structural | A check or reconciliation declaration has invalid arguments. |
| `MANTRA-CHECK-FAILED` | business | An applicable declarative Boolean check evaluated to false. |
| `MANTRA-CHECK-OP` | structural | A check or reconciliation attempts to contribute to a running total. |
| `MANTRA-CHECK-SEVERITY` | structural | A check severity is outside error or warning. |
| `MANTRA-CHOICE-OPTION` | structural | A choice option is malformed. |
| `MANTRA-CHOICE-RULE` | structural | The choice selection rule is unsupported. |
| `MANTRA-CYCLE` | structural | The calculation dependency graph contains a cycle. |
| `MANTRA-DATA-CSV` | structural | A CSV source cannot be read or lacks its declared member/mapping columns. |
| `MANTRA-DATA-JSON` | structural | A JSON source cannot be read or parsed. |
| `MANTRA-DATA-PATH` | structural | A requested JSON source path is absent. |
| `MANTRA-DATA-SOURCE` | structural | An external source declaration is unsupported or its file cannot be loaded. |
| `MANTRA-DATA-UNKNOWN-INPUT` | structural | A source mapping targets an undeclared input. |
| `MANTRA-DATA-UTF8` | structural | CSV or JSON import bytes are not valid UTF-8; the import is rejected without replacing characters. |
| `MANTRA-DATA-XLSX` | structural | A workbook source supplies no recognized named input cells. |
| `MANTRA-DATA-XLSX-CELL` | structural | An input workbook cell contains an Excel error, an invalid date serial or an unsupported value; import fails without a nil or zero substitute. |
| `MANTRA-DATA-XLSX-CONTAINER` | structural | The workbook ZIP is malformed, duplicates entries, lacks required OOXML parts or has inconsistent local and central contents. |
| `MANTRA-DATA-XLSX-LIMIT` | structural | A workbook import exceeds its compressed-byte, entry-count, per-entry expanded-byte or total expanded-byte limit. |
| `MANTRA-DATA-XLSX-NAME` | structural | A named input cell has an unknown, incomplete or ambiguous coordinate, a conflicting sanitized name or an invalid cell reference. |
| `MANTRA-DEFN` | structural | A schema or case function declaration is malformed. |
| `MANTRA-DIMENSION` | structural | A dimension declaration lacks a valid identifier, members or table source. |
| `MANTRA-DIMENSION-KEY` | structural | A dimension member key is missing, invalid or duplicated. |
| `MANTRA-DIMENSION-PARENT` | structural | A child member refers to an unknown or missing parent. |
| `MANTRA-DIMENSION-RELATION` | structural | A declared dimension parent relation is invalid. |
| `MANTRA-DIMENSION-TABLE` | structural | A dimension table source is missing or not a table input. |
| `MANTRA-DIMENSION-UNKNOWN` | structural | A node references an undeclared dimension. |
| `MANTRA-EVALUATION` | evaluation | The pinned kernel rejected a formula during evaluation; its cause code is retained in the message. |
| `MANTRA-FIELD` | structural | A field has no corresponding declared input. |
| `MANTRA-FORMULA` | structural | A formula cannot compile, resolve its roots or satisfy its declared type. |
| `MANTRA-FORMULA-SLOT-OWNER` | structural | A formula slot is placed outside its permitted schema context. |
| `MANTRA-FORMULA-SLOT-REFERENCE` | structural | A case-bound formula uses a root not licensed by its formula slot. |
| `MANTRA-FORMULA-SLOT-USES` | structural | A formula-slot uses declaration is not a valid root-symbol vector. |
| `MANTRA-ID-DUPLICATE` | structural | The schema declares an identifier more than once. |
| `MANTRA-ID-INVALID` | structural | An identifier does not match the supported naming syntax. |
| `MANTRA-ID-RESERVED` | structural | An identifier conflicts with a reserved engine or kernel name. |
| `MANTRA-ID-SHADOWED` | structural | An application function shadows an existing callable name. |
| `MANTRA-INCLUDE-CYCLE` | structural | Fragments recursively include each other. |
| `MANTRA-INCLUDE-MISSING` | structural | An included document cannot be resolved. |
| `MANTRA-INCLUDE-PATH` | structural | An include form does not supply a string path. |
| `MANTRA-INCLUDE-ROOT` | structural | An included document is not a fragment. |
| `MANTRA-INPUT` | structural | An input declaration is malformed. |
| `MANTRA-INPUT-COLUMN` | structural | A table record has an unknown or required nonnumeric missing column. |
| `MANTRA-INPUT-COLUMNS` | structural | A table declaration lacks valid scalar column types. |
| `MANTRA-INPUT-MIN-ROWS` | business / structural | The table has too few rows; an invalid minimum-row declaration uses the same code with structural category. |
| `MANTRA-INPUT-MISSING` | structural | A nonoptional input cannot bind a missing fact without a supported default. |
| `MANTRA-INPUT-OPTIONS` | structural | An input options declaration is malformed. |
| `MANTRA-INPUT-RANGE` | business | An input number is outside its declared minimum or maximum. |
| `MANTRA-INPUT-REFERENCE` | structural | A supplied table foreign key refers to an unavailable member. |
| `MANTRA-INPUT-REQUIRED` | business | An unconditional or applicable conditional requirement lacks an explicitly supplied nonblank fact. |
| `MANTRA-INPUT-TYPE` | structural | A supplied input or table-cell value does not match its declared type. |
| `MANTRA-LAYOUT-COL` | structural | A layout column declaration is malformed. |
| `MANTRA-LAYOUT-COLUMNS` | structural | A layout column list or style selection is malformed. |
| `MANTRA-LAYOUT-CONTENT` | structural | A layout column requests unsupported content. |
| `MANTRA-LAYOUT-FORM` | structural | A layout contains an unsupported declaration form. |
| `MANTRA-LAYOUT-ID` | structural | A layout selector requires a valid item or section identifier. |
| `MANTRA-LAYOUT-PRESET` | structural | The layout preset name is unknown. |
| `MANTRA-LAYOUT-ROOT` | structural | The document is not a layout document. |
| `MANTRA-LAYOUT-ROW-NUMBERS` | structural | The row-numbering mode is unsupported. |
| `MANTRA-LAYOUT-SELECTOR` | structural | A style selector is malformed or uses an unsupported selector key. |
| `MANTRA-LAYOUT-STYLE` | structural | Style declarations contain unsupported values or properties. |
| `MANTRA-LAYOUT-TABLE` | structural | A layout table declaration is malformed. |
| `MANTRA-LINE-ARITY` | structural | A line has unexpected arguments. |
| `MANTRA-LINE-FORMULA` | structural | A line is missing its calculation formula. |
| `MANTRA-NOTE-TEXT` | structural | A note does not supply a text literal. |
| `MANTRA-PARAM` | structural | A schema parameter declaration is malformed. |
| `MANTRA-PARAMETERS-DUPLICATE` | structural | A parameter-set document assigns a parameter twice. |
| `MANTRA-PARAMETERS-FORM` | structural | A parameter-set document contains an unsupported form. |
| `MANTRA-PARAMETERS-ID` | structural | The parameter-set identifier is missing. |
| `MANTRA-PARAMETERS-ROOT` | structural | The document is not a parameter-set document. |
| `MANTRA-PARAMETERS-SCHEMA` | structural | A parameter set targets a different schema. |
| `MANTRA-PARAMETERS-UNKNOWN` | structural | A parameter set assigns an undeclared parameter. |
| `MANTRA-PARAMETERS-VALUE` | structural | A parameter-set value declaration is malformed. |
| `MANTRA-READ-DUPLICATE-KEY` | structural | An options map contains duplicate keys. |
| `MANTRA-READ-LITERAL` | parsing | A literal violates kernel value-construction limits or literal rules. |
| `MANTRA-READ-MAP` | structural | A map literal has an odd number of key/value forms. |
| `MANTRA-READ-OPTIONS` | structural | A declaration expected an options map. |
| `MANTRA-READ-SYNTAX` | parsing | The document cannot be parsed as valid DSL syntax. |
| `MANTRA-RECONCILE-FAILED` | business | The absolute left-minus-right difference exceeds the declared inclusive tolerance. |
| `MANTRA-RECONCILE-TOLERANCE` | structural | A reconciliation tolerance is negative, nonnumeric or not a literal. |
| `MANTRA-RECORD` | structural | An input or member record cannot be bound to the kernel record type. |
| `MANTRA-RESULT-TYPE` | evaluation | A numeric formula produced an incompatible value. |
| `MANTRA-SCHEMA-CLASS` | structural | A presentation class declaration is malformed. |
| `MANTRA-SCHEMA-DISPLAY` | structural | A section display mode is unsupported. |
| `MANTRA-SCHEMA-FORM` | structural | A schema contains an unsupported declaration form. |
| `MANTRA-SCHEMA-GROUP` | structural | An input group is not a keyword. |
| `MANTRA-SCHEMA-GROUP-TITLES` | structural | Input-group titles are not a valid text-label map. |
| `MANTRA-SCHEMA-HEADLINE` | structural | The schema headline does not identify a valid node. |
| `MANTRA-SCHEMA-ID` | structural | The schema identifier is missing. |
| `MANTRA-SCHEMA-LABEL` | structural | A schema item lacks a valid label. |
| `MANTRA-SCHEMA-LAYOUT` | structural | The schema layout selection is malformed. |
| `MANTRA-SCHEMA-OP` | structural | A contribution operator is outside plus, minus or info. |
| `MANTRA-SCHEMA-PER` | structural | An item dimension declaration is not a symbol or symbol vector. |
| `MANTRA-SCHEMA-PLACEMENT` | structural | A declaration appears in a context where it is not allowed. |
| `MANTRA-SCHEMA-PRESENTATION` | structural | A presentation attribute has the wrong value type. |
| `MANTRA-SCHEMA-ROOT` | structural | The document is not a schema document. |
| `MANTRA-SCHEMA-ROUND` | structural | The rounding scale or mode is malformed. |
| `MANTRA-SCHEMA-SIGN-LABELS` | structural | Positive, negative or zero display labels are malformed. |
| `MANTRA-SCHEMA-TYPE` | structural | A declared value type is unknown. |
| `MANTRA-SLOT-CONTENT` | structural | An extension slot contains an unsupported item. |
| `MANTRA-SPREAD-DIMS` | structural | An allocation-spread line has an invalid dimension context. |
| `MANTRA-TOTAL-DIMS` | structural | A total combines incompatible dimension contexts. |
| `MANTRA-TOTAL-TRAILING` | structural | An unterminated contributing sequence has no appropriate total. |
| `MANTRA-TYPES` | structural | Internal record-type definitions are inconsistent. |
| `MANTRA-VALUE` | evaluation | A formula result violates the kernel value-construction contract. |
| `MANTRA-VALUE-LIMIT` | evaluation | A formula result exceeds a kernel value-size limit. |
| `MANTRA-WORKBENCH-BUSY` | structural | The workbench has reached its concurrent event-stream limit. |
| `MANTRA-WORKBENCH-CASE-SCHEMA` | structural | A workspace case does not select a schema. |
| `MANTRA-WORKBENCH-CONFLICT` | structural | An edit revision is stale or the workspace changed before commit. |
| `MANTRA-WORKBENCH-DOCUMENT` | structural | A requested edit or document was rejected by its contract. |
| `MANTRA-WORKBENCH-EDIT` | structural | Editor text, input coordinates or authoring operations are invalid. |
| `MANTRA-WORKBENCH-HOST` | structural | The request host is outside the permitted loopback host. |
| `MANTRA-WORKBENCH-INTERNAL` | structural | The workbench request failed unexpectedly. |
| `MANTRA-WORKBENCH-NOT-FOUND` | structural | The requested workspace item or route is absent. |
| `MANTRA-WORKBENCH-REQUEST` | structural | The request method, route parameters or payload is invalid. |
| `MANTRA-WORKBENCH-TOKEN` | structural | The required local-session token is missing or invalid. |
| `MANTRA-WORKBENCH-TOO-LARGE` | structural | A request, workbook or output exceeds its configured size limit. |
| `MANTRA-WORKBENCH-UNAVAILABLE` | structural | The requested endpoint is unavailable. |


## M2 periods and multidimensional layouts

Period and layout declaration errors are structural. `DSL-MANTRA-*` codes below are evaluation causes.
Runtime cycles retain `MANTRA-CYCLE` and show
node/member coordinates; they are not bypassed merely because a schema contains a previous-period reference.

| Code | Meaning |
| --- | --- |
| `MANTRA-AGGREGATE-BOUNDARY` | First/last aggregation needs a numeric node containing the declared period axis |
| `MANTRA-LAYOUT-AXES` | Row, column or fixed axes/members are unknown, overlap, or select an incompatible node |
| `MANTRA-LAYOUT-FIXED` | A fixed layout member must be a keyword key |
| `MANTRA-LAYOUT-ROW-DIMENSION` | A row dimension must be an identifier |
| `MANTRA-PERIOD-CONTINUITY` | Declared periods are out of order, overlap or leave a gap |
| `MANTRA-PERIOD-COUNT` | A period dimension needs at least one period |
| `MANTRA-PERIOD-DECLARATION` | Static/generated period syntax or options are invalid |
| `MANTRA-PERIOD-KEY` | Period keys must be nonblank and unique |
| `MANTRA-PERIOD-LIMIT` | Period member count exceeds the collection ceiling |
| `MANTRA-PERIOD-PARENT` | A child period is outside or crosses the parent interval |
| `MANTRA-PERIOD-RANGE` | A period start must precede its exclusive end |
| `DSL-MANTRA-ROLLUP-BOUNDARY` | Five-argument rollup needs a first/last boundary policy |
| `DSL-MANTRA-ROLLUP-ORDER` | Boundary rollup needs an explicit unique sequence of period keys |
| `DSL-MANTRA-PREV` | Previous-period reference has an invalid target or ambiguous period context |
| `DSL-MANTRA-CALC-ITERATIONS` | evaluation cause | Convergence maximum must be an exact integer from 1 through 1000. |
| `DSL-MANTRA-CALC-NOT-CONVERGED` | evaluation cause | No adjacent iterate meets the tolerance within the declared maximum; no iterate becomes a result. |
| `DSL-MANTRA-CALC-NUMBER` | evaluation cause | Convergence requires a defined scalar numeric seed, tolerance, bound and callback result. |
| `DSL-MANTRA-CALC-TOLERANCE` | evaluation cause | Convergence tolerance must be nonnegative. |
| `MANTRA-CALC-INVALID-BOUND` | evaluation | A projected calculation primitive has an invalid explicit bound. |
| `MANTRA-CALC-NOT-CONVERGED` | evaluation | Bounded convergence exhausted its maximum; the failed node is nil with genuine failure evidence. |
| `MANTRA-CALC-NUMBER` | evaluation | A projected calculation primitive requires a defined numeric scalar. |
| `MANTRA-CASE-LINK` | structural | A link declaration lacks a literal path, exact schema/version, or complete mapping records. |
| `MANTRA-CASE-SCHEMA-VERSION` | structural | Case version is invalid or differs from the actual loaded schema version. |
| `MANTRA-LINK-ADDRESS` | structural | A source, target, participating case or full coordinate cannot be resolved unambiguously. |
| `MANTRA-LINK-CONFLICT` | structural | A linked target already has a local, imported or competing linked fact, including explicit nil. |
| `MANTRA-LINK-CYCLE` | structural | The canonical source case is already on the active graph path. |
| `MANTRA-LINK-REVISION` | structural | Requested Explain evidence refers to a changed source revision. |
| `MANTRA-LINK-TYPE` | structural | A defined source scalar does not match the target input declaration. |
| `MANTRA-LINK-UNDEFINED` | evaluation | A source failed technically, has a nil/inactive/missing value, or an authored link has not been materialized. |
| `MANTRA-LINK-VERSION` | structural | The source schema identity and opaque version do not match the exact authored assertion. |
| `MANTRA-RUN-CANCELLED` | evaluation | Shared cancellation stopped the current run; any partial graph is reported without an old root result. |
| `MANTRA-RUN-DEADLINE` | evaluation | The shared effective run deadline was reached. |
| `MANTRA-RUN-LIMIT` | evaluation | A cumulative counter or high-water bound was exceeded; typed usage includes counter, limit and attempted amount. |
| `MANTRA-SCHEMA-VERSION` | structural | Schema version must be nonblank literal text. |

## M4 package and explicit migration contracts

Package and migration failures are structural request failures. Source BUSINESS diagnostics remain
attached to their actual calculation and never become package-load or migration failures.

| Code | Category | Meaning |
| --- | --- | --- |
| `MANTRA-COMPILE-INCOMPATIBLE` | structural | The compile incompatible contract failed; correct the explicit request or resources before retrying. |
| `MANTRA-COMPILE-INPUT` | structural | The compile input contract failed; correct the explicit request or resources before retrying. |
| `MANTRA-COMPILE-PLAN` | structural | The compile plan contract failed; correct the explicit request or resources before retrying. |
| `MANTRA-MIGRATION-BINDING` | structural | The migration binding contract failed; correct the explicit request or resources before retrying. |
| `MANTRA-MIGRATION-COMMIT` | structural | The migration commit contract failed; correct the explicit request or resources before retrying. |
| `MANTRA-MIGRATION-EDIT` | structural | The migration edit contract failed; correct the explicit request or resources before retrying. |
| `MANTRA-MIGRATION-LIMIT` | structural | The migration limit contract failed; correct the explicit request or resources before retrying. |
| `MANTRA-MIGRATION-REVIEW` | structural | The supplied token does not identify the exact reviewed migration preview. |
| `MANTRA-MIGRATION-STALE` | structural | Case bytes, the source graph or target resources changed after the reviewed preview. |
| `MANTRA-MIGRATION-STORE` | structural | The migration store contract failed; correct the explicit request or resources before retrying. |
| `MANTRA-MIGRATION-TECHNICAL` | structural | The target evaluation has a technical failure; no migration is committed. |
| `MANTRA-PACKAGE-BINDING` | structural | The package binding contract failed; correct the explicit request or resources before retrying. |
| `MANTRA-PACKAGE-CONTAINER` | structural | The package container contract failed; correct the explicit request or resources before retrying. |
| `MANTRA-PACKAGE-DATE` | structural | The package date contract failed; correct the explicit request or resources before retrying. |
| `MANTRA-PACKAGE-DATE-GAP` | structural | No declared candidate provides a required parameter key at the explicit effective date. |
| `MANTRA-PACKAGE-DATE-OVERLAP` | structural | More than one declared candidate provides the same required key at the effective date. |
| `MANTRA-PACKAGE-DEPENDENCY` | structural | The package dependency contract failed; correct the explicit request or resources before retrying. |
| `MANTRA-PACKAGE-DUPLICATE` | structural | The package duplicate contract failed; correct the explicit request or resources before retrying. |
| `MANTRA-PACKAGE-ENGINE` | structural | The package engine comparator intersection excludes the supplied engine version. |
| `MANTRA-PACKAGE-INTEGRITY` | structural | Captured resource bytes do not match the manifest length or SHA-256 digest. |
| `MANTRA-PACKAGE-IMPORT` | structural | A captured-data importer is missing or changed the source-owned case or link identity. |
| `MANTRA-PACKAGE-LIMIT` | structural | The package limit contract failed; correct the explicit request or resources before retrying. |
| `MANTRA-PACKAGE-MANIFEST` | structural | The package manifest contract failed; correct the explicit request or resources before retrying. |
| `MANTRA-PACKAGE-PARAMETERS` | structural | The package parameters contract failed; correct the explicit request or resources before retrying. |
| `MANTRA-PACKAGE-PATH` | structural | A resource path is noncanonical, escapes its container or follows a prohibited symbolic link. |
| `MANTRA-PACKAGE-RANGE` | structural | The package range contract failed; correct the explicit request or resources before retrying. |
| `MANTRA-PACKAGE-RESOURCE` | structural | The package resource contract failed; correct the explicit request or resources before retrying. |
| `MANTRA-PACKAGE-ROLE` | structural | The package role contract failed; correct the explicit request or resources before retrying. |
| `MANTRA-PACKAGE-SECURE-READ` | structural | The selected filesystem cannot provide the explicitly required directory-relative access capability. |
| `MANTRA-PACKAGE-SOURCE-CHANGED` | structural | A cooperative directory capture detected a file or directory change before the capture completed. |
| `MANTRA-PACKAGE-UNLISTED` | structural | A requested resource is not declared in the captured manifest. |
| `MANTRA-PACKAGE-UNREGISTERED` | structural | A referenced package has not been explicitly mounted by the host. |
| `MANTRA-PACKAGE-UTF8` | structural | A captured resource is not valid UTF-8. |
| `MANTRA-PACKAGE-VERSION` | structural | The package version contract failed; correct the explicit request or resources before retrying. |

## PDF export

| Code | Category | Meaning |
| --- | --- | --- |
| `MANTRA-PDF-LIMIT` | presentation | PDF row, page, column, header or byte capacity was exceeded; no incomplete PDF is returned. |
| `MANTRA-PDF-GLYPH` | presentation | A text glyph cannot be encoded by the embedded font; export fails explicitly. |

| `MANTRA-PACKAGE-HOST-BINDING` | structural | The writable host case has an invalid or stale package binding; review its explicit package identity and source revision. |
