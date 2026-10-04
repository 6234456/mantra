package com.xqiou.mantra.core.view

import com.xqiou.mantra.core.model.CaseData
import com.xqiou.mantra.core.model.CaseLink
import com.xqiou.mantra.core.model.CheckItem
import com.xqiou.mantra.core.model.ChoiceItem
import com.xqiou.mantra.core.model.DimensionDecl
import com.xqiou.mantra.core.model.FieldItem
import com.xqiou.mantra.core.model.InputAddress
import com.xqiou.mantra.core.model.InputDecl
import com.xqiou.mantra.core.model.Item
import com.xqiou.mantra.core.model.LineItem
import com.xqiou.mantra.core.model.LinkProvenance
import com.xqiou.mantra.core.model.NoteItem
import com.xqiou.mantra.core.model.ParamDecl
import com.xqiou.mantra.core.model.PeriodSpec
import com.xqiou.mantra.core.model.Presentation
import com.xqiou.mantra.core.model.ReconcileItem
import com.xqiou.mantra.core.model.Schema
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

internal fun Value.snapshot(scan: () -> Unit = {}): Value {
    scan()
    return when (this) {
        Value.Nil, is Value.Num, is Value.Bool, is Value.Kw, is Value.Text, is Value.Date -> this
        is Value.Vec -> copy(items = frozenList(items.map { it.snapshot(scan) }))
        is Value.MapV -> copy(
            entries = frozenMap(
                entries.mapKeys { (key, _) ->
                    key.snapshot(scan)
                }.mapValues { (_, value) -> value.snapshot(scan) },
            ),
        )
    }
}

internal fun Presentation.snapshot(scan: () -> Unit = {}): Presentation = copy(
    attributes = frozenMap(attributes.mapValues { (_, value) -> value.snapshot(scan) }),
    classes = frozenList(classes),
)

internal fun Item.snapshot(scan: () -> Unit = {}): Item {
    scan()
    return when (this) {
        is SectionItem -> copy(
            per = per?.let(::frozenList),
            children = frozenList(
                children.map {
                    it.snapshot(scan)
                },
            ),
            presentation = presentation.snapshot(scan),
        )
        is LineItem -> copy(
            per = per?.let(::frozenList),
            presentation = presentation.snapshot(scan),
            allowedRefs = allowedRefs?.let {
                Collections.unmodifiableSet(LinkedHashSet(it))
            },
        )
        is FieldItem -> copy(presentation = presentation.snapshot(scan))
        is TotalItem -> copy(presentation = presentation.snapshot(scan))
        is ChoiceItem -> copy(
            options = frozenList(options),
            per = per?.let(::frozenList),
            presentation = presentation.snapshot(scan),
        )
        is NoteItem -> copy(presentation = presentation.snapshot(scan))
        is CheckItem -> copy(per = per?.let(::frozenList), presentation = presentation.snapshot(scan))
        is ReconcileItem -> copy(per = per?.let(::frozenList), presentation = presentation.snapshot(scan))
    }
}

internal fun CaseData.snapshot(scan: () -> Unit = {}): CaseData = copy(
    meta = frozenMap(meta.mapValues { (_, value) -> value.snapshot(scan) }),
    inputs = frozenMap(inputs.mapValues { (_, value) -> value.snapshot(scan) }),
    params = frozenMap(params.mapValues { (_, value) -> value.snapshot(scan) }),
    extensions = frozenMap(
        extensions.mapValues { (_, items) ->
            frozenList(
                items.map {
                    scan()
                    it.snapshot(scan)
                },
            )
        },
    ),
    formulaBindings = frozenMap(formulaBindings),
    functions = frozenList(functions),
    inputLocations = frozenMap(inputLocations),
    paramLocations = frozenMap(paramLocations),
    sources = frozenList(
        sources.map { binding ->
            binding.copy(options = frozenMap(binding.options.mapValues { (_, value) -> value.snapshot(scan) }))
        },
    ),
    inputOrigins = frozenMap(inputOrigins.mapValues { (_, origins) -> frozenMap(origins) }),
    inputCells = frozenMap(
        inputCells.mapValues { (_, cells) ->
            frozenList(
                cells.map {
                    scan()
                    it.copy(coord = frozenList(it.coord))
                },
            )
        },
    ),
    links = frozenList(links.map { it.snapshot() }),
    linkInputs = frozenMap(
        linkInputs.mapKeys { (address, _) -> address.snapshot() }
            .mapValues { (_, provenance) -> provenance.snapshot() },
    ),
)

internal fun InputAddress.snapshot(): InputAddress = copy(coord = frozenList(coord))
internal fun LinkProvenance.snapshot(): LinkProvenance = copy(from = from.snapshot())
internal fun CaseLink.snapshot(): CaseLink = copy(
    mappings = frozenList(mappings.map { it.copy(from = it.from.snapshot(), to = it.to.snapshot()) }),
)

internal fun Schema.snapshot(scan: () -> Unit = {}): Schema = copy(
    meta = meta.snapshot(scan),
    params = frozenList(params.map { it.snapshot(scan) }),
    inputs = frozenList(inputs.map { it.snapshot(scan) }),
    dimensions = frozenList(dimensions.map { it.snapshot(scan) }),
    functions = frozenList(functions),
    root = root.snapshot(scan) as SectionItem,
    sources = frozenList(sources),
)

internal fun ExplainTrace.snapshot(scan: () -> Unit = {}): ExplainTrace = copy(
    steps = frozenList(
        steps.map {
            scan()
            it.copy(value = it.value?.snapshot(scan))
        },
    ),
    branches = frozenList(
        branches.map {
            scan()
            it
        },
    ),
)

internal fun SchemaMeta.snapshot(scan: () -> Unit = {}): SchemaMeta = copy(
    attributes = frozenMap(
        attributes.mapValues { (_, value) ->
            value.snapshot(scan)
        },
    ),
)

internal fun InputDecl.snapshot(scan: () -> Unit = {}): InputDecl = copy(
    per = per?.let(::frozenList),
    default = default?.snapshot(scan),
    options = frozenMap(options),
    columns = frozenList(columns),
    presentation = presentation.snapshot(scan),
    references = frozenMap(references),
)

internal fun ParamDecl.snapshot(
    scan: () -> Unit = {
    },
): ParamDecl = copy(value = value.snapshot(scan), presentation = presentation.snapshot(scan))

internal fun DimensionDecl.snapshot(scan: () -> Unit = {}): DimensionDecl = copy(
    members = frozenList(
        members.map {
            scan()
            it
        },
    ),
    periods = when (val spec = periods) {
        is PeriodSpec.Generated -> spec
        is PeriodSpec.Listed -> spec.copy(
            entries = frozenList(
                spec.entries.map {
                    scan()
                    it
                },
            ),
        )
        null -> null
    },
)

internal fun Member.snapshot(
    scan: () -> Unit = {
    },
): Member = copy(record = frozenMap(record.mapValues { (_, value) -> value.snapshot(scan) }))

internal fun NodeTrace.snapshot(
    traceSnapshot: (ExplainTrace) -> ExplainTrace = { it.snapshot() },
    aggregateSnapshot: (RatioAggregateTrace) -> RatioAggregateTrace = { it.snapshot() },
    scan: () -> Unit = {},
): NodeTrace = when (this) {
    is NodeTrace.Input -> copy(link = link?.snapshot())
    is NodeTrace.Param, is NodeTrace.Inactive -> this
    is NodeTrace.Failed -> copy(explanation = explanation?.let(traceSnapshot))
    is NodeTrace.Computed -> copy(
        references = frozenList(
            references.map {
                scan()
                it.copy(
                    value = it.value.snapshot(scan),
                    coord = it.coord?.let { coord -> frozenList(coord) },
                    fixed = it.fixed?.let { fixed -> frozenMap(fixed) },
                )
            },
        ),
        raw = raw.snapshot(scan),
        explanation = explanation?.let(traceSnapshot),
    )
    is NodeTrace.Sum -> copy(
        parts = frozenList(
            parts.map {
                scan()
                it.copy(
                    aggregate = it.aggregate?.let(aggregateSnapshot),
                    reduction = when (val generic = it.reduction) {
                        is RatioAggregateTrace -> aggregateSnapshot(generic)
                        else -> generic?.snapshot()
                    },
                )
            },
        ),
    )
    is NodeTrace.Validation -> copy(
        explanation = explanation?.let(traceSnapshot),
        rightExplanation = rightExplanation?.let(traceSnapshot),
    )
    is NodeTrace.Choice -> copy(
        options = frozenList(
            options.map {
                scan()
                it.copy(
                    value = it.value.snapshot(scan),
                    explanation = it.explanation?.let(traceSnapshot),
                    conditionExplanation = it.conditionExplanation?.let(traceSnapshot),
                )
            },
        ),
        raw = raw.snapshot(scan),
    )
}

internal fun <T> Map<Coord, T>.snapshotCoords(scan: () -> Unit = {}, value: (T) -> T): Map<Coord, T> = frozenMap(
    entries.associate { (coord, item) ->
        scan()
        frozenList(coord) to
            value(item)
    },
)

internal fun RatioAggregateTrace.snapshot(): RatioAggregateTrace = copy(
    dimensions = frozenList(dimensions),
    fixed = frozenMap(fixed),
    members = frozenList(members.map { it.copy(coord = frozenList(it.coord)) }),
)

internal fun AggregateTrace.snapshot(): AggregateTrace = when (this) {
    is RatioAggregateTrace -> snapshot()
    is SumAggregateTrace -> copy(
        dimensions = frozenList(dimensions),
        fixed = frozenMap(fixed),
        members = frozenList(members.map { it.copy(coord = frozenList(it.coord), value = it.value.snapshot()) }),
    )
    is BoundaryAggregateTrace -> copy(
        dimensions = frozenList(dimensions),
        fixed = frozenMap(fixed),
        periodKeys = frozenList(periodKeys),
        selected = frozenList(selected.map { it.copy(coord = frozenList(it.coord), value = it.value.snapshot()) }),
        members = frozenList(members.map { it.copy(coord = frozenList(it.coord), value = it.value.snapshot()) }),
    )
}

internal fun AggregationResult.snapshot(): AggregationResult =
    copy(value = value?.snapshot(), trace = trace?.snapshot())

internal fun SchemaMap.snapshot(): SchemaMap = SchemaMap(
    schemaId = schemaId,
    title = title,
    panels = frozenList(
        panels.map { panel ->
            Panel(
                id = panel.id, title = panel.title, order = panel.order, role = panel.role,
                step = panel.step, dims = frozenList(panel.dims), parentId = panel.parentId,
                resultId = panel.resultId, fields = frozenList(panel.fields), nodes = frozenList(panel.nodes),
                imports = frozenList(panel.imports), exports = frozenList(panel.exports),
                entries = frozenList(panel.entries.map { it.copy(path = frozenList(it.path)) }),
                breadcrumb = frozenList(panel.breadcrumb),
            )
        },
    ),
    mainline = frozenList(mainline),
    generalInputs = frozenList(generalInputs),
    params = frozenList(params),
    flows = frozenList(flows),
)
