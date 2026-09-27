// Test-only acceptance module. Domain schemas live next to this file (de-est-2025/, ifrs-*/) and are
// exercised through the public engine API exactly like an external application would use it.
dependencies {
    testImplementation(project(":mantra-core"))
    testImplementation(project(":mantra-render"))
    testImplementation(project(":mantra-excel"))
    testImplementation(project(":mantra-workbench"))
    testImplementation("org.apache.poi:poi-ooxml:5.4.1")
}

tasks.named<Test>("test") {
    // Schema, case and layout documents are test inputs even though they are not on the classpath.
    inputs.files(fileTree(projectDir) { include("**/*.mantra") }).withPropertyName("mantraDocuments")
    outputs.dir(layout.buildDirectory.dir("out")).withPropertyName("renderedPapers")
}
