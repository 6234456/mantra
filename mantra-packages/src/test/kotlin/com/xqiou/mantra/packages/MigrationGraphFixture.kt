package com.xqiou.mantra.packages

import com.xqiou.mantra.core.Mantra
import com.xqiou.mantra.core.api.CanonicalCaseKey
import com.xqiou.mantra.core.api.CaseGraphRunner
import com.xqiou.mantra.core.api.CaseLoadControl
import com.xqiou.mantra.core.api.CasePackageResolver
import com.xqiou.mantra.core.api.CaseReference
import com.xqiou.mantra.core.api.CaseRunRequest
import com.xqiou.mantra.core.api.CaseRunResult
import com.xqiou.mantra.core.api.ParticipatingSource
import com.xqiou.mantra.core.api.PreparedCasePackage
import com.xqiou.mantra.core.api.SourceRole
import com.xqiou.mantra.core.model.SchemaIdentity
import com.xqiou.mantra.core.read.SourceResolver
import com.xqiou.mantra.core.read.SourceText
import java.nio.file.Files
import java.nio.file.Path

/** Real file capture -> public graph runner -> source-owned exact binding; no fake calculation. */
internal class MigrationGraphFixture(val root: Path, technical: Boolean = false) : MigrationRuntime {
    val old = PackageSchemaBinding(SchemaIdentity("migration/root", "1.0.0"), SchemaVersionMode.SEMVER)
    val next = PackageSchemaBinding(SchemaIdentity("migration/root", "2.0.0"), SchemaVersionMode.SEMVER)
    val schemas = linkedMapOf(
        "migration/root@1.0.0" to """(schema migration/root {:version "1.0.0"}
            (input transferred :decimal) (input base-value :decimal) (input enabled :boolean)
            (line answer "Answer" (+ transferred base-value)))""",
        "migration/root@2.0.0" to """(schema migration/root {:version "2.0.0"}
            (input transferred :decimal) (input base-value :decimal) (input enabled :boolean)
            (line answer "Answer" ${if (technical) "(/ transferred 0)" else "(* (+ transferred base-value) 2)"})
            (check ceiling "Ceiling" (<= answer 5)))""",
        "migration/source@1.0.0" to """(schema migration/source {:version "1.0.0"}
            (input original :decimal) (line exported "Exported" (+ original 0))
            (check source-check "Source finding" (>= original 0)))""",
    )
    val target get() = MigrationTarget(
        next,
        PackageIdentity("fictional.migration", SemanticVersion.parse("2.0.0")),
        digest(schemas.getValue("migration/root@2.0.0").toByteArray()),
        null,
    )
    val casePath = "cases/root.mantra"

    init {
        Files.createDirectories(root.resolve("cases"))
        Files.writeString(
            root.resolve(casePath),
            """
            (case root {:schema "migration/root" :schema-version "1.0.0" :title "Keep my title"}
              ;; Original zero/false facts must survive migration.
              (inputs {:base-value 0 :enabled false})
              (links {:path "source.mantra" :schema "migration/source" :schema-version "1.0.0"
                      :mappings [{:from {:node exported :coord []} :to {:input transferred :coord []}}]}))
            """.trimIndent(),
        )
        source(10)
    }

    fun source(amount: Int) {
        Files.writeString(
            root.resolve("cases/source.mantra"),
            """(case source {:schema "migration/source" :schema-version "1.0.0"} (inputs {:original $amount}))""",
        )
    }

    override fun current(casePath: String): MigrationState {
        val source = Files.readString(root.resolve(casePath))
        val case = Mantra.loadCase(SourceText(casePath, source))
        val binding =
            PackageSchemaBinding(
                SchemaIdentity(checkNotNull(case.schemaId), case.schemaVersion),
                SchemaVersionMode.SEMVER,
            )
        val run = run(casePath, null)
        return MigrationState(
            binding,
            checkNotNull(run.cases[run.root]).revision,
            digest(source.toByteArray()),
            run.result,
            findings(run),
        )
    }

    override fun evaluate(casePath: String, candidate: SourceText, target: MigrationTarget): MigrationEvaluation {
        val run = run(casePath, candidate)
        // The adapter reports actual target bytes, so package changes cannot be hidden by the plan.
        return MigrationEvaluation(
            this.target,
            run.cases[run.root]?.revision ?: "technical-failure",
            run.result,
            findings(run),
        )
    }

    @Synchronized
    override fun commitIfCurrent(casePath: String, graphRevision: String, sourceSha256: String, commit: () -> Unit) {
        val current = current(casePath)
        if (current.graphRevision != graphRevision ||
            current.sourceSha256 != sourceSha256
        ) {
            fail("MANTRA-MIGRATION-STALE", "Live linked graph changed")
        }
        commit()
    }

    private fun findings(run: CaseRunResult) = run.diagnostics.map { item ->
        item.finding.copy(
            caseKey = item.case?.value ?: item.finding.caseKey,
            caseRevision =
            item.revision ?: item.finding.caseRevision,
        )
    }

    private fun run(rootPath: String, caseOverride: SourceText?): CaseRunResult {
        val charged = mutableSetOf<String>() // New resolver/charge ownership for each real graph epoch.
        val resolver = object : CasePackageResolver {
            override fun identify(reference: CaseReference, control: CaseLoadControl): CanonicalCaseKey {
                control.checkpoint()
                return CanonicalCaseKey(relativePath(reference.path, reference.fromCase?.value))
            }
            override fun load(key: CanonicalCaseKey, control: CaseLoadControl): PreparedCasePackage {
                control.checkpoint()
                val text = if (key.value == rootPath &&
                    caseOverride != null
                ) {
                    caseOverride.text
                } else {
                    Files.readString(root.resolve(logicalPath(key.value)))
                }
                val case = Mantra.loadCase(SourceText(key.value, text))
                val identity = "${case.schemaId}@${case.schemaVersion}"
                val schemaText = schemas.getValue(identity)
                val schema = Mantra.loadSchema(
                    SourceText("$identity.mantra", schemaText),
                    SourceResolver { _, _ ->
                        null
                    },
                )
                val sources = listOf(
                    ParticipatingSource(
                        key.value,
                        SourceRole.CASE,
                        digest(text.toByteArray()),
                        text.toByteArray().size.toLong(),
                    ),
                    ParticipatingSource(
                        "$identity.mantra",
                        SourceRole.SCHEMA,
                        digest(schemaText.toByteArray()),
                        schemaText.toByteArray().size.toLong(),
                    ),
                )
                sources.forEach { if (charged.add(it.identity)) control.chargeParticipatingBytes(it.byteLength) }
                return PreparedCasePackage(
                    key,
                    case.id,
                    schema.identity,
                    schema,
                    case,
                    emptyList(),
                    sources,
                    digest(sources.joinToString("\u0000") { "${it.identity}:${it.sha256}" }.toByteArray()),
                )
            }
        }
        return CaseGraphRunner(resolver).use { it.run(CaseRunRequest(CaseReference(rootPath))) }
    }
}
