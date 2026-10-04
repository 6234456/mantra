package com.xqiou.mantra.core.engine

import com.xqiou.mantra.core.model.Value
import com.xqiou.mantra.core.model.ValueType
import com.xqiou.normein.dsl.binding.DslFingerprintAlgorithm
import com.xqiou.normein.dsl.binding.DslFingerprintAuthority
import com.xqiou.normein.dsl.environment.DslEnvironment
import com.xqiou.normein.dsl.environment.DslEnvironmentBuildResult
import com.xqiou.normein.dsl.environment.DslEnvironmentBuilder
import com.xqiou.normein.dsl.identity.DslInputIdentity
import com.xqiou.normein.dsl.identity.DslInputLocatorResult
import com.xqiou.normein.dsl.identity.DslInputLocators
import com.xqiou.normein.dsl.language.DslNameCategory
import com.xqiou.normein.dsl.language.DslNameResult
import com.xqiou.normein.dsl.language.DslNames
import com.xqiou.normein.dsl.language.DslNormalizedName
import com.xqiou.normein.dsl.library.DslArtifactIdentity
import com.xqiou.normein.dsl.stdlib.NormeinStandardLibraries
import com.xqiou.normein.dsl.type.DslType
import com.xqiou.normein.dsl.type.DslTypeId
import com.xqiou.normein.dsl.type.DslTypes
import com.xqiou.normein.dsl.value.DslValue
import com.xqiou.normein.dsl.value.DslValues
import java.math.BigDecimal
import java.security.MessageDigest

/** The single Normein environment used by Mantra: standard libraries + [MantraLibrary]. */
internal object MantraKernel {
    const val ENVIRONMENT_ID: String = "mantra.calc"
    const val ENVIRONMENT_VERSION: String = "1"

    val environment: DslEnvironment by lazy {
        val built = DslEnvironmentBuilder.create(
            ENVIRONMENT_ID,
            ENVIRONMENT_VERSION,
            MantraLibrary.extendLanguage(NormeinStandardLibraries.language),
            NormeinStandardLibraries.pureExecutionProfile,
        )
            .addAll(NormeinStandardLibraries.descriptors)
            .add(MantraLibrary.descriptor())
            .build()
        when (built) {
            is DslEnvironmentBuildResult.Success -> built.environment
            is DslEnvironmentBuildResult.Failure -> error("Mantra kernel environment is invalid: ${built.diagnostics}")
        }
    }

    /** Special forms and literals; schema identifiers can never use these names. */
    val reservedNames: Set<String> by lazy {
        environment.registry.specialFormsByName.keys +
            setOf("nil", "true", "false", "all", "fn", "let", "defn", "recur", "loop", "def", "mantra", "periods")
    }

    /**
     * Function names of the environment. A schema identifier may equal one of them (e.g. `amount`);
     * formulas then reference the node with its qualified name `mantra/amount`.
     */
    val callableNames: Set<String> by lazy { environment.registry.functionsByName.keys }

    /** Namespace for fully qualified references to schema nodes: `mantra/<id>`. */
    const val NAMESPACE: String = "mantra/"

    /** Root name used for the qualified reference; same length as `mantra/<id>` so spans stay valid. */
    fun qualifiedRoot(id: String): String = "mantra_$id"

    val kernelArtifact: DslArtifactIdentity get() = MantraLibrary.artifact

    fun inputIdentity(caseId: String, fingerprintSource: String): DslInputIdentity {
        val safeId = caseId.replace(Regex("[^A-Za-z0-9._-]"), "-").take(64).ifEmpty { "case" }
        val locator = when (val result = DslInputLocators.create("mantra", "case", safeId)) {
            is DslInputLocatorResult.Success -> result.locator
            is DslInputLocatorResult.Failure -> (
                DslInputLocators.create(
                    "mantra",
                    "case",
                    "case",
                ) as DslInputLocatorResult.Success
                ).locator
        }
        return DslInputIdentity(
            locator = locator,
            snapshotVersion = "1",
            fingerprintAuthority = DslFingerprintAuthority.KERNEL,
            fingerprintAlgorithm = DslFingerprintAlgorithm.NORMEIN_CANONICAL_SHA_256_V1,
            snapshotFingerprint = sha256(fingerprintSource),
        )
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
}

internal object Names {
    fun field(raw: String): DslNormalizedName? =
        (DslNames.normalize(raw, DslNameCategory.FIELD) as? DslNameResult.Valid)?.name

    fun rootIsValid(raw: String): Boolean = DslNames.normalize(raw, DslNameCategory.ROOT) is DslNameResult.Valid

    fun typeId(namespace: String, name: String): DslTypeId = DslTypeId(component(namespace), component(name))

    private fun component(raw: String): DslNormalizedName {
        val cleaned = raw.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "x" }
        return (DslNames.normalize(cleaned, DslNameCategory.TYPE_COMPONENT) as? DslNameResult.Valid)?.name
            ?: error("Invalid type component `$raw`")
    }
}

internal object Types {
    val number: DslType = DslTypes.union(DslType.Integer, DslType.Long, DslType.Decimal)

    fun element(type: ValueType): DslType = when (type) {
        ValueType.DECIMAL -> DslType.Decimal
        ValueType.INTEGER -> DslType.Integer
        ValueType.BOOLEAN -> DslType.Boolean
        ValueType.KEYWORD -> DslType.Keyword
        ValueType.TEXT -> DslType.Text
        ValueType.DATE -> DslType.Date
        ValueType.TABLE, ValueType.ANY -> DslType.Any
    }

    /**
     * Expected result type used when compiling a formula of the given element type. Numeric results
     * may be nil (e.g. `decimal/divide` by zero); the evaluator records nil and uses 0.
     */
    fun expected(type: ValueType): DslType = when (type) {
        ValueType.DECIMAL -> DslTypes.nullable(number)
        ValueType.INTEGER -> DslTypes.nullable(DslTypes.union(DslType.Integer, DslType.Long))
        else -> DslTypes.nullable(element(type))
    }

    fun infer(value: Value): DslType = when (value) {
        Value.Nil -> DslType.Null
        is Value.Num -> DslType.Decimal
        is Value.Bool -> DslType.Boolean
        is Value.Kw -> DslType.Keyword
        is Value.Text -> DslType.Text
        is Value.Date -> DslType.Date
        is Value.Vec -> DslTypes.vector(
            if (value.items.isEmpty()) DslType.Any else DslTypes.union(value.items.map(::infer)),
        )
        is Value.MapV -> if (value.entries.isEmpty()) {
            DslTypes.map(DslType.Any, DslType.Any)
        } else {
            DslTypes.map(
                DslTypes.union(value.entries.keys.map(::infer)),
                DslTypes.union(value.entries.values.map(::infer)),
            )
        }
    }
}

/** Conversion between Mantra [Value]s and Normein runtime values. */
internal object Values {
    fun toDsl(value: Value): DslValue = toDslControlled(value) {}

    fun toDslControlled(value: Value, scan: () -> Unit): DslValue {
        scan()
        return when (value) {
            Value.Nil -> DslValue.Nil
            is Value.Num -> DslValues.decimal(value.value)
            is Value.Bool -> DslValues.boolean(value.value)
            is Value.Kw -> keyword(value.name)
            is Value.Text -> DslValues.text(value.value)
            is Value.Date -> DslValues.date(value.value)
            is Value.Vec -> DslValues.vector(value.items.map { toDslControlled(it, scan) })
            is Value.MapV -> DslValues.map(
                value.entries.map { (k, v) ->
                    toDslControlled(k, scan) to
                        toDslControlled(v, scan)
                },
            )
        }
    }

    fun toIntegerDsl(value: Value): DslValue = when (value) {
        is Value.Num -> DslValues.integer(value.value.toBigIntegerExact())
        else -> toDsl(value)
    }

    fun keyword(name: String): DslValue.KeywordValue {
        val slash = name.indexOf('/')
        return if (slash >
            0
        ) {
            DslValues.keyword(name.substring(0, slash), name.substring(slash + 1))
        } else {
            DslValues.keyword(null, name)
        }
    }

    fun fromDsl(value: DslValue): Value = fromDslControlled(value) {}

    fun fromDslControlled(value: DslValue, scan: () -> Unit): Value {
        scan()
        return when (value) {
            DslValue.Nil -> Value.Nil
            is DslValue.DecimalValue -> Value.Num(value.value)
            is DslValue.IntegerValue -> Value.Num(BigDecimal(value.value))
            is DslValue.LongValue -> Value.Num(BigDecimal.valueOf(value.value))
            is DslValue.BooleanValue -> Value.Bool(value.value)
            is DslValue.TextValue -> Value.Text(value.value)
            is DslValue.KeywordValue -> Value.Kw((value.namespace?.let { "$it/" } ?: "") + value.name)
            is DslValue.DateValue -> Value.Date(value.value)
            is DslValue.VectorValue -> Value.Vec(value.values.map { fromDslControlled(it, scan) })
            is DslValue.SequentialValue -> Value.Vec(value.values.map { fromDslControlled(it, scan) })
            is DslValue.SetValue -> Value.Vec(value.valuesInIterationOrder.map { fromDslControlled(it, scan) })
            is DslValue.MapValue -> Value.MapV(
                LinkedHashMap<Value, Value>().apply {
                    value.entriesInIterationOrder.forEach {
                        put(fromDslControlled(it.key, scan), fromDslControlled(it.value, scan))
                    }
                },
            )
            is DslValue.CharacterValue -> Value.Text(value.value.toString())
            is DslValue.EnumValue -> Value.Kw(value.symbol)
            else -> Value.Text(value.toString())
        }
    }
}
