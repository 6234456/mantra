package com.xqiou.mantra.core.view

import com.xqiou.mantra.core.engine.Coord
import com.xqiou.mantra.core.engine.Member
import com.xqiou.mantra.core.engine.NodeTrace
import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.ChoiceItem
import com.xqiou.mantra.core.model.DimensionDecl
import com.xqiou.mantra.core.model.FieldItem
import com.xqiou.mantra.core.model.InputDecl
import com.xqiou.mantra.core.model.Item
import com.xqiou.mantra.core.model.LineItem
import com.xqiou.mantra.core.model.NoteItem
import com.xqiou.mantra.core.model.ParamDecl
import com.xqiou.mantra.core.model.Presentation
import com.xqiou.mantra.core.model.SchemaMeta
import com.xqiou.mantra.core.model.SectionItem
import com.xqiou.mantra.core.model.TotalItem
import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.structure.Panel
import com.xqiou.mantra.core.structure.SchemaMap
import java.util.Collections

/** Copy source-owned collections before exposing them through a calculation snapshot. */
internal fun <T> frozenList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
internal fun <K, V> frozenMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(values))

internal fun Value.snapshot(): Value = when (this) {
    Value.Nil, is Value.Num, is Value.Bool, is Value.Kw, is Value.Text, is Value.Date -> this
    is Value.Vec -> copy(items = frozenList(items.map { it.snapshot() }))
    is Value.MapV -> copy(entries = frozenMap(entries.mapKeys { (key, _) -> key.snapshot() }.mapValues { (_, value) -> value.snapshot() }))
}

internal fun Presentation.snapshot(): Presentation = copy(
    attributes = frozenMap(attributes.mapValues { (_, value) -> value.snapshot() }),
    classes = frozenList(classes),
)

internal fun Item.snapshot(): Item = when (this) {
    is SectionItem -> copy(per = per?.let(::frozenList), children = frozenList(children.map { it.snapshot() }), presentation = presentation.snapshot())
    is LineItem -> copy(per = per?.let(::frozenList), presentation = presentation.snapshot(), allowedRefs = allowedRefs?.let { Collections.unmodifiableSet(LinkedHashSet(it)) })
    is FieldItem -> copy(presentation = presentation.snapshot())
    is TotalItem -> copy(presentation = presentation.snapshot())
    is ChoiceItem -> copy(options = frozenList(options), per = per?.let(::frozenList), presentation = presentation.snapshot())
    is NoteItem -> copy(presentation = presentation.snapshot())
}

internal fun CaseData.snapshot(): CaseData = copy(
    meta = frozenMap(meta.mapValues { (_, value) -> value.snapshot() }),
    inputs = frozenMap(inputs.mapValues { (_, value) -> value.snapshot() }),
    params = frozenMap(params.mapValues { (_, value) -> value.snapshot() }),
    extensions = frozenMap(extensions.mapValues { (_, items) -> frozenList(items.map { it.snapshot() }) }),
    formulaBindings = frozenMap(formulaBindings),
    functions = frozenList(functions),
)

internal fun SchemaMeta.snapshot(): SchemaMeta = copy(attributes = frozenMap(attributes.mapValues { (_, value) -> value.snapshot() }))

internal fun InputDecl.snapshot(): InputDecl = copy(
    per = per?.let(::frozenList), default = default?.snapshot(), options = frozenMap(options),
    columns = frozenList(columns), presentation = presentation.snapshot(), references = frozenMap(references),
)

internal fun ParamDecl.snapshot(): ParamDecl = copy(value = value.snapshot(), presentation = presentation.snapshot())

internal fun DimensionDecl.snapshot(): DimensionDecl = copy(members = frozenList(members))

internal fun Member.snapshot(): Member = copy(record = frozenMap(record.mapValues { (_, value) -> value.snapshot() }))

internal fun NodeTrace.snapshot(): NodeTrace = when (this) {
    is NodeTrace.Input, is NodeTrace.Param, is NodeTrace.Inactive, is NodeTrace.Failed -> this
    is NodeTrace.Computed -> copy(references = frozenList(references.map { it.copy(value = it.value.snapshot()) }), raw = raw.snapshot())
    is NodeTrace.Sum -> copy(parts = frozenList(parts))
    is NodeTrace.Choice -> copy(options = frozenList(options.map { it.copy(value = it.value.snapshot()) }), raw = raw.snapshot())
}

internal fun <T> Map<Coord, T>.snapshotCoords(value: (T) -> T): Map<Coord, T> =
    frozenMap(entries.associate { (coord, item) -> frozenList(coord) to value(item) })

internal fun SchemaMap.snapshot(): SchemaMap = SchemaMap(
    schemaId = schemaId, title = title,
    panels = frozenList(panels.map { panel ->
        Panel(
            id = panel.id, title = panel.title, order = panel.order, role = panel.role,
            step = panel.step, dims = frozenList(panel.dims), parentId = panel.parentId,
            resultId = panel.resultId, fields = frozenList(panel.fields), nodes = frozenList(panel.nodes),
            imports = frozenList(panel.imports), exports = frozenList(panel.exports),
            entries = frozenList(panel.entries.map { it.copy(path = frozenList(it.path)) }),
            breadcrumb = frozenList(panel.breadcrumb),
        )
    }),
    mainline = frozenList(mainline), generalInputs = frozenList(generalInputs),
    params = frozenList(params), flows = frozenList(flows),
)
