plugins {
    java
    kotlin("jvm")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
kotlin { jvmToolchain(21) }

dependencies { implementation(project(":library")) }

tasks.register("writeRuntimeClasspath") {
    dependsOn("classes", ":library:jar")
    val destination = layout.buildDirectory.file("runtime-classpath.txt")
    outputs.file(destination)
    doLast { destination.get().asFile.writeText(sourceSets.main.get().runtimeClasspath.asPath) }
}

tasks.register("writeJavaExecutable") {
    val destination = layout.buildDirectory.file("java-executable.txt")
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    outputs.file(destination)
    doLast { destination.get().asFile.writeText(launcher.get().executablePath.asFile.absolutePath) }
}
