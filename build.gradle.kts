import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.2.20" apply false
    id("com.diffplug.spotless") version "8.10.3"
}

// Pin formatters so local and CI output stays identical. Normein and build outputs are excluded.
spotless {
    kotlin {
        target("mantra-*/src/**/*.kt", "apps/*/src/**/*.kt", "build-support/**/*.kt", "benchmarks/src/**/*.kt")
        ktlint("1.7.1")
    }
    kotlinGradle {
        target("*.gradle.kts", "mantra-*/build.gradle.kts", "apps/*/build.gradle.kts", "benchmarks/build.gradle.kts")
        ktlint("1.7.1")
    }
}

group = "com.xqiou.mantra"
version = "0.2.0-SNAPSHOT"

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
