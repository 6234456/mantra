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

val checkDeferredTaxReference by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies locked independent deferred-tax Decimal sources and expected values."
    workingDir(projectDir)
    commandLine("python3", "verify_roll_forward.py", "--check")
    inputs.file("verify_roll_forward.py")
    inputs.dir("independent/roll-forward")
}

tasks.named("check") {
    dependsOn(checkDeferredTaxReference)
}
