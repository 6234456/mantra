# Edit advanced layout settings

The real `/template-preview` page can edit fourteen root-layout settings through typed controls.
They update the same in-memory DSL source shown in the editor. Use **Preview draft** afterwards to
calculate the candidate. Applying settings does not save a template file or publish a build.

Start the file workbench as described in [Template draft preview](template-draft-preview.md).
This feature requires the updated server and UI; rebuilding only the UI does not add its endpoint.
Package workspaces do not provide the settings endpoint.

## Inspect, edit and preview

1. Choose an example case and expand **Advanced layout settings**. Click **Inspect layout settings**.
   The server reads the current root layout and returns its property types, editable values and
   inherited defaults. Other captured documents and table-level overrides are outside this form.
2. Change a field to stage an explicit override. To deliberately keep the currently inherited value
   as an override, click **Use explicit value**. This also lets you choose explicit empty text,
   `false` or `0` even when the displayed input already looks identical.
3. **Reset to inherited** stages removal of that source option. It does not write the default value
   into the document. **Cancel setting changes** discards unapplied fields and keeps the source intact.
4. Click **Apply layout settings**. The server returns exact source patches and the updated root
   properties. The page accepts them only if the case, captured revision and current layout text
   still match the request. When the source changes, the source editor receives the changes and the
   earlier candidate becomes **Previous**; the captured saved-template comparison remains independent.
5. Click **Preview draft** to see the actual calculation, formatted tables and Explain. While fields
   are unapplied, preview is disabled with an Apply/Cancel reminder, so it cannot silently calculate
   the old source instead of the settings you are editing.

Each field distinguishes **Explicit**, **Inherited**, **Root effective value** and **Inherited value**.
These are root-layout values, not the final style or number format of every cell. Schema presentation
hints and table-specific settings may still override the global configuration. An inherited null
title can fall back to the schema title in the resulting Paper.

## Available settings

| Group | Source options | Accepted edits |
| --- | --- | --- |
| Report | `title`, `subtitle` | Text, including deliberate empty strings |
| Report | `language` | `de` or `en`, the existing report caption bundles |
| Number display | `locale` | A valid BCP 47 tag with a language, such as `de-DE` or `en-US` |
| Number display | `precision`, `percent-precision` | Whole decimal digits from 0 through 18 in this editor |
| Number display | `negative` | `minus` or `parentheses` |
| Number display | `zero` | Replacement text for zero, including an explicit empty string |
| Number display | `grouping`, `signed` | Explicit true/false or inherited |
| Visibility | `hide-zero`, `show-inactive`, `expand-members` | Explicit true/false or inherited |
| Visibility | `row-numbers` | `global`, `table`, or reset to the preset/column configuration |

Display precision does not change the exact calculation result or its explicit `:round` policy.
Changing number locale also changes how enabled raw-text example overrides are parsed at the next
preview; their original text is retained. Mantra percent formatting uses ratios: `0.19` displays as
19 percent. Language, locale and calculation rounding are separate settings.

The 0–18 limit belongs to this structured editor. A manually authored value outside its editing
range, an unsupported literal type or a nonliteral declaration remains in the source. The form
shows a read-only reason and lets you reset that option when it can be removed unambiguously.
Unknown options, comments, line endings and other layout declarations remain unchanged.

Text is checked against the pinned public literal reader before acceptance. Quotes, ordinary
backslashes, supported whitespace and Unicode text are preserved. A character that cannot be
represented without changing its meaning is rejected, rather than saved as a different escape
spelling. This does not change the kernel or reinterpret existing source text.

## Stale fields, errors and conflicts

- Manual source editing makes previously inspected properties stale. Your unapplied fields are kept.
  Use **Refresh layout settings**, review the retained changes, then apply explicitly.
- Changing a field while a request is in flight keeps that newer field buffer. An older confirmation
  cannot clear it or overwrite newer source text.
- Incomplete numbers and IME composition remain in the field buffer. Composition confirmation does
  not submit a settings edit. Physical system IME testing is separate from synthetic browser checks.
- An invalid value returns an error and retains the fields and source. A `409` retains the draft and
  marks the captured source revision as conflicted. Copy any source you need to keep before choosing
  **Reload saved source**, which explicitly discards this page's draft.
- The settings service resolves the saved baseline for its revision checks. Its success is not
  evidence that the current unsaved schema, example inputs or calculation is valid; only a matching
  **Preview draft** response supplies that evidence.

## API and scope

`POST /api/v1/cases/{encoded-case}/template-layout-settings` inspects or edits submitted root-layout
text. It binds the request to the full captured revision map and opaque root-layout handle. It
accepts up to sixteen unique set/unset operations and returns typed properties plus sequential
UTF-16 source patches with exact removed text. Applying the patches yields the returned source;
reversing them restores the original input. The service writes no workspace files or history.

The [workbench contract](../workbench/contract.md#71-操作) defines the request, response, budgets and
error semantics. This slice does not edit calculation rules, presets, table columns, local style
definitions, shared includes or source bindings. Those remain available through their existing
source interfaces or future authoring contracts.

The separate [Style classes](style-classes.md) form now edits local style definitions in the same
draft. Applying either form makes the other's inspection stale; pending fields are retained for
review and another explicit Apply.
