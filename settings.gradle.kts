rootProject.name = "mantra"

// Library: generic engine capabilities only (no domain business logic).
include(":mantra-core")
include(":mantra-packages")
include(":mantra-render")
include(":mantra-excel")
include(":mantra-cli")
include(":mantra-workbench")
include(":mantra-server")
include(":mantra-lsp")
include(":benchmarks")
include(":conformance-adapter")

// Complete domain demonstrations. Each owns its documents and acceptance tests and depends only
// on library modules; applications are never published as Maven library artifacts.
include(
    ":apps:de-est",
    ":apps:ifrs-impairment",
    ":apps:cost-accounting",
    ":apps:ifrs-income-taxes",
    ":apps:fixed-assets",
    ":apps:ifrs-leases",
    ":apps:de-gewst",
    ":apps:circular-calculation",
    ":apps:energy-budget",
    ":apps:project-portfolio",
)

// ── Normein DSL kernel (Maven Central, optional pinned composite build) ──────
//
// Mantra normally resolves the unmodified normein-dsl artifact from Maven Central. A source
// composite is enabled only by an explicit checkout path; an existing .deps/normein is ignored.
// A selected checkout must be clean and at the exact commit recorded in normein-build.lock.
// Resolution order for an explicitly selected checkout location:
//   1. -PnormeinBuildPath=<dir>
//   2. NORMEIN_BUILD_PATH=<dir>
//
// -PnormeinCandidate=true skips the commit and cleanliness checks so that an unreleased kernel can
// be assessed against Mantra's tests (RFC 0001, acceptance contracts). It requires an explicit
// source path. Never release from such a build.

private val normeinLockFileName = "normein-build.lock"

private fun readNormeinLock(lockFile: java.io.File): Map<String, String> {
    require(lockFile.isFile) { "Normein lock manifest is missing: ${lockFile.absolutePath}" }
    val entries = linkedMapOf<String, String>()
    lockFile.readLines().forEachIndexed { index, rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
        val separator = line.indexOf('=')
        require(separator > 0) { "Invalid Normein lock entry at ${lockFile.name}:${index + 1}; expected key=value" }
        val key = line.substring(0, separator).trim()
        require(entries.put(key, line.substring(separator + 1).trim()) == null) {
            "Normein lock manifest contains duplicate key '$key'"
        }
    }
    return entries
}

private fun runNormeinGit(checkout: java.io.File, vararg arguments: String): String {
    val process = ProcessBuilder(listOf("git", "-C", checkout.absolutePath) + arguments.toList())
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText().trim() }
    check(process.waitFor() == 0) {
        "Unable to verify the Normein checkout at ${checkout.absolutePath}: git ${arguments.joinToString(" ")}: $output"
    }
    return output
}

val normeinBuildPath =
    providers.gradleProperty("normeinBuildPath").orNull
        ?: System.getenv("NORMEIN_BUILD_PATH")
val normeinCandidate = providers.gradleProperty("normeinCandidate").orNull?.toBoolean() == true
require(!normeinCandidate || normeinBuildPath != null) {
    "normeinCandidate requires an explicit source checkout via -PnormeinBuildPath or NORMEIN_BUILD_PATH."
}
if (normeinBuildPath != null) {
    require(normeinBuildPath.isNotBlank()) { "The explicitly selected Normein source path must not be blank." }
    val normeinLock = readNormeinLock(file(normeinLockFileName))
    val expectedNormeinCommit = normeinLock["normeinCommit"] ?: error("normein-build.lock must define normeinCommit")
    val normeinBuild = file(normeinBuildPath)
    require(normeinBuild.isDirectory) {
        "Normein source checkout not found at ${normeinBuild.absolutePath}. " +
            "Select a clean checkout of commit $expectedNormeinCommit or omit the source path to use Maven Central."
    }
    if (normeinCandidate) {
        logger.warn(
            "Mantra: using an UNPINNED Normein candidate at ${normeinBuild.absolutePath}; " +
                "$normeinLockFileName ($expectedNormeinCommit) is not enforced.",
        )
    } else {
        val actualNormeinCommit = runNormeinGit(normeinBuild, "rev-parse", "--verify", "HEAD^{commit}")
        check(actualNormeinCommit == expectedNormeinCommit) {
            "Normein checkout ${normeinBuild.absolutePath} is at $actualNormeinCommit but $normeinLockFileName " +
                "requires $expectedNormeinCommit."
        }
        val normeinDirtyFiles = runNormeinGit(normeinBuild, "status", "--porcelain", "--untracked-files=no")
        check(normeinDirtyFiles.isEmpty()) {
            "Normein checkout ${normeinBuild.absolutePath} has local modifications:\n$normeinDirtyFiles"
        }
    }

    includeBuild(normeinBuild) {
        dependencySubstitution {
            substitute(module("com.xqiou:normein-dsl")).using(project(":normein-dsl"))
        }
    }
}
