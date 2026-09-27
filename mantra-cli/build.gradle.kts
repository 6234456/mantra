plugins {
    application
}

dependencies {
    implementation(project(":mantra-core"))
    implementation(project(":mantra-render"))
    implementation(project(":mantra-excel"))
}

application {
    mainClass.set("com.xqiou.mantra.cli.MainKt")
    applicationName = "mantra"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
