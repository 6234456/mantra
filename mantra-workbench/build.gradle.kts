plugins {
    `java-library`
}

dependencies {
    api(project(":mantra-core"))
    implementation(project(":mantra-render"))
    implementation(project(":mantra-excel"))
}
