// Demonstration application: documents and public-API acceptance tests, never a library artifact.
dependencies {
    testImplementation(project(":mantra-core"))
    testImplementation(project(":mantra-render"))
    testImplementation(project(":mantra-excel"))
    testImplementation(project(":mantra-workbench"))
    testImplementation("org.apache.poi:poi-ooxml:5.5.1")
}

tasks.named<Test>("test") {
    inputs.files(
        fileTree(projectDir) {
            include("**/*.mantra")
            exclude("build/**")
        },
    )
        .withPropertyName("mantraDocuments")
    outputs.dir(layout.buildDirectory.dir("out")).withPropertyName("renderedPapers")
}

// Application example only; no publication of demonstration apps.
dependencies {
    implementation(project(":mantra-core"))
    implementation(project(":mantra-packages"))
    implementation(project(":mantra-workbench"))
}
tasks.register<JavaExec>("estMigrationDemo") {
    group = "application"
    description = "Previews or explicitly applies an independently checked historical case migration."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.xqiou.mantra.apps.deest.EStMigrationDemo")
}
