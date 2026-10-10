package com.xqiou.mantra.core.engine

import com.xqiou.normein.dsl.library.DslFunctionInvocationException
import com.xqiou.normein.dsl.type.DslType
import com.xqiou.normein.dsl.value.DslValue
import com.xqiou.normein.dsl.value.DslValueTypes
import com.xqiou.normein.dsl.value.DslValues
import java.math.BigDecimal
import java.math.BigInteger

/** Strict, domain-neutral selection over concrete ordered records; no coercion or rounding. */
internal object TableSelection {
    private data class Key(val namespace: String?, val name: String)

    fun sum(records: DslValue, criteria: DslValue, column: DslValue, work: LibraryWork): DslValue {
        val key = key(column)
        var total = BigDecimal.ZERO
        visit(records, criteria, work) { row ->
            val amount = row[key]?.number(work)
                ?: fail("DSL-MANTRA-TABLE-NUMBER", "A matched record must have a numeric :${key.name} value")
            work.numeric()
            total = total.add(amount)
        }
        return DslValues.decimal(total)
    }

    fun count(records: DslValue, criteria: DslValue, work: LibraryWork): DslValue {
        var count = BigInteger.ZERO
        visit(records, criteria, work) {
            work.numeric()
            count = count.add(BigInteger.ONE)
        }
        return DslValues.integer(count)
    }

    private fun visit(
        records: DslValue,
        criteria: DslValue,
        work: LibraryWork,
        selected: (Map<Key, DslValue>) -> Unit,
    ) {
        val wanted = record(criteria, work)
        for (value in work.scanned(wanted.values)) {
            if (!scalar(value)) {
                fail("DSL-MANTRA-TABLE-CRITERIA", "Criteria values must be nil, Boolean, text, keyword or numeric")
            }
        }
        val rows = when (records) {
            is DslValue.VectorValue -> records.values
            is DslValue.SequentialValue -> records.values
            else -> fail("DSL-MANTRA-TABLE-ROWS", "Records must be a concrete ordered vector or sequence")
        }
        for (value in work.scanned(rows)) {
            // Validate every row's entire map shape, including rows that fail an early criterion.
            val row = record(value, work, structuredFields = true)
            var matched = true
            for ((key, expected) in work.scanned(wanted.entries)) {
                val actual = row[key]
                if (actual == null || !equal(actual, expected, work)) matched = false
            }
            if (matched) selected(row)
        }
    }

    private fun record(value: DslValue, work: LibraryWork, structuredFields: Boolean = false): Map<Key, DslValue> {
        val record = value as? DslValue.MapValue
            ?: fail("DSL-MANTRA-TABLE-RECORD", "Records and criteria must be keyword-keyed maps")
        val entries = linkedMapOf<Key, DslValue>()
        // Normein's public structured importer retains text field names inside typed table rows.
        // Ordinary authored maps still require keyword keys; no untyped text-key coercion occurs.
        var typedFields: Boolean? = null
        for (entry in work.scanned(record.entriesInIterationOrder)) {
            work.temporary()
            val field = entry.key as? DslValue.TextValue
            if (field != null && structuredFields && typedFields == null) {
                val inferred = DslValueTypes.inferredType(record)
                typedFields = inferred is DslType.TypeRef || inferred is DslType.ObjectType
            }
            val key = if (typedFields == true && field != null) {
                Key(null, field.value)
            } else {
                key(entry.key)
            }
            if (entries.put(key, entry.value) != null) {
                fail("DSL-MANTRA-TABLE-KEY", "A record must not contain duplicate field names")
            }
        }
        return entries
    }

    private fun key(value: DslValue): Key {
        val key = value as? DslValue.KeywordValue
            ?: fail("DSL-MANTRA-TABLE-KEY", "Record keys and value columns must be keywords")
        return Key(key.namespace, key.name)
    }

    private fun scalar(value: DslValue): Boolean = value === DslValue.Nil ||
        value is DslValue.BooleanValue || value is DslValue.TextValue || value is DslValue.KeywordValue ||
        value is DslValue.IntegerValue || value is DslValue.LongValue || value is DslValue.DecimalValue

    private fun equal(actual: DslValue, expected: DslValue, work: LibraryWork): Boolean {
        val actualNumber = actual.number(work)
        val expectedNumber = expected.number(work)
        if (actualNumber != null && expectedNumber != null) {
            work.numeric()
            return actualNumber.compareTo(expectedNumber) == 0
        }
        return when {
            actual === DslValue.Nil && expected === DslValue.Nil -> true
            actual is DslValue.BooleanValue && expected is DslValue.BooleanValue -> actual.value == expected.value
            actual is DslValue.TextValue && expected is DslValue.TextValue -> actual.value == expected.value
            actual is DslValue.KeywordValue && expected is DslValue.KeywordValue ->
                actual.namespace == expected.namespace && actual.name == expected.name
            else -> false
        }
    }

    private fun DslValue.number(work: LibraryWork): BigDecimal? = when (this) {
        is DslValue.DecimalValue -> value
        is DslValue.IntegerValue -> {
            work.conversion()
            BigDecimal(value)
        }
        is DslValue.LongValue -> {
            work.conversion()
            BigDecimal.valueOf(value)
        }
        else -> null
    }

    private fun fail(code: String, message: String): Nothing = throw DslFunctionInvocationException(code, message)
}
