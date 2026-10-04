// Complete showcase application; never a published library artifact.
dependencies {
    testImplementation(project(":mantra-core"))
    testImplementation(project(":mantra-render"))
    testImplementation(project(":mantra-excel"))
    testImplementation(project(":mantra-workbench"))
    testImplementation(project(":mantra-packages"))
    testImplementation("org.apache.poi:poi-ooxml:5.5.1")
}
