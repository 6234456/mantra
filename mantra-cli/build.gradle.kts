plugins {
    application
}

dependencies {
    implementation(project(":mantra-core"))
    implementation(project(":mantra-render"))
    implementation(project(":mantra-excel"))
    implementation(project(":mantra-workbench"))
    implementation(project(":mantra-server"))
    implementation(project(":mantra-lsp"))
}

application {
    mainClass.set("com.xqiou.mantra.cli.MainKt")
    applicationName = "mantra"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

// Execute the exact downloadable tutorial source against public library entry points.
val tutorial by sourceSets.creating
kotlin.sourceSets.named(tutorial.name) {
    kotlin.srcDir(rootProject.file("docs/site/examples"))
}
configurations[tutorial.implementationConfigurationName].extendsFrom(configurations.implementation.get())
tasks.register<JavaExec>("verifyDocumentationExamples") {
    group = "verification"
    dependsOn(tutorial.classesTaskName)
    classpath = tutorial.runtimeClasspath
    mainClass.set("InvoiceEmbeddingKt")
    workingDir = rootProject.projectDir
}
