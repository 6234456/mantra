plugins {
    `java-library`
}

dependencies {
    api("com.xqiou:normein-dsl:${project.extra["normeinVersion"]}")
}

tasks.named<Test>("test") {
    inputs.files(rootProject.fileTree("docs/templates") { include("**/*.mantra", "README.md") })
        .withPropertyName("syntaxPatternTemplates")
    inputs.files(rootProject.fileTree("docs/patterns") { include("**/*.mantra", "README.md") })
        .withPropertyName("sharedCalculationPatterns")
}

val generatedVersionSource = layout.buildDirectory.dir("generated/sources/runtime-version")
val generateRuntimeVersion by tasks.registering {
    val engineVersion = project.version.toString()
    val kernelVersion = project.extra["normeinVersion"].toString()
    inputs.property("mantraVersion", engineVersion)
    inputs.property("normeinVersion", kernelVersion)
    outputs.dir(generatedVersionSource)
    doLast {
        val file = generatedVersionSource.get().file("com/xqiou/mantra/core/api/BuildVersions.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package com.xqiou.mantra.core.api
            internal object BuildVersions {
                val MANTRA = "$engineVersion"
                val NORMEIN = "$kernelVersion"
            }
            """.trimIndent() + "\n",
        )
    }
}
kotlin.sourceSets.named("main") { kotlin.srcDir(generatedVersionSource) }
tasks.named("compileKotlin") { dependsOn(generateRuntimeVersion) }
// Publication and API documentation read the same generated source as compilation.
tasks.matching { it.name == "sourcesJar" || it.name == "dokkaHtml" }.configureEach {
    dependsOn(generateRuntimeVersion)
}
