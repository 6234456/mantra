plugins {
    `java-library`
}

dependencies {
    api(project(":mantra-core"))
    api(project(":mantra-render"))
    api(project(":mantra-packages"))
    implementation(project(":mantra-excel"))
    implementation("org.apache.poi:poi-ooxml:5.5.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    testImplementation("com.networknt:json-schema-validator:3.0.7")
}
