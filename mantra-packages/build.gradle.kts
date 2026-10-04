plugins {
    `java-library`
}

dependencies {
    api(project(":mantra-core"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
}
