plugins {
    `java-library`
}

dependencies {
    api(project(":mantra-core"))
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
}
