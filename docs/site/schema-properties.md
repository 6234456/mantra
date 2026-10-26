# Edit schema presentation properties by source owner

`template-schema-properties` provides source editing for an authoring client. It inspects labels,
note text and class assignments, then returns exact in-memory source patches. Owners come from
the public schema reader and its source ranges, including declarations hidden from the Paper.
Workspace files remain unchanged; use a real template preview after accepting a patch.

## Start the example workspace

From the repository root with JDK 21 and a supported Node.js version:

```sh
npm --prefix workbench-ui ci
./gradlew --no-daemon :mantra-cli:installDist
VITE_WORKBENCH_MODE=live npm --prefix workbench-ui run build
mantra-cli/build/install/mantra/bin/mantra serve docs/examples/schema-properties \
  --ui workbench-ui/dist --port 8080
```

The live index supplies the session metadata used by the script. The existing source-first page
is described in [template draft preview](template-draft-preview.md).

## Use the presentation property panel

Open **Schema presentation properties** on `/template-preview`, then **Inspect schema properties**.
Choose a declaration by document, line, form, node and property. The list includes hidden, zero and
inactive declarations; captured includes show their read-only reason. It is a source declaration
list, not a projection of visible Paper rows or their final computed text/style.

Edit **Declared text** for a label or note. For **Assigned classes**, enter unique simple names
separated by whitespace, without colons. Unknown valid names remain allowed. Clearing the field
and applying sets explicit `[]`; **Remove class declaration** removes the option entirely.

**Apply schema property** accepts the real service's source patch into the in-memory root schema.
It joins manual edits, layout settings and style definitions in the same **Undo source / Redo source**
history. It does not save a template file or calculate a preview. Apply or Cancel all pending fields
before **Preview draft**; source-only inspection does not validate formulas or example inputs.

If source changes, refresh the directory explicitly and review retained fields before applying.
A retained field must still match its declaration path, form, node, property and exact declaration
text. If that declaration changed, cancel and select the intended declaration again. Responses from
older requests, contexts or field edits cannot overwrite newer edits. External graph conflicts retain
the draft and require a new source capture.

Unapplied fields participate in [browser recovery and JSON backups](template-recovery.md). A backup
with schema fields uses recovery format v2; v1 remains readable and is still exported when no schema
fields are pending. Restoring does not restore owner permissions or calculated results: inspect and
explicitly apply again. System input-method behavior still requires manual verification.

## API example

An authoring client can also use the generated contract and validated `LiveSchemaPropertiesClient`
transport directly.

Example sources: [schema](repo:docs/examples/schema-properties/schema.mantra),
[shared fragment](repo:docs/examples/schema-properties/shared.mantra),
[layout](repo:docs/examples/schema-properties/layout.mantra) and
[case](repo:docs/examples/schema-properties/case.mantra).

## Inspect, edit and preview

The following Python 3 script uses only the standard library. Run it while the example server
above is listening. It captures the source graph, inspects owners, changes the answer label and
class together, then sends the resulting source to the real preview service. It does not save
template files or print the session credential.

```python
import json
from html.parser import HTMLParser
from urllib.request import Request, urlopen

base = "http://127.0.0.1:8080"
case = "/api/v1/cases/case.mantra"

class SessionMeta(HTMLParser):
    token = None
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "meta" and attrs.get("name") == "mantra-session-token":
            self.token = attrs.get("content")

meta = SessionMeta()
meta.feed(urlopen(base + "/").read().decode("utf-8"))
assert meta.token, "Start the live file-workspace server first"

def post(path, body):
    request = Request(base + path, json.dumps(body).encode("utf-8"), method="POST",
                      headers={"Content-Type": "application/json", "X-Mantra-Token": meta.token})
    return json.load(urlopen(request))

snapshot = json.load(urlopen(base + case + "/template-sources"))
source = next(doc for doc in snapshot["data"]["documents"] if doc["role"] == "schema")
request = {"baseRevision": snapshot["revision"],
           "baseRevisions": snapshot["data"]["baseRevisions"], "draftSequence": 1,
           "handle": source["handle"], "text": source["text"], "operations": []}
catalog = post(case + "/template-schema-properties", request)["data"]
label = next(o for o in catalog["owners"] if o["node"] == "answer" and o["property"] == "label")
classes = next(o for o in catalog["owners"] if o["node"] == "answer" and o["property"] == "classes")
request["operations"] = [{"op": "setText", "owner": label["handle"], "text": "计算结果😀"},
                         {"op": "setClasses", "owner": classes["handle"], "classes": ["highlighted"]}]
edited = post(case + "/template-schema-properties", request)["data"]
documents = [{"handle": d["handle"],
              "text": edited["source"]["text"] if d["handle"] == source["handle"] else d["text"]}
             for d in snapshot["data"]["documents"] if d["editable"]]
preview = post(case + "/template-preview", {
    "baseRevision": snapshot["revision"], "baseRevisions": snapshot["data"]["baseRevisions"],
    "draftSequence": 2, "documents": documents, "inputs": []})["data"]
print(preview["run"]["values"]["answer"][""]["value"])  # independently: 10 * 2 = {"n": "20"}
print(edited["source"]["text"])
```

The candidate Paper shows the new label and the layout's declared bold/accent/subtle rule. Its
exact answer remains twenty: presentation edits do not change the formula. Use
[Paper acceptance checks](template-paper-acceptance.md) to verify final text and styles across
scenarios.

## Owners and operations

The catalog covers presentation items: sections/slots, lines and aliases, fields, totals,
choices and aliases, checks/reconciliations, and notes. Each item has a label or note-text owner
and a classes owner. Independent input/parameter/dimension declarations, helper functions,
choice options and the schema root title are outside this first catalog.

An owner identifies its document, declaration kind, node when available, nested ordinal path,
property, exact UTF-16 ranges and current value. A note has no node ID. Included definitions are
listed with a read-only reason; the endpoint edits only the selected root schema. Zero, hidden
and inactive declarations remain locatable because the catalog is derived from source rather
than visible rows. A section label may differ from its explicit `:title` and final Paper heading.

| Operation | Required payload |
| --- | --- |
| `setText` | `owner`, `text` for a label or note-text owner |
| `setClasses` | `owner`, `classes` for a classes owner |
| `unset` | `owner` for a classes owner |

Class names are simple lowercase tags without a colon. New unknown tags are allowed; they affect
styles only when layout rules match them. A set operation accepts up to 64 unique names. An empty
list declares `:class []`; unset removes the override. Those states remain distinct. Existing
logical class lists retain their source spelling on a no-op, including a single-keyword form.

Labels and note text can be empty or contain Unicode. The service writes literal text and checks
that the current reader can read it back exactly. Unsupported control escapes produce a source
diagnostic rather than silently changing the text. Required text cannot be unset.
Unpaired UTF-16 surrogates are rejected with 422; valid Unicode pairs such as emoji remain exact.

## Revisions, patches and validation

Capture the complete revision map and document handle with `template-sources`. Send the exact
current root text to inspect. Property handles bind that complete baseline, document, exact
draft text, declaration occurrence and property. Use handles from the initial catalog for a
batch of at most 32 operations. A label and classes on the same declaration can be changed in
one batch; each property can occur once.

The result contains the final source, fresh owners and sequential patches with `removedText`.
Apply them to the exact submitted text in order, then verify the inverse roundtrip. Comments,
CRLF and text outside edited tokens are retained. After any text change, inspect again or use
the final catalog; old property handles are expired. A client must still check that its current
case, text, revision and draft sequence match the submitted request before accepting a reply.
The transport matches the immutable request snapshot and does not own the page's editing state.

The saved baseline must be technically readable. The candidate catalog uses `SchemaReader`
without compiling or running formulas, so a syntax-valid formula with a binding error can still
have its label repaired. Success proves source ownership and a reader-valid edit; it does not
prove calculation validity, values, visibility or final styles. Run the real candidate preview.
Business findings and runtime failures retain the existing preview semantics.

| Status | Meaning |
| --- | --- |
| `400` | Invalid shape, wrong or expired owner, read-only target or incorrect operation type |
| `409` | The captured participating source graph changed |
| `413` | Request or source budget exceeded |
| `422` | Invalid source, ambiguous metadata or text that cannot roundtrip |

The endpoint requires the current server session token and accepts POST without query parameters.
It is available for file workspaces. Formal template Save, shared-source editing, structural
operations, source history, publication and complete visual-authoring integration remain separate.
The [response schema](repo:docs/workbench/schema/template-schema-properties.schema.json) defines
the new payload independently of existing workbench endpoints.
