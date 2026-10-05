@file:OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)

import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.abi.AbiValidationExtension

plugins {
    kotlin("jvm") version "2.2.20" apply false
    id("com.diffplug.spotless") version "8.10.3"
    id("org.jetbrains.dokka") version "2.0.0" apply false
}

// Pin formatters so local and CI output stays identical. Normein and build outputs are excluded.
spotless {
    kotlin {
        target(
            "mantra-*/src/**/*.kt",
            "apps/*/src/**/*.kt",
            "build-support/**/*.kt",
            "benchmarks/src/**/*.kt",
            "conformance-adapter/src/**/*.kt",
        )
        ktlint("1.7.1")
    }
    kotlinGradle {
        target(
            "*.gradle.kts",
            "mantra-*/build.gradle.kts",
            "apps/*/build.gradle.kts",
            "benchmarks/build.gradle.kts",
            "conformance-adapter/build.gradle.kts",
        )
        ktlint("1.7.1")
    }
}

group = "com.xqiou.mantra"
val mantraReleaseVersion = providers.gradleProperty("mantraReleaseVersion").orNull
if (mantraReleaseVersion != null) {
    require(
        mantraReleaseVersion.length <= 64 &&
            Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)").matches(mantraReleaseVersion),
    ) { "mantraReleaseVersion must be an explicit stable major.minor.patch version" }
    require(gradle.includedBuilds.isEmpty()) {
        "Release staging must resolve the public kernel without source substitution"
    }
}
version = mantraReleaseVersion ?: "1.0.0-rc.1"
val mantraSignRelease = providers.gradleProperty("mantraSignRelease").map { it.toBooleanStrict() }.getOrElse(false)
require(!mantraSignRelease || mantraReleaseVersion != null) { "Release signing requires explicit mantraReleaseVersion" }
val mantraStagingDirectory = providers.gradleProperty("mantraStagingPath")
    .map { rootProject.file(it) }.getOrElse(rootProject.layout.buildDirectory.dir("staging").get().asFile)

repositories {
    mavenCentral()
}

val normeinVersion: String = java.util.Properties().apply {
    file("normein-build.lock").inputStream().use { load(it) }
}.getProperty("normeinVersion") ?: error("normein-build.lock must declare normeinVersion")

configure(subprojects.filter { it.path != ":apps" }) {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
        if (project.path.startsWith(":apps:")) {
            sourceSets.named("test") {
                kotlin.srcDir(rootProject.file("build-support/app-acceptance"))
            }
        }
    }

    extra["normeinVersion"] = normeinVersion

    dependencies {
        "testImplementation"(kotlin("test"))
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.0")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        workingDir = rootProject.projectDir
        inputs.files(
            rootProject.fileTree("apps") {
                include("**/*.mantra", "**/data/*.csv", "**/import-templates/*.json")
                exclude("**/build/**")
            },
        ).withPropertyName("applicationDocuments")
        inputs.property("updateGolden", System.getenv("MANTRA_UPDATE_GOLDEN") ?: "0")
        if (project.path.startsWith(":apps:")) {
            inputs.files(
                project.fileTree(project.projectDir) {
                    include("manifest.json", "**/*.mantra", "**/*.md", "**/*.csv", "**/*.json", "**/*.py")
                    exclude("build/**")
                },
            ).withPropertyName("applicationPackageResources")
            systemProperty("mantra.appDir", project.projectDir.absolutePath)
        }
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

val checkBoundaries by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks public API imports, domain identifiers and one-way application dependencies."
    commandLine("python3", "scripts/check-boundaries.py")
    doFirst {
        val libraryPaths = subprojects.filter { it.path.startsWith(":mantra-") }.map { it.path }.toSet()
        subprojects.forEach { consumer ->
            consumer.configurations.forEach { configuration ->
                configuration.dependencies.withType<ProjectDependency>().forEach { dependency ->
                    val target = dependency.path
                    check(!consumer.path.startsWith(":mantra-") || !target.startsWith(":apps")) {
                        "${consumer.path}:${configuration.name} must not depend on application $target"
                    }
                    check(!consumer.path.startsWith(":apps:") || target in libraryPaths) {
                        "${consumer.path}:${configuration.name} may depend only on library modules, found $target"
                    }
                    check(consumer.path != ":benchmarks" || target in libraryPaths) {
                        "${consumer.path}:${configuration.name} may depend only on library modules, found $target"
                    }
                }
            }
        }
    }
}

val checkFrontend by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks frontend formatting, ESLint rules and generated TypeScript contracts (run npm ci first)."
    workingDir = file("workbench-ui")
    commandLine("npm", "run", "check")
}

val checkSourceSize by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks that handwritten Kotlin files stay within 1200 nonblank lines."
    commandLine("python3", "scripts/check-source-size.py")
}

val checkDiagnostics by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks that diagnostic codes and their documented categories stay synchronized."
    commandLine("python3", "scripts/check-diagnostics.py")
}

val checkBuildGates by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs regression tests for the architecture and quality gates."
    commandLine("python3", "-m", "unittest", "discover", "-s", "scripts/tests")
}

val checkTestCounts by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks executed-test floors and failures in every module's JUnit reports."
    dependsOn(subprojects.filter { it.path != ":apps" }.map { it.tasks.named("test") })
    commandLine("python3", "scripts/check-test-counts.py")
}

tasks.named("check") {
    group = "verification"
    dependsOn(
        checkBoundaries,
        "spotlessCheck",
        checkFrontend,
        checkSourceSize,
        checkDiagnostics,
        checkBuildGates,
        checkTestCounts,
    )
    dependsOn(subprojects.filter { it.path != ":apps" }.map { it.tasks.named("check") })
}

subprojects.filter { it.path != ":apps" }.forEach { project ->
    project.tasks.named("check") { dependsOn(checkBoundaries) }
    project.tasks.named("test") { dependsOn(checkBoundaries) }
}

// Full public surface: model/read/layout/public external types are not hidden by api/view filters.
val abiLibraryPaths =
    setOf(":mantra-core", ":mantra-render", ":mantra-excel", ":mantra-workbench", ":mantra-server", ":mantra-packages")
configure(subprojects.filter { it.path in abiLibraryPaths }) {
    val libraryProject = this
    extensions.getByType<KotlinJvmProjectExtension>().extensions.configure<AbiValidationExtension> {
        enabled.set(true)
        legacyDump.referenceDumpDir.set(libraryProject.layout.projectDirectory.dir("api"))
    }
    // Explicit for Kotlin 2.2: merely enabling validation does not make check protect the ABI.
    tasks.named("check") { dependsOn("checkLegacyAbi") }

    apply(plugin = "maven-publish")
    apply(plugin = "org.jetbrains.dokka")
    plugins.withId("java-library") {
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
        }
        // Kotlin KDoc HTML, not an empty Java-only Javadoc archive.
        tasks.named<Jar>("javadocJar") {
            dependsOn("dokkaHtml")
            from(tasks.named("dokkaHtml").map { it.outputs.files })
        }
        dependencies { "api"(kotlin("stdlib")) }
        extensions.configure<PublishingExtension> {
            publications.register<MavenPublication>("mavenJava") {
                from(components["java"])
                artifactId = libraryProject.name
                pom {
                    name.set(libraryProject.name)
                    description.set("Mantra generic calculation, evidence and embedding library")
                    url.set("https://github.com/6234456/mantra")
                    licenses {
                        license {
                            name.set("Apache-2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("6234456")
                            name.set("6234456")
                            url.set("https://github.com/6234456")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/6234456/mantra.git")
                        developerConnection.set("scm:git:ssh://git@github.com/6234456/mantra.git")
                        url.set("https://github.com/6234456/mantra")
                    }
                }
            }
            repositories.maven {
                name = "LocalStaging"
                url = uri(mantraStagingDirectory)
            }
        }
        if (mantraSignRelease) {
            val keyId = providers.environmentVariable("MANTRA_SIGNING_KEY_ID").orNull
                ?: error("Explicit MANTRA_SIGNING_KEY_ID is required")
            val key = providers.environmentVariable("MANTRA_SIGNING_KEY").orNull
                ?: error("Explicit MANTRA_SIGNING_KEY is required")
            val password = providers.environmentVariable("MANTRA_SIGNING_PASSWORD").orNull
                ?: error("Explicit MANTRA_SIGNING_PASSWORD is required")
            require(Regex("(?:[A-Fa-f0-9]{8}|[A-Fa-f0-9]{16})").matches(keyId)) {
                "MANTRA_SIGNING_KEY_ID must be an explicit OpenPGP key ID"
            }
            require(key.startsWith("-----BEGIN PGP PRIVATE KEY BLOCK-----") && password.isNotEmpty()) {
                "An armored password-protected signing key is required"
            }
            apply(plugin = "signing")
            extensions.configure<SigningExtension> {
                // Gradle 9.4's PgpKeyId accepts the short ID; bundle verification checks the full fingerprint.
                useInMemoryPgpKeys(keyId.takeLast(8), key, password)
                isRequired = true
                sign(libraryProject.extensions.getByType<PublishingExtension>().publications["mavenJava"])
            }
        }
    }
}

val checkAbiCoverage by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks reviewed full-surface library ABI baselines, including public external types."
    dependsOn(abiLibraryPaths.map { "$it:checkLegacyAbi" })
    commandLine("python3", "scripts/check-abi-coverage.py")
}

val checkAbiCounterexample by tasks.registering(Exec::class) {
    group = "verification"
    description = "Proves that ABI checks reject a real binary break in unchanged Java/Kotlin consumers."
    commandLine("python3", "scripts/check-abi-counterexample.py", "--gradle", rootProject.file("gradlew").absolutePath)
}

tasks.named("check") { dependsOn(checkAbiCoverage) }

tasks.register("stageLibraries") {
    group = "publishing"
    description =
        "Creates local Maven artifacts with optional explicit release signing; no remote repository is configured."
    dependsOn(abiLibraryPaths.map { "$it:publishAllPublicationsToLocalStagingRepository" })
}

val verifyLocalStaging by tasks.registering(Exec::class) {
    group = "verification"
    description = "Inspects local Maven binary, sources, API HTML, compile scopes and library-only publication."
    dependsOn("stageLibraries")
    commandLine(
        "python3",
        "scripts/verify-local-staging.py",
        mantraStagingDirectory.absolutePath,
        rootProject.version.toString(),
    )
}

val checkCleanConsumer by tasks.registering(Exec::class) {
    group = "verification"
    description = "Executes staged Java/Kotlin POM consumers with a fresh Maven Central kernel dependency."
    dependsOn(verifyLocalStaging)
    val executable = checkNotNull(gradle.gradleHomeDir).resolve(
        if (System.getProperty("os.name").startsWith("Windows")) "bin/gradle.bat" else "bin/gradle",
    )
    commandLine(
        "python3",
        "scripts/check-clean-consumer.py",
        "--gradle",
        executable.absolutePath,
        "--version",
        rootProject.version.toString(),
        "--mantra-repository",
        mantraStagingDirectory.absolutePath,
    )
}

val checkConformance by tasks.registering(Exec::class) {
    group = "verification"
    description = "Executes the independent frozen DSL 1 corpus through a separate process adapter."
    dependsOn(":conformance-adapter:installDist")
    commandLine(
        "python3",
        "conformance/run.py",
        "--adapter",
        rootProject.file(
            "conformance-adapter/build/install/mantra-conformance-adapter/bin/mantra-conformance-adapter",
        ).absolutePath,
    )
}
tasks.named("check") { dependsOn(checkConformance) }
