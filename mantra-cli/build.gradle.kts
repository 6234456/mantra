plugins {
    application
}

dependencies {
    implementation(project(":mantra-core"))
    implementation(project(":mantra-render"))
    implementation(project(":mantra-excel"))
    implementation(project(":mantra-workbench"))
    implementation(project(":mantra-server"))
}

application {
    mainClass.set("com.xqiou.mantra.cli.MainKt")
    applicationName = "mantra"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
