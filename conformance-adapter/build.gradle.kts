plugins {
    application
}

dependencies {
    implementation(project(":mantra-core"))
    implementation(project(":mantra-workbench"))
}

application {
    mainClass.set("com.xqiou.mantra.conformance.MainKt")
    applicationName = "mantra-conformance-adapter"
}
