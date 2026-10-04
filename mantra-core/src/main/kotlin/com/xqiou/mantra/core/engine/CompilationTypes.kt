package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.model.Value
import com.xqiou.normein.dsl.type.DslType
import com.xqiou.normein.dsl.type.DslTypes

/** Mirrors checked parameter-root inference, charging each actual collection/type visit. */
internal object CompilationTypes {
    fun infer(value: Value, scan: () -> Unit): DslType {
        scan()
        return when (value) {
            Value.Nil -> DslType.Null
            is Value.Num -> DslType.Decimal
            is Value.Bool -> DslType.Boolean
            is Value.Kw -> DslType.Keyword
            is Value.Text -> DslType.Text
            is Value.Date -> DslType.Date
            is Value.Vec -> DslTypes.vector(
                if (value.items.isEmpty()) DslType.Any else DslTypes.union(value.items.map { infer(it, scan) }),
            )
            is Value.MapV -> if (value.entries.isEmpty()) {
                DslTypes.map(DslType.Any, DslType.Any)
            } else {
                DslTypes.map(
                    DslTypes.union(value.entries.keys.map { infer(it, scan) }),
                    DslTypes.union(value.entries.values.map { infer(it, scan) }),
                )
            }
        }
    }
}
