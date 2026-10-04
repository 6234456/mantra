plugins {
    java
    application
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

dependencies {
    // No direct POI, Normein or kotlin-stdlib dependency: compile reachability must come from POMs.
    implementation("com.xqiou.mantra:mantra-excel:${project.extra["mantraVersion"]}")
}

application { mainClass.set("com.xqiou.mantra.consumer.JavaExcelConsumer") }
