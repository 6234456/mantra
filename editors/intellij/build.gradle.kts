plugins { java }
group = "com.xqiou.mantra"
version = "0.1.0"
repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
val ideaHome = providers.gradleProperty("ideaHome")
val protocolOnly = providers.gradleProperty("protocolOnly").orNull == "true"
if (protocolOnly) {
    sourceSets.main {
        java.exclude("**/MantraActions.java", "**/MantraProjectService.java")
        resources.exclude("META-INF/plugin.xml")
    }
}
// Uses an already installed IDE. No Gradle task downloads, registers, or launches an IDE.
val ideJars = if (protocolOnly) files() else files(ideaHome.map { fileTree("$it/lib") { include("*.jar") } })
dependencies {
    compileOnly(ideJars)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.compileJava { doFirst { require(protocolOnly || ideaHome.isPresent) { "Set -PideaHome to an installed IntelliJ platform Contents directory" } } }
tasks.test {
    useJUnitPlatform()
    providers.gradleProperty("lspCommand").orNull?.let { systemProperty("mantra.test.lspCommand", it) }
}
tasks.register<Zip>("pluginZip") {
    doFirst { check(!protocolOnly) { "pluginZip requires a full platform compile" } }
    dependsOn(tasks.jar)
    archiveBaseName.set("mantra-language-tools")
    into("mantra-language-tools/lib") { from(tasks.jar); from(configurations.runtimeClasspath) }
}
