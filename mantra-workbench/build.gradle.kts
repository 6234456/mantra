plugins {
    `java-library`
}

dependencies {
    api(project(":mantra-core"))
    implementation(project(":mantra-render"))
    implementation(project(":mantra-excel"))
    implementation("org.apache.poi:poi-ooxml:5.4.1")
    testImplementation("com.networknt:json-schema-validator:2.0.1")
}
