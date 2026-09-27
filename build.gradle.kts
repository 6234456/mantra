import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.2.20" apply false
}

group = "com.xqiou.mantra"
version = "0.1.0-SNAPSHOT"

val normeinVersion: String = java.util.Properties().apply {
    file("normein-build.lock").inputStream().use { load(it) }
}.getProperty("normeinVersion") ?: error("normein-build.lock must declare normeinVersion")

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    extra["normeinVersion"] = normeinVersion

    dependencies {
        "testImplementation"(kotlin("test"))
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.0")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        workingDir = rootProject.projectDir
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
