plugins {
    `java-library`
}

dependencies {
    api(project(":mantra-workbench"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    testImplementation("com.networknt:json-schema-validator:3.0.7")
    testImplementation("org.apache.poi:poi-ooxml:5.5.1")
}
