# Edit reusable style classes

The real `/template-preview` page can inspect and edit local `style-class` definitions in the root
layout. Its catalog comes from the running engine and submitted layout source. Preset definitions
remain read-only; a local definition can override the same name. Applying changes updates the
in-memory DSL, then **Preview draft** shows their actual effect.

Start the updated server and UI using [Template draft preview](template-draft-preview.md). This
feature requires a file workspace. It is separate from the recorded `/authoring` prototype.

## Inspect and edit a definition

1. Select an example case, expand **Style classes**, then click **Inspect style classes**.
   The catalog contains the selected presets, local definitions and literal class selector names
   used in this root layout. To select a different preset, edit the existing `:style-preset` source
   option and refresh the catalog.
2. Choose a **Style class**. The page shows its local declarations, inherited preset contribution
   and merged class contribution. **Class effective style** is not the final style of every cell.
   Later rules, selectors and table rules can override it; the matching engine Paper is authoritative.
3. For a preset-only class, click **Create local override**. For an existing local class, edit its
   controls directly. Alternatively, choose **Create local style class** and enter a new lowercase
   name without a colon, matching `[a-z][a-z0-9-]*`.
4. Set weight, tone and fill. **Omit (use preset if available)** removes that local property.
   It differs from explicit `normal`, `default` or `none`. Omitting all three keeps an empty local
   definition, which differs from removing the definition itself.
5. Click **Apply style changes**, then **Preview draft**. A source change marks the earlier candidate
   as previous. Inspect actual cell styles and values in the new preview; Apply does not calculate
   or save a template. **Cancel style changes** discards unapplied fields and leaves the source intact.

| Property | Explicit choices | Omitted property |
| --- | --- | --- |
| `weight` | `normal`, `bold` | Leaves inherited weight unchanged |
| `tone` | `default`, `muted`, `accent` | Leaves inherited tone unchanged |
| `fill` | `none`, `subtle`, `accent` | Leaves inherited fill unchanged |

The source remains the same layout DSL:

```clojure
(layout example/layout {:style-preset [:utilities :working-paper]}
  (style-class :company-total {:weight :bold :tone :accent})
  (style {:class :company-total :column :value} {:fill :subtle}))
```

A class definition does not assign a class to a schema node. Use the existing schema source to
assign `:class :company-total`, or an existing style rule's `:use` to reuse its declarations.
The form edits definitions only; it does not add arbitrary fonts, colors or CSS.

## References and removal

**References in submitted layout** lists literal `:class` selectors and style `:use` occurrences,
with their UTF-16 source ranges. This list covers only the submitted root layout. It does not scan
schema tags, includes or other templates, and does not establish the complete current draft's
impact. Preview the complete candidate to check its effect.

Choose **Remove local style class**, review the pending removal, then **Apply style changes**.
If a selected preset also supplies that class, removal reveals the preset contribution again.
Otherwise, a remaining `:use` reference blocks removal and explains the dependency. Edit that
reference in the source first and refresh. A selector or schema tag may legally have no matching
class rule, so those are not hard dependencies. Removal never rewrites references or renames tags.

Some valid selector names, such as `:future/tag`, cannot be declared as local style classes.
They remain visible with a read-only reason; they do not make an otherwise valid layout invalid.

## Source edits, conflicts and preview

Style fields and [advanced layout settings](layout-settings.md) share the same root source.
Unapplied fields in either form disable Preview with an Apply/Cancel explanation. Applying one
form makes the other's previous inspection stale without discarding its pending fields.
Refresh, review the retained values and apply explicitly. Manual source edits follow the same rule.

Delayed responses cannot replace newer source text or clear fields edited after a request began.
Invalid source or edits retain the draft. A `409` retains the draft and marks its captured source
revision as conflicted; explicitly reload the saved source after preserving any text you need.
The settings service checks the saved baseline, so a successful edit is not evidence that an
unsaved schema or calculation is valid. The complete draft still needs a matching real preview.

The service preserves comments, line endings, unrelated declarations and existing rule order.
New definitions append to the layout; they can affect rule precedence, which should be checked
in Paper. Removing a definition retains comments and whitespace between its syntax tokens.
The source editor may therefore retain blank space where a definition was removed.

## API

`POST /api/v1/cases/{encoded-case}/template-style-classes` uses the captured full revision map and
opaque root-layout handle. Empty operations inspect; `upsert` replaces the three local declaration
choices and `remove` removes a local definition. A request accepts up to sixteen distinct names.
Responses contain typed contributions, scoped references and sequential UTF-16 source patches
with exact removed text. The endpoint writes no workspace files, source history or builds.

See the [workbench contract](../workbench/contract.md#71-操作) for exact shapes, budgets and errors,
and the [DSL reference](../dsl-reference.md) for style rules and preset composition.
