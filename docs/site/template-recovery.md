# Recover a real template draft

The real `/template-preview` page keeps a browser recovery copy of edited root schema/layout text,
raw example inputs and unapplied layout/style/schema presentation fields. A JSON backup can also
transfer this source draft to another browser or matching workspace. It contains no calculated results or validation
authority and does not save template files.

Start the updated server and UI using [Template draft preview](template-draft-preview.md). The
recovery tools require a file workspace with a valid workspace identity. This is separate from
the recorded `/authoring` prototype's recovery format.

An older server can still provide ordinary draft preview. If recovery identity is unavailable,
the recovery tools stay disabled; rebuild or update the live server and UI to enable them.

## Browser recovery

1. Edit source, an example input or a layout/style/schema field. The page briefly waits, then writes
   the current source and raw fields to this browser. **Draft recovery and JSON backup** reports the
   actual state. A confirmed backup has been read back from storage; a pending or failed write
   is not a protected copy of the newest edit.
2. After a page refresh, the stored copy is offered explicitly. Opening the page does not apply
   it or replace it with the clean template. Choose **Restore browser draft** or **Discard browser
   draft**. Download it first with **Download browser draft** if you want to keep an extra copy.
3. Restore requires the same workspace, example case, complete source revision map and original
   editable documents. A source or dependency change prevents automatic restoration. The old
   copy remains available for download; there is no automatic source merge or handle remapping.
4. A restored draft is marked **Restored · not validated**. Its source and raw inputs are editable,
   including invalid text such as `nine` or an empty override. Those inputs remain visible even
   before the first successful preview. Remove an override to return to the saved case value.
5. Restored unapplied fields remain unapplied. Their raw operations and Cancel are available even
   before inspection. Inspect the current source again, review the fields and explicitly Apply,
   or cancel them before previewing. No old property catalog or edit permission is restored.
6. Click **Preview draft**. Only a matching technical-valid candidate clears the restored marker;
   a business finding or runtime failure still displays its own engine status. A `422`, `413`,
   saved-template baseline response or older preview does not validate the recovered draft.

Restoration clears previous Paper, Explain, diagnostics and the page's in-memory Undo/Redo history.
The next preview uses the real engine. History entries are not included in recovery backups.

The page uses a slot for each workspace and case at this browser origin. Workspace identity comes
from the server's canonical root path; an identical source graph in another directory has a separate
automatic slot. Port/origin changes also change browser storage access. A backup file can cross
those boundaries through the explicit import flow below.

## Download and import JSON

Use **Download draft backup** to export the current draft, including pending fields. This works
independently of browser storage and does not require a successful draft preview. Keep this file
when moving between a cloud workbench and local development.

Choose **Import backup file**, or paste into **Draft backup JSON** and click **Validate backup JSON**.
Validation alone does not change the current editor. A compatible import offers **Restore imported
draft**. Restoring over current edits requires confirmation.

An imported file may name another workspace, but its case, complete saved source graph and original
editable documents must match exactly. The page explains that the workspace differs before you
explicitly restore it here. Different template revisions require manual recovery of the source;
import does not rebase, migrate or rewrite document identities.

Backups are limited to 2 MiB of UTF-8 JSON. Their strict versioned format rejects duplicate keys,
unknown fields, unsupported values, excessive nesting and forged result/permission fields.
Backups containing pending schema presentation fields use `mantra.template-preview-recovery/2`
([format](../workbench/schema/template-recovery-v2.schema.json)); the updated page also accepts
v1 and exports its unchanged shape when no schema fields are pending. Older clients cannot read v2.
A large or technically invalid source is still recoverable within that total budget; preview limits remain
independent. Oversized backups fail visibly without truncating the source.

## Storage failures and deliberate discard

If storage is disabled, full or changed by another page, the current draft stays editable and the
page reports that it is not protected. Download a JSON backup and use **Retry browser backup** after
addressing the cause. Recovery does not synchronize or merge concurrent tabs.

A discovered copy awaiting Restore/Discard is preserved while you inspect it. New editing does
not silently overwrite it; choose what to retain and download a copy before discarding it.
An incompatible or malformed stored copy also remains downloadable.

**Reload saved source** is a deliberate discard action. Its confirmation includes the corresponding
browser recovery copy; it then captures the current saved template. A physical page refresh instead
offers recovery. Browser copies are local to this browser and can be removed by clearing site data.

See [the workbench contract](../workbench/contract.md#71-操作) for the format and identity rules.
Identity comes from the optional authenticated `/api/v1/template-recovery-context` endpoint;
existing v4 workspace and source responses keep their original shape.
Formal template Save, server drafts and publication remain separate capabilities.
