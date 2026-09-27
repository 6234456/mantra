plugins {
    `java-library`
}

dependencies {
    api(project(":mantra-core"))
    implementation(project(":mantra-render"))
    implementation(project(":mantra-excel"))
    testImplementation("com.networknt:json-schema-validator:2.0.1")
}
