plugins {
    `java-library`
}

dependencies {
    api(project(":mantra-render"))
    implementation("org.apache.poi:poi-ooxml:5.5.1")
}
