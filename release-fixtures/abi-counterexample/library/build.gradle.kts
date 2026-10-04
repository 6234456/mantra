@file:OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)

import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.abi.AbiValidationExtension

plugins {
    `java-library`
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

extensions.getByType<KotlinJvmProjectExtension>().extensions.configure<AbiValidationExtension> {
    enabled.set(true)
    legacyDump.referenceDumpDir.set(layout.projectDirectory.dir("api"))
    // No package/class filters: the deliberately public model class must be covered.
}

// Kotlin 2.2 does not wire this dependency automatically.
tasks.named("check") { dependsOn("checkLegacyAbi") }
