plugins {
    application
}

dependencies {
    implementation(project(":mantra-core"))
    implementation(project(":mantra-render"))
    implementation(project(":mantra-excel"))
    implementation("org.apache.poi:poi-ooxml:5.4.1")
}

application {
    mainClass.set("com.xqiou.mantra.benchmarks.PerformanceBaselineKt")
    applicationName = "mantra-benchmark"
    applicationDefaultJvmArgs = listOf("-Xms512m", "-Xmx2g", "-XX:+UseG1GC", "-Dfile.encoding=UTF-8")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
