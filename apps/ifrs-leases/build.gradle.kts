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

dependencies {
    implementation(project(":mantra-core"))
    implementation(project(":mantra-packages"))
}

tasks.register<JavaExec>("leaseBatchDemo") {
    group = "application"
    description = "Runs the explicitly configured typed lease stream and independent value checks."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.xqiou.mantra.apps.leases.LeaseBatchDemo")
    maxHeapSize = "512m"
    workingDir = rootProject.projectDir
    // Supply options explicitly via the normal JavaExec --args mechanism; no unmeasured hard budget.
}
