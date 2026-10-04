package com.xqiou.mantra.packages

import com.xqiou.mantra.core.model.SchemaIdentity
import java.math.BigInteger

/** Full spelling is identity; ordering ignores build metadata, as required by SemVer 2.0.0. */
class SemanticVersion private constructor(
    val text: String,
    private val major: BigInteger,
    private val minor: BigInteger,
    private val patch: BigInteger,
    private val prerelease: List<String>,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        listOf(major to other.major, minor to other.minor, patch to other.patch).forEach { (a, b) ->
            a.compareTo(b).takeIf { it != 0 }?.let { return it }
        }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return when {
                prerelease.isEmpty() && other.prerelease.isEmpty() -> 0
                prerelease.isEmpty() -> 1
                else -> -1
            }
        }
        prerelease.zip(other.prerelease).forEach { (a, b) ->
            val an = a.all(Char::isDigit)
            val bn = b.all(Char::isDigit)
            val order = when {
                an && bn -> a.toBigInteger().compareTo(b.toBigInteger())
                an -> -1
                bn -> 1
                else -> a.compareTo(b)
            }
            if (order != 0) return order
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    override fun equals(other: Any?): Boolean = other is SemanticVersion && text == other.text
    override fun hashCode(): Int = text.hashCode()
    override fun toString(): String = text

    companion object {
        private val syntax =
            Regex(
                "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?",
            )

        fun parse(text: String): SemanticVersion {
            val match = syntax.matchEntire(text) ?: fail("MANTRA-PACKAGE-VERSION", "Not strict SemVer: $text")
            val pre = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.') ?: emptyList()
            if (pre.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) {
                fail("MANTRA-PACKAGE-VERSION", "Numeric prerelease identifiers cannot have leading zeroes: $text")
            }
            return SemanticVersion(
                text,
                match.groupValues[1].toBigInteger(),
                match.groupValues[2].toBigInteger(),
                match.groupValues[3].toBigInteger(),
                pre,
            )
        }
    }
}

/** Only whitespace-separated comparator intersections. No latest, OR, wildcard, caret or tilde. */
class VersionRange private constructor(val text: String, private val tests: List<Pair<String, SemanticVersion>>) {
    fun contains(version: SemanticVersion): Boolean = tests.all { (operator, boundary) ->
        val comparison = version.compareTo(boundary)
        when (operator) {
            "=" -> comparison == 0
            ">" -> comparison > 0
            ">=" -> comparison >= 0
            "<" -> comparison < 0
            "<=" -> comparison <= 0
            else -> error("Validated comparator")
        }
    }

    companion object {
        fun parse(text: String): VersionRange {
            if (text.isBlank() ||
                text != text.trim()
            ) {
                fail("MANTRA-PACKAGE-RANGE", "An explicit comparator intersection is required")
            }
            val tests = text.split(Regex("\\s+")).map { token ->
                val match = Regex("(>=|<=|>|<|=)(.+)").matchEntire(token)
                    ?: fail("MANTRA-PACKAGE-RANGE", "Unsupported comparator: $token")
                match.groupValues[1] to SemanticVersion.parse(match.groupValues[2])
            }
            // A range that no version can satisfy is a configuration error, not an empty candidate set.
            val lower = tests.filter { it.first in setOf(">", ">=", "=") }.maxWithOrNull(compareBy { it.second })
            val upper = tests.filter { it.first in setOf("<", "<=", "=") }.minWithOrNull(compareBy { it.second })
            if (lower != null && upper != null) {
                val order = lower.second.compareTo(upper.second)
                if (order > 0 ||
                    (
                        order == 0 && tests.any {
                            it.first in setOf(">", "<") && it.second.compareTo(lower.second) == 0
                        }
                        )
                ) {
                    fail("MANTRA-PACKAGE-RANGE", "Comparator intersection is empty: $text")
                }
            }
            return VersionRange(text, tests)
        }
    }
}

enum class SchemaVersionMode { SEMVER, LEGACY_EXACT }

/** Historic opaque versions (including null) remain exact; they never enter a range comparison. */
data class PackageSchemaBinding(val identity: SchemaIdentity, val mode: SchemaVersionMode) {
    init {
        if (mode == SchemaVersionMode.SEMVER) {
            SemanticVersion.parse(
                identity.version ?: fail("MANTRA-PACKAGE-VERSION", "A SemVer schema requires a version"),
            )
        }
    }
}
