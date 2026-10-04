plugins {
    `java-library`
}

dependencies {
    api("com.xqiou:normein-dsl:${project.extra["normeinVersion"]}")
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
            "package com.xqiou.mantra.core.api\ninternal object BuildVersions { const val MANTRA = \"$engineVersion\"; const val NORMEIN = \"$kernelVersion\" }\n",
        )
    }
}
kotlin.sourceSets.named("main") { kotlin.srcDir(generatedVersionSource) }
tasks.named("compileKotlin") { dependsOn(generateRuntimeVersion) }
// Publication and API documentation read the same generated source as compilation.
tasks.matching { it.name == "sourcesJar" || it.name == "dokkaHtml" }.configureEach {
    dependsOn(generateRuntimeVersion)
}
