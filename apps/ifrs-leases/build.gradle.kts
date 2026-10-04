// Demonstration application: documents and public-API acceptance tests, never a library artifact.
dependencies {
    testImplementation(project(":mantra-core"))
    testImplementation(project(":mantra-render"))
    testImplementation(project(":mantra-excel"))
    testImplementation(project(":mantra-workbench"))
    testImplementation("org.apache.poi:poi-ooxml:5.4.1")
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
