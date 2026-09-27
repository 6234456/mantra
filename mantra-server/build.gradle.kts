plugins {
    `java-library`
}

dependencies {
    api(project(":mantra-workbench"))
    testImplementation("com.networknt:json-schema-validator:2.0.1")
}
